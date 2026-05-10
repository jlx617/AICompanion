package com.ai.companion.audio

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 语音转文字服务，将音频片段发送到 SiliconFlow 的 SenseVoice API 进行转录。
 *
 * 音频要求：WAV 格式（PCM 16kHz 单声道 16-bit）。
 * API 端点：POST https://api.siliconflow.cn/v1/audio/transcriptions
 */
class SpeechToTextService(context: Context) {

    companion object {
        private const val TAG = "STTService"

        private const val PREFS_NAME = "ai_companion_prefs"
        private const val KEY_API_KEY = "api_key"

        private const val API_URL = "https://api.siliconflow.cn/v1/audio/transcriptions"
        private const val MODEL = "FunAudioLLM/SenseVoiceSmall"

        private const val CONNECT_TIMEOUT = 30L
        private const val READ_TIMEOUT = 60L

        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .build()

    /**
     * 转录结果
     *
     * @param success 是否转录成功
     * @param text 转录出的文字内容
     * @param error 错误信息，成功时为 null
     */
    data class TranscriptionResult(
        val success: Boolean,
        val text: String,
        val error: String? = null
    )

    /**
     * 将原始 PCM 音频数据发送到 SiliconFlow SenseVoice API 进行转录。
     *
     * @param audioData 原始 PCM 音频字节数组（16kHz 单声道 16-bit）
     * @return [TranscriptionResult] 包含转录文本或错误信息
     */
    suspend fun transcribe(audioData: ByteArray): TranscriptionResult {
        val apiKey = prefs.getString(KEY_API_KEY, null)
        if (apiKey.isNullOrBlank()) {
            Log.e(TAG, "API 密钥未配置")
            return TranscriptionResult(
                success = false,
                text = "",
                error = "API 密钥未配置，请在设置中填入 SiliconFlow API Key"
            )
        }

        if (audioData.isEmpty()) {
            Log.e(TAG, "音频数据为空")
            return TranscriptionResult(
                success = false,
                text = "",
                error = "音频数据为空"
            )
        }

        return withContext(Dispatchers.IO) {
            try {
                // 将 PCM 数据转换为 WAV 格式
                val wavData = pcmToWav(
                    pcmData = audioData,
                    sampleRate = SAMPLE_RATE,
                    channels = CHANNELS,
                    bitsPerSample = BITS_PER_SAMPLE
                )

                // 创建临时 WAV 文件用于上传
                val tempFile = File.createTempFile("stt_audio_", ".wav")
                try {
                    FileOutputStream(tempFile).use { fos ->
                        fos.write(wavData)
                    }

                    // 构建 multipart/form-data 请求
                    val audioRequestBody = tempFile.asRequestBody(
                        "audio/wav".toMediaType()
                    )
                    val multipartBody = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("model", MODEL)
                        .addFormDataPart("file", "audio.wav", audioRequestBody)
                        .build()

                    val request = Request.Builder()
                        .url(API_URL)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .post(multipartBody)
                        .build()

                    Log.d(TAG, "开始发送音频到 SiliconFlow STT API，音频大小: ${wavData.size} 字节")

                    val response = httpClient.newCall(request).execute()
                    val responseBody = response.body?.string()

                    if (!response.isSuccessful) {
                        Log.e(TAG, "API 请求失败，HTTP ${response.code}: $responseBody")
                        return@withContext TranscriptionResult(
                            success = false,
                            text = "",
                            error = "API 请求失败 (HTTP ${response.code}): ${extractErrorMessage(responseBody)}"
                        )
                    }

                    if (responseBody.isNullOrEmpty()) {
                        Log.e(TAG, "API 返回空响应")
                        return@withContext TranscriptionResult(
                            success = false,
                            text = "",
                            error = "API 返回空响应"
                        )
                    }

                    // 解析 JSON 响应
                    val json = JSONObject(responseBody)
                    val transcribedText = json.optString("text", "")

                    if (transcribedText.isBlank()) {
                        Log.w(TAG, "API 返回的转录文本为空")
                        return@withContext TranscriptionResult(
                            success = true,
                            text = "",
                            error = null
                        )
                    }

                    Log.d(TAG, "转录成功: $transcribedText")
                    TranscriptionResult(
                        success = true,
                        text = transcribedText,
                        error = null
                    )
                } finally {
                    // 清理临时文件
                    tempFile.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "转录过程发生异常", e)
                TranscriptionResult(
                    success = false,
                    text = "",
                    error = "转录失败: ${e.message}"
                )
            }
        }
    }

    /**
     * 将原始 PCM 音频数据转换为标准 WAV 格式。
     *
     * WAV 文件结构（44 字节头部）：
     * - RIFF chunk descriptor（12 字节）
     * - fmt sub-chunk（24 字节）
     * - data sub-chunk header（8 字节）
     *
     * @param pcmData 原始 PCM 音频字节数组
     * @param sampleRate 采样率（Hz）
     * @param channels 声道数
     * @param bitsPerSample 每个采样位的位数
     * @return 包含 WAV 头部和 PCM 数据的完整 WAV 字节数组
     */
    private fun pcmToWav(
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val totalSize = 44 + dataSize

        val wavHeader = ByteArray(44)

        // ===== RIFF chunk descriptor（12 字节） =====
        // ChunkID: "RIFF"
        wavHeader[0] = 'R'.code.toByte()
        wavHeader[1] = 'I'.code.toByte()
        wavHeader[2] = 'F'.code.toByte()
        wavHeader[3] = 'F'.code.toByte()
        // ChunkSize: 文件总大小 - 8
        writeLittleEndianInt(wavHeader, 4, totalSize - 8)
        // Format: "WAVE"
        wavHeader[8] = 'W'.code.toByte()
        wavHeader[9] = 'A'.code.toByte()
        wavHeader[10] = 'V'.code.toByte()
        wavHeader[11] = 'E'.code.toByte()

        // ===== fmt sub-chunk（24 字节） =====
        // Subchunk1ID: "fmt "
        wavHeader[12] = 'f'.code.toByte()
        wavHeader[13] = 'm'.code.toByte()
        wavHeader[14] = 't'.code.toByte()
        wavHeader[15] = ' '.code.toByte()
        // Subchunk1Size: 16（PCM 格式）
        writeLittleEndianInt(wavHeader, 16, 16)
        // AudioFormat: 1（PCM）
        writeLittleEndianShort(wavHeader, 20, 1)
        // NumChannels
        writeLittleEndianShort(wavHeader, 22, channels.toShort())
        // SampleRate
        writeLittleEndianInt(wavHeader, 24, sampleRate)
        // ByteRate
        writeLittleEndianInt(wavHeader, 28, byteRate)
        // BlockAlign
        writeLittleEndianShort(wavHeader, 32, blockAlign.toShort())
        // BitsPerSample
        writeLittleEndianShort(wavHeader, 34, bitsPerSample.toShort())

        // ===== data sub-chunk header（8 字节） =====
        // Subchunk2ID: "data"
        wavHeader[36] = 'd'.code.toByte()
        wavHeader[37] = 'a'.code.toByte()
        wavHeader[38] = 't'.code.toByte()
        wavHeader[39] = 'a'.code.toByte()
        // Subchunk2Size: PCM 数据大小
        writeLittleEndianInt(wavHeader, 40, dataSize)

        // 合并 WAV 头部和 PCM 数据
        return wavHeader + pcmData
    }

    /**
     * 以小端序写入 16 位短整型到字节数组。
     */
    private fun writeLittleEndianInt(array: ByteArray, offset: Int, value: Int) {
        array[offset] = (value and 0xFF).toByte()
        array[offset + 1] = ((value shr 8) and 0xFF).toByte()
        array[offset + 2] = ((value shr 16) and 0xFF).toByte()
        array[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    /**
     * 以小端序写入 16 位短整型到字节数组。
     */
    private fun writeLittleEndianShort(array: ByteArray, offset: Int, value: Short) {
        array[offset] = (value.toInt() and 0xFF).toByte()
        array[offset + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
    }

    /**
     * 从 API 错误响应中提取可读的错误信息。
     */
    private fun extractErrorMessage(responseBody: String?): String {
        if (responseBody.isNullOrEmpty()) return "未知错误"
        return try {
            val json = JSONObject(responseBody)
            json.optString("error", json.optString("message", responseBody))
        } catch (e: Exception) {
            responseBody
        }
    }
}
