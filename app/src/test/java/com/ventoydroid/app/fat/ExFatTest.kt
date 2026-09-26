package com.ventoydroid.app.fat

import com.ventoydroid.app.disk.VirtualBlockDevice
import com.ventoydroid.app.util.Bin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Checks the formatted on-disk structures against the exFAT specification
 * values (mirrored from exfatprogs): boot checksum algorithm, upcase table
 * bytes, dentry-set/name hashes, and a full format + file roundtrip.
 */
class ExFatTest {

    /** 128 MiB keeps the in-memory disk small (VirtualBlockDevice is a single array). */
    private fun newDisk(mib: Int = 128): VirtualBlockDevice =
        VirtualBlockDevice(totalBytes = mib.toLong() * 1024L * 1024L)

    @Test
    fun `boot sector fields match spec offsets`() = runTest {
        val disk = newDisk()
        val partStart = 2048L
        val partSectors = disk.sectorCount - partStart
        ExFatFormatter.format(disk, partStart, partSectors, label = "VENTOY", random = Random(7))

        val boot = disk.snapshotRegion(partStart, 1)
        assertEquals(0xEB, boot[0].toInt() and 0xFF)
        assertEquals(0x76, boot[1].toInt() and 0xFF)
        assertEquals(0x90, boot[2].toInt() and 0xFF)
        assertEquals("EXFAT   ", String(boot, 3, 8, Charsets.US_ASCII))
        // MustBeZero region
        assertTrue(boot.copyOfRange(11, 64).all { it == 0.toByte() })
        assertEquals(partStart, Bin.u64le(boot, 64))
        assertEquals(partSectors, Bin.u64le(boot, 72))
        assertEquals(0x0100, Bin.u16le(boot, 104).toLong()) // revision 1.00 (le16 0x0100 = 256 raw)
        assertEquals(9, boot[108].toInt())    // bytes/sector shift (512)
        assertEquals(0x55, boot[510].toInt() and 0xFF)
        assertEquals(0xAA, boot[511].toInt() and 0xFF)
    }

    @Test
    fun `boot checksum sector validates against spec algorithm`() = runTest {
        val disk = newDisk()
        val partStart = 2048L
        val partSectors = disk.sectorCount - partStart
        ExFatFormatter.format(disk, partStart, partSectors, random = Random(7))

        // Recompute the checksum independently over the checksummed region
        // (boot + 8 extended boot + OEM = 10 sectors; the reserved and checksum
        // sectors themselves are excluded, as in exfatprogs).
        val regions = disk.snapshotRegion(partStart, 10)
        var checksum = 0L
        for (s in 0 until 10) {
            val sector = regions.copyOfRange(s * 512, (s + 1) * 512)
            for (i in 0 until 512) {
                if (s == 0 && (i == 106 || i == 107 || i == 112)) continue
                checksum = (((checksum and 1L) shl 31) + (checksum ushr 1) +
                    (sector[i].toLong() and 0xFF)) and 0xFFFFFFFFL
            }
        }
        val checksumSector = disk.snapshotRegion(partStart + 11, 1)
        for (i in 0 until 128) {
            assertEquals(checksum, Bin.u32le(checksumSector, i * 4))
        }
        // Backup boot region carries the same checksum.
        val backupChecksum = disk.snapshotRegion(partStart + 23, 1)
        for (i in 0 until 128) {
            assertEquals(checksum, Bin.u32le(backupChecksum, i * 4))
        }
    }

    @Test
    fun `upcase table is the canonical one`() {
        val bytes = ExFatUpcase.decodedBytes()
        assertEquals(5836, bytes.size)
        // Table maps 0x0061 ('a') -> 0x0041 ('A') and leaves '0x007B' unchanged.
        val units = ExFatUpcase.decoded()
        assertEquals(0x0041, units[0x61].toInt())
        assertEquals(0x0049, units[0x69].toInt()) // 'i' maps to 'I'
        assertEquals(0x007B, units[0x7B].toInt())
        assertEquals(2918, ExFatUpcase.ENTRY_COUNT)
    }

    @Test
    fun `name hash matches exfatprogs algorithm`() {
        val upcase = ExFatUpcase.decoded()
        // Reference value computed with the exfatprogs algorithm for "Ventoy":
        // hash = rotl(hash,15) + hi; rotl; + lo, per UTF-16 code unit, hi first.
        fun refHash(name: String): Int {
            var h = 0
            for (ch in name) {
                val u = ch.uppercaseChar().code
                h = ((((h shl 15) or (h ushr 1)) and 0xFFFF) + ((u shr 8) and 0xFF)) and 0xFFFF
                h = ((((h shl 15) or (h ushr 1)) and 0xFFFF) + (u and 0xFF)) and 0xFFFF
            }
            return h
        }
        for (n in listOf("win11.iso", "VENTOY", "ubuntu-24.04-desktop-amd64.iso", "a")) {
            assertEquals(refHash(n), ExFatVolume.nameHash(n, upcase))
        }
    }

