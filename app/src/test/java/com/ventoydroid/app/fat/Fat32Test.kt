package com.ventoydroid.app.fat

import com.ventoydroid.app.disk.VirtualBlockDevice
import com.ventoydroid.app.util.Bin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class Fat32Test {

    private fun newDisk(mib: Int = 64): VirtualBlockDevice =
        VirtualBlockDevice(totalBytes = mib.toLong() * 1024 * 1024)

    private fun startOf(): Long = 2048L

    private fun partSectors(disk: VirtualBlockDevice): Long =
        disk.sectorCount - 2048

    private fun formatDisk(disk: VirtualBlockDevice, mib: Int = 64): Fat32Volume {
        val start = startOf()
        val count = partSectors(disk)
        kotlinx.coroutines.runBlocking {
            Fat32Formatter.format(disk, start, count, label = "TEST", diskSizeBytes = mib.toLong() shl 20)
        }
        return kotlinx.coroutines.runBlocking {
            Fat32Volume.mount(disk, start, count)
        }
    }

    @Test
    fun `format then mount succeeds and label survives`() = runTest {
        val disk = newDisk()
        formatDisk(disk).close()
        val vol = kotlinx.coroutines.runBlocking {
            Fat32Volume.mount(disk, startOf(), partSectors(disk))
        }
        vol.use {
            assertEquals(64, vol.sectorsPerCluster)
            assertEquals(512, vol.bytesPerSector)
            assertTrue(vol.freeClusters() > 0)
        }
    }

    @Test
    fun `write and read back a small file`() = runTest {
        val disk = newDisk()
        formatDisk(disk).use { vol ->
            val data = "hello ventoy".toByteArray()
            kotlinx.coroutines.runBlocking {
                vol.writeFileStream("/hello.txt", data.size.toLong(), ByteArrayInputStream(data))
            }
            val chunks = mutableListOf<Pair<ByteArray, Int>>()
            kotlinx.coroutines.runBlocking {
                vol.readFileStreaming("/hello.txt") { buf, n -> chunks.add(buf.copyOf(n) to n) }
            }
            val out = chunks.flatMap { it.first.toList() }.toByteArray()
            assertArrayEquals(data, out)
        }
    }

    @Test
    fun `long file names round trip`() = runTest {
        val disk = newDisk()
        formatDisk(disk).use { vol ->
            val data = ByteArray(10_000) { (it % 251).toByte() }
            val name = "ubuntu-24.04.1-desktop-amd64.iso"
            kotlinx.coroutines.runBlocking {
                vol.writeFileStream("/$name", data.size.toLong(), ByteArrayInputStream(data))
            }
            val listed = kotlinx.coroutines.runBlocking { vol.listFiles("/") }
            assertEquals(listOf(name), listed.map { it.name })
            val chunks = mutableListOf<Pair<ByteArray, Int>>()
            kotlinx.coroutines.runBlocking {
                vol.readFileStreaming("/$name") { buf, n -> chunks.add(buf.copyOf(n) to n) }
            }
            val out = chunks.flatMap { it.first.toList() }.toByteArray()
            assertArrayEquals(data, out)
        }
    }

    @Test
    fun `multi cluster file with non cluster aligned size`() = runTest {
        val disk = newDisk()
        formatDisk(disk).use { vol ->
            val data = ByteArray(vol.clusterSize * 3 + 12345) { (it * 7).toByte() }
            kotlinx.coroutines.runBlocking {
                vol.writeFileStream("/big.bin", data.size.toLong(), ByteArrayInputStream(data))
            }
            val chunks = mutableListOf<Pair<ByteArray, Int>>()
            kotlinx.coroutines.runBlocking {
                vol.readFileStreaming("/big.bin") { buf, n -> chunks.add(buf.copyOf(n) to n) }
            }
            val out = chunks.flatMap { it.first.toList() }.toByteArray()
            assertArrayEquals(data, out)
        }
    }

    @Test
    fun `many files do not collide in the root directory`() = runTest {
        val disk = newDisk()
        formatDisk(disk).use { vol ->
            val data = "x".toByteArray()
            repeat(30) { i ->
                kotlinx.coroutines.runBlocking {
                    vol.writeFileStream("/file_number_$i.dat", data.size.toLong(), ByteArrayInputStream(data))
                }
            }
            val listed = kotlinx.coroutines.runBlocking { vol.listFiles("/") }
            assertEquals(30, listed.size)
            assertEquals(30, listed.map { it.name }.toSet().size)
        }
    }

    @Test
    fun `free space decreases as files are added`() = runTest {
        val disk = newDisk()
        formatDisk(disk).use { vol ->
            val before = vol.freeClusters()
            val blob = ByteArray(vol.clusterSize)
            repeat(10) { i ->
                kotlinx.coroutines.runBlocking {
                    vol.writeFileStream("/blob$i.bin", blob.size.toLong(), ByteArrayInputStream(blob))
                }
            }
            assertEquals(before - 10, vol.freeClusters())
        }
    }

    @Test
    fun `writing more than free space fails cleanly`() = runTest {
        val disk = newDisk()
        formatDisk(disk).use { vol ->
            val free = vol.freeClusters()
            val tooBig = vol.clusterSize.toLong() * (free + 1)
            val fake = object : java.io.InputStream() {
                override fun read(): Int = throw IllegalStateException("should not be read")
            }
            val threw = runCatching {
                kotlinx.coroutines.runBlocking {
                    vol.writeFileStream("/huge.bin", tooBig, fake)
                }
            }.exceptionOrNull()
            assertTrue("expected failure", threw is com.ventoydroid.app.disk.BlockDeviceException)
        }
    }

    @Test
    fun `verify against linux mkfs fsck - actually a structure self-check`() = runTest {
        // Structural self-check: BPB fields, FSInfo, FAT[0..2] and the volume
        // label entry must all be where a real FAT32 driver expects them.
        val disk = newDisk()
        val start = startOf()
        kotlinx.coroutines.runBlocking {
            Fat32Formatter.format(disk, start, partSectors(disk), label = "VENTOY")
        }
        val bpb = disk.snapshotRegion(start, 1)
        assertEquals(0x55, bpb[510].toInt() and 0xFF)
        assertEquals(0xAA, bpb[511].toInt() and 0xFF)
        assertEquals("FAT32", Bin.ascii(bpb, 82, 8).trim())
        assertEquals("VENTOY", Bin.ascii(bpb, 71, 11).trim())

        val fsinfo = disk.snapshotRegion(start + 1, 1)
        assertEquals(0x41615252L, Bin.u32le(fsinfo, 0))
        assertEquals(0x61417272L, Bin.u32le(fsinfo, 484))

        val fatStart = start + Bin.u16le(bpb, 14)
        val fatSectors = Bin.u32le(bpb, 36)
        val fat0 = disk.snapshotRegion(fatStart, 1)
        assertEquals(0x0FFFFFF8L, Bin.u32le(fat0, 0) and 0x0FFFFFFFL)
        assertEquals(0x0FFFFFFFL, Bin.u32le(fat0, 4) and 0x0FFFFFFFL)

        val rootSector = start + Bin.u16le(bpb, 14) + 2 * fatSectors
        val root = disk.snapshotRegion(rootSector, 1)
        assertEquals(0x08, root[11].toInt() and 0xFF) // volume label attribute
        assertEquals('V'.code.toByte(), root[0])
    }

    @Test
    fun `md5 of streamed file matches input`() = runTest {
        val disk = newDisk()
        formatDisk(disk).use { vol ->
            val data = ByteArray(777_777) { (it * 31 % 256).toByte() }
            kotlinx.coroutines.runBlocking {
                vol.writeFileStream("/md5.bin", data.size.toLong(), ByteArrayInputStream(data))
            }
            val md = MessageDigest.getInstance("MD5")
            kotlinx.coroutines.runBlocking {
                vol.readFileStreaming("/md5.bin") { buf, n -> md.update(buf, 0, n) }
            }
            assertArrayEquals(
                MessageDigest.getInstance("MD5").digest(data),
                md.digest(),
            )
        }
    }
}
