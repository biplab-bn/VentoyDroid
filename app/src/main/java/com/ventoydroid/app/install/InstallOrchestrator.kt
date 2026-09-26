package com.ventoydroid.app.install

import com.ventoydroid.app.disk.BlockDevice
import com.ventoydroid.app.fat.ExFatFormatter
import com.ventoydroid.app.fat.ExFatVolume
import com.ventoydroid.app.fat.Fat32Formatter
import com.ventoydroid.app.fat.Fat32Volume
import com.ventoydroid.app.util.Bin
import com.ventoydroid.app.ventoy.VentoyInstaller
import com.ventoydroid.app.ventoy.VentoyJson
import com.ventoydroid.app.ventoy.VentoyLayout
import com.ventoydroid.app.ventoy.VentoyPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest
import kotlin.random.Random

/** What to copy onto the fresh Ventoy data partition. */
data class IsoSource(
    val displayName: String,
    val size: Long,
    val open: () -> InputStream,
)

sealed class InstallProgress {
    data class Stage(val name: String, val detail: String = "") : InstallProgress()
    data class Fraction(val stage: String, val done: Long, val total: Long) : InstallProgress()
    data class Done(val part1Sectors: Long, val ventoyVersion: String) : InstallProgress()
}

class InstallCancelledException : Exception("Install cancelled")

/** Data-partition filesystem chosen for an install. */
enum class DataFs { FAT32, EXFAT }

/**
 * Runs the whole install against any [BlockDevice] (real USB stick or virtual
 * disk in tests): MBR + boot code, VTOYEFI payload, data partition (FAT32 or
 * exFAT), then optional ISO copies.
 */
