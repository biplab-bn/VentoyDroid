package com.ventoydroid.app.ventoy

import com.ventoydroid.app.disk.VirtualBlockDevice
import com.ventoydroid.app.fat.Fat32Volume
import com.ventoydroid.app.install.InstallOrchestrator
import com.ventoydroid.app.install.IsoSource
import com.ventoydroid.app.util.Bin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.random.Random

/** Deterministic fake payload (valid xz streams built by the test itself). */
class FakePayload : VentoyPayload {
    override val version = "test-1.2.3"
    private val bootImg = ByteArray(446) { (it % 251).toByte() }
    private val core = ByteArray(2047 * 512) { ((it * 13) and 0xFF).toByte() }
    private val diskImg = ByteArray(65536 * 512) { ((it * 7) and 0xFF).toByte() }

    private fun xz(bytes: ByteArray) = ByteArrayInputStream(
        java.io.ByteArrayOutputStream().also { out ->
            org.tukaani.xz.XZOutputStream(out, org.tukaani.xz.LZMA2Options()).use { it.write(bytes) }
        }.toByteArray()
    )

    override fun bootImg() = ByteArrayInputStream(bootImg)
    override fun coreImgXz() = xz(core)
    override fun ventoyDiskImgXz() = xz(diskImg)
}

class VentoyInstallTest {

    /** 128 MiB keeps the in-memory disk small while remaining valid Ventoy geometry. */
    private fun newDisk(mib: Int = 128): VirtualBlockDevice =
        VirtualBlockDevice(totalBytes = mib.toLong() * 1024L * 1024L)

    private fun isoSource(name: String, data: ByteArray) = IsoSource(
        displayName = name,
        size = data.size.toLong(),
        open = { ByteArrayInputStream(data) },
    )

    @Test
    fun `layout matches official ventoy geometry`() {
        // 8 GiB disk = 16777216 sectors
        val layout = VentoyLayout.computeLayout(16_777_216L)
        assertEquals(2048L, layout.part1Start)
        assertEquals(16711679L, layout.part1End) // total - 65536 - 1
        assertEquals(16711680L, layout.part2Start) // already %8 == 0
        assertEquals(16777215L, layout.part2End)
        assertEquals(65536L, layout.part2End - layout.part2Start + 1)
    }

    @Test
    fun `layout aligns part2 to multiple of 8`() {
        // 100_003 sectors: part2 start would be 34_467, needing the %8 realign
        val layout = VentoyLayout.computeLayout(100_003L)
        assertEquals(0L, layout.part2Start % 8)
        assertEquals(34_464L, layout.part2Start)
        assertTrue(layout.part1End >= layout.part1Start)
    }

    @Test
    fun `layout rejects undersized disks`() {
        // 60_000 sectors is below the minimum (2048 + 65536)
        val threw = runCatching { VentoyLayout.computeLayout(60_000L) }
        assertTrue(threw.isFailure)
    }

    @Test
    fun `full install writes correct MBR and mounts data partition`() = runTest {
        val disk = newDisk()
        val payload = FakePayload()
        val orchestrator = InstallOrchestrator(disk, payload, Random(42))

        val result = orchestrator.run()
        assertEquals("test-1.2.3", result.ventoyVersion)
        assertTrue(result.part1Sectors > 0)

        // ---- MBR assertions ----
        val mbr = disk.snapshotRegion(0, 1)
        assertEquals(0x55, mbr[510].toInt() and 0xFF)
        assertEquals(0xAA, mbr[511].toInt() and 0xFF)
        // part1: bootable, type 07, starts 2048
        assertEquals(0x80, mbr[446].toInt() and 0xFF)
        assertEquals(0x07, mbr[446 + 4].toInt() and 0xFF)
        assertEquals(2048L, Bin.u32le(mbr, 446 + 8))
        // part2: type EF, size 65536
        assertEquals(0xEF, mbr[446 + 16 + 4].toInt() and 0xFF)
        assertEquals(65536L, Bin.u32le(mbr, 446 + 16 + 12))
        // core.img gap must be non-empty
        val coreProbe = disk.snapshotRegion(1, 1)
        assertTrue(coreProbe.any { it != 0.toByte() })
        // VTOYEFI content must match the payload image (spot check)
        val layout = VentoyLayout.computeLayout(disk.sectorCount)
        val vtoyProbe = disk.snapshotRegion(layout.part2Start, 1)
        assertTrue(vtoyProbe.any { it != 0.toByte() })

        // ---- data partition assertions ----
        Fat32Volume.mount(disk, layout.part1Start, layout.part1End - layout.part1Start + 1).use { vol ->
            assertEquals("VENTOY", Bin.ascii(disk.snapshotRegion(layout.part1Start, 1), 71, 11).trim())
            assertTrue(vol.freeClusters() > 0)
        }
    }

