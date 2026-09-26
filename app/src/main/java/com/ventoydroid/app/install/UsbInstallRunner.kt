package com.ventoydroid.app.install

import android.content.Context
import android.hardware.usb.UsbManager
import com.ventoydroid.app.disk.BlockDeviceException
import com.ventoydroid.app.usb.UsbMassStorageDevice
import com.ventoydroid.app.usb.UsbScanner
import com.ventoydroid.app.ventoy.AssetVentoyPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** High-level, UI-facing install state. */
sealed class InstallState {
    data object Idle : InstallState()
    data class Running(val progress: com.ventoydroid.app.install.InstallProgress) : InstallState()
    data class Success(val message: String) : InstallState()
    data class Failed(val error: String, val hint: String? = null) : InstallState()
}

/**
 * Owns one install at a time: opens the USB device, runs the orchestrator and
 * publishes progress as a [StateFlow] for both the UI and the foreground
 * service notification.
 */
class UsbInstallRunner(context: Context) {

    private val appContext = context.applicationContext
    private val payload by lazy { AssetVentoyPayload(appContext) }

    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state

    private var current: InstallOrchestrator? = null

    fun cancel() {
        current?.cancel()
    }

    suspend fun run(deviceKey: String, isos: List<IsoSource>) = withContext(Dispatchers.IO) {
        if (_state.value is InstallState.Running) {
            _state.value = InstallState.Failed("An install is already running")
            return@withContext
        }
        val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val candidate = UsbScanner.listCandidates(manager)
            .firstOrNull { it.device.deviceId.toString() == deviceKey }
        if (candidate == null || !candidate.hasPermission) {
            _state.value = InstallState.Failed("USB device not found or permission missing")
            return@withContext
        }

        try {
            _state.value = InstallState.Running(InstallProgress.Stage("Opening device"))
            UsbScanner.open(manager, candidate.device).use { usb: UsbMassStorageDevice ->
                usb.initialize()
                val orchestrator = InstallOrchestrator(usb, payload)
                current = orchestrator
                val result = orchestrator.run(isos) { progress ->
                    _state.value = InstallState.Running(progress)
                }
                val gib = result.part1Sectors * 512.0 / (1 shl 30)
                _state.value = InstallState.Success(
                    "Ventoy ${result.ventoyVersion} installed — data partition %.1f GiB".format(gib)
                )
            }
        } catch (e: InstallCancelledException) {
            _state.value = InstallState.Failed("Cancelled — the stick's layout may be incomplete; reinstall to fix")
        } catch (e: BlockDeviceException) {
            _state.value = InstallState.Failed(e.message ?: "USB error", HINT_USB)
        } catch (e: SecurityException) {
            _state.value = InstallState.Failed("USB permission denied", "Grant USB access and retry")
        } catch (e: Exception) {
            _state.value = InstallState.Failed(e.message ?: e.javaClass.simpleName, HINT_GENERIC)
        } finally {
            current = null
        }
    }

    companion object {
        const val HINT_USB = "Try a different cable/adapter or a powered hub; some sticks need more power than the phone provides"
        const val HINT_GENERIC = "If this persists, try another USB stick"
    }
}
