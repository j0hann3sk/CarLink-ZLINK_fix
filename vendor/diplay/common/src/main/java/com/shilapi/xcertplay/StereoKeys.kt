package com.shilapi.xcertplay

/** CarLink x86: wheel keys a head unit's firmware hands over outside the Android key stream (FYT zLink bridge). */
object StereoKeys {
    fun siri(): Boolean = CarPlayMediaKeys.onExternalSiriKey()
    fun answerCall(): Boolean = CarPlayMediaKeys.onExternalCallKey(answer = true)
    fun endCall(): Boolean = CarPlayMediaKeys.onExternalCallKey(answer = false)
}
