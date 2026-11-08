package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * CarLink x86: the head unit's voice key. FYT/SYU firmware does not send the steering-wheel voice
 * key as a key event: its MainServer starts the assistant with an intent (VOICE_ASSIST). Choosing
 * CarLink for that intent once ("Always") sends the key to Siri while a CarPlay session runs, and
 * to the assistant the head unit would have opened otherwise.
 */
class VoiceKeyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val siri = CarPlayMediaKeys.onExternalSiriKey()
        val message = "voice intent ${intent?.action} -> Siri sent=$siri"
        Log.i(TAG, message)
        WheelKeyLog.note(message)
        if (!siri) forwardToAssistant()
        finish()
    }

    private fun forwardToAssistant() {
        val action = intent?.action ?: "android.intent.action.VOICE_ASSIST"
        val target = runCatching {
            packageManager.queryIntentActivities(Intent(action), 0)
                .firstOrNull { it.activityInfo.packageName != packageName }
        }.getOrNull() ?: return
        runCatching {
            startActivity(Intent(action)
                .setClassName(target.activityInfo.packageName, target.activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "voice intent: could not open ${target.activityInfo.packageName}", it) }
    }

    private companion object {
        const val TAG = "CarLinkVoiceKey"
    }
}
