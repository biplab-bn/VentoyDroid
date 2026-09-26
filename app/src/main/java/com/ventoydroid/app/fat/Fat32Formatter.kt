package com.ventoydroid.app.fat

import com.ventoydroid.app.disk.BlockDevice
import com.ventoydroid.app.disk.BlockDeviceException
import com.ventoydroid.app.util.Bin
import kotlin.random.Random

/**
 * Formats a partition as FAT32, following the same cluster-size rules as the
 * official Ventoy installer (128KiB clusters on disks > 32GiB, 32KiB otherwise).
 * Writes the BPB, FSInfo, backup boot sector, both FAT copies and an empty
 * root directory with a volume label entry.
 */
object Fat32Formatter {

    suspend fun format(
        device: BlockDevice,
        partStartSector: Long,
        partSectorCount: Long,
        label: String = "VENTOY",
        sectorsPerClusterOverride: Int? = null,
        diskSizeBytes: Long = partSectorCount * 512L,
        random: Random = Random.Default,
    ) {
        require(partSectorCount > 4096) { "Partition too small for FAT32" }

        // Ventoy rule: >32GiB -> 256 sectors/cluster (128KiB), else 64 (32KiB)
        val spc = sectorsPerClusterOverride
            ?: if (diskSizeBytes > 32L * 1024 * 1024 * 1024) 256 else 64

        val reserved = 32
        val totalSectors = partSectorCount

        // Converge on a FAT size that fits the cluster count
        var fatSectors = ((totalSectors - reserved) / spc * 4 + 511) / 512 + 1
        repeat(8) {
            val dataSectors = totalSectors - reserved - 2 * fatSectors
            val clusters = dataSectors / spc
            fatSectors = ((clusters * 4 + 511) / 512) + 1
        }
        if (fatSectors < 1) throw BlockDeviceException("FAT format: partition too small")

        val clusters = (totalSectors - reserved - 2 * fatSectors) / spc
        if (clusters < 1) throw BlockDeviceException("FAT format: partition too small")

        // ---- Boot sector / BPB ----
        val bpb = ByteArray(512)
        bpb[0] = 0xEB.toByte(); bpb[1] = 0x3C.toByte(); bpb[2] = 0x90.toByte()
        "MSWIN4.1".toByteArray(Charsets.US_ASCII).copyInto(bpb, 3)
        Bin.putU16le(bpb, 11, 512)
        bpb[13] = spc.toByte()
        Bin.putU16le(bpb, 14, reserved)
        bpb[16] = 2
        Bin.putU16le(bpb, 17, 0)      // root entries (FAT32: 0)
        Bin.putU16le(bpb, 19, 0)      // total sectors 16 (FAT32: 0)
        bpb[21] = 0xF8.toByte()       // media descriptor (fixed disk)
        Bin.putU16le(bpb, 22, 0)      // FAT16 size
        Bin.putU16le(bpb, 24, 63)     // sectors per track
        Bin.putU16le(bpb, 26, 255)    // heads
        Bin.putU32le(bpb, 28, partStartSector) // hidden sectors
        Bin.putU32le(bpb, 32, totalSectors)
        Bin.putU32le(bpb, 36, fatSectors.toLong())
        Bin.putU16le(bpb, 40, 0)      // extended flags: FAT0 active, mirrored
        Bin.putU16le(bpb, 42, 0)      // filesystem version
        Bin.putU32le(bpb, 44, 2)      // root cluster
        Bin.putU16le(bpb, 48, 1)      // FSInfo sector
        Bin.putU16le(bpb, 50, 6)      // backup boot sector
        bpb[64] = 0x80.toByte()       // BIOS drive number
        bpb[66] = 0x29.toByte()       // extended boot signature
        Bin.putU32le(bpb, 67, random.nextInt().toLong() and 0xFFFFFFFFL) // volume id
        val labelPadded = ByteArray(11) { ' '.code.toByte() }
        label.uppercase().take(11).toByteArray(Charsets.US_ASCII).copyInto(labelPadded)
        labelPadded.copyInto(bpb, 71)
        "FAT32   ".toByteArray(Charsets.US_ASCII).copyInto(bpb, 82)
        bpb[510] = 0x55; bpb[511] = 0xAA.toByte()
        device.writeSectors(partStartSector, 1, bpb)
        device.writeSectors(partStartSector + 6, 1, bpb) // backup

        // ---- FSInfo ----
        val fsinfo = ByteArray(512)
        Bin.putU32le(fsinfo, 0, 0x41615252)
        Bin.putU32le(fsinfo, 484, 0x61417272)
        Bin.putU32le(fsinfo, 488, clusters - 1)      // free clusters (minus root)
        Bin.putU32le(fsinfo, 492, 2)                 // next free cluster
        Bin.putU32le(fsinfo, 508, 0xAA550000)
        device.writeSectors(partStartSector + 1, 1, fsinfo)
        device.writeSectors(partStartSector + 7, 1, fsinfo) // backup

        // ---- FATs ----
        val fat = ByteArray((fatSectors * 512).toInt())
        Bin.putU32le(fat, 0, 0x0FFFFFF8)
        Bin.putU32le(fat, 4, 0x0FFFFFFF)
        Bin.putU32le(fat, 8, 0x0FFFFFFF) // cluster 2 = root, EOC
        for (f in 0 until 2) {
            device.writeSectors(partStartSector + reserved + f * fatSectors, fatSectors.toInt(), fat)
        }

        // ---- Root directory cluster ----
        val root = ByteArray(spc * 512)
        // volume label entry
        labelPadded.copyInto(root, 0)
        root[11] = 0x08
        val rootCluster = 2L
        val rootSector = partStartSector + reserved + 2 * fatSectors
        device.writeSectors(rootSector, spc, root)

        // ---- Sanity: the volume must mount back ----
        Fat32Volume.mount(device, partStartSector, partSectorCount).close()
    }
}
