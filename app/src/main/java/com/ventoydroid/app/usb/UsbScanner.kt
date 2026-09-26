package com.ventoydroid.app.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.ventoydroid.app.disk.BlockDeviceException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Info about a candidate USB stick shown in the device picker. */
data class UsbDiskCandidate(
    val device: UsbDevice,
    val vendor: String,
    val product: String,
    val serial: String?,
    val hasPermission: Boolean,
)

object UsbScanner {
    private const val ACTION_USB_PERMISSION = "com.ventoydroid.app.USB_PERMISSION"

    /** Mass-storage devices report class 0x08 either at the device or interface level. */
    fun isMassStorage(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_MASS_STORAGE) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) return true
        }
        return false
    }

    fun listCandidates(manager: UsbManager): List<UsbDiskCandidate> =
        manager.deviceList.values
            .filter { isMassStorage(it) }
            .map { dev ->
                // On Android 15/MIUI, reading device properties (even the
                // serial number) throws SecurityException while USB permission
                // is ungranted. The picker must list the stick, not crash.
                UsbDiskCandidate(
                    device = dev,
                    vendor = runCatching { dev.manufacturerName }.getOrNull()
                        ?: "Vendor 0x%04X".format(dev.vendorId),
                    product = runCatching { dev.productName }.getOrNull()
                        ?: "Product 0x%04X".format(dev.productId),
                    serial = runCatching { dev.serialNumber }.getOrNull(),
                    hasPermission = manager.hasPermission(dev),
                )
            }

    /** Requests USB permission for [device] and suspends until granted/denied. */
    suspend fun requestPermission(context: Context, manager: UsbManager, device: UsbDevice): Boolean =
        suspendCancellableCoroutine { cont ->
            if (manager.hasPermission(device)) {
                cont.resume(true)
                return@suspendCancellableCoroutine
            }
            val flags = if (Build.VERSION.SDK_INT >= 31) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val intent = PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                flags,
            )
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.action != ACTION_USB_PERMISSION) return
                    c.unregisterReceiver(this)
                    val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (cont.isActive) cont.resume(granted)
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION))
            }
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            manager.requestPermission(device, intent)
        }

    /** Opens a BOT block device for [device]; caller must have permission. */
    fun open(manager: UsbManager, device: UsbDevice): UsbMassStorageDevice {
        val connection = manager.openDevice(device)
            ?: throw BlockDeviceException("Could not open USB connection (permission missing?)")
        val mscInterface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE }
            ?: device.getInterface(0)
        return UsbMassStorageDevice(connection, mscInterface)
    }
}