class InstallOrchestrator(
    private val device: BlockDevice,
    private val payload: VentoyPayload,
    private val random: Random = Random.Default,
) {

    /** Prewritten bytes for ventoy.json — the installer embeds it into VTOYEFI. */
    var ventoyJson: String = VentoyJson.default

    private var cancelled = false
    fun cancel() {
        cancelled = true
    }

    suspend fun run(
        isos: List<IsoSource> = emptyList(),
        onProgress: (InstallProgress) -> Unit = {},
    ): InstallProgress.Done = withContext(Dispatchers.IO) {
        val layout = VentoyLayout.computeLayout(device.sectorCount)
        val installer = VentoyInstaller(device, payload)

        onProgress(InstallProgress.Stage("Payload check"))
        val integrity = payload.verifyIntegrity()
        require(integrity.isEmpty()) { "Bundled Ventoy payload failed verification: $integrity" }

        step(onProgress, "Writing MBR + GRUB boot code")
        val diskSignature = random.nextInt()
        installer.installMbrStage(layout, diskSignature)
        installer.writeDiskSignature(diskSignature)
        installer.writeDiskUuid(randomUuid())

        step(onProgress, "Writing VTOYEFI partition")
        installer.installVtoyEfiStage(layout) { written ->
            onProgress(InstallProgress.Fraction("Writing VTOYEFI partition", written, VTOYEFI_DECOMPRESSED_BYTES))
        }

        val fs = chooseFilesystem(isos)
        val fsLabel = if (fs == DataFs.EXFAT) "(exFAT)" else "(FAT32)"
        step(onProgress, "Formatting data partition $fsLabel")
        when (fs) {
            DataFs.FAT32 -> Fat32Formatter.format(
                device = device,
                partStartSector = layout.part1Start,
                partSectorCount = layout.part1End - layout.part1Start + 1,
                label = "VENTOY",
                diskSizeBytes = device.size,
                random = random,
            )
            DataFs.EXFAT -> ExFatFormatter.format(
                device = device,
                partStartSector = layout.part1Start,
                partSectorCount = layout.part1End - layout.part1Start + 1,
                label = "VENTOY",
                diskSizeBytes = device.size,
                random = random,
            )
        }

        step(onProgress, "Copying ISO files")
        when (fs) {
            DataFs.FAT32 -> copyIsos(layout, isos, onProgress)
            DataFs.EXFAT -> copyIsosExFat(layout, isos, onProgress)
        }

        step(onProgress, "Verifying")
        verify(layout)

        InstallProgress.Done(
            part1Sectors = layout.part1End - layout.part1Start + 1,
            ventoyVersion = payload.version,
        )
    }

    /**
     * exFAT when any ISO is >= 4 GiB (FAT32 per-file limit); FAT32 otherwise —
     * it is the well-tested fast path for small images. exFAT removes the
     * ceiling entirely: Windows 11 (~5-6 GiB), big Ubuntu images, etc.
     */
    private fun chooseFilesystem(isos: List<IsoSource>): DataFs {
        val fourGiB = 4L * 1024 * 1024 * 1024
        return if (isos.any { it.size >= fourGiB }) DataFs.EXFAT else DataFs.FAT32
    }

    private suspend fun copyIsosExFat(
        layout: VentoyLayout.Layout,
        isos: List<IsoSource>,
        onProgress: (InstallProgress) -> Unit,
    ) {
        if (isos.isEmpty()) return
        currentCoroutineContext().ensureActive()
        val volume = ExFatVolume.mount(
            device,
            layout.part1Start,
            layout.part1End - layout.part1Start + 1,
        )
        volume.use { vol ->
            for (iso in isos) {
                currentCoroutineContext().ensureActive()
                val free = vol.freeClusters()
                val needed = (iso.size + vol.clusterBytes - 1) / vol.clusterBytes
                if (free < needed) {
                    val needGib = needed * vol.clusterBytes / (1024.0 * 1024 * 1024)
                    val freeGib = free * vol.clusterBytes / (1024.0 * 1024 * 1024)
                    throw IllegalArgumentException(
                        "${iso.displayName} needs %.1f GiB but only %.1f GiB is free on the stick".format(needGib, freeGib)
                    )
                }
                val isoBuf = ByteArray(1 shl 20)
                var lastReported = 0L
                vol.writeFileStream(iso.displayName, iso.size, iso.open()) { written ->
                    if (written - lastReported >= (16 shl 20) || written == iso.size) {
                        lastReported = written
                        onProgress(
                            InstallProgress.Fraction("Copying ${iso.displayName}", written, iso.size)
                        )
                    }
                    if (cancelled) throw InstallCancelledException()
                }
            }
        }
    }

    private suspend fun copyIsos(
        layout: VentoyLayout.Layout,
        isos: List<IsoSource>,
        onProgress: (InstallProgress) -> Unit,
    ) {
        if (isos.isEmpty()) return
        currentCoroutineContext().ensureActive()
        val volume = Fat32Volume.mount(
            device,
            layout.part1Start,
            layout.part1End - layout.part1Start + 1,
        )
        volume.use { vol ->
            // 4 GiB is the FAT32 per-file limit — enforced up front with a clear error.
            val fourGiB = 4L * 1024 * 1024 * 1024
            for (iso in isos) {
                currentCoroutineContext().ensureActive()
                if (iso.size >= fourGiB) {
                    throw IllegalArgumentException(
                        "${iso.displayName} is ${(iso.size + fourGiB - 1) / fourGiB} GiB; " +
                            "FAT32 supports up to 4 GiB per file"
                    )
                }
                val totalClusters = vol.totalClusters
                val isoBuf = ByteArray(1 shl 20)
                var lastReported = 0L
                vol.writeFileStream("/${iso.displayName}", iso.size, iso.open()) { written ->
                    if (written - lastReported >= (16 shl 20) || written == iso.size) {
                        lastReported = written
                        onProgress(
                            InstallProgress.Fraction("Copying ${iso.displayName}", written, iso.size)
                        )
                    }
                    if (cancelled) throw InstallCancelledException()
                }
                check(totalClusters > 0) // sanity: volume reported a usable size
            }
        }
    }

    /** Re-reads critical on-disk structures after the write completes. */
    private suspend fun verify(layout: VentoyLayout.Layout) {
        val mbr = ByteArray(512)
        device.readSectors(0, 1, mbr)
        check((mbr[510].toInt() and 0xFF) == 0x55 && (mbr[511].toInt() and 0xFF) == 0xAA) {
            "Verify: MBR signature missing"
        }
        check((mbr[450].toInt() and 0xFF) == 0x07) { "Verify: part1 type wrong" }
        check((mbr[466].toInt() and 0xFF) == 0xEF) { "Verify: part2 type wrong" }
        val coreProbe = ByteArray(512)
        device.readSectors(1, 1, coreProbe)
        check(!coreProbe.all { it == 0.toByte() }) { "Verify: core.img gap is empty" }
    }

    private fun randomUuid(): ByteArray = ByteArray(16).also { random.nextBytes(it) }

    private suspend fun step(onProgress: (InstallProgress) -> Unit, name: String) {
        currentCoroutineContext().ensureActive()
        onProgress(InstallProgress.Stage(name))
    }

    companion object {
        // ventoy.disk.img decompressed size (32 MiB), for progress fractions.
        const val VTOYEFI_DECOMPRESSED_BYTES = 33554432L
    }
}
