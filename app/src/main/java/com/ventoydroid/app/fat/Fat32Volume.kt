package com.ventoydroid.app.fat

import com.ventoydroid.app.disk.BlockDevice
import com.ventoydroid.app.disk.BlockDeviceException
import com.ventoydroid.app.util.Bin
import java.io.Closeable
import java.io.InputStream
import java.util.GregorianCalendar
import kotlin.math.ceil

/** Metadata for a file found on the volume. */
data class FatFileInfo(
    val name: String,
    val size: Long,
    val firstCluster: Long,
    val isDirectory: Boolean,
)

/**
 * A userspace FAT32 driver over a [BlockDevice]: mounts existing volumes,
 * streams files in and out, and answers free-space queries. Used for the
 * Ventoy data partition (part1) — no OS FAT driver involved.
 */
class Fat32Volume private constructor(
    private val device: BlockDevice,
    private val partStartSector: Long,
    private val partSectorCount: Long,
    val bytesPerSector: Int,
    val sectorsPerCluster: Int,
    private val reservedSectors: Int,
    private val numberOfFats: Int,
    private val rootCluster: Long,
    private val totalSectors: Long,
    private val sectorsPerFat: Long,
    private var fatBytes: ByteArray,
) : Closeable {

    val clusterSize: Int = bytesPerSector * sectorsPerCluster
    val totalClusters: Long = (totalSectors - reservedSectors - numberOfFats * sectorsPerFat) / sectorsPerCluster
    private val firstDataSector: Long = partStartSector + reservedSectors + numberOfFats * sectorsPerFat
    private var fatDirty = false
    private var nextFreeHint: Long = 2

    companion object {
        private const val ATTR_READ_ONLY = 0x01
        private const val ATTR_HIDDEN = 0x02
        private const val ATTR_SYSTEM = 0x04
        private const val ATTR_VOLUME_ID = 0x08
        private const val ATTR_DIRECTORY = 0x10
        private const val ATTR_LONG_NAME = 0x0F
        private const val ATTR_LFN_MASK = 0x3F
        private const val LFN_LAST = 0x40
        private const val ENTRY_FREE = 0x00
        private const val ENTRY_DELETED = 0xE5
        private const val FAT32_EOC = 0x0FFFFFF8
        private const val FAT32_EOC_MARKER = 0x0FFFFFFF
        private const val FAT32_FREE = 0x00000000

        /** Mounts a FAT32 volume living at [partStartSector] on [device]. */
        suspend fun mount(device: BlockDevice, partStartSector: Long, partSectorCount: Long): Fat32Volume {
            val bpb = ByteArray(512)
            device.readSectors(partStartSector, 1, bpb)
            if ((bpb[510].toInt() and 0xFF) != 0x55 || (bpb[511].toInt() and 0xFF) != 0xAA) {
                throw BlockDeviceException("FAT: missing 0x55AA boot signature")
            }
            val bps = Bin.u16le(bpb, 11)
            val spc = bpb[13].toInt() and 0xFF
            val reserved = Bin.u16le(bpb, 14)
            val nfats = bpb[16].toInt() and 0xFF
            val total32 = Bin.u32le(bpb, 32)
            val spf32 = Bin.u32le(bpb, 36)
            val rootClus = Bin.u32le(bpb, 44)
            if (bps != 512) throw BlockDeviceException("FAT: unsupported bytes/sector $bps")
            if (spc == 0 || (spc and (spc - 1)) != 0) {
                throw BlockDeviceException("FAT: invalid sectors/cluster $spc")
            }
            if (nfats == 0 || spf32 == 0L) {
                throw BlockDeviceException("FAT: invalid BPB (not a FAT32 volume?)")
            }
            val total = if (total32 != 0L) total32 else partSectorCount
            val fatSectors = (spf32 * bps).toInt()
            if (fatSectors <= 0 || total > partSectorCount) {
                throw BlockDeviceException("FAT: BPB inconsistent with partition size")
            }
            // Pad the FAT buffer to a whole number of sectors for block I/O.
            val fatAlloc = ((fatSectors + 511) / 512) * 512
            val fatBytes = ByteArray(fatAlloc)
            device.readSectors(partStartSector + reserved, fatAlloc / 512, fatBytes)
            return Fat32Volume(
                device, partStartSector, partSectorCount,
                bps, spc, reserved, nfats, rootClus, total, spf32, fatBytes,
            )
        }

        // ---- DOS date/time ----
        internal fun dosTime(cal: GregorianCalendar = GregorianCalendar()): Int {
            val t = (cal.get(GregorianCalendar.HOUR_OF_DAY) shl 11) or
                (cal.get(GregorianCalendar.MINUTE) shl 5) or
                (cal.get(GregorianCalendar.SECOND) / 2)
            return t and 0xFFFF
        }

        internal fun dosDate(cal: GregorianCalendar = GregorianCalendar()): Int {
            val d = ((cal.get(GregorianCalendar.YEAR) - 1980) shl 9) or
                ((cal.get(GregorianCalendar.MONTH) + 1) shl 5) or
                cal.get(GregorianCalendar.DAY_OF_MONTH)
            return d and 0xFFFF
        }

        /** Generates the LFN checksum of an 11-byte short name. */
        internal fun shortNameChecksum(short: ByteArray): Int {
            var sum = 0
            for (b in short) {
                sum = (((sum and 1) shl 7) + (sum ushr 1) + (b.toInt() and 0xFF)) and 0xFF
            }
            return sum
        }

        internal fun build83ShortName(name: String, existing: Set<String>): ByteArray {
            val cleaned = name.uppercase().replace(Regex("[^A-Z0-9!#\\$%&'()@^_`{}~.-]"), "_")
            val dot = cleaned.lastIndexOf('.')
            var base = if (dot > 0) cleaned.substring(0, dot) else cleaned
            var ext = if (dot > 0) cleaned.substring(dot + 1) else ""
            base = base.replace(".", "_").take(8)
            ext = ext.replace(".", "_").take(3)

            fun make(tail: Int): ByteArray {
                val b = ByteArray(11) { ' '.code.toByte() }
                val bName = if (tail == 0) base else base.take(8 - "~$tail".length) + "~$tail"
                bName.toByteArray().copyInto(b, 0)
                ext.toByteArray().copyInto(b, 8)
                return b
            }

            var tail = 0
            var candidate = make(0)
            while (existing.contains(String(candidate, Charsets.US_ASCII))) {
                tail += 1
                candidate = make(tail)
            }
            return candidate
        }
    }

    // ---------------- FAT access ----------------

    private fun fatEntry(cluster: Long): Long {
        val off = (cluster * 4).toInt()
        return Bin.u32le(fatBytes, off) and 0x0FFFFFFFL
    }

    private fun fatSetEntry(cluster: Long, value: Long) {
        val off = (cluster * 4).toInt()
        Bin.putU32le(fatBytes, off, value)
        fatDirty = true
    }

    private fun isEoc(v: Long) = v >= FAT32_EOC

    private suspend fun flushFat() {
        if (!fatDirty) return
        val fatSectorsInt = ((fatBytes.size + 511) / 512).toInt()
        val buf = fatBytes
        for (f in 0 until numberOfFats) {
            device.writeSectors(partStartSector + reservedSectors + f * sectorsPerFat, fatSectorsInt, buf)
        }
        fatDirty = false
    }

    fun freeClusters(): Long {
        var free = 0L
        for (c in 2 until totalClusters + 2) {
            if (fatEntry(c) == FAT32_FREE.toLong()) free++
        }
        return free
    }

    private fun findFreeCluster(): Long {
        val count = totalClusters
        for (i in 0 until count) {
            val c = 2 + ((nextFreeHint - 2 + i) % count)
            if (fatEntry(c) == FAT32_FREE.toLong()) {
                nextFreeHint = c + 1
                return c
            }
        }
        throw BlockDeviceException("FAT: volume full")
    }

    // ---------------- cluster I/O ----------------

    private suspend fun readCluster(cluster: Long, out: ByteArray, offset: Int = 0) {
        device.readSectors(sectorOf(cluster), sectorsPerCluster, out, offset)
    }

    private suspend fun writeCluster(cluster: Long, src: ByteArray, offset: Int = 0) {
        device.writeSectors(sectorOf(cluster), sectorsPerCluster, src, offset)
    }

    private fun sectorOf(cluster: Long): Long =
        firstDataSector + (cluster - 2) * sectorsPerCluster

    /** Walks the chain starting at [first], returning the clusters in order. */
    private fun chainOf(first: Long): List<Long> {
        val chain = ArrayList<Long>()
        var c = first
        var guard = 0
        while (c in 2 until totalClusters + 2) {
            chain.add(c) // the first cluster is always part of the chain
            val next = fatEntry(c)
            if (isEoc(next)) break
            c = next
            if (++guard > totalClusters) throw BlockDeviceException("FAT: cluster chain loop")
        }
        return chain
    }

    // ---------------- directory handling ----------------

    private suspend fun readDirChain(firstCluster: Long): ByteArray {
        val chunks = ArrayList<ByteArray>()
        val buf = ByteArray(clusterSize)
        var c = firstCluster
        var guard = 0
        while (c in 2 until totalClusters + 2) {
            readCluster(c, buf)
            chunks.add(buf.copyOf())
            val next = fatEntry(c)
            if (isEoc(next)) break
            c = next
            if (++guard > totalClusters) throw BlockDeviceException("FAT: directory chain loop")
        }
        val total = chunks.sumOf { it.size }
        val out = ByteArray(total)
        var off = 0
        for (ch in chunks) {
            ch.copyInto(out, off)
            off += ch.size
        }
        return out
    }

    private fun rootClusterFor(first: Long): Long = first

    /** Writes [data] as the directory chain at [first]; grows/frees the chain as needed. */
    private suspend fun writeDirChain(first: Long, data: ByteArray) {
        val needed = ceil(data.size / clusterSize.toDouble()).toLong().coerceAtLeast(1)
        val chain = chainOf(first).toMutableList()
        // grow
        while (chain.size < needed) {
            val nc = findFreeCluster()
            if (chain.isEmpty()) throw BlockDeviceException("FAT: empty directory chain")
            fatSetEntry(chain.last(), nc)
            chain.add(nc)
        }
        // shrink
        while (chain.size > needed) {
            val last = chain.removeAt(chain.size - 1)
            fatSetEntry(if (chain.isEmpty()) last else chain.last(), if (chain.isEmpty()) FAT32_FREE.toLong() else FAT32_EOC_MARKER.toLong())
            if (chain.isEmpty()) break
        }
        val buf = ByteArray(clusterSize)
        var off = 0
        for ((i, c) in chain.withIndex()) {
            buf.fill(0)
            val n = minOf(clusterSize, data.size - off)
            if (n > 0) data.copyInto(buf, 0, off, off + n)
            writeCluster(c, buf)
            off += n
        }
        if (chain.isNotEmpty()) fatSetEntry(chain.last(), FAT32_EOC_MARKER.toLong())
    }

    private fun dirEntries(data: ByteArray): Sequence<Pair<Int, ByteArray>> =
        sequence {
            var off = 0
            while (off + 32 <= data.size) {
                yield(off to data)
                off += 32
            }
        }

    private fun isEndOfDir(entry: ByteArray, off: Int) = entry[off] == ENTRY_FREE.toByte()

    private fun isDeleted(entry: ByteArray, off: Int) =
        (entry[off].toInt() and 0xFF) == ENTRY_DELETED

    private fun isLfn(entry: ByteArray, off: Int): Boolean {
        val attr = entry[off + 11].toInt() and 0xFF
        return attr == ATTR_LONG_NAME
    }

    /** Parses the long name + short name of the entry whose short entry is at [off]. */
    private fun parseName(data: ByteArray, shortOff: Int, lfnCount: Int): String {
        if (lfnCount == 0) {
            val sb = StringBuilder()
            for (i in 0 until 8) {
                val c = data[shortOff + i].toInt() and 0xFF
                if (c == ' '.code) break
                sb.append(c.toChar())
            }
            val dot = sb.length
            for (i in 8 until 11) {
                val c = data[shortOff + i].toInt() and 0xFF
                if (c == ' '.code) break
                sb.append(c.toChar())
            }
            return if (sb.length > dot) {
                sb.insert(dot, '.').toString()
            } else sb.toString()
        }
        val sb = StringBuilder()
        // LFN entries: ordinal k sits at shortOff - 32*k and holds chars (k-1)*13..k*13-1
        for (k in 1..lfnCount) {
            val off = shortOff - 32 * k
            for (i in intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 26, 28)) {
                val lo = data[off + i].toInt() and 0xFF
                val hi = data[off + i + 1].toInt() and 0xFF
                val v = (hi shl 8) or lo
                if (v == 0x0000 || v == 0xFFFF) break
                sb.append(v.toChar())
            }
        }
        return sb.toString()
    }

    private data class DirEntryInfo(
        val offset: Int,
        val lfnCount: Int,
        val attr: Int,
        val firstCluster: Long,
        val size: Long,
        val name: String,
    )

    private fun scanDir(data: ByteArray): List<DirEntryInfo> {
        val out = ArrayList<DirEntryInfo>()
        var off = 0
        while (off + 32 <= data.size) {
            if (isEndOfDir(data, off)) break
            if (isDeleted(data, off) || isLfn(data, off)) {
                off += 32
                continue
            }
            val attr = data[off + 11].toInt() and 0xFF
            if (attr and ATTR_VOLUME_ID != 0) {
                off += 32
                continue
            }
            var lfnCount = 0
            var p = off - 32
            while (p >= 0 && isLfn(data, p)) {
                lfnCount++
                p -= 32
            }
            val clusHi = Bin.u16le(data, off + 20)
            val clusLo = Bin.u16le(data, off + 26)
            out.add(
                DirEntryInfo(
                    offset = off,
                    lfnCount = lfnCount,
                    attr = attr,
                    firstCluster = ((clusHi.toLong() shl 16) or clusLo.toLong()),
                    size = Bin.u32le(data, off + 28),
                    name = parseName(data, off, lfnCount),
                )
            )
            off += 32
        }
        return out
    }

    private fun exists(entries: List<DirEntryInfo>, name: String) =
        entries.any { it.name.equals(name, ignoreCase = true) }

    // ---------------- public API ----------------

    suspend fun listFiles(dir: String = "/"): List<FatFileInfo> {
        val e = resolveDir(dir) ?: throw BlockDeviceException("FAT: no such directory $dir")
        val data = readDirChain(e.firstCluster)
        return scanDir(data)
            .filter { it.attr and ATTR_VOLUME_ID == 0 }
            .map { FatFileInfo(it.name, it.size, it.firstCluster, it.attr and ATTR_DIRECTORY != 0) }
    }

    private data class DirRef(val firstCluster: Long, val path: String)

    private suspend fun resolveDir(path: String): DirRef {
        var cur = DirRef(rootCluster, "/")
        for (seg in path.split('/').filter { it.isNotEmpty() }) {
            val data = readDirChain(cur.firstCluster)
            val hit = scanDir(data).firstOrNull { it.name.equals(seg, ignoreCase = true) }
                ?: throw BlockDeviceException("FAT: path not found: $path (segment '$seg')")
            if (hit.attr and ATTR_DIRECTORY == 0) {
                throw BlockDeviceException("FAT: '$seg' is not a directory")
            }
            cur = DirRef(hit.firstCluster, cur.path + seg + "/")
        }
        return cur
    }

    /**
     * Streams [size] bytes from [input] into a newly created file at [path],
     * writing clusters straight to the device. Never holds the whole file in
     * memory. Calls [onProgress] with the number of bytes written so far.
     */
    suspend fun writeFileStream(
        path: String,
        size: Long,
        input: InputStream,
        onProgress: (Long) -> Unit = {},
    ) {
        val (parentPath, name) = splitPath(path)
        val parent = resolveDir(parentPath)
        val dirData = readDirChain(parent.firstCluster)
        val entries = scanDir(dirData)
        if (exists(entries, name)) throw BlockDeviceException("FAT: '$name' already exists in $parentPath")

        val needed = ceil(size / clusterSize.toDouble()).toLong()
        val free = freeClusters()
        if (free < needed) {
            throw BlockDeviceException(
                "FAT: not enough space (need $needed clusters, free $free)"
            )
        }

        // Pre-allocate the whole chain so failure happens before any data lands.
        val chain = ArrayList<Long>(needed.toInt())
        for (i in 0 until needed) {
            val c = findFreeCluster()
            if (chain.isNotEmpty()) fatSetEntry(chain.last(), c)
            chain.add(c)
        }
        if (chain.isNotEmpty()) fatSetEntry(chain.last(), FAT32_EOC_MARKER.toLong())
        val firstCluster = if (chain.isEmpty()) 0L else chain.first()

        val buf = ByteArray(clusterSize)
        var written = 0L
        for ((i, c) in chain.withIndex()) {
            val want = minOf(clusterSize.toLong(), size - written).toInt()
            buf.fill(0)
            Bin.readExact(input, buf, want)
            writeCluster(c, buf)
            written += want
            onProgress(written)
        }
        flushFat()

        appendDirEntry(dirData, parent.firstCluster, buildEntriesForFile(name, firstCluster, size))
    }

    /** Streams a file out cluster by cluster (for verification hashing). */
    suspend fun readFileStreaming(path: String, sink: (ByteArray, Int) -> Unit) {
        val (parentPath, name) = splitPath(path)
        val parent = resolveDir(parentPath)
        val entries = scanDir(readDirChain(parent.firstCluster))
        val hit = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: throw BlockDeviceException("FAT: no such file $path")
        val buf = ByteArray(clusterSize)
        var remaining = hit.size
        var c = hit.firstCluster
        while (remaining > 0) {
            if (c !in 2 until totalClusters + 2) throw BlockDeviceException("FAT: file chain corrupt")
            readCluster(c, buf)
            val n = minOf(remaining, clusterSize.toLong()).toInt()
            sink(buf, n)
            remaining -= n
            c = fatEntry(c)
        }
    }

    suspend fun setVolumeLabel(label: String) {
        val short = ByteArray(11) { ' '.code.toByte() }
        label.uppercase().take(11).toByteArray(Charsets.US_ASCII).copyInto(short)
        val data = readDirChain(rootCluster)
        // replace existing label if present
        var off = 0
        while (off + 32 <= data.size) {
            if (isEndOfDir(data, off)) break
            val attr = data[off + 11].toInt() and 0xFF
            if (attr and ATTR_VOLUME_ID != 0 && !isLfn(data, off)) {
                short.copyInto(data, off)
                writeDirChain(rootCluster, data)
                return
            }
            off += 32
        }
        // append new label
        val entry = ByteArray(32)
        short.copyInto(entry, 0)
        entry[11] = ATTR_VOLUME_ID.toByte()
        appendDirEntry(data, rootCluster, entry)
    }

    // ---------------- entry building ----------------

    private fun buildEntriesForFile(name: String, firstCluster: Long, size: Long): ByteArray {
        val entries = ArrayList<ByteArray>(2)
        val lfnChars = name.toCharArray()
        val needLfn = name.length > 12 || name.any { it.code > 127 } ||
            name.any { it.isLowerCase() }

        if (needLfn) {
            val n = ((name.length + 12) / 13).coerceAtLeast(1) // ceil(len/13)
            // 8.3 fallback short name must not collide — caller ensures dir is fresh
            val short = build83ShortName(name, emptySet())
            val csum = shortNameChecksum(short)
            for (k in n downTo 1) {
                val e = ByteArray(32)
                e[0] = (k or (if (k == n) LFN_LAST else 0)).toByte()
                e[11] = ATTR_LONG_NAME.toByte()
                e[12] = 0
                e[13] = csum.toByte()
                fillLfnName(e, lfnChars, (k - 1) * 13)
                entries.add(e)
            }
            entries.add(buildShortEntry(short, firstCluster, size, attr = 0))
        } else {
            val short = build83ShortName(name, emptySet())
            entries.add(buildShortEntry(short, firstCluster, size, attr = 0))
        }
        val out = ByteArray(entries.sumOf { it.size })
        var off = 0
        for (e in entries) {
            e.copyInto(out, off)
            off += e.size
        }
        return out
    }

    private fun fillLfnName(e: ByteArray, chars: CharArray, start: Int) {
        val slots = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 26, 28)
        for ((idx, slot) in slots.withIndex()) {
            val ci = start + idx
            val pad = when {
                ci < chars.size -> chars[ci].code
                ci == chars.size -> 0x0000 // terminating NUL
                else -> 0xFFFF             // padding
            }
            e[slot] = (pad and 0xFF).toByte()
            e[slot + 1] = ((pad shr 8) and 0xFF).toByte()
        }
    }

    private fun buildShortEntry(short: ByteArray, firstCluster: Long, size: Long, attr: Int): ByteArray {
        val e = ByteArray(32)
        short.copyInto(e, 0)
        e[11] = attr.toByte()
        e[13] = 0 // creation tenths
        val cal = GregorianCalendar()
        Bin.putU16le(e, 14, dosTime(cal))
        Bin.putU16le(e, 16, dosDate(cal))
        Bin.putU16le(e, 18, dosDate(cal)) // access date
        Bin.putU16le(e, 20, ((firstCluster shr 16) and 0xFFFF).toInt())
        Bin.putU16le(e, 22, dosTime(cal))
        Bin.putU16le(e, 24, dosDate(cal))
        Bin.putU16le(e, 26, (firstCluster and 0xFFFF).toInt())
        Bin.putU32le(e, 28, size)
        return e
    }

    /** Appends prebuilt 32-byte entries to the directory chain, growing it if needed. */
    private suspend fun appendDirEntry(dirData: ByteArray, dirFirstCluster: Long, newEntries: ByteArray) {
        var freeAt = 0
        while (freeAt + 32 <= dirData.size) {
            if (isEndOfDir(dirData, freeAt)) break
            freeAt += 32
        }
        val required = freeAt + newEntries.size + 32 // keep a terminator
        val out = if (required <= dirData.size) dirData.copyOf() else {
            ByteArray(required).also { dirData.copyInto(it) }
        }
        newEntries.copyInto(out, freeAt)
        writeDirChain(dirFirstCluster, out)
        flushFat() // directory growth touched the FAT too
    }

    private fun splitPath(path: String): Pair<String, String> {
        val trimmed = path.trim('/')
        val idx = trimmed.lastIndexOf('/')
        return if (idx < 0) "/" to trimmed
        else "/" + trimmed.substring(0, idx) to trimmed.substring(idx + 1)
    }

    override fun close() {
        // Writes are flushed eagerly; nothing buffered here.
    }
}
