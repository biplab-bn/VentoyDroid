package com.ventoydroid.app.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.ventoydroid.app.ads.Ads
import com.ventoydroid.app.install.InstallState
import com.ventoydroid.app.install.InstallProgress
import com.ventoydroid.app.usb.UsbDiskCandidate
import java.util.Locale

private val ISO_EXT = Regex("(?i)\\.(iso|img|wim|vhd|raw)$")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VentoyDroidScreen(vm: AppViewModel, state: InstallState) {
    val context = LocalContext.current
    val activity = context as? Activity
    val devices by vm.devices.collectAsState()
    val selected by vm.selectedDevice.collectAsState()
    val isos by vm.isos.collectAsState()

    // Interstitial once per successful install; failure paths skip ads.
    var adShownForSuccess by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state is InstallState.Success && !adShownForSuccess) {
            adShownForSuccess = true
            activity?.let { Ads.maybeShowInterstitial(it) }
        }
        if (state !is InstallState.Success) adShownForSuccess = false
    }
    // Preload early so the ad is ready by the time an install finishes.
    LaunchedEffect(Unit) { activity?.let { Ads.preloadInterstitial(it) } }

    var confirmOpen by remember { mutableStateOf(false) }
    var tooBigIso by remember { mutableStateOf<SelectedIso?>(null) }
    var powerDialogOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshDevices() }

    // Android 13+: the install-progress notification needs a runtime request.
    // Denial only means no notification — the install itself still runs.
    var pendingInstall by remember { mutableStateOf(false) }
    val requestNotifPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        if (pendingInstall) {
            pendingInstall = false
            vm.startInstall()
        }
    }

    // ---- battery-optimization exemption popup ----
    // OEM battery savers (MIUI in particular) can kill background installs or
    // cut OTG power when the screen sleeps. Ask once for the whitelist; the
    // user may decline and install anyway.
    val prefs = remember { context.getSharedPreferences("install_prefs", Context.MODE_PRIVATE) }
    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    val exemptionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // Continue with the install either way — the whitelist is a bonus,
        // wake locks and the foreground service already carry most of it.
        confirmOpen = true
    }

    fun openExemptionPrompt() {
        try {
            exemptionLauncher.launch(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:" + context.packageName))
            )
        } catch (e: ActivityNotFoundException) {
            // Some OEM builds remove the direct prompt; fall back to the list.
            try {
                exemptionLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: ActivityNotFoundException) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                confirmOpen = true
            }
        }
    }

    fun maybeAskPowerExemption() {
        if (isIgnoringBatteryOptimizations() ||
            prefs.getBoolean("power_exemption_asked", false)
        ) {
            confirmOpen = true
            return
        }
        prefs.edit().putBoolean("power_exemption_asked", true).apply()
        powerDialogOpen = true
    }

    fun launchInstall() {
        val needsNotifPerm = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsNotifPerm) {
            pendingInstall = true
            requestNotifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.startInstall()
        }
    }

    val pickIso = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val flagged = uris.map { uri ->
                // Persist read permission across the install.
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                uri
            }
            vm.setIsoUris(flagged)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text("VentoyDroid") })
        },
        bottomBar = {
            // Bottom banner ad; fail-silent if the SDK or network is unavailable.
            AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).also { host ->
                        (ctx as? Activity)?.let { Ads.attachBanner(it, host) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Multiboot USB sticks from your phone — no root needed.\nBundled Ventoy version: ${vm.payloadVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- Step 1: device ----
            item {
                StepCard(title = "1. Choose your USB stick") {
                    if (devices.isEmpty()) {
                        Text("No USB stick detected.\n\nPlug a stick in with an OTG adapter, then tap Refresh.")
                    } else {
                        devices.forEach { dev ->
                            DeviceRow(
                                candidate = dev,
                                selected = selected?.device?.deviceId == dev.device.deviceId,
                                onSelect = {
                                    if (dev.hasPermission) vm.selectDevice(dev) else vm.requestUsbPermission(dev)
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { vm.refreshDevices() }) { Text("Refresh") }
                }
            }

            // ---- Step 2: ISOs ----
            item {
                StepCard(title = "2. Pick ISO files to copy") {
                    if (isos.isEmpty()) {
                        Text("Optional — you can also copy ISOs to the stick later from any computer.")
                    } else {
                        isos.forEach { iso ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(iso.name, fontWeight = FontWeight.Medium)
                                    Text("${iso.size / (1 shl 20)} MiB", style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(onClick = { vm.removeIso(iso) }) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove")
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        pickIso.launch(arrayOf("application/octet-stream", "*/*"))
                    }) { Text("Add ISO files") }
                }
            }

            // ---- Step 3: install ----
            item {
                StepCard(title = "3. Install") {
                    when (state) {
                        is InstallState.Running -> {
                            ProgressView(state)
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { vm.cancelInstall() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Cancel") }
                        }
                        is InstallState.Success -> {
                            Text(state.message, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { vm.refreshDevices() }) { Text("Done — refresh") }
                        }
                        is InstallState.Failed -> {
                            Text(state.error, color = MaterialTheme.colorScheme.error)
                            state.hint?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { vm.refreshDevices() }) { Text("Back") }
                        }
                        InstallState.Idle -> {
                            Button(
                                onClick = {
                                    val over = isos.firstOrNull { it.size >= FOUR_GIB }
                                    if (over != null) tooBigIso = over else maybeAskPowerExemption()
                                },
                                enabled = selected != null,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Install Ventoy") }
                            if (selected == null) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Select a USB stick first",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "This will ERASE ALL DATA on the selected stick. " +
                        "The stick will boot ISOs on any PC (UEFI + Legacy BIOS).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }

    // ---- power-management shortcut popup ----
    if (powerDialogOpen) {
        AlertDialog(
            onDismissRequest = { powerDialogOpen = false; confirmOpen = true },
            title = { Text("Keep installs alive") },
            text = {
                Text(
                    "Some phones (Xiaomi/MIUI and others) stop background apps or " +
                        "cut USB power to save battery, which can abort a long install " +
                        "mid-copy. Allow VentoyDroid to ignore battery optimization " +
                        "so installs always run to the end. You can also change this " +
                        "later in system Settings → Battery.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    powerDialogOpen = false
                    openExemptionPrompt()
                }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = {
                    powerDialogOpen = false
                    confirmOpen = true
                }) { Text("Not now") }
            },
        )
    }

    // ---- destructive-action confirmation ----
    if (confirmOpen && selected != null) {
        AlertDialog(
            onDismissRequest = { confirmOpen = false },
            title = { Text("Erase this USB stick?") },
            text = {
                Text(
                    "All data on \"${selected!!.vendor} ${selected!!.product}\" will be " +
                        "permanently erased and replaced with a Ventoy layout. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmOpen = false
                    launchInstall()
                }) { Text("Erase and install", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmOpen = false }) { Text("Cancel") }
            },
        )
    }

    if (tooBigIso != null) {
        AlertDialog(
            onDismissRequest = { tooBigIso = null },
            title = { Text("Large ISO — exFAT will be used") },
            text = {
                Text(
                    "\"${tooBigIso!!.name}\" is larger than 4 GiB, so the data partition " +
                        "will be formatted as exFAT (same as official Ventoy). Windows, Linux " +
                        "and the boot menu all read it fine."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    tooBigIso = null
                    maybeAskPowerExemption()
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { tooBigIso = null }) { Text("Back") }
            },
        )
    }
}

@Composable
private fun StepCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun DeviceRow(candidate: UsbDiskCandidate, selected: Boolean, onSelect: () -> Unit) {
    Card(
        onClick = onSelect,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (selected) colorScheme.primaryContainer else colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${candidate.vendor} ${candidate.product}", fontWeight = FontWeight.Medium)
                Text(
                    candidate.serial ?: "id: ${candidate.device.deviceId}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(if (selected) "Selected" else "Select", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ProgressView(state: InstallState.Running) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (val p = state.progress) {
            is InstallProgress.Stage -> {
                Text(p.name, fontWeight = FontWeight.Medium)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is InstallProgress.Fraction -> {
                Text("${p.stage} — ${(100 * p.done / p.total.coerceAtLeast(1))}%")
                LinearProgressIndicator(
                    progress = { (p.done.toFloat() / p.total.toFloat().coerceAtLeast(1f)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is InstallProgress.Done -> Text("Done")
        }
    }
}

private const val FOUR_GIB: Long = 4L * 1024 * 1024 * 1024
