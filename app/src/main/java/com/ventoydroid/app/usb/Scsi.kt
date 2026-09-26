package com.ventoydroid.app.usb

import com.ventoydroid.app.util.Bin

/** SCSI check-condition data decoded from REQUEST SENSE. */
data class SenseData(val senseKey: Int, val asc: Int, val ascq: Int) {
    val isNotReady: Boolean get() = senseKey == 0x02
    val isMediumError: Boolean get() = senseKey == 0x03
    val isUnitAttention: Boolean get() = senseKey == 0x06

    fun describe(): String {
        val key = when (senseKey) {
            0x00 -> "NO SENSE"
            0x01 -> "RECOVERED"
            0x02 -> "NOT READY"
            0x03 -> "MEDIUM ERROR"
            0x04 -> "HARDWARE ERROR"
            0x05 -> "ILLEGAL REQUEST"
            0x06 -> "UNIT ATTENTION"
            0x07 -> "DATA PROTECT"
            0x0A -> "ABORTED COMMAND"
            else -> String.format("KEY 0x%02X", senseKey)
        }
        return String.format("%s ASC=0x%02X ASCQ=0x%02X", key, asc, ascq)
    }
}

/** Builders for the small SCSI command subset used by the installer. */
object Scsi {
    const val READ_CAPACITY_DATA_LEN = 8
    const val SENSE_DATA_LEN = 18

    fun testUnitReady(cb: ByteArray) {
        java.util.Arrays.fill(cb, 0, 6, 0)
        cb[0] = 0x00
    }

    fun inquiry(cb: ByteArray, allocLen: Int) {
        java.util.Arrays.fill(cb, 0, 6, 0)
        cb[0] = 0x12
        cb[4] = allocLen.toByte()
    }

    fun requestSense(cb: ByteArray) {
        java.util.Arrays.fill(cb, 0, 6, 0)
        cb[0] = 0x03
        cb[4] = SENSE_DATA_LEN.toByte()
    }

    fun readCapacity10(cb: ByteArray) {
        java.util.Arrays.fill(cb, 0, 10, 0)
        cb[0] = 0x25
    }

    fun read10(cb: ByteArray, lba: Long, blocks: Int) {
        require(blocks in 1..0xFFFF) { "READ(10) block count out of range: $blocks" }
        require(lba in 0..0xFFFFFFFFL) { "READ(10) LBA out of range: $lba" }
        java.util.Arrays.fill(cb, 0, 10, 0)
        cb[0] = 0x28
        Bin.putU32be(cb, 2, lba) // CDB fields are BIG-endian
        cb[7] = ((blocks shr 8) and 0xFF).toByte()
        cb[8] = (blocks and 0xFF).toByte()
    }

    fun write10(cb: ByteArray, lba: Long, blocks: Int) {
        require(blocks in 1..0xFFFF) { "WRITE(10) block count out of range: $blocks" }
        require(lba in 0..0xFFFFFFFFL) { "WRITE(10) LBA out of range: $lba" }
        java.util.Arrays.fill(cb, 0, 10, 0)
        cb[0] = 0x2A
        Bin.putU32be(cb, 2, lba) // CDB fields are BIG-endian
        cb[7] = ((blocks shr 8) and 0xFF).toByte()
        cb[8] = (blocks and 0xFF).toByte()
    }

    fun parseSense(data: ByteArray): SenseData {
        val valid = data.size >= SENSE_DATA_LEN
        val key = if (valid) (data[2].toInt() and 0x0F) else 0xFF
        val asc = if (valid) (data[12].toInt() and 0xFF) else 0
        val ascq = if (valid) (data[13].toInt() and 0xFF) else 0
        return SenseData(key, asc, ascq)
    }

    /**
     * Parses the 8-byte READ CAPACITY(10) response. Returns (lastLba, blockSize).
     * NOTE: SCSI parameter data is BIG-endian — parsing it little-endian turns
     * a 512-byte sector into 131072 (0x00000200 BE vs 0x00020000 LE).
     */
    fun parseReadCapacity(data: ByteArray): Pair<Long, Int> {
        val lastLba = Bin.u32be(data, 0)
        val blockSize = Bin.u32be(data, 4).toInt()
        return lastLba to blockSize
    }
}
