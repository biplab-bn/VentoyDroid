package com.ventoydroid.app.fat

import com.ventoydroid.app.disk.BlockDevice
import com.ventoydroid.app.disk.BlockDeviceException
import com.ventoydroid.app.util.Bin
import java.io.Closeable
import java.io.InputStream

/** Metadata for a file found on an exFAT volume. */
data class ExFatFileInfo(
    val name: String,
    val size: Long,
    val firstCluster: Long,
)

/**
 * A userspace exFAT driver over a [BlockDevice], used for the Ventoy data
 * partition when ISOs exceed FAT32's 4 GiB per-file limit (Windows 11,
 * large Ubuntu images, ...). Reads the boot sector and root directory,
 * lists files and streams file data in and out.
 *
 * Written files use one contiguous pre-allocated cluster run with the
 * NoFatChain flag set — the layout official Ventoy prefers and the simplest
 * crash-consistent scheme for multi-GiB ISOs (data lands before the
 * directory entry does).
 */
class ExFatVolume private constructor(
    private val device: BlockDevice,
    private val partStartSector: Long,
    private val partSectorCount: Long,
    val bytesPerSector: Int,
    val sectorsPerCluster: Int,
    private val clusterHeapOffset: Long,
    val clusterCount: Long,
    private val rootCluster: Long,
) : Closeable {

    val clusterBytes: Int = bytesPerSector * sectorsPerCluster
    val totalClusters: Long = clusterCount

    private var bitmapCache: ByteArray? = null
    private var bitmapDirty = false

    companion object {
        private const val ENTRY_END = 0x00
        private const val ENTRY_DELETED = 0xE5 // any type with the in-use bit clear
        private const val TYPE_BITMAP = 0x81
        private const val TYPE_UPCASE = 0x82
        private const val TYPE_VOLUME_LABEL = 0x83
        private const val TYPE_FILE = 0x85
        private const val TYPE_STREAM = 0xC0
        private const val TYPE_NAME = 0xC1
        private const val NO_FAT_CHAIN = 0x02
        private const val IN_USE_MASK = 0x80 // clear => dentry not in use

        /** Mounts an exFAT volume living at [partStartSector] on [device]. */
        suspend fun mount(device: BlockDevice, partStartSector: Long, partSectorCount: Long): ExFatVolume {
            val boot = ByteArray(512)
            device.readSectors(partStartSector, 1, boot)
            if ((boot[510].toInt() and 0xFF) != 0x55 || (boot[511].toInt() and 0xFF) != 0xAA) {
                throw BlockDeviceException("exFAT: missing 0x55AA boot signature")
            }
            val oemName = String(boot, 3, 8, Charsets.US_ASCII)
            if (!oemName.startsWith("EXFAT")) {
                throw BlockDeviceException("exFAT: boot sector says '$oemName' (not exFAT)")
            }
            val bpsShift = boot[108].toInt()
            val spcShift = boot[109].toInt()
            if (bpsShift != 9) {
                throw BlockDeviceException("exFAT: unsupported bytes/sector shift $bpsShift")
            }
            val bytesPerSector = 1 shl bpsShift
            val sectorsPerCluster = 1 shl spcShift
            val volumeLength = Bin.u64le(boot, 72)
            if (volumeLength > partSectorCount) {
                throw BlockDeviceException("exFAT: boot sector volume length exceeds partition")
            }
            val clusterHeapOffset = Bin.u32le(boot, 88)
            val clusterCount = Bin.u32le(boot, 92)
            val rootCluster = Bin.u32le(boot, 96)
            if (clusterHeapOffset + clusterCount * sectorsPerCluster > volumeLength) {
                throw BlockDeviceException("exFAT: cluster heap exceeds volume")
            }
            return ExFatVolume(
                device, partStartSector, partSectorCount,
                bytesPerSector, sectorsPerCluster,
                clusterHeapOffset, clusterCount, rootCluster,
            )
        }

        /**
         * exFAT name hash over the upcased UTF-16 code units: rotate-left-1
         * accumulate, one byte at a time, high byte first (verified against
         * exfatprogs lib/exfat_dir.c exfat_calc_name_hash).
         */
        internal fun nameHash(name: String, upcase: ShortArray): Int {
            var hash = 0
            for (ch in name) {
                val code = ch.code
                val u = if (code < upcase.size) (upcase[code].toInt() and 0xFFFF) else code
                hash = ((hash shl 15) or (hash ushr 1)) and 0xFFFF
                hash = (hash + ((u shr 8) and 0xFF)) and 0xFFFF
                hash = ((hash shl 15) or (hash ushr 1)) and 0xFFFF
                hash = (hash + (u and 0xFF)) and 0xFFFF
            }
            return hash
        }

        /**
         * Secondary-stream checksum over a dentry set (verified against
         * exfatprogs): the File (primary) entry contributes bytes 0..1 and
         * 4..31 (checksum field 2..3 is excluded); every other entry
         * contributes bytes 2..31 (type/in-use byte pair excluded).
         */
        internal fun dentrySetChecksum(entries: List<ByteArray>): Int {
            var sum = 0
            for ((idx, e) in entries.withIndex()) {
                var i = if (idx == 0) 0 else 2
                while (i < 32) {
                    if (idx == 0 && i == 2) { i = 4; continue } // skip the checksum field itself
                    sum = ((((sum and 1) shl 15) or (sum ushr 1)) and 0xFFFF) + (e[i].toInt() and 0xFF)
                    sum = sum and 0xFFFF
                    i++
                }
            }
            return sum
        }

        /** DOS/OTA time and date packing (shared with the dentry builder). */
        private fun dosTime(cal: java.util.GregorianCalendar): Int =
            ((cal.get(java.util.GregorianCalendar.HOUR_OF_DAY) shl 11) or
                (cal.get(java.util.GregorianCalendar.MINUTE) shl 5) or
                (cal.get(java.util.GregorianCalendar.SECOND) / 2)) and 0xFFFF

        private fun dosDate(cal: java.util.GregorianCalendar): Int =
            (((cal.get(java.util.GregorianCalendar.YEAR) - 1980) shl 9) or
                ((cal.get(java.util.GregorianCalendar.MONTH) + 1) shl 5) or
                cal.get(java.util.GregorianCalendar.DAY_OF_MONTH)) and 0xFFFF

        /**
         * Builds File + Stream + Name dentries for a contiguous file, with the
         * field offsets and set checksum exactly as exfatprogs/the Windows
         * formatter write them.
         */
        internal fun buildDentrySet(
            name: String,
            size: Long,
            firstCluster: Long,
            upcase: ShortArray,
        ): ByteArray {
            val nameUnits = name.map { it.code }.toIntArray()
            val nameLen = nameUnits.size
            val nameDentries = ((nameLen + 14) / 15).coerceAtLeast(1)
            val secondary = 1 + nameDentries // stream + names
            val hash = nameHash(name, upcase)

            // File entry (32 bytes)
            val file = ByteArray(32)
            file[0] = TYPE_FILE.toByte()
            file[1] = secondary.toByte()
            // checksum at 2..3, filled in below
            Bin.putU16le(file, 4, 0x20)        // attributes: archive
            Bin.putU16le(file, 6, 0)           // reserved1
            val cal = java.util.GregorianCalendar()
            val t = dosTime(cal); val d = dosDate(cal)
            Bin.putU16le(file, 8, t)           // create time
            Bin.putU16le(file, 10, d)          // create date
            Bin.putU16le(file, 12, t)          // modify time
            Bin.putU16le(file, 14, d)          // modify date
            Bin.putU16le(file, 16, t)          // access time
            Bin.putU16le(file, 18, d)          // access date
            // 20 create_10ms, 21 modify_ms, 22..24 tz offsets (0 = UTC), 25..31 reserved: zeros

            // Stream extension entry (32 bytes)
            val stream = ByteArray(32)
            stream[0] = TYPE_STREAM.toByte()
            stream[1] = NO_FAT_CHAIN.toByte()  // allocation flags: contiguous
            stream[2] = 0                      // reserved1
            stream[3] = nameLen.toByte()       // name length (UTF-16 code units)
            Bin.putU16le(stream, 4, hash)      // name hash
            Bin.putU16le(stream, 6, 0)         // reserved2
            Bin.putU64le(stream, 8, size)      // valid data length == size (fully written)
            Bin.putU32le(stream, 16, 0)        // reserved3
            Bin.putU32le(stream, 20, firstCluster)
            Bin.putU64le(stream, 24, size)

            // Name entries (32 bytes each, 15 UTF-16 code units per entry)
            val parts = ArrayList<ByteArray>(2 + nameDentries)
            parts.add(file)
            parts.add(stream)
            for (k in 0 until nameDentries) {
                val e = ByteArray(32)
                e[0] = TYPE_NAME.toByte()
                e[1] = 0 // flags
                for (ci in 0 until 15) {
                    val idx = k * 15 + ci
                    val u = when {
                        idx < nameLen -> nameUnits[idx]
                        idx == nameLen -> 0x0000
                        else -> 0xFFFF
                    }
                    Bin.putU16le(e, 2 + ci * 2, u)
                }
                parts.add(e)
            }
            Bin.putU16le(file, 2, dentrySetChecksum(parts))

            val out = ByteArray(parts.sumOf { it.size })
            var off = 0
            for (p in parts) { p.copyInto(out, off); off += p.size }
            return out
        }
    }

    // ---------------- cluster / bitmap ----------------

    private fun clusterSector(cluster: Long): Long =
        partStartSector + clusterHeapOffset + (cluster - 2) * sectorsPerCluster

    private suspend fun bitmapEntry(): RootDentry =
        scanRoot().firstOrNull { it.type == TYPE_BITMAP }
            ?: throw BlockDeviceException("exFAT: allocation bitmap entry missing")

    private suspend fun loadBitmap(): ByteArray {
        val bmp = bitmapEntry()
        val size = bmp.size.toInt()
        if (size <= 0 || (bmp.firstCluster !in 2 until clusterCount + 2)) {
            throw BlockDeviceException("exFAT: bitmap entry corrupt")
        }
        val padded = ByteArray(((size + clusterBytes - 1) / clusterBytes) * clusterBytes)
        var c = bmp.firstCluster
        var off = 0
        while (off < padded.size) {
            device.readSectors(clusterSector(c), sectorsPerCluster, padded, off)
            off += clusterBytes
            c++
        }
        return padded.copyOf(size)
    }

    private suspend fun flushBitmap() {
        val bmp = bitmapCache ?: return
        if (!bitmapDirty) return
        val bmpEntry = bitmapEntry()
        val padded = ByteArray(((bmp.size + clusterBytes - 1) / clusterBytes) * clusterBytes)
        bmp.copyInto(padded)
        var c = bmpEntry.firstCluster
        var off = 0
        while (off < padded.size) {
            device.writeSectors(clusterSector(c), sectorsPerCluster, padded, off)
            off += clusterBytes
            c++
        }
        bitmapDirty = false
    }

    /** Bit i of the bitmap describes cluster i+2 (spec 7.2.6). */
    private suspend fun bitmapIsUsed(bmp: ByteArray, cluster: Long): Boolean {
        val bit = (cluster - 2).toInt()
        val byte = bmp[bit / 8].toInt() and 0xFF
        return byte and (1 shl (bit % 8)) != 0
    }

    // ---------------- root directory ----------------

    private data class RootDentry(
        val type: Int,
        val firstCluster: Long = 0,
        val size: Long = 0,
        val name: String = "",
        val noFatChain: Boolean = false,
        val rawSet: List<ByteArray> = emptyList(),
    )

    private suspend fun readRoot(): ByteArray {
        val buf = ByteArray(clusterBytes)
        device.readSectors(clusterSector(rootCluster), sectorsPerCluster, buf)
        return buf
    }

    private suspend fun scanRoot(): List<RootDentry> {
        val buf = readRoot()
        val out = ArrayList<RootDentry>()
        var i = 0
        while (i + 32 <= buf.size) {
            val t = buf[i].toInt() and 0xFF
            if (t == ENTRY_END) break
            if (t == ENTRY_DELETED || (t and IN_USE_MASK) == 0) { i += 32; continue }
            when (t) {
                TYPE_BITMAP -> out.add(
                    RootDentry(TYPE_BITMAP, firstCluster = Bin.u32le(buf, i + 20), size = Bin.u64le(buf, i + 24))
                )
                TYPE_VOLUME_LABEL, TYPE_UPCASE -> Unit // not needed for file ops
                TYPE_FILE -> {
                    val secondary = buf[i + 1].toInt() and 0xFF
                    val total = 32 * (secondary + 1)
                    if (secondary < 2 || i + total > buf.size) break // truncated set
                    val set = (0..secondary).map { k -> buf.copyOfRange(i + k * 32, i + (k + 1) * 32) }
                    val stream = set[1]
                    val flags = stream[1].toInt() and 0xFF
                    val nameLen = stream[3].toInt() and 0xFF
                    val firstCluster = Bin.u32le(stream, 20)
                    val size = Bin.u64le(stream, 24)
                    val sb = StringBuilder()
                    for (k in 2 until set.size) {
                        val nameE = set[k]
                        for (ci in 0 until 15) {
                            val u = Bin.u16le(nameE, 2 + ci * 2)
                            if (u == 0 || u == 0xFFFF) break
                            sb.append(u.toChar())
                        }
                    }
                    val name = sb.toString().take(nameLen)
                    out.add(
                        RootDentry(
                            type = TYPE_FILE,
                            firstCluster = firstCluster,
                            size = size,
                            name = name,
                            noFatChain = flags and NO_FAT_CHAIN != 0,
                            rawSet = set,
                        )
                    )
                    i += total
                    continue
                }
            }
            i += 32
        }
        return out
    }

    // ---------------- public API ----------------

    suspend fun listFiles(): List<ExFatFileInfo> =
        scanRoot().filter { it.type == TYPE_FILE }.map { ExFatFileInfo(it.name, it.size, it.firstCluster) }

    /** Free cluster count from the allocation bitmap. */
    suspend fun freeClusters(): Long {
        val bmp = bitmapCache ?: loadBitmap().also { bitmapCache = it }
        var free = 0L
        for (cl in 2 until clusterCount + 2) {
            if (!bitmapIsUsed(bmp, cl)) free++
        }
        return free
    }

    /**
     * Streams [size] bytes from [input] into a new file /name in the root
     * directory as one contiguous pre-allocated run with NoFatChain set.
     * Data is written before the directory entry, so an interrupted install
     * leaves allocated-but-invisible clusters rather than a truncated file.
     */
    suspend fun writeFileStream(
        name: String,
        size: Long,
        input: InputStream,
        onProgress: (Long) -> Unit = {},
    ) {
        if (name.isEmpty() || name.length > 255 || name.contains('/') || name.contains('\\')) {
            throw BlockDeviceException("exFAT: invalid file name '$name'")
        }
        if (scanRoot().any { it.type == TYPE_FILE && it.name.equals(name, ignoreCase = true) }) {
            throw BlockDeviceException("exFAT: '$name' already exists")
        }
        val bmp = bitmapCache ?: loadBitmap().also { bitmapCache = it }
        val needClusters = ((size + clusterBytes - 1) / clusterBytes).coerceAtLeast(1)
        var free = 0L
        for (cl in 2 until clusterCount + 2) {
            if (!bitmapIsUsed(bmp, cl)) free++
        }
        if (free < needClusters) {
            throw BlockDeviceException("exFAT: not enough space (need $needClusters clusters, free $free)")
        }
        // First-fit contiguous run — fresh volumes are empty, so this lands
        // right at cluster 2 behind the metadata.
        var runStart = -1L
        var runLen = 0L
        var c = 2L
        while (c < clusterCount + 2) {
            if (!bitmapIsUsed(bmp, c)) {
                runLen++
                if (runLen == needClusters) { runStart = c - needClusters + 1; break }
            } else {
                runLen = 0
            }
            c++
        }
        if (runStart < 0) {
            throw BlockDeviceException("exFAT: no contiguous run of $needClusters clusters (volume fragmented?)")
        }
        for (cl in runStart until runStart + needClusters) {
            val bit = (cl - 2).toInt()
            val idx = bit / 8
            bmp[idx] = (bmp[idx].toInt() or (1 shl (bit % 8))).toByte()
        }
        bitmapDirty = true

        // 1) data
        val buf = ByteArray(clusterBytes)
        var written = 0L
        var cl = runStart
        while (written < size) {
            val want = minOf(clusterBytes.toLong(), size - written).toInt()
            buf.fill(0)
            Bin.readExact(input, buf, want)
            device.writeSectors(clusterSector(cl), sectorsPerCluster, buf)
            written += want
            onProgress(written)
            cl++
        }
        // 2) directory entry (makes the file visible)
        val upcase = ExFatUpcase.decoded()
        val set = buildDentrySet(name, size, runStart, upcase)
        val root = readRoot()
        var off = 0
        while (off + 32 <= root.size && root[off].toInt() != ENTRY_END) off += 32
        if (off + set.size > root.size) throw BlockDeviceException("exFAT: root directory full")
        set.copyInto(root, off)
        device.writeSectors(clusterSector(rootCluster), sectorsPerCluster, root)
        // 3) allocation bitmap
        flushBitmap()
    }

    /** Streams a file's bytes out cluster by cluster (for verify hashing). */
    suspend fun readFileStreaming(name: String, sink: (ByteArray, Int) -> Unit) {
        val hit = scanRoot().firstOrNull { it.type == TYPE_FILE && it.name.equals(name, ignoreCase = true) }
            ?: throw BlockDeviceException("exFAT: no such file $name")
        if (!hit.noFatChain) throw BlockDeviceException("exFAT: '${hit.name}' is not contiguous (not written by us)")
        val buf = ByteArray(clusterBytes)
        var remaining = hit.size
        var c = hit.firstCluster
        while (remaining > 0) {
            if (c < 2 || c >= clusterCount + 2) throw BlockDeviceException("exFAT: file chain corrupt")
            device.readSectors(clusterSector(c), sectorsPerCluster, buf)
            val n = minOf(remaining, clusterBytes.toLong()).toInt()
            sink(buf, n)
            remaining -= n
            c++
        }
    }

    // ---------------- dentry set building ----------------

    override fun close() {
        // Writes are flushed eagerly; nothing buffered here.
    }
}
