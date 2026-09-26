package com.ventoydroid.app.disk

import java.io.Closeable

/** A random-access, sector-addressable block device. */
interface BlockDevice : Closeable {
    /** Logical block size in bytes (usually 512). */
    val blockSize: Int

    /** Total size of the device in bytes. */
    val size: Long

    /** Total number of addressable sectors. */
    val sectorCount: Long get() = size / blockSize

    /** Reads [count] sectors starting at [sector] into [buffer] at [offset]. */
    suspend fun readSectors(sector: Long, count: Int, buffer: ByteArray, offset: Int = 0)

    /** Writes [count] sectors from [buffer] at [offset] starting at [sector]. */
    suspend fun writeSectors(sector: Long, count: Int, buffer: ByteArray, offset: Int = 0)
}

/** Raised when the block device rejects an out-of-range access. */
class BlockDeviceException(message: String, cause: Throwable? = null) : Exception(message, cause)
