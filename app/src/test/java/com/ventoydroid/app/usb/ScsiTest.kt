package com.ventoydroid.app.usb

import com.ventoydroid.app.util.Bin
import org.junit.Assert.assertEquals
import org.junit.Test

class ScsiTest {

    @Test
    fun `read capacity parses big-endian scsi data`() {
        // 8 GiB stick: last LBA 16,777,215 (0x00FFFFFF), 512-byte sectors.
        // These bytes are what a real device sends on the wire.
        val response = byteArrayOf(
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // returned LBA (BE)
            0x00, 0x00, 0x02, 0x00,                            // block length 512 (BE)
        )
        val (lastLba, blockSize) = Scsi.parseReadCapacity(response)
        assertEquals(16_777_215L, lastLba)
        assertEquals(512, blockSize)
    }

    @Test
    fun `the exact regression that broke real hardware`() {
        // 0x00000200 stored big-endian must parse as 512.
        // The old little-endian read produced 131072 and rejected the stick.
        val blockLengthField = byteArrayOf(0x00, 0x00, 0x02, 0x00)
        assertEquals(512, Bin.u32be(blockLengthField, 0).toInt())
        assertEquals(131072L, Bin.u32le(blockLengthField, 0))
    }

    @Test
    fun `read10 write10 encode lba big-endian`() {
        val cb = ByteArray(16)
        Scsi.write10(cb, 2048L, 128)
        assertEquals(0x2A, cb[0].toInt() and 0xFF)
        // LBA 2048 = 0x00000800 must appear as 00 00 08 00 on the wire
        assertEquals(0x00, cb[2].toInt() and 0xFF)
        assertEquals(0x00, cb[3].toInt() and 0xFF)
        assertEquals(0x08, cb[4].toInt() and 0xFF)
        assertEquals(0x00, cb[5].toInt() and 0xFF)
        // block count 128 = 0x0080 big-endian in bytes 7-8
        assertEquals(0x00, cb[7].toInt() and 0xFF)
        assertEquals(0x80, cb[8].toInt() and 0xFF)
    }

    @Test
    fun `read10 write10 handle large lba without byte swap`() {
        val cb = ByteArray(16)
        val lba = 16_711_680L // typical VTOYEFI start on an 8 GiB stick
        Scsi.read10(cb, lba, 1)
        assertEquals(lba, com.ventoydroid.app.util.Bin.u32be(cb, 2))
    }

    @Test
    fun `sense parsing handles keys`() {
        val sense = ByteArray(18)
        sense[2] = 0x02 // NOT READY
        sense[12] = 0x04
        sense[13] = 0x01
        val s = Scsi.parseSense(sense)
        assertEquals(0x02, s.senseKey)
        assertEquals(true, s.isNotReady)
    }
}
