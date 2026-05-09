package com.ai.companion.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.min

class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 2
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    interface AudioCallback {
        fun onAudioData(data: ByteArray)
        fun onError(error: String)
    }

    private var callback: AudioCallback? = null

    fun setCallback(cb: AudioCallback) {
        callback = cb
    }

    fun startRecording(): Boolean {
        if (isRecording) return true

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )

        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            callback?.onError("Failed to get minimum buffer size")
            return false
        }

        val readSize = min(bufferSize * BUFFER_SIZE_FACTOR, 65536)

        return try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                readSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                callback?.onError("AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            audioRecord?.startRecording()
            isRecording = true

            recordingThread = Thread {
                val buffer = ByteArray(readSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        val chunk = buffer.copyOf(read)
                        callback?.onAudioData(chunk)
                    } else if (read < 0) {
                        Log.e(TAG, "AudioRecord read error: $read")
                        if (read == AudioRecord.ERROR_INVALID_OPERATION) {
                            break
                        }
                    }
                }
            }.apply {
                name = "AudioRecorderThread"
                start()
            }

            Log.d(TAG, "Recording started")
            true
        } catch (e: SecurityException) {
            callback?.onError("No permission to record audio: ${e.message}")
            false
        } catch (e: Exception) {
            callback?.onError("Failed to start recording: ${e.message}")
            false
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        try {
            recordingThread?.join(2000)
        } catch (_: InterruptedException) {
        }
        recordingThread = null

        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
        }
        audioRecord?.release()
        audioRecord = null

        Log.d(TAG, "Recording stopped")
    }

    val recording: Boolean
        get() = isRecording
}
