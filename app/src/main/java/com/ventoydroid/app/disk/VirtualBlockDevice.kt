package com.ventoydroid.app.disk

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An in-memory block device used for unit tests and dry-run installs.
 * Optionally fault-injects at a given sector to exercise retry paths.
 */
class VirtualBlockDevice(
    override val blockSize: Int = 512,
    private val sectorCountValue: Long,
) : BlockDevice {

    constructor(totalBytes: Long) : this(512, totalBytes / 512)

    private val mutex = Mutex()

    /** In-memory disks must fit in a single array (2 GiB). */
    private val data = ByteArray(totalSizeBytes().toInt())

    var failWritesAtSector: Long = -1
    var faultInjected = false
        private set

    fun totalSizeBytes(): Long = sectorCountValue * blockSize

    override val size: Long get() = totalSizeBytes()

    override suspend fun readSectors(sector: Long, count: Int, buffer: ByteArray, offset: Int) {
        require(count >= 0)
        boundsCheck(sector, count)
        mutex.withLock {
            System.arraycopy(data, (sector * blockSize).toInt(), buffer, offset, count * blockSize)
        }
    }

    override suspend fun writeSectors(sector: Long, count: Int, buffer: ByteArray, offset: Int) {
        require(count >= 0)
        boundsCheck(sector, count)
        if (sector == failWritesAtSector) {
            faultInjected = true
            throw BlockDeviceException("Injected write fault at sector $sector")
        }
        mutex.withLock {
            System.arraycopy(buffer, offset, data, (sector * blockSize).toInt(), count * blockSize)
        }
    }

    fun snapshotRegion(sector: Long, count: Int): ByteArray {
        val out = ByteArray(count * blockSize)
        System.arraycopy(data, (sector * blockSize).toInt(), out, 0, out.size)
        return out
    }

    fun pokeRegion(sector: Long, bytes: ByteArray) {
        System.arraycopy(bytes, 0, data, (sector * blockSize).toInt(), bytes.size)
    }

    override fun close() {}

    private fun boundsCheck(sector: Long, count: Int) {
        if (sector < 0 || count < 0 || sector + count > sectorCountValue) {
            throw BlockDeviceException(
                "Access out of range: sector=$sector count=$count capacity=$sectorCountValue"
            )
        }
    }
}
