package com.ai.companion.audio

import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * 语音活动检测器 (Voice Activity Detector)
 *
 * 分析音频块以检测何时有人在说话，并将音频分割为语音片段用于转录。
 * 基于 RMS 音量阈值判断语音活动，支持静音检测、最短片段过滤和防抖动。
 */
class VoiceActivityDetector(private val listener: SpeechSegmentListener) {

    companion object {
        private const val TAG = "VAD"

        /** RMS 音量低于此值视为静音 */
        private const val SILENCE_THRESHOLD = 100

        /** 持续静音超过此时长（毫秒），判定为一段语音结束 */
        private const val SUSTAINED_SILENCE_MS = 800L

        /** 最短语音片段时长（毫秒），低于此值的片段视为噪声被丢弃 */
        private const val MIN_SEGMENT_MS = 300L

        /** 防抖时长（毫秒），触发一段语音结束后等待此时间再开始新片段 */
        private const val DEBOUNCE_MS = 1000L
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

    /**
     * 处理来自 AudioRecorder 的音频数据块。
     *
     * 对每个 PCM 16-bit 音频块计算 RMS 音量，判断是否为语音活动，
     * 并据此管理语音片段的起始、累积和结束。
     *
     * @param data PCM 16-bit 音频字节数组
     * @param sampleRate 当前音频采样率
     */
    @Synchronized
    fun processAudioData(data: ByteArray, sampleRate: Int) {
        currentSampleRate = sampleRate
        val now = System.currentTimeMillis()

        // 防抖：如果距离上一段结束时间不足 DEBOUNCE_MS，忽略所有音频
        if (!isSpeaking && lastSegmentEndTime > 0 && (now - lastSegmentEndTime) < DEBOUNCE_MS) {
            return
        }

        val rms = calculateRms(data)

        Log.d(TAG, "processAudioData: RMS=$rms, threshold=$SILENCE_THRESHOLD, isSpeaking=$isSpeaking")

        if (rms > SILENCE_THRESHOLD) {
            // 检测到语音
            lastSpeechTime = now

            if (!isSpeaking) {
                // 语音开始
                isSpeaking = true
                speechStartTime = now
                audioBuffer = ByteArrayOutputStream()
                Log.d(TAG, "语音开始 (RMS=$rms)")
                listener.onSpeechStarted()
            }

            // 累积音频数据
            audioBuffer.write(data)
        } else {
            // 静音
            if (isSpeaking) {
                // 仍在语音状态中，继续累积（可能是短暂停顿）
                audioBuffer.write(data)

                // 检查是否持续静音超过阈值
                if ((now - lastSpeechTime) >= SUSTAINED_SILENCE_MS) {
                    endSpeechSegment(now)
                }
            }
        }
    }

    /**
     * 结束当前语音片段。
     * 如果片段时长满足最短要求，则回调 listener；否则丢弃。
     *
     * @param now 当前时间戳
     */
    private fun endSpeechSegment(now: Long) {
        val segmentDurationMs = now - speechStartTime
        isSpeaking = false
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

    /**
     * 计算 PCM 16-bit 音频数据的 RMS（均方根）音量。
     *
     * @param data PCM 16-bit 音频字节数组
     * @return RMS 音量值
     */
    private fun calculateRms(data: ByteArray): Double {
        var sum = 0.0
        val sampleCount = data.size / 2

        for (i in 0 until sampleCount) {
            // PCM 16-bit 小端序：低字节在前，高字节在后
            val low = data[i * 2].toInt() and 0xFF
            val high = data[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            // 转为有符号 16-bit
            val signedSample = if (sample > Short.MAX_VALUE.toInt()) {
                sample - 65536
            } else {
                sample
            }
            sum += signedSample.toDouble() * signedSample.toDouble()
        }

        return if (sampleCount > 0) {
            Math.sqrt(sum / sampleCount)
        } else {
            0.0
        }
    }

    /**
     * 重置所有内部状态。
     * 清除语音检测状态、音频缓冲区和时间戳。
     */
    @Synchronized
    fun reset() {
        if (isSpeaking) {
            // 如果正在录音，先结束当前片段
            val now = System.currentTimeMillis()
            endSpeechSegment(now)
        }

        isSpeaking = false
        speechStartTime = 0L
        lastSpeechTime = 0L
        lastSegmentEndTime = 0L
        currentSampleRate = 0
        audioBuffer = ByteArrayOutputStream()

        Log.d(TAG, "VAD 状态已重置")
    }
}

/**
 * 语音片段监听器接口。
 * 用于接收语音活动检测器产生的事件和完整的语音片段。
 */
interface SpeechSegmentListener {
    /**
     * 当一段完整的语音片段结束时回调。
     * 返回的音频数据可直接用于语音转文字 (STT)。
     *
     * @param audioData 完整的语音片段 PCM 音频字节数据
     */
    fun onSpeechSegment(audioData: ByteArray)

    /**
     * 当检测到有人开始说话时回调。
     */
    fun onSpeechStarted()

    /**
     * 当检测到有人停止说话时回调。
     */
    fun onSpeechEnded()
}
