package com.carlink.core.headunit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.carlink.core.connection.Connection
import com.carlink.core.connection.SessionState
import com.carlink.core.core.Transport
import com.carlink.core.core.Engine
import com.carlink.core.diagnostics.EventLog
import com.shilapi.xcertplay.StereoKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * CarLink x86 (FYT/SYU head units): tells the firmware (MainServer, com.syu.ms) "CarPlay connected"
 * the way the stereo's own zLink does, while a CarPlay session runs. MainServer then drops its
 * Bluetooth hands-free link to the iPhone (CarPlay carries calls), stops opening its Bluetooth
 * app over CarPlay, and sends the steering-wheel voice key as zLink's special function 1500
 * (and answer 5 / hang up 6) instead of starting voice dialling over Bluetooth. Those come back
 * here as Siri and CarPlay call controls. Off: Settings › Connection › "Act as zLink for the stereo".
 */
object ZLinkBridge {
    private const val TAG = "CarLink-ZLinkBridge"
    private const val ACTION = "com.zjinnova.zlink"
    private const val MAIN_SERVER = "com.syu.ms"
    private const val PREFS = "carlink_zlink"
    private const val KEY_ENABLED = "bridge"
    private const val SIRI = 1500
    private const val ANSWER = 5
    private const val HANG_UP = 6

    @Volatile private var announced: Transport? = null

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        if (!on) withdraw(context.applicationContext)
    }

    fun install(context: Context) {
        val app = context.applicationContext
        if (!installed(app)) return
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onFirmwareCommand(intent)
        }, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            Connection.get(app).state
                .map { state ->
                    (state as? SessionState.Active)
                        ?.takeIf { it.request.engine == Engine.CARPLAY && it.phase == SessionState.Phase.Connected }
                        ?.request?.transport
                }
                .distinctUntilChanged()
                .collect { transport ->
                    if (transport != null && enabled(app)) announce(app, transport) else withdraw(app)
                }
        }
    }

    private fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getApplicationInfo(MAIN_SERVER, 0) }.isSuccess

    private fun announce(context: Context, transport: Transport) {
        announced = transport
        val mode = if (transport == Transport.WIRELESS) "carplay_wireless" else "carplay_wired"
        send(context, Intent(ACTION).putExtra("status", "CONNECTED")
            .putExtra("phoneType", "ios_carplay").putExtra("phoneMode", mode))
        send(context, Intent(ACTION).putExtra("status", "MAIN_PAGE_SHOW"))
        note("announced CarPlay ($mode) to the stereo")
    }

    private fun withdraw(context: Context) {
        val transport = announced ?: return
        announced = null
        val mode = if (transport == Transport.WIRELESS) "carplay_wireless" else "carplay_wired"
        send(context, Intent(ACTION).putExtra("status", "MAIN_PAGE_HIDDEN"))
        send(context, Intent(ACTION).putExtra("status", "DISCONNECT")
            .putExtra("phoneType", "ios_carplay").putExtra("phoneMode", mode))
        note("withdrew CarPlay from the stereo")
    }

    private fun send(context: Context, intent: Intent) {
        runCatching { context.sendBroadcast(intent.setPackage(MAIN_SERVER)) }
            .onFailure { Log.w(TAG, "broadcast failed", it) }
    }

    private fun onFirmwareCommand(intent: Intent) {
        if (intent.getStringExtra("command") != "REQ_SPEC_FUNC_CMD" || announced == null) return
        val code = intent.getIntExtra("specFuncCode", 0)
        val sent = when (code) {
            SIRI -> StereoKeys.siri()
            ANSWER -> StereoKeys.answerCall()
            HANG_UP -> StereoKeys.endCall()
            else -> return note("stereo key code $code not used")
        }
        note("stereo key code $code -> CarPlay sent=$sent")
    }

    private fun note(message: String) {
        Log.i(TAG, message)
        EventLog.add(TAG, message)
    }
}
