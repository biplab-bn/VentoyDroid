package com.ventoydroid.app.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BinTest {

    @Test
    fun `u16 round trip`() {
        val b = ByteArray(2)
        Bin.putU16le(b, 0, 0xBEEF)
        assertEquals(0xBEEF, Bin.u16le(b, 0))
    }

    @Test
    fun `u32 round trip with sign handling`() {
        val b = ByteArray(4)
        Bin.putU32le(b, 0, 0xFFFF_8000L)
        assertEquals(0xFFFF_8000L, Bin.u32le(b, 0))
    }

    @Test
    fun `u64 round trip`() {
        val b = ByteArray(8)
        Bin.putU64le(b, 0, 0x0102030405060708L)
        assertEquals(0x0102030405060708L, Bin.u32le(b, 0) or (Bin.u32le(b, 4) shl 32))
    }

    @Test
    fun `crc32 known vector`() {
        assertEquals(0xCBF43926L, "123456789".toByteArray().let { Bin.crc32(it) })
    }

    @Test
    fun `copyChunked copies exact length`() {
        val data = ByteArray(10_000) { (it % 255).toByte() }
        val input = java.io.ByteArrayInputStream(data)
        val out = java.io.ByteArrayOutputStream()
        Bin.copyChunked(input, out, data.size.toLong(), chunkSize = 1000)
        assertArrayEquals(data, out.toByteArray())
    }
}
