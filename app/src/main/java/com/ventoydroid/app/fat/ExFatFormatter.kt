package com.ventoydroid.app.fat

import com.ventoydroid.app.disk.BlockDevice
import com.ventoydroid.app.disk.BlockDeviceException
import com.ventoydroid.app.util.Bin
import kotlin.math.ceil
import kotlin.random.Random

/**
 * Formats a partition as exFAT — the filesystem official Ventoy uses for its
 * data partition, chosen automatically when an ISO exceeds FAT32's 4 GiB
 * per-file limit (Windows 11, large Ubuntu images, ...). The GRUB exfat
 * driver bundled in the VTOYEFI payload reads it natively, so bootability is
 * unchanged.
 *
 * Layout and semantics are a faithful port of exfatprogs 1.2.2 mkfs.exfat
 * (verified against the source): 24-sector boot region (main + backup) with
 * boot-checksum sectors, one FAT, cluster-aligned metadata, allocation
 * bitmap, the canonical up-case table and a root directory holding the
 * volume label, bitmap and upcase entries.
 */
object ExFatFormatter {

    const val BYTES_PER_SECTOR = 512
    const val BYTES_PER_SECTOR_SHIFT = 9

    // Boot region (sectors, volume-relative): 0 boot, 1-8 extended boot,
    // 9 OEM, 10 reserved, 11 checksum; 12-23 is the backup copy.
    const val BOOT_REGION_SECTORS = 24
    const val MAIN_CHECKSUM_SECTOR = 11
    const val BACKUP_BOOT_SECTOR = 12
    const val BACKUP_CHECKSUM_SECTOR = 23

    private const val FIRST_CLUSTER = 2L
    private const val EOF_CLUSTER = 0xFFFFFFFFL

    /**
     * Chooses the cluster size (as log2 bytes) like mkfs.exfat: bigger disks
     * get bigger clusters (128 KiB up to 32 GiB, 256 KiB to 512 GiB, then
     * 512 KiB) to keep the FAT and bitmap small.
     */
    fun defaultClusterShift(partSectorCount: Long): Int {
        val gib = partSectorCount * BYTES_PER_SECTOR / (1L shl 30)
        return when {
            gib > 512 -> 19 // 512 KiB clusters
            gib > 32 -> 18  // 256 KiB clusters
            else -> 17      // 128 KiB clusters
        }
    }

    /** Layout numbers shared by the formatter, the volume driver and tests. */
    data class Layout(
        val clusterShift: Int,          // sectors-per-cluster = 1 << (clusterShift - 9)
        val fatOffset: Long,            // volume-relative sectors
        val fatLength: Long,            // sectors
        val clusterHeapOffset: Long,    // volume-relative sectors
        val clusterCount: Long,
        val rootCluster: Long,          // volume-relative cluster index
        val bitmapClusters: Long,
        val upcaseClusters: Long,
    ) {
        val sectorsPerCluster: Int get() = 1 shl (clusterShift - BYTES_PER_SECTOR_SHIFT)
        val clusterBytes: Int get() = sectorsPerCluster * BYTES_PER_SECTOR
        val bitmapBytes: Long get() = (clusterCount + 7) / 8
    }

    /** Computes the on-disk layout for a partition of [partSectorCount] sectors. */
    fun computeLayout(partSectorCount: Long, clusterShift: Int): Layout {
        require(clusterShift in BYTES_PER_SECTOR_SHIFT..25) { "cluster shift out of range" }
        val spc = 1 shl (clusterShift - BYTES_PER_SECTOR_SHIFT)
        if (partSectorCount < BOOT_REGION_SECTORS + 4L * spc) {
            throw BlockDeviceException("Partition too small for exFAT ($partSectorCount sectors)")
        }
        // Converge FAT size <-> cluster count (mirrors exfatprogs iteration;
        // FatLength is in sectors and may legally exceed its minimum).
        var fatLength = 1L
        var clusterHeapOffset = 0L
        var clusterCount = 0L
        repeat(8) {
            fatLength = roundUpTo((clusterCount + 2) * 4, BYTES_PER_SECTOR.toLong()) / BYTES_PER_SECTOR
            clusterHeapOffset = BOOT_REGION_SECTORS + fatLength
            clusterCount = (partSectorCount - clusterHeapOffset) / spc
            if (clusterCount > 0xFFFFFFF5L) clusterCount = 0xFFFFFFF5L
        }
        if (clusterCount < 3) {
            throw BlockDeviceException("Partition too small for exFAT ($clusterCount clusters)")
        }
        val bitmapClusters = ceil((clusterCount + 7).toDouble() / 8 / (spc.toLong() * BYTES_PER_SECTOR)).toLong().coerceAtLeast(1L)
        val upcaseClusters = ceil(ExFatUpcase.TABLE_BYTES.toDouble() / (spc.toLong() * BYTES_PER_SECTOR)).toLong().coerceAtLeast(1L)
        val rootCluster = FIRST_CLUSTER + bitmapClusters + upcaseClusters
        return Layout(
            clusterShift = clusterShift,
            fatOffset = BOOT_REGION_SECTORS.toLong(),
            fatLength = fatLength,
            clusterHeapOffset = clusterHeapOffset,
            clusterCount = clusterCount,
            rootCluster = rootCluster,
            bitmapClusters = bitmapClusters,
            upcaseClusters = upcaseClusters,
        )
    }

