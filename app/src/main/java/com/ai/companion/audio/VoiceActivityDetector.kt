package com.ai.companion.audio

import android.util.Log
import java.io.ByteArrayOutputStream

class VoiceActivityDetector(private val listener: SpeechSegmentListener) {

    companion object {
        private const val TAG = "VAD"

        /** RMS 阈值 - 设为极低值确保能检测到任何语音 */
        private const val SILENCE_THRESHOLD = 10

        /** 持续静音超过此时长（毫秒），判定为一段语音结束 */
        private const val SUSTAINED_SILENCE_MS = 600L

        /** 最短语音片段时长（毫秒） */
        private const val MIN_SEGMENT_MS = 200L

        /** 防抖时长（毫秒） */
        private const val DEBOUNCE_MS = 300L
    }

    private var isSpeaking = false
    private var speechStartTime = 0L
    private var lastSpeechTime = 0L
    private var lastSegmentEndTime = 0L
    private var audioBuffer = ByteArrayOutputStream()

    /** 最近一次 RMS 值 - 供外部读取显示 */
    @Volatile
    var lastRms: Double = 0.0
        private set

    @Volatile
    var isCurrentlySpeaking: Boolean = false
        private set

    /** 回调：每次 RMS 更新时通知外部 */
    var onRmsUpdate: ((Double) -> Unit)? = null

    @Synchronized
    fun processAudioData(data: ByteArray, sampleRate: Int) {
        val now = System.currentTimeMillis()

        // 防抖
        if (!isSpeaking && lastSegmentEndTime > 0 && (now - lastSegmentEndTime) < DEBOUNCE_MS) {
            return
        }

        val rms = calculateRms(data)
        lastRms = rms

        // 每次都通知外部更新 RMS（用于实时显示）
        onRmsUpdate?.invoke(rms)

        if (rms > SILENCE_THRESHOLD) {
            lastSpeechTime = now

            if (!isSpeaking) {
                isSpeaking = true
                isCurrentlySpeaking = true
                speechStartTime = now
                audioBuffer = ByteArrayOutputStream()
                Log.d(TAG, "语音开始 (RMS=$rms, 阈值=$SILENCE_THRESHOLD)")
                listener.onSpeechStarted()
            }

            audioBuffer.write(data)
        } else {
            if (isSpeaking) {
                audioBuffer.write(data)

                if ((now - lastSpeechTime) >= SUSTAINED_SILENCE_MS) {
                    endSpeechSegment(now)
                }
            }
        }
    }

    private fun endSpeechSegment(now: Long) {
        val segmentDurationMs = now - speechStartTime
        isSpeaking = false
        isCurrentlySpeaking = false
        lastSegmentEndTime = now

        Log.d(TAG, "语音结束 (持续 ${segmentDurationMs}ms)")

        if (segmentDurationMs >= MIN_SEGMENT_MS) {
            val audioData = audioBuffer.toByteArray()
            Log.d(TAG, "语音片段已提交 (${audioData.size} 字节)")
            listener.onSpeechSegment(audioData)
        } else {
            Log.d(TAG, "语音片段过短，已丢弃")
        }

        audioBuffer = ByteArrayOutputStream()
        listener.onSpeechEnded()
    }

    private fun calculateRms(data: ByteArray): Double {
        var sum = 0.0
        val sampleCount = data.size / 2

        for (i in 0 until sampleCount) {
            val low = data[i * 2].toInt() and 0xFF
            val high = data[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            val signedSample = if (sample > Short.MAX_VALUE.toInt()) sample - 65536 else sample
            sum += signedSample.toDouble() * signedSample.toDouble()
        }

        return if (sampleCount > 0) kotlin.math.sqrt(sum / sampleCount) else 0.0
    }

    @Synchronized
    fun reset() {
        if (isSpeaking) {
            endSpeechSegment(System.currentTimeMillis())
        }
        isSpeaking = false
        isCurrentlySpeaking = false
        audioBuffer = ByteArrayOutputStream()
        Log.d(TAG, "VAD 已重置")
    }
}

interface SpeechSegmentListener {
    fun onSpeechSegment(audioData: ByteArray)
    fun onSpeechStarted()
    fun onSpeechEnded()
}
