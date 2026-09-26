package com.ventoydroid.app.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.ventoydroid.app.disk.BlockDevice
import com.ventoydroid.app.disk.BlockDeviceException
import com.ventoydroid.app.util.Bin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Arrays
import kotlin.math.min

/**
 * Speaks SCSI over USB Bulk-Only Transport (BOT) to a mass-storage device,
 * implementing [BlockDevice]. This is the rootless write path: Android's
 * kernel storage stack is never involved.
 *
 * Recovery model (per USB Mass Storage Class Bulk-Only Transport spec):
 * a stalled data phase leaves the device still owing a CSW, so the sequence
 * is: clear halt on the data endpoint -> read the pending CSW -> retry the
 * whole command with a fresh CBW. If the device stops responding entirely
 * (CBW rejected / no CSW), escalate to the class-specific Mass Storage
 * Reset + clear-halt on both endpoints. Every command is retried a bounded
 * number of times with backoff; many flash sticks stall their first command
 * (UNIT ATTENTION / power-up not-ready) and recover fine.
 */
class UsbMassStorageDevice(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val timeoutMs: Int = 15_000,
) : BlockDevice {

    private val inEndpoint: UsbEndpoint
    private val outEndpoint: UsbEndpoint
    private val botMutex = Mutex()
    private var tagCounter = 1

    // 512 is the only sector size Ventoy supports (4Kn devices are rejected).
    override val blockSize: Int = 512
    override var size: Long = 0
        private set

    init {
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until usbInterface.endpointCount) {
            val ep = usbInterface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
        }
        requireNotNull(inEp) { "USB mass-storage device has no bulk IN endpoint" }
        requireNotNull(outEp) { "USB mass-storage device has no bulk OUT endpoint" }
        inEndpoint = inEp
        outEndpoint = outEp

        if (!connection.claimInterface(usbInterface, true)) {
            throw BlockDeviceException("Failed to claim USB interface")
        }
    }

    /**
     * Performs INQUIRY + waits for readiness and reads capacity. Call before use.
     *
     * Devices commonly return CHECK_CONDITION/UNIT ATTENTION on their first
     * commands after enumeration (power-up not-ready while internal init runs).
     * TUR is therefore looped with sense inspection and a generous deadline.
     */
    suspend fun initialize(maxReadyWaitMs: Long = 20_000) = botMutex.withLock {
        withContext(Dispatchers.IO) {
            // Reset any stall left over from a previous failed session.
            clearHalt(inEndpoint)
            clearHalt(outEndpoint)

            val cb = ByteArray(16)

            Scsi.inquiry(cb, INQUIRY_ALLOC_LEN)
            val inquiry = ByteArray(INQUIRY_ALLOC_LEN)
            try {
                if (botTransaction(cb, 6, inquiry, dataIn = true) != INQUIRY_ALLOC_LEN) {
                    throw BlockDeviceException("INQUIRY short read")
                }
            } catch (e: BlockDeviceException) {
                throw BlockDeviceException(
                    "INQUIRY failed: ${e.message}${currentSenseSuffix()}", e
                )
            }

            val deadline = System.currentTimeMillis() + maxReadyWaitMs
            while (true) {
                Scsi.testUnitReady(cb)
                when (val status = botTransactionStatus(cb, 6)) {
                    BotStatus.GOOD -> break
                    BotStatus.CHECK_CONDITION -> {
                        val sense = requestSenseLocked()
                        if (System.currentTimeMillis() > deadline) {
                            throw BlockDeviceException("Device not ready: ${sense.describe()}")
                        }
                        // Unit attention / not-ready: wait and retry.
                        Thread.sleep(200)
                    }
                    BotStatus.PHASE_ERROR -> throw BlockDeviceException("BOT phase error on TUR")
                }
            }

            Scsi.readCapacity10(cb)
            val cap = ByteArray(Scsi.READ_CAPACITY_DATA_LEN)
            if (botTransaction(cb, 10, cap, dataIn = true) != 8) {
                throw BlockDeviceException("READ CAPACITY failed")
            }
            val (lastLba, bytesPerBlock) = Scsi.parseReadCapacity(cap)
            if (bytesPerBlock != blockSize) {
                throw BlockDeviceException(
                    "Unsupported block size $bytesPerBlock (Ventoy requires 512-byte sectors)"
                )
            }
            size = (lastLba + 1) * bytesPerBlock.toLong()
        }
    }

    override suspend fun readSectors(sector: Long, count: Int, buffer: ByteArray, offset: Int) {
        botMutex.withLock {
            withContext(Dispatchers.IO) {
                validateAccess(sector, count, buffer.size, offset)
                var done = 0
                while (done < count) {
                    val chunk = min(count - done, MAX_SECTORS_PER_COMMAND)
                    readChunked(sector + done, chunk, buffer, offset + done * blockSize)
                    done += chunk
                }
            }
        }
    }

    override suspend fun writeSectors(sector: Long, count: Int, buffer: ByteArray, offset: Int) {
        botMutex.withLock {
            withContext(Dispatchers.IO) {
                validateAccess(sector, count, buffer.size, offset)
                var done = 0
                while (done < count) {
                    val chunk = min(count - done, MAX_SECTORS_PER_COMMAND)
                    writeChunked(sector + done, chunk, buffer, offset + done * blockSize)
                    done += chunk
                }
            }
        }
    }

    override fun close() {
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
    }

    // ---- internals (called with botMutex held) ----

    private fun validateAccess(sector: Long, count: Int, bufferLen: Int, offset: Int) {
        if (sector < 0 || count <= 0 || sector + count > sectorCount) {
            throw BlockDeviceException("Sector access out of range: $sector+$count")
        }
        if (offset < 0 || offset + count * blockSize > bufferLen) {
            throw BlockDeviceException("Buffer too small for transfer")
        }
    }

    private fun readChunked(sector: Long, count: Int, buffer: ByteArray, offset: Int) {
        val cb = ByteArray(16)
        Scsi.read10(cb, sector, count)
        try {
            val rc = botTransaction(cb, 10, buffer, dataIn = true, length = count * blockSize, outOffset = offset)
            if (rc != count * blockSize) {
                throw BlockDeviceException("READ(10) @$sector transferred $rc of ${count * blockSize} bytes")
            }
        } catch (e: BlockDeviceException) {
            throw BlockDeviceException(
                "READ(10) sector $sector ($count sectors): ${e.message}${currentSenseSuffix()}", e
            )
        }
    }

    private fun writeChunked(sector: Long, count: Int, buffer: ByteArray, offset: Int) {
        val cb = ByteArray(16)
        Scsi.write10(cb, sector, count)
        try {
            val rc = botTransaction(cb, 10, buffer, dataIn = false, length = count * blockSize, outOffset = offset)
            if (rc != count * blockSize) {
                throw BlockDeviceException("WRITE(10) @$sector transferred $rc of ${count * blockSize} bytes")
            }
        } catch (e: BlockDeviceException) {
            throw BlockDeviceException(
                "WRITE(10) sector $sector ($count sectors): ${e.message}${currentSenseSuffix()}", e
            )
        }
    }

    private fun nextTag(): Int = tagCounter++

    private fun newCbw(tag: Int, dataLen: Int, dataIn: Boolean, cb: ByteArray, cbLen: Int): ByteArray {
        val cbw = ByteArray(31)
        // dCBWSignature 'USBC'
        cbw[0] = 'U'.code.toByte(); cbw[1] = 'S'.code.toByte()
        cbw[2] = 'B'.code.toByte(); cbw[3] = 'C'.code.toByte()
        Bin.putU32le(cbw, 4, tag.toLong())
        Bin.putU32le(cbw, 8, dataLen.toLong())
        cbw[12] = if (dataIn) 0x80.toByte() else 0x00
        cbw[13] = 0 // LUN 0
        cbw[14] = cbLen.toByte()
        System.arraycopy(cb, 0, cbw, 15, cbLen)
        return cbw
    }

    /**
     * Runs one complete BOT command, mapping the CSW status to data (GOOD) or
     * an exception. Stalls and transient transport failures are retried with
     * backoff up to [MAX_ATTEMPTS] times before giving up.
     */
    private fun botTransaction(
        cb: ByteArray,
        cbLen: Int,
        buffer: ByteArray,
        dataIn: Boolean,
        length: Int = buffer.size,
        outOffset: Int = 0,
    ): Int {
        return when (val status = botTransactionStatus(cb, cbLen, buffer, dataIn, length, outOffset)) {
            BotStatus.GOOD -> length
            BotStatus.CHECK_CONDITION -> {
                val sense = requestSenseLocked()
                throw BlockDeviceException("SCSI command failed: ${sense.describe()}")
            }
            BotStatus.PHASE_ERROR -> throw BlockDeviceException("BOT phase error")
        }
    }

    private fun botTransactionStatus(
        cb: ByteArray,
        cbLen: Int,
        buffer: ByteArray? = null,
        dataIn: Boolean = true,
        length: Int = 0,
        outOffset: Int = 0,
    ): BotStatus {
        var lastError: Exception? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            try {
                return botAttempt(cb, cbLen, buffer, dataIn, length, outOffset)
            } catch (e: Exception) {
                lastError = e
                android.util.Log.w(
                    "VentoyDroid",
                    "BOT attempt ${attempt + 1}/$MAX_ATTEMPTS failed: ${e.message}"
                )
                if (attempt == MAX_ATTEMPTS - 1) break
                recoverAfterFailure(e, attempt)
            }
        }
        throw when (val e = lastError!!) {
            is BlockDeviceException -> e
            else -> BlockDeviceException(e.message ?: "USB transfer failed", e)
        }
    }

    /** One CBW -> (data phase) -> CSW sequence. Throws on any failure. */
    private fun botAttempt(
        cb: ByteArray,
        cbLen: Int,
        buffer: ByteArray?,
        dataIn: Boolean,
        length: Int,
        outOffset: Int,
    ): BotStatus {
        val tag = nextTag()
        val cbw = newCbw(tag, length, dataIn, cb, cbLen)
        if (connection.bulkTransfer(outEndpoint, cbw, 31, timeoutMs) != 31) {
            throw BotProtocolException("Failed to send CBW")
        }

        var transferred = 0
        if (buffer != null && length > 0) {
            var remaining = length
            while (remaining > 0) {
                val chunk = min(remaining, MAX_TRANSFER_BYTES)
                val ep = if (dataIn) inEndpoint else outEndpoint
                val n = connection.bulkTransfer(ep, buffer, outOffset + transferred, chunk, timeoutMs)
                if (n < 0) {
                    // Stall (or transient error) in the data phase. Per the BOT
                    // spec the device still owes a CSW for this tag: clear the
                    // halt, consume the CSW, then let the caller retry the whole
                    // command with a fresh CBW. Retrying the data chunk alone is
                    // not an option — the device no longer expects data.
                    clearHalt(ep)
                    try {
                        readCsw(tag)
                    } catch (cswErr: Exception) {
                        // Device is wedged: surface as protocol failure so the
                        // recovery path escalates to a Mass Storage Reset.
                        throw BotProtocolException(
                            "Data phase failed after $transferred bytes (chunk $chunk, " +
                                "${if (dataIn) "IN" else "OUT"}); CSW also failed: ${cswErr.message}",
                            cswErr
                        )
                    }
                    throw DataPhaseStallException(transferred, chunk, dataIn)
                }
                transferred += n
                remaining -= n
                // Short packet ends the data phase early.
                if (n < chunk) break
            }
        }

        val csw = readCsw(tag)
        val residue = Bin.u32le(csw, 8)
        if (residue > length) {
            throw BlockDeviceException("CSW residue $residue exceeds transfer length")
        }
        return when (csw[12].toInt()) {
            0 -> BotStatus.GOOD
            1 -> BotStatus.CHECK_CONDITION
            else -> BotStatus.PHASE_ERROR
        }
    }

    /** Reads the 13-byte CSW and validates signature + tag. */
    private fun readCsw(tag: Int): ByteArray {
        val csw = ByteArray(13)
        var cswRead = 0
        while (cswRead < 13) {
            val n = connection.bulkTransfer(inEndpoint, csw, cswRead, 13 - cswRead, timeoutMs)
            if (n <= 0) {
                clearHalt(inEndpoint)
                throw BotProtocolException("Failed to read CSW (got $cswRead bytes)")
            }
            cswRead += n
        }
        // dCSWSignature 'USBS'
        if (!(csw[0] == 'U'.code.toByte() && csw[1] == 'S'.code.toByte() &&
                csw[2] == 'B'.code.toByte() && csw[3] == 'S'.code.toByte())
        ) {
            throw BotProtocolException("Invalid CSW signature")
        }
        if (Bin.u32le(csw, 4) != tag.toLong()) {
            throw BotProtocolException("CSW tag mismatch")
        }
        return csw
    }

    /**
     * Recovery between retries of the same command. Data-phase stalls: the
     * pending CSW was already consumed, just back off and retry with a fresh
     * CBW. Transport failures (CBW/CSW): Mass Storage Reset + clear both
     * halts. Anything else (odd SCSI status): capture sense for diagnostics.
     */
    private fun recoverAfterFailure(e: Exception, attempt: Int) {
        when (e) {
            is DataPhaseStallException -> Unit // CSW already consumed; retry below
            is BotProtocolException -> massStorageReset()
            else -> runCatching { requestSenseLocked() }
        }
        Thread.sleep(BACKOFF_BASE_MS shl attempt.coerceAtMost(3))
    }

    /**
     * Class-specific request 0xFF (BULK_ONLY_MASS_STORAGE_RESET). Resets the
     * BOT state machine without touching USB enumeration or the media; data
     * endpoints must then be unhalted explicitly (spec requires both).
     */
    private fun massStorageReset() {
        runCatching {
            connection.controlTransfer(
                0x21, // bmRequestType: class-specific, host-to-device, interface recipient
                0xFF, // bRequest: BULK_ONLY_MASS_STORAGE_RESET
                0, 0,
                null, 0, timeoutMs
            )
        }
        Thread.sleep(50)
        clearHalt(inEndpoint)
        clearHalt(outEndpoint)
    }

    /** Best-effort sense capture for diagnostics; never throws. */
    private fun currentSenseSuffix(): String {
        return try {
            val s = requestSenseLocked()
            if (s.senseKey == 0xFF) "" else " — sense: ${s.describe()}"
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * REQUEST SENSE, tolerant of a wedged device (transfer results ignored).
     * Call with botMutex held.
     */
    private fun requestSenseLocked(): SenseData {
        val cb = ByteArray(16)
        Scsi.requestSense(cb)
        val sense = ByteArray(Scsi.SENSE_DATA_LEN)
        val tag = nextTag()
        val cbw = newCbw(tag, sense.size, dataIn = true, cb = cb, cbLen = 6)
        if (connection.bulkTransfer(outEndpoint, cbw, 31, timeoutMs) != 31) {
            return SenseData(0xFF, 0, 0)
        }
        val n = connection.bulkTransfer(inEndpoint, sense, sense.size, timeoutMs)
        if (n <= 0) return SenseData(0xFF, 0, 0)
        val csw = ByteArray(13)
        connection.bulkTransfer(inEndpoint, csw, 13, timeoutMs)
        return Scsi.parseSense(sense)
    }

    private fun clearHalt(ep: UsbEndpoint) {
        connection.controlTransfer(0x02, USB_CLEAR_FEATURE, 0, ep.address, null, 0, timeoutMs)
    }

    /** Stall in the BOT data phase; CSW was consumed, command must be retried whole. */
    private class DataPhaseStallException(
        val transferred: Int,
        val chunk: Int,
        val dataIn: Boolean,
    ) : RuntimeException(
        "Data phase failed after $transferred bytes (chunk $chunk, ${if (dataIn) "IN" else "OUT"})"
    )

    /** BOT transport-level failure (CBW rejected / CSW unreadable or invalid). */
    private class BotProtocolException(message: String, cause: Throwable? = null) :
        RuntimeException(message, cause)

    private enum class BotStatus { GOOD, CHECK_CONDITION, PHASE_ERROR }

    companion object {
        private const val USB_CLEAR_FEATURE = 0x01
        private const val INQUIRY_ALLOC_LEN = 36
        // Bounded whole-command retries with exponential backoff. Flash sticks
        // frequently stall their first command after enumeration (UNIT
        // ATTENTION / power-up not-ready); a fresh CBW after recovery fixes it.
        // Four attempts over ~4s bridges brief controller/thermal wedges that
        // a single Mass Storage Reset cannot pull a stick out of.
        private const val MAX_ATTEMPTS = 4
        private const val BACKOFF_BASE_MS = 250L
        // Many phone USB controllers fail bulk transfers above 16 KiB —
        // keep commands at 32 sectors (16 KiB) for maximum compatibility.
        private const val MAX_SECTORS_PER_COMMAND = 32
        private const val MAX_TRANSFER_BYTES = 16 * 1024
    }
}
