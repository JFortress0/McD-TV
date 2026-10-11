package com.mcd.tv.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * Hears back from the system installer after [Updater] hands it the new APK (Android 12+ session install).
 *  - Pending user action: this TV still wants a confirmation (first update after a Downloader install, or a
 *    TV that doesn't allow silent self-updates). The system's Install screen opens.
 *  - Success: Android stops the old app as it replaces it; [Relaunch] opens the new one.
 *  - Anything else: the update screen shows the reason.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    Updater.installResult(false, "The TV didn't open its installer. Try again, or install with Downloader.")
                    return
                }
                Updater.installResult(true, "This TV wants a yes once. Press Install.")
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { Updater.installResult(false, "The TV didn't open its installer. Try again, or install with Downloader.") }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.installResult(true, "Installed. Jarvis restarts by itself.")
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Updater.installResult(false, "Update failed ($status${if (why.isNotBlank()) ": $why" else ""}). Try again, or install with Downloader.")
            }
        }
    }
}

/**
 * Opens Jarvis again after it updated itself. Android 9 and older Fire TVs allow this; newer Android may
 * block a start from the background, and then Jarvis simply opens next time from the home screen.
 */
class Relaunch : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)
            ?: return
        runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
