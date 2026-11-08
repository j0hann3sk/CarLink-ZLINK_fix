package com.carlink.core.headunit

import android.content.Context
import android.content.Intent
import android.util.Log
import com.carlink.core.diagnostics.EventLog

/**
 * CarLink x86 (FYT/SYU head units): the firmware's own zLink is a persistent system app that
 * cannot be disabled from Settings. When a phone is plugged in, its daemon switches the USB port
 * to device mode (dwc_otg host_dev) and the iPhone vanishes from Android 3 s later. zLink obeys the
 * firmware's broadcast command ACTION_EXIT: it stops its connect daemon and keeps a "suspend"
 * flag file across restarts. ACTION_ENTER deletes that flag and starts it again. Nothing of
 * zLink's licence or data is touched.
 */
object ZLinkPause {
    private const val TAG = "CarLink-ZLink"
    private const val PACKAGE = "com.zjinnova.zlink"
    private const val PREFS = "carlink_zlink"
    private const val KEY_PAUSED = "paused"

    fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getApplicationInfo(PACKAGE, 0) }.isSuccess

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PAUSED, true)

    fun setEnabled(context: Context, paused: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PAUSED, paused).apply()
        send(context, if (paused) "ACTION_EXIT" else "ACTION_ENTER")
    }

    /** At start and before a wired session: pauses zLink again, when the driver keeps it paused. */
    fun apply(context: Context) {
        if (enabled(context)) send(context, "ACTION_EXIT")
    }

    private fun send(context: Context, command: String) {
        if (!installed(context)) return
        runCatching {
            context.sendBroadcast(Intent(PACKAGE).setPackage(PACKAGE).putExtra("command", command))
        }.onSuccess {
            Log.i(TAG, "zLink $command sent")
            EventLog.add(TAG, "zLink $command sent")
        }.onFailure { Log.w(TAG, "zLink $command failed", it) }
    }
}