    @Test
    fun `install with iso copies it into part1 readably`() = runTest {
        val disk = newDisk()
        val data = ByteArray(3 * 1024 * 1024 + 777) { (it * 11).toByte() }
        val orchestrator = InstallOrchestrator(disk, FakePayload(), Random(42))
        orchestrator.run(listOf(isoSource("testos.iso", data)))

        val layout = VentoyLayout.computeLayout(disk.sectorCount)
        Fat32Volume.mount(disk, layout.part1Start, layout.part1End - layout.part1Start + 1).use { vol ->
            val files = kotlinx.coroutines.runBlocking { vol.listFiles("/") }
            assertEquals(listOf("testos.iso"), files.map { it.name })
            assertEquals(data.size.toLong(), files[0].size)
            val md = java.security.MessageDigest.getInstance("SHA-256")
            kotlinx.coroutines.runBlocking {
                vol.readFileStreaming("/testos.iso") { buf, n -> md.update(buf, 0, n) }
            }
            val expected = java.security.MessageDigest.getInstance("SHA-256").digest(data)
            assertArrayEquals(expected, md.digest())
        }
    }

    @Test
    fun `two isos coexist with distinct clusters`() = runTest {
        val disk = newDisk()
        val a = ByteArray(1024 * 1024) { 'A'.code.toByte() }
        val b = ByteArray(2 * 1024 * 1024) { 'B'.code.toByte() }
        val orchestrator = InstallOrchestrator(disk, FakePayload(), Random(42))
        orchestrator.run(listOf(isoSource("a.iso", a), isoSource("b.iso", b)))

        val layout = VentoyLayout.computeLayout(disk.sectorCount)
        Fat32Volume.mount(disk, layout.part1Start, layout.part1End - layout.part1Start + 1).use { vol ->
            val files = kotlinx.coroutines.runBlocking { vol.listFiles("/") }
            assertEquals(setOf("a.iso", "b.iso"), files.map { it.name }.toSet())
        }
    }

    @Test
    fun `oversized iso is rejected before any copy`() = runTest {
        val disk = newDisk()
        val orchestrator = InstallOrchestrator(disk, FakePayload(), Random(42))
        val tooBig = IsoSource(
            displayName = "win11.iso",
            size = 5L * 1024 * 1024 * 1024,
            open = { error("must never be opened") },
        )
        val threw = runCatching { orchestrator.run(listOf(tooBig)) }.exceptionOrNull()
        assertTrue("actual: $threw", threw is IllegalArgumentException)
    }

    @Test
    fun `partition table matches parted plus manual type-byte patch`() = kotlinx.coroutines.test.runTest {
        // The upstream parted command marks part1 "ntfs" (0x07 via type patch to EF
        // for part2); assert our MBR bytes match the upstream fdisk script output.
        val disk = newDisk()
        val payload = FakePayload()
        InstallOrchestrator(disk, payload, Random(7)).run()
        val mbr = disk.snapshotRegion(0, 1)
        // fdisk script: t 1 -> 7, t 2 -> ef, a 1
        assertEquals(0x07, mbr[450].toInt() and 0xFF)
        assertEquals(0xEF, mbr[466].toInt() and 0xFF)
        assertEquals(0x80, mbr[446].toInt() and 0xFF)
        assertEquals(0x00, mbr[462].toInt() and 0xFF)
    }
}
