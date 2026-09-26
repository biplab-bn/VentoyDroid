package com.ventoydroid.app.util

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

object Bin {
    fun u16le(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    fun u32le(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    /** SCSI descriptor fields are big-endian (unlike USB wrapper fields). */
    fun u32be(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or
            ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or
            (b[off + 3].toLong() and 0xFF)

    fun putU32be(b: ByteArray, off: Int, v: Long) {
        b[off] = ((v shr 24) and 0xFF).toByte()
        b[off + 1] = ((v shr 16) and 0xFF).toByte()
        b[off + 2] = ((v shr 8) and 0xFF).toByte()
        b[off + 3] = (v and 0xFF).toByte()
    }

    fun putU16le(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    fun putU32le(b: ByteArray, off: Int, v: Long) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    fun putU64le(b: ByteArray, off: Int, v: Long) {
        putU32le(b, off, v)
        putU32le(b, off + 4, v ushr 32)
    }

    fun u64le(b: ByteArray, off: Int): Long =
        u32le(b, off) or (u32le(b, off + 4) shl 32)

    fun ascii(b: ByteArray, off: Int, len: Int): String {
        val sb = StringBuilder(len)
        for (i in 0 until len) {
            val c = b[off + i].toInt() and 0xFF
            if (c == 0) break
            sb.append(c.toChar())
        }
        return sb.toString().trim()
    }

    fun crc32(bytes: ByteArray, offset: Int = 0, len: Int = bytes.size, seed: Long = 0L): Long {
        var crc = seed.inv() and 0xFFFFFFFFL // stay in 32 bits or the shifts corrupt everything
        for (i in offset until offset + len) {
            crc = crc xor (bytes[i].toLong() and 0xFF)
            repeat(8) {
                crc = if (crc and 1L != 0L) ((crc ushr 1) xor 0xEDB88320L) and 0xFFFFFFFFL else crc ushr 1
            }
        }
        return crc.inv() and 0xFFFFFFFFL
    }

    /** Reads exactly [len] bytes or throws. */
    fun readExact(input: InputStream, buffer: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val n = input.read(buffer, off, len - off)
            if (n < 0) throw EOFException("Unexpected end of stream after $off of $len bytes")
            off += n
        }
    }

    /** Copies [len] bytes from [input] to [output] in [chunkSize] steps. */
    fun copyChunked(
        input: InputStream,
        output: OutputStream,
        len: Long,
        chunkSize: Int = 1 shl 20,
        onChunk: (copiedSoFar: Long) -> Unit = {},
    ): Long {
        val buffer = ByteArray(chunkSize)
        var done = 0L
        while (done < len) {
            val want = minOf(chunkSize.toLong(), len - done).toInt()
            readExact(input, buffer, want)
            output.write(buffer, 0, want)
            done += want
            onChunk(done)
        }
        return done
    }
}