    @Test
    fun `dentry set checksum excludes the right bytes`() {
        val upcase = ExFatUpcase.decoded()
        val set = ExFatVolume.buildDentrySet("win11.iso", 5L * 1024 * 1024 * 1024, 100L, upcase)
        // File + Stream + 1 Name entry for a 9-char name
        assertEquals(96, set.size)
        val file = set.copyOfRange(0, 32)
        val stream = set.copyOfRange(32, 64)
        val name = set.copyOfRange(64, 96)
        assertEquals(0x85, file[0].toInt() and 0xFF)
        assertEquals(2, file[1].toInt()) // secondary count
        assertEquals(0xC0, stream[0].toInt() and 0xFF)
        assertEquals(0x02, stream[1].toInt() and 0xFF) // NoFatChain
        assertEquals(9, stream[3].toInt())             // name length
        assertEquals(100L, Bin.u32le(stream, 20))      // first cluster
        assertEquals(0xC1, name[0].toInt() and 0xFF)
        // Independent recomputation: file contributes 0..1 and 4..31; others 2..31.
        var sum = 0
        fun rot(b: Int) { sum = ((((sum and 1) shl 15) or (sum ushr 1)) and 0xFFFF) + b }
        for (i in 0..1) rot(file[i].toInt() and 0xFF)
        for (i in 4 until 32) rot(file[i].toInt() and 0xFF)
        for (i in 2 until 32) rot(stream[i].toInt() and 0xFF)
        for (i in 2 until 32) rot(name[i].toInt() and 0xFF)
        assertEquals(sum, Bin.u16le(file, 2))
    }

    @Test
    fun `format then write and read back a large iso`() = runTest {
        val disk = newDisk()
        val partStart = 2048L
        val partSectors = disk.sectorCount - partStart
        ExFatFormatter.format(disk, partStart, partSectors, random = Random(7))

        ExFatVolume.mount(disk, partStart, partSectors).use { vol ->
            assertEquals(0, vol.listFiles().size)

            // ~48 MiB synthetic ISO: exercises multi-cluster contiguous writes.
            val size = 48L * 1024 * 1024
            val seed = object : java.io.InputStream() {
                var produced = 0L
                override fun read(): Int {
                    if (produced >= size) return -1
                    val v = ((produced * 31 + 7) and 0xFF).toInt()
                    produced++
                    return v
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (produced >= size) return -1
                    val n = minOf(len.toLong(), size - produced, 1 shl 20).toInt()
                    for (i in 0 until n) b[off + i] = (((produced + i) * 31 + 7) and 0xFF).toByte()
                    produced += n
                    return n
                }
            }
            val mdBefore = MessageDigest.getInstance("SHA-256")
            // Hash the source stream as it is consumed: wrap to tee into the digest.
            val tee = object : java.io.InputStream() {
                val single = ByteArray(1)
                override fun read(): Int {
                    val n = read(single, 0, 1)
                    return if (n < 0) -1 else (single[0].toInt() and 0xFF)
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = seed.read(b, off, len)
                    if (n > 0) mdBefore.update(b, off, n)
                    return n
                }
            }
            vol.writeFileStream("win11-test.iso", size, tee)

            val files = vol.listFiles()
            assertEquals(listOf("win11-test.iso"), files.map { it.name })
            assertEquals(size, files[0].size)

            val mdAfter = MessageDigest.getInstance("SHA-256")
            vol.readFileStreaming("win11-test.iso") { buf, n -> mdAfter.update(buf, 0, n) }
            org.junit.Assert.assertArrayEquals(mdBefore.digest(), mdAfter.digest())

            assertTrue(vol.freeClusters() > 0)
        }
    }

    @Test
    fun `two large isos coexist in the root directory`() = runTest {
        val disk = newDisk()
        val partStart = 2048L
        val partSectors = disk.sectorCount - partStart
        ExFatFormatter.format(disk, partStart, partSectors, random = Random(7))

        ExFatVolume.mount(disk, partStart, partSectors).use { vol ->
            val a = ByteArray(3 * 1024 * 1024) { 'A'.code.toByte() }
            val b = ByteArray(5 * 1024 * 1024) { 'B'.code.toByte() }
            vol.writeFileStream("aaa.iso", a.size.toLong(), ByteArrayInputStream(a))
            vol.writeFileStream("bbb.iso", b.size.toLong(), ByteArrayInputStream(b))
            val names = vol.listFiles().map { it.name }.toSet()
            assertEquals(setOf("aaa.iso", "bbb.iso"), names)
        }
    }

    @Test
    fun `oversized write fails cleanly without leaving a visible file`() = runTest {
        val disk = newDisk(64)
        val partStart = 2048L
        val partSectors = disk.sectorCount - partStart
        ExFatFormatter.format(disk, partStart, partSectors, random = Random(7))

        ExFatVolume.mount(disk, partStart, partSectors).use { vol ->
            val tooBig = vol.clusterBytes.toLong() * vol.clusterCount + vol.clusterBytes
            val threw = runCatching {
                vol.writeFileStream("huge.iso", tooBig, ByteArrayInputStream(ByteArray(1024)))
            }
            assertTrue(threw.isFailure)
            assertEquals(0, vol.listFiles().size)
        }
    }
}