    suspend fun format(
        device: BlockDevice,
        partStartSector: Long,
        partSectorCount: Long,
        label: String = "VENTOY",
        clusterShiftOverride: Int? = null,
        diskSizeBytes: Long = partSectorCount * BYTES_PER_SECTOR.toLong(),
        random: Random = Random.Default,
    ) {
        val shift = clusterShiftOverride ?: defaultClusterShift(partSectorCount)
        val layout = computeLayout(partSectorCount, shift)
        val spc = layout.sectorsPerCluster

        // ---- Boot sector (identical for main and backup) ----
        val boot = ByteArray(BYTES_PER_SECTOR)
        boot[0] = 0xEB.toByte(); boot[1] = 0x76.toByte(); boot[2] = 0x90.toByte()
        "EXFAT   ".toByteArray(Charsets.US_ASCII).copyInto(boot, 3)
        // bytes 11..63 MustBeZero (already zero)
        Bin.putU64le(boot, 64, partStartSector)              // PartitionOffset
        Bin.putU64le(boot, 72, partSectorCount)              // VolumeLength
        Bin.putU32le(boot, 80, layout.fatOffset)
        Bin.putU32le(boot, 84, layout.fatLength)
        Bin.putU32le(boot, 88, layout.clusterHeapOffset)
        Bin.putU32le(boot, 92, layout.clusterCount)
        Bin.putU32le(boot, 96, layout.rootCluster)
        Bin.putU32le(boot, 100, random.nextInt().toLong() and 0xFFFFFFFFL) // VolumeSerialNumber
        Bin.putU16le(boot, 104, 0x0100)                      // FileSystemRevision 1.00
        Bin.putU16le(boot, 106, 0)                           // VolumeFlags (excluded from checksum)
        boot[108] = BYTES_PER_SECTOR_SHIFT.toByte()          // BytesPerSectorShift
        boot[109] = (shift - BYTES_PER_SECTOR_SHIFT).toByte()// SectorsPerClusterShift
        boot[110] = 1                                        // NumberOfFats
        boot[111] = 0x80.toByte()                            // DriveSelect
        boot[112] = 0                                        // PercentInUse (excluded from checksum)
        // 113..119 reserved, 120..509 boot code: all zero
        boot[510] = 0x55; boot[511] = 0xAA.toByte()

        // ---- Extended boot sectors: zeros + 0xAA550000 signature ----
        val ebs = Array(8) { ByteArray(BYTES_PER_SECTOR) }
        for (s in ebs) Bin.putU32le(s, BYTES_PER_SECTOR - 4, 0xAA550000)

        // ---- OEM + reserved sectors: zero (OEM is part of the checksum) ----
        val oem = ByteArray(BYTES_PER_SECTOR)

        // ---- Boot checksum over boot + 8 EBS + OEM ----
        var checksum = 0L
        fun feed(sector: ByteArray, isBoot: Boolean) {
            for (i in sector.indices) {
                if (isBoot && (i == 106 || i == 107 || i == 112)) continue
                checksum = (((checksum and 1L) shl 31) + (checksum ushr 1) +
                    (sector[i].toLong() and 0xFF)) and 0xFFFFFFFFL
            }
        }
        feed(boot, true)
        for (s in ebs) feed(s, false)
        feed(oem, false)
        val checksumSector = ByteArray(BYTES_PER_SECTOR)
        for (i in 0 until BYTES_PER_SECTOR / 4) Bin.putU32le(checksumSector, i * 4, checksum)

        // Write both boot regions.
        device.writeSectors(partStartSector + 0, 1, boot)
        for ((i, s) in ebs.withIndex()) device.writeSectors(partStartSector + 1 + i, 1, s)
        device.writeSectors(partStartSector + 9, 1, oem)
        device.writeSectors(partStartSector + 10, 1, oem) // reserved
        device.writeSectors(partStartSector + MAIN_CHECKSUM_SECTOR, 1, checksumSector)
        device.writeSectors(partStartSector + BACKUP_BOOT_SECTOR, 1, boot)
        for ((i, s) in ebs.withIndex()) device.writeSectors(partStartSector + BACKUP_BOOT_SECTOR + 1 + i, 1, s)
        device.writeSectors(partStartSector + BACKUP_BOOT_SECTOR + 9, 1, oem)
        device.writeSectors(partStartSector + BACKUP_BOOT_SECTOR + 10, 1, oem)
        device.writeSectors(partStartSector + BACKUP_CHECKSUM_SECTOR, 1, checksumSector)

        // ---- FAT: entry 0 = 0xFFFFFFF8, entry 1 = 0xFFFFFFFF, then the
        //      linear chains of bitmap, upcase and root (last = EOF). ----
        val fatBytes = ByteArray((layout.fatLength * BYTES_PER_SECTOR).toInt())
        Bin.putU32le(fatBytes, 0, 0xFFFFFFF8)
        Bin.putU32le(fatBytes, 4, 0xFFFFFFFFL)
        fun fatPut(cluster: Long, value: Long) {
            Bin.putU32le(fatBytes, (cluster * 4).toInt(), value)
        }
        var c = FIRST_CLUSTER
        repeat(layout.bitmapClusters.toInt()) {
            val last = it.toLong() == layout.bitmapClusters - 1
            fatPut(c, if (last) EOF_CLUSTER else c + 1)
            c++
        }
        repeat(layout.upcaseClusters.toInt()) {
            val last = it.toLong() == layout.upcaseClusters - 1
            fatPut(c, if (last) EOF_CLUSTER else c + 1)
            c++
        }
        fatPut(c, EOF_CLUSTER) // root directory (one cluster)
        check(c == layout.rootCluster) { "root cluster math off: $c vs ${layout.rootCluster}" }
        device.writeSectors(partStartSector + layout.fatOffset, layout.fatLength.toInt(), fatBytes)

        // ---- Allocation bitmap: bit i describes cluster i+2 (spec 7.2.6);
        // clusters 2 .. rootCluster are in use ----
        val usedThrough = layout.rootCluster // last used cluster
        val bitmapBytes = roundUpTo(layout.bitmapBytes, spc.toLong() * BYTES_PER_SECTOR).toInt()
        val bitmap = ByteArray(bitmapBytes)
        for (cl in FIRST_CLUSTER..usedThrough) {
            val bit = (cl - FIRST_CLUSTER).toInt()
            val idx = bit / 8
            bitmap[idx] = (bitmap[idx].toInt() or (1 shl (bit % 8))).toByte()
        }
        device.writeSectors(
            partStartSector + layout.clusterHeapOffset,
            bitmapBytes / BYTES_PER_SECTOR,
            bitmap,
        )

        // ---- Up-case table ----
        val upcaseRaw = ExFatUpcase.decodedBytes()
        check(upcaseRaw.size == ExFatUpcase.TABLE_BYTES)
        val upcase = ByteArray(layout.upcaseClusters.toInt() * spc * BYTES_PER_SECTOR)
        upcaseRaw.copyInto(upcase)
        device.writeSectors(
            partStartSector + layout.clusterHeapOffset + layout.bitmapClusters * spc,
            layout.upcaseClusters.toInt(),
            upcase,
        )

        // ---- Root directory: label + bitmap + upcase entries ----
        val root = ByteArray(spc * BYTES_PER_SECTOR)
        val labelEntry = ByteArray(32)
        labelEntry[0] = 0x83.toByte() // volume label
        val chars = label.take(11)
        labelEntry[1] = chars.length.toByte()
        val utf16 = chars.toByteArray(Charsets.UTF_16LE)
        utf16.copyInto(labelEntry, 2)
        labelEntry.copyInto(root, 0)

        val bitmapEntry = ByteArray(32)
        bitmapEntry[0] = 0x81.toByte() // allocation bitmap
        bitmapEntry[1] = 0    // flags: first bitmap
        Bin.putU32le(bitmapEntry, 20, FIRST_CLUSTER)
        Bin.putU64le(bitmapEntry, 24, roundUpTo(layout.clusterCount, 8) / 8)
        bitmapEntry.copyInto(root, 32)

        val upcaseEntry = ByteArray(32)
        upcaseEntry[0] = 0x82.toByte() // up-case table
        Bin.putU32le(upcaseEntry, 4, ExFatUpcase.TABLE_CHECKSUM)
        Bin.putU32le(upcaseEntry, 20, FIRST_CLUSTER + layout.bitmapClusters)
        Bin.putU64le(upcaseEntry, 24, ExFatUpcase.TABLE_BYTES.toLong())
        upcaseEntry.copyInto(root, 64)

        device.writeSectors(
            partStartSector + layout.clusterHeapOffset + (layout.rootCluster - FIRST_CLUSTER) * spc,
            spc,
            root,
        )

        // ---- Sanity: the volume must mount back ----
        ExFatVolume.mount(device, partStartSector, partSectorCount).close()
    }

    private fun roundUpTo(v: Long, alignment: Long): Long = ((v + alignment - 1) / alignment) * alignment
}
