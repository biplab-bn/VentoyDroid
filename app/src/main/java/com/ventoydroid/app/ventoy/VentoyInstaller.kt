package com.ventoydroid.app.ventoy

import com.ventoydroid.app.disk.BlockDevice
import com.ventoydroid.app.disk.BlockDeviceException
import com.ventoydroid.app.util.Bin
import kotlin.math.ceil

/**
 * Port of the disk-layout logic in Ventoy's INSTALL/tool/VentoyWorker.sh +
 * ventoy_lib.sh (format_ventoy_disk_mbr) to Kotlin. All sector math mirrors
 * the upstream script exactly. SPDX-License-Identifier: GPL-3.0-or-later
 */
object VentoyLayout {
    const val SECTOR = 512
    const val PART1_START_SECTOR = 2048L
    const val VTOYEFI_SECTOR_COUNT = 65536L // 32 MiB

    data class Layout(
        val part1Start: Long,
        val part1End: Long,      // inclusive
        val part2Start: Long,
        val part2End: Long,      // inclusive
    )

    /**
     * Computes the Ventoy MBR layout for a disk of [totalSectors], matching
     * format_ventoy_disk_mbr: part1 starts at 2048, part2 (VTOYEFI) is the
     * last 65536 sectors, part2 start aligned down to a multiple of 8.
     */
    fun computeLayout(totalSectors: Long): Layout {
        if (totalSectors <= VTOYEFI_SECTOR_COUNT + PART1_START_SECTOR) {
            throw BlockDeviceException("Disk too small for Ventoy (${totalSectors} sectors)")
        }
        var part1End = totalSectors - VTOYEFI_SECTOR_COUNT - 1
        var part2Start = part1End + 1
        val mod = part2Start % 8
        if (mod > 0) {
            part1End -= mod
            part2Start = part1End + 1
        }
        val part2End = part2Start + VTOYEFI_SECTOR_COUNT - 1
        return Layout(PART1_START_SECTOR, part1End, part2Start, part2End)
    }
}

/**
 * Writes the complete Ventoy MBR layout to a block device:
 *  - zeroes the first 512 sectors (like `dd if=/dev/zero bs=64 count=512`)
 *  - MBR partition table with part1 (NTFS/microsoft type 0x07, boot flag) and
 *    part2 (EFI type 0xEF)
 *  - GRUB boot.img patched with the partition table + disk signature
 *  - core.img written into the LBA 1..2047 gap
 *  - 16-byte pseudo-random UUID at offset 384, 4-byte disk signature at 440
 *  - the VTOYEFI partition image extracted at part2 start
 */
class VentoyInstaller(
    private val device: BlockDevice,
    private val payload: VentoyPayload,
) {

    /** Writes the MBR partition table + boot code; part2 content comes later. */
    suspend fun installMbrStage(layout: VentoyLayout.Layout, diskSignature: Int) {
        // Zero the head of the disk, as the official installer does.
        val zero = ByteArray(64 * 512)
        device.writeSectors(0, 64, zero)

        // GRUB boot.img (first 446 bytes) + our partition table + signature.
        val mbr = ByteArray(512)
        payload.bootImg().use { Bin.readExact(it, mbr, 446) }
        writePartitionTable(mbr, layout, diskSignature)
        mbr[510] = 0x55; mbr[511] = 0xAA.toByte()
        device.writeSectors(0, 1, mbr)

        // core.img fills the gap LBA 1..2047 (2047 sectors).
        val core = ByteArray(VentoyLayout.SECTOR * 2047)
        payload.coreImgXz().use { xz ->
            org.tukaani.xz.XZInputStream(xz).use { Bin.readExact(it, core, core.size) }
        }
        device.writeSectors(1, 2047, core)
    }

    /** Writes the VTOYEFI partition image at part2's start sector. */
    suspend fun installVtoyEfiStage(layout: VentoyLayout.Layout, onProgress: (Long) -> Unit = {}) {
        val buf = ByteArray(1 shl 20)
        var written = 0L
        payload.ventoyDiskImgXz().use { xz ->
            org.tukaani.xz.XZInputStream(xz).use { plain ->
                var sector = layout.part2Start
                while (true) {
                    val n = plain.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    val sectors = ceil(n / VentoyLayout.SECTOR.toDouble()).toInt()
                    val padded = if (n == buf.size) buf else buf.copyOf(sectors * VentoyLayout.SECTOR)
                    device.writeSectors(sector, sectors, padded)
                    sector += sectors
                    written += n
                    onProgress(written)
                }
            }
        }
    }

    /** Writes the 16-byte disk UUID at offset 384 (Ventoy: vtoy_gen_uuid seek=384). */
    suspend fun writeDiskUuid(uuid: ByteArray) {
        require(uuid.size == 16) { "UUID must be 16 bytes" }
        val sector0 = ByteArray(512)
        device.readSectors(0, 1, sector0)
        uuid.copyInto(sector0, 384)
        device.writeSectors(0, 1, sector0)
    }

    /** Writes the 4-byte disk signature at offset 440 (skip=12 seek=440 count=4). */
    suspend fun writeDiskSignature(sig: Int) {
        val sector0 = ByteArray(512)
        device.readSectors(0, 1, sector0)
        Bin.putU32le(sector0, 440, sig.toLong() and 0xFFFFFFFFL)
        device.writeSectors(0, 1, sector0)
    }

    private fun writePartitionTable(mbr: ByteArray, layout: VentoyLayout.Layout, diskSignature: Int) {
        // Partition 1: type 0x07 (NTFS/exFAT marker used by parted's "ntfs"),
        // bootable, spanning the data area.
        writeEntry(mbr, 446, active = true, type = 0x07,
            startLba = layout.part1Start, count = layout.part1End - layout.part1Start + 1)
        // Partition 2: type 0xEF (EFI), the VTOYEFI partition.
        writeEntry(mbr, 446 + 16, active = false, type = 0xEF,
            startLba = layout.part2Start, count = layout.part2End - layout.part2Start + 1)
        Bin.putU32le(mbr, 440, diskSignature.toLong() and 0xFFFFFFFFL)
        // 444..447 is 0 (disk signature at 440 leaves 444..445 zero like upstream)
    }

    private fun writeEntry(mbr: ByteArray, at: Int, active: Boolean, type: Int, startLba: Long, count: Long) {
        mbr[at] = if (active) 0x80.toByte() else 0x00
        // CHS start/end are legacy placeholders (0xFE 0xFF 0xFF), LBA is what matters.
        mbr[at + 1] = 0xFE.toByte(); mbr[at + 2] = 0xFF.toByte(); mbr[at + 3] = 0xFF.toByte()
        mbr[at + 4] = type.toByte()
        mbr[at + 5] = 0xFE.toByte(); mbr[at + 6] = 0xFF.toByte(); mbr[at + 7] = 0xFF.toByte()
        Bin.putU32le(mbr, at + 8, startLba)
        Bin.putU32le(mbr, at + 12, count)
    }
}
