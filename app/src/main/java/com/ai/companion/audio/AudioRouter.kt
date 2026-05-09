package com.ai.companion.audio

import android.content.Context
import android.media.AudioManager

class AudioRouter(private val context: Context) {

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    fun routeToHeadset() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false

        try {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
        } catch (e: SecurityException) {
            // Bluetooth SCO may not be available
        }
    }

    fun routeToSpeaker() {
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = true

        try {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
        } catch (e: SecurityException) {
            // Bluetooth SCO may not be available
        }
    }

    fun isWiredHeadsetConnected(): Boolean {
        return audioManager.isWiredHeadsetOn
    }

    fun isBluetoothHeadsetConnected(): Boolean {
        return try {
            audioManager.isBluetoothScoAvailableOffCall
        } catch (e: Exception) {
            false
        }
    }

    fun setStreamVolume(streamType: Int, index: Int, flags: Int) {
        audioManager.setStreamVolume(streamType, index, flags)
    }

    fun getStreamMaxVolume(streamType: Int): Int {
        return audioManager.getStreamMaxVolume(streamType)
    }
}
