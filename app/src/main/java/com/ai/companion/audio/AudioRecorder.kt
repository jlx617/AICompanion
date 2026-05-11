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
            callback?.onError("无法获取最小缓冲区大小")
            return false
        }

        val readSize = min(bufferSize * BUFFER_SIZE_FACTOR, 65536)

        // 尝试多种音频源，确保至少有一种能工作
        val audioSources = listOf(
            MediaRecorder.AudioSource.MIC to "MIC",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
            MediaRecorder.AudioSource.DEFAULT to "DEFAULT"
        )

        for ((source, name) in audioSources) {
            try {
                val recorder = AudioRecord(
                    source,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    readSize
                )

                if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                    recorder.startRecording()
                    
                    // 验证是否真的读到了数据
                    val testBuffer = ByteArray(readSize)
                    val testRead = recorder.read(testBuffer, 0, testBuffer.size)
                    
                    if (testRead > 0) {
                        val testRms = calculateTestRms(testBuffer, testRead)
                        Log.d(TAG, "音频源 $name 初始化成功, 测试读取 $testRead 字节, RMS=$testRms")
                        
                        audioRecord = recorder
                        isRecording = true

                        recordingThread = Thread {
                            val buffer = ByteArray(readSize)
                            while (isRecording) {
                                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                                if (read > 0) {
                                    val chunk = buffer.copyOf(read)
                                    callback?.onAudioData(chunk)
                                } else if (read < 0) {
                                    Log.e(TAG, "AudioRecord 读取错误: $read")
                                    if (read == AudioRecord.ERROR_INVALID_OPERATION) {
                                        break
                                    }
                                }
                            }
                        }.apply {
                            setName("AudioRecorderThread")
                            start()
                        }

                        Log.d(TAG, "录音已启动 (音频源: $name)")
                        return true
                    } else {
                        Log.w(TAG, "音频源 $name 初始化成功但无法读取数据 (read=$testRead)")
                        recorder.stop()
                        recorder.release()
                    }
                } else {
                    Log.w(TAG, "音频源 $name 初始化失败")
                    recorder.release()
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "音频源 $name 没有权限: ${e.message}")
                callback?.onError("没有录音权限: ${e.message}")
                return false
            } catch (e: Exception) {
                Log.w(TAG, "音频源 $name 不可用: ${e.message}")
            }
        }

        callback?.onError("所有音频源均不可用")
        return false
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

        Log.d(TAG, "录音已停止")
    }

    val recording: Boolean
        get() = isRecording

    private fun calculateTestRms(data: ByteArray, length: Int): Double {
        var sum = 0.0
        val sampleCount = length / 2
        for (i in 0 until sampleCount) {
            val low = data[i * 2].toInt() and 0xFF
            val high = data[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            val signedSample = if (sample > Short.MAX_VALUE.toInt()) sample - 65536 else sample
            sum += signedSample.toDouble() * signedSample.toDouble()
        }
        return if (sampleCount > 0) kotlin.math.sqrt(sum / sampleCount) else 0.0
    }
}
