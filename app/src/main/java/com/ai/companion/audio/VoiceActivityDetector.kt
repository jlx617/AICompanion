package com.ai.companion.audio

import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * 语音活动检测器 (Voice Activity Detector)
 *
 * 分析音频块以检测何时有人在说话，并将音频分割为语音片段用于转录。
 */
class VoiceActivityDetector(private val listener: SpeechSegmentListener) {

    companion object {
        private const val TAG = "VAD"

        /** RMS 音量低于此值视为静音 - 设置较低以确保灵敏度 */
        private const val SILENCE_THRESHOLD = 50

        /** 持续静音超过此时长（毫秒），判定为一段语音结束 */
        private const val SUSTAINED_SILENCE_MS = 700L

        /** 最短语音片段时长（毫秒），低于此值的片段视为噪声被丢弃 */
        private const val MIN_SEGMENT_MS = 200L

        /** 防抖时长（毫秒），触发一段语音结束后等待此时间再开始新片段 */
        private const val DEBOUNCE_MS = 500L
    }

    /** 当前是否处于语音活动状态 */
    private var isSpeaking = false

    /** 语音开始时间戳 */
    private var speechStartTime = 0L

    /** 最后一次检测到语音（非静音）的时间戳 */
    private var lastSpeechTime = 0L

    /** 上一次语音片段结束时间戳，用于防抖 */
    private var lastSegmentEndTime = 0L

    /** 当前采样率，由 processAudioData 传入 */
    private var currentSampleRate = 0

    /** 用于累积当前语音片段的音频字节 */
    private var audioBuffer = ByteArrayOutputStream()

    /** 用于调试：记录最近一次RMS值 */
    @Volatile
    var lastRms: Double = 0.0
        private set

    /** 用于调试：是否正在说话 */
    @Volatile
    var isCurrentlySpeaking: Boolean = false
        private set

    @Synchronized
    fun processAudioData(data: ByteArray, sampleRate: Int) {
        currentSampleRate = sampleRate
        val now = System.currentTimeMillis()

        // 防抖：如果距离上一段结束时间不足 DEBOUNCE_MS，忽略所有音频
        if (!isSpeaking && lastSegmentEndTime > 0 && (now - lastSegmentEndTime) < DEBOUNCE_MS) {
            return
        }

        val rms = calculateRms(data)
        lastRms = rms

        // 每5秒打印一次RMS日志（避免日志过多）
        if (now % 5000 < 100) {
            Log.d(TAG, "RMS=$rms, threshold=$SILENCE_THRESHOLD, speaking=$isSpeaking")
        }

        if (rms > SILENCE_THRESHOLD) {
            lastSpeechTime = now

            if (!isSpeaking) {
                isSpeaking = true
                isCurrentlySpeaking = true
                speechStartTime = now
                audioBuffer = ByteArrayOutputStream()
                Log.d(TAG, "语音开始 (RMS=$rms)")
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
            Log.d(TAG, "语音片段已提交 (${audioData.size} 字节, ${segmentDurationMs}ms)")
            listener.onSpeechSegment(audioData)
        } else {
            Log.d(TAG, "语音片段过短 (${segmentDurationMs}ms < ${MIN_SEGMENT_MS}ms)，已丢弃")
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
            val signedSample = if (sample > Short.MAX_VALUE.toInt()) {
                sample - 65536
            } else {
                sample
            }
            sum += signedSample.toDouble() * signedSample.toDouble()
        }

        return if (sampleCount > 0) {
            kotlin.math.sqrt(sum / sampleCount)
        } else {
            0.0
        }
    }

    @Synchronized
    fun reset() {
        if (isSpeaking) {
            val now = System.currentTimeMillis()
            endSpeechSegment(now)
        }

        isSpeaking = false
        isCurrentlySpeaking = false
        speechStartTime = 0L
        lastSpeechTime = 0L
        lastSegmentEndTime = 0L
        currentSampleRate = 0
        audioBuffer = ByteArrayOutputStream()

        Log.d(TAG, "VAD 状态已重置")
    }
}

interface SpeechSegmentListener {
    fun onSpeechSegment(audioData: ByteArray)
    fun onSpeechStarted()
    fun onSpeechEnded()
}
