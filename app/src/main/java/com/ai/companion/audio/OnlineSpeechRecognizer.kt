package com.ai.companion.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 在线语音识别 - 使用 SiliconFlow 的 Whisper API
 * 
 * 优点：
 * - 不需要下载模型
 * - 识别准确率高
 * - 支持流式识别
 */
class OnlineSpeechRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "OnlineSTT"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE = 3200 // 100ms @ 16kHz
        private const val SILENCE_THRESHOLD = 500
        private const val MIN_SPEECH_MS = 500L
        private const val SILENCE_TIMEOUT_MS = 1500L
        
        // SiliconFlow Whisper API
        private const val API_URL = "https://api.siliconflow.cn/v1/audio/transcriptions"
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    interface SpeechRecognitionListener {
        fun onReady()
        fun onSpeechStart()
        fun onSpeechEnd()
        fun onResult(text: String)
        fun onError(error: String)
    }

    private var listener: SpeechRecognitionListener? = null

    fun setListener(l: SpeechRecognitionListener) {
        listener = l
    }

    fun startListening(): Boolean {
        if (isRecording) {
            Log.w(TAG, "已经在录音中")
            return false
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "无效的缓冲区大小")
            return false
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化失败")
                return false
            }

            audioRecord?.startRecording()
            isRecording = true
            listener?.onReady()

            recordingJob = scope.launch {
                recordAndRecognize()
            }

            Log.d(TAG, "开始录音")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "启动录音失败", e)
            listener?.onError("启动录音失败: ${e.message}")
            return false
        }
    }

    fun stopListening() {
        isRecording = false
        recordingJob?.cancel()
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "停止录音异常", e)
        }
        audioRecord = null
        
        Log.d(TAG, "停止录音")
    }

    private suspend fun recordAndRecognize() {
        val audioRecord = this.audioRecord ?: return
        val buffer = ShortArray(BUFFER_SIZE)
        val audioBuffer = ByteArrayOutputStream()
        
        var isSpeechActive = false
        var speechStartTime = 0L
        var lastSpeechTime = 0L
        
        try {
            while (isRecording && isActive) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read < 0) {
                    Log.e(TAG, "读取音频失败: $read")
                    break
                }
                
                // 计算音量
                val amplitude = calculateAmplitude(buffer, read)
                val isSpeech = amplitude > SILENCE_THRESHOLD
                
                if (isSpeech) {
                    if (!isSpeechActive) {
                        isSpeechActive = true
                        speechStartTime = System.currentTimeMillis()
                        listener?.onSpeechStart()
                        Log.d(TAG, "检测到语音开始")
                    }
                    lastSpeechTime = System.currentTimeMillis()
                }
                
                if (isSpeechActive) {
                    // 写入缓冲区
                    for (i in 0 until read) {
                        audioBuffer.write(buffer[i].toInt() and 0xFF)
                        audioBuffer.write((buffer[i].toInt() shr 8) and 0xFF)
                    }
                    
                    // 检查是否结束
                    val silenceDuration = System.currentTimeMillis() - lastSpeechTime
                    if (silenceDuration > SILENCE_TIMEOUT_MS) {
                        val speechDuration = lastSpeechTime - speechStartTime
                        if (speechDuration >= MIN_SPEECH_MS) {
                            // 发送识别
                            listener?.onSpeechEnd()
                            val audioData = audioBuffer.toByteArray()
                            recognize(audioData)
                        }
                        // 重置
                        isSpeechActive = false
                        audioBuffer.reset()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "录音循环异常", e)
            listener?.onError("录音异常: ${e.message}")
        }
    }

    private fun calculateAmplitude(buffer: ShortArray, read: Int): Double {
        var sum = 0.0
        for (i in 0 until read) {
            sum += kotlin.math.abs(buffer[i].toInt())
        }
        return if (read > 0) sum / read else 0.0
    }

    private suspend fun recognize(audioData: ByteArray) {
        try {
            Log.d(TAG, "发送识别请求，音频大小: ${audioData.size} 字节")
            
            val prefs = context.getSharedPreferences("ai_companion_prefs", Context.MODE_PRIVATE)
            val apiKey = prefs.getString("api_key", "") ?: ""
            
            if (apiKey.isBlank()) {
                listener?.onError("未配置 API 密钥")
                return
            }
            
            // 转换为 WAV 格式
            val wavData = pcmToWav(audioData, SAMPLE_RATE)
            
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "audio.wav",
                    wavData.toRequestBody("audio/wav".toMediaType())
                )
                .addFormDataPart("model", "FunAudioLLM/SenseVoiceSmall")
                .build()
            
            val request = Request.Builder()
                .url(API_URL)
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()
            
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            
            if (!response.isSuccessful) {
                val error = response.body?.string() ?: "未知错误"
                Log.e(TAG, "识别失败: $error")
                listener?.onError("识别失败: ${response.code}")
                return
            }
            
            val responseBody = response.body?.string()
            if (responseBody != null) {
                val json = JSONObject(responseBody)
                val text = json.optString("text", "")
                if (text.isNotBlank()) {
                    Log.d(TAG, "识别结果: $text")
                    listener?.onResult(text)
                } else {
                    Log.d(TAG, "识别结果为空")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "识别请求异常", e)
            listener?.onError("识别异常: ${e.message}")
        }
    }

    private fun pcmToWav(pcmData: ByteArray, sampleRate: Int): ByteArray {
        val byteRate = sampleRate * 2 // 16-bit mono
        val wavData = ByteArrayOutputStream()
        
        // WAV header
        wavData.write("RIFF".toByteArray())
        wavData.write(intToBytes(36 + pcmData.size))
        wavData.write("WAVE".toByteArray())
        wavData.write("fmt ".toByteArray())
        wavData.write(intToBytes(16)) // Subchunk1Size
        wavData.write(shortToBytes(1)) // AudioFormat (PCM)
        wavData.write(shortToBytes(1)) // NumChannels (Mono)
        wavData.write(intToBytes(sampleRate))
        wavData.write(intToBytes(byteRate))
        wavData.write(shortToBytes(2)) // BlockAlign
        wavData.write(shortToBytes(16)) // BitsPerSample
        wavData.write("data".toByteArray())
        wavData.write(intToBytes(pcmData.size))
        wavData.write(pcmData)
        
        return wavData.toByteArray()
    }

    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun shortToBytes(value: Short): ByteArray {
        return byteArrayOf(
            (value.toInt() and 0xFF).toByte(),
            ((value.toInt() shr 8) and 0xFF).toByte()
        )
    }

    fun destroy() {
        stopListening()
        scope.cancel()
    }
}
