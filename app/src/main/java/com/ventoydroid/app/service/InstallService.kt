package com.ventoydroid.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.ventoydroid.app.R
import com.ventoydroid.app.VentoyDroidApp
import com.ventoydroid.app.install.InstallProgress
import com.ventoydroid.app.install.InstallState
import com.ventoydroid.app.install.UsbInstallRunner
import com.ventoydroid.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps an install alive when the user backgrounds
 * the app and holds a partial wakelock so the phone doesn't sleep mid-write.
 */
class InstallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var runningJob: Job? = null
    private lateinit var runner: UsbInstallRunner
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        // Share the app-scoped runner so the UI sees the same state flow.
        runner = (application as VentoyDroidApp).installRunner
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                runner.cancel()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val deviceKey = intent?.getStringExtra(EXTRA_DEVICE_KEY) ?: return START_NOT_STICKY
                val isoUris = intent.getStringArrayListExtra(EXTRA_ISO_URIS).orEmpty()
                startInForeground("Preparing install")
                observeState()
                runningJob?.cancel()
                runningJob = scope.launch {
                    val sources = com.ventoydroid.app.iso.IsoRepository.fromUris(
                        this@InstallService,
                        isoUris.map { android.net.Uri.parse(it) },
                    )
                    runner.run(deviceKey, sources)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun observeState() = scope.launch {
        runner.state.collect { state ->
            when (state) {
                is InstallState.Running -> updateNotification(state.progress)
                is InstallState.Success -> {
                    notifyDone(state.message, error = false)
                    stopSelf()
                }
                is InstallState.Failed -> {
                    notifyDone(state.error, error = true)
                    stopSelf()
                }
                InstallState.Idle -> Unit
            }
        }
    }

    private fun updateNotification(progress: InstallProgress) {
        val (text, frac) = when (progress) {
            is InstallProgress.Stage -> progress.name to -1f
            is InstallProgress.Fraction ->
                "${progress.stage} — ${progress.done / (1 shl 20)} / ${progress.total / (1 shl 20)} MiB" to
                    (progress.done.toDouble() / progress.total.toDouble().coerceAtLeast(1.0)).toFloat()
            is InstallProgress.Done -> "Finishing…" to 1f
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text, frac))
    }

    private fun startInForeground(text: String) {
        acquireWakeLock()
        val notification = buildNotification(text, -1f)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String, fraction: Float): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, InstallService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "Cancel", cancelIntent)
        if (fraction >= 0f) b.setProgress(100, (fraction * 100).toInt(), false)
        else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun notifyDone(message: String, error: Boolean) {
        releaseWakeLock()
        val nm = getSystemService(NotificationManager::class.java)
        val icon = if (error) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID + 1, n)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VentoyDroid:install").also {
            it.acquire(WAKELOCK_TIMEOUT_MS)
        }
        // Keep the screen on too: some OEMs (MIUI in particular) cut OTG bus
        // power when the display sleeps, which drops the stick mid-write with
        // a random-sector BOT failure a Mass Storage Reset cannot fix. A
        // partial wakelock keeps the CPU alive but does not prevent that.
        @Suppress("DEPRECATION")
        screenWakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "VentoyDroid:installScreen",
        ).also { it.acquire(WAKELOCK_TIMEOUT_MS) }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        runCatching { screenWakeLock?.takeIf { it.isHeld }?.release() }
        screenWakeLock = null
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            getString(R.string.service_notification_channel),
            getString(R.string.service_notification_channel_title),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        releaseWakeLock()
        runningJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        private const val CHANNEL_ID = "installs"
        private const val NOTIFICATION_ID = 42
        private const val WAKELOCK_TIMEOUT_MS = 60 * 60 * 1000L
        const val EXTRA_DEVICE_KEY = "deviceKey"
        const val EXTRA_ISO_URIS = "isoUris"
        const val ACTION_CANCEL = "com.ventoydroid.app.CANCEL"

        fun start(context: Context, deviceKey: String, isoUris: List<String>) {
            val intent = Intent(context, InstallService::class.java)
                .putExtra(EXTRA_DEVICE_KEY, deviceKey)
                .putStringArrayListExtra(EXTRA_ISO_URIS, ArrayList(isoUris))
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, InstallService::class.java).setAction(ACTION_CANCEL)
            )
        }
    }
}
