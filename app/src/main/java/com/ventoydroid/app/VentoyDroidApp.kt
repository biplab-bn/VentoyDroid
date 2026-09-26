package com.ventoydroid.app

import android.app.Application
import android.os.Build
import android.util.Log
import com.ventoydroid.app.ads.Ads
import com.ventoydroid.app.install.UsbInstallRunner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Holds process-singletons: one install runner shared by the UI and the service.
 * Also captures uncaught exceptions to a file the user can share, so launch
 * crashes are diagnosable without adb.
 */
class VentoyDroidApp : Application() {

    lateinit var installRunner: UsbInstallRunner
        private set

    override fun onCreate() {
        super.onCreate()
        installRunner = UsbInstallRunner(this)
        installCrashLogger()
        Ads.init(this)
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val dir = getExternalFilesDir(null) ?: filesDir
                val file = File(dir, "crash.txt")
                val entry = buildString {
                    appendLine("=== VentoyDroid crash @ " +
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + " ===")
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine(Log.getStackTraceString(throwable))
                    appendLine()
                    // Keep the most recent crash at the top; drop history beyond ~100 KiB.
                    if (file.exists() && file.length() < 100_000) {
                        append(file.readText())
                    }
                }
                file.writeText(entry)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
