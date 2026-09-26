package com.ventoydroid.app.ui

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ventoydroid.app.VentoyDroidApp
import com.ventoydroid.app.install.InstallState
import com.ventoydroid.app.install.UsbInstallRunner
import com.ventoydroid.app.service.InstallService
import com.ventoydroid.app.usb.UsbDiskCandidate
import com.ventoydroid.app.usb.UsbScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An ISO picked via SAF, resolved to a display name and size. */
data class SelectedIso(val uri: Uri, val name: String, val size: Long)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    // Defensive fallback: the launch path must never hard-crash over wiring.
    private val runner by lazy {
        val app = getApplication<Application>()
        (app as? VentoyDroidApp)?.installRunner ?: UsbInstallRunner(app)
    }

    val installState: StateFlow<InstallState> = runner.state

    private val _devices = MutableStateFlow<List<UsbDiskCandidate>>(emptyList())
    val devices: StateFlow<List<UsbDiskCandidate>> = _devices

    private val _selectedDevice = MutableStateFlow<UsbDiskCandidate?>(null)
    val selectedDevice: StateFlow<UsbDiskCandidate?> = _selectedDevice

    private val _isos = MutableStateFlow<List<SelectedIso>>(emptyList())
    val isos: StateFlow<List<SelectedIso>> = _isos

    val payloadVersion: String by lazy {
        runCatching {
            com.ventoydroid.app.ventoy.AssetVentoyPayload(app).version
        }.getOrDefault("?")
    }

    private val usbManager get() =
        getApplication<Application>().getSystemService(Context.USB_SERVICE) as UsbManager

    fun refreshDevices() {
        viewModelScope.launch(Dispatchers.IO) {
            _devices.value = UsbScanner.listCandidates(usbManager)
            // Drop the selection if the stick vanished.
            val sel = _selectedDevice.value
            if (sel != null && _devices.value.none { it.device.deviceId == sel.device.deviceId }) {
                _selectedDevice.value = null
            }
        }
    }

    fun selectDevice(candidate: UsbDiskCandidate?) {
        _selectedDevice.value = candidate
    }

    /** Asks the system for USB permission; refreshes the list afterwards. */
    fun requestUsbPermission(candidate: UsbDiskCandidate) {
        viewModelScope.launch {
            val granted = UsbScanner.requestPermission(getApplication(), usbManager, candidate.device)
            withContext(Dispatchers.IO) {
                _devices.value = UsbScanner.listCandidates(usbManager)
            }
            if (granted) _selectedDevice.value = candidate
        }
    }

    fun setIsoUris(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolved = uris.mapNotNull { uri ->
                runCatching {
                    val src = com.ventoydroid.app.iso.IsoRepository.fromUri(getApplication(), uri)
                    SelectedIso(uri, src.displayName, src.size)
                }.getOrNull()
            }
            _isos.value = resolved
        }
    }

    fun removeIso(iso: SelectedIso) {
        _isos.value = _isos.value - iso
    }

    /** Fires the install through the foreground service. */
    fun startInstall() {
        val context = getApplication<Application>()
        val device = _selectedDevice.value ?: return
        InstallService.start(
            context,
            deviceKey = device.device.deviceId.toString(),
            isoUris = _isos.value.map { it.uri.toString() },
        )
    }

    fun cancelInstall() {
        val context = getApplication<Application>()
        runner.cancel()
        InstallService.cancel(context)
    }
}
