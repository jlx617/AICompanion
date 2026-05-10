package com.ai.companion.ai

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class AIService(private val context: Context) {

    companion object {
        private const val TAG = "AIService"
        private const val PREFS_NAME = "ai_companion_prefs"
        private const val KEY_PROVIDER = "api_provider"
        private const val KEY_API_KEY = "api_key"

        private const val SILICONFLOW_URL = "https://api.siliconflow.com/v1/chat/completions"
        private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent"
        private const val DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions"
        private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
    }

    enum class Provider(val index: Int, val displayName: String, val model: String, val url: String) {
        SILICONFLOW(0, "SiliconFlow", "Qwen/Qwen2.5-7B-Instruct", SILICONFLOW_URL),
        DEEPSEEK(1, "DeepSeek", "deepseek-chat", DEEPSEEK_URL),
        GOOGLE_GEMINI(2, "Google Gemini", "gemini-pro", GEMINI_URL),
        GROQ(3, "Groq", "llama3-8b-8192", GROQ_URL);

        companion object {
            fun fromIndex(index: Int): Provider {
                return values().find { it.index == index } ?: SILICONFLOW
            }
        }
    }

    data class TestResult(
        val success: Boolean,
        val message: String,
        val responseCode: Int = 0
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    private fun getProvider(): Provider {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val index = prefs.getInt(KEY_PROVIDER, 0)
        return Provider.fromIndex(index)
    }

    private fun getApiKey(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_API_KEY, "") ?: ""
    }

    data class ChatMessage(
        val role: String,
        val content: String
    )

    data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        @SerializedName("max_tokens")
        val maxTokens: Int = 1024,
        val temperature: Double = 0.7
    )

    data class ChatResponse(
        val choices: List<Choice>?
    )

    data class Choice(
        val message: Message?
    )

    data class Message(
        val content: String?
    )

    suspend fun transcribe(audioDataBase64: String): String = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            return@withContext "[未配置API密钥]"
        }

        try {
            val provider = getProvider()
            val prompt = "请转录以下音频数据。音频为base64编码的PCM 16kHz单声道16位格式。"

            val request = buildChatRequest(provider, apiKey, listOf(
                ChatMessage("system", "你是一个语音转文字服务。"),
                ChatMessage("user", prompt)
            ))

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext "[响应为空]"

            parseChatResponse(responseBody, provider)
        } catch (e: Exception) {
            Log.e(TAG, "转录失败", e)
            "[转录错误: ${e.message}]"
        }
    }

    suspend fun generateSuggestion(
        transcript: String,
        sceneContext: String
    ): String = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            return@withContext "[未配置API密钥，请在设置中配置]"
        }

        try {
            val provider = getProvider()
            val systemPrompt = buildSystemPrompt(sceneContext)

            val messages = listOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", "根据这段对话内容: \"$transcript\"，请提供一个简洁有用的建议（最多2句话）。")
            )

            Log.d(TAG, "请求AI: provider=${provider.displayName}, model=${provider.model}")

            val request = buildChatRequest(provider, apiKey, messages)
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "未知错误"
                Log.e(TAG, "API请求失败: ${response.code} - $errorBody")
                return@withContext "[API请求失败: ${response.code}]"
            }
            
            val responseBody = response.body?.string() ?: return@withContext "[响应为空]"

            parseChatResponse(responseBody, provider)
        } catch (e: Exception) {
            Log.e(TAG, "建议生成失败", e)
            "[生成错误: ${e.message}]"
        }
    }

    suspend fun testConnection(): TestResult = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            return@withContext TestResult(false, "API密钥为空，请先输入密钥")
        }

        try {
            val provider = getProvider()
            Log.d(TAG, "测试连接: provider=${provider.displayName}, url=${provider.url}, model=${provider.model}")
            
            val messages = listOf(
                ChatMessage("user", "请回复'连接成功'")
            )
            val request = buildChatRequest(provider, apiKey, messages)
            
            val response = client.newCall(request).execute()
            val responseCode = response.code
            val responseBody = response.body?.string() ?: ""
            
            Log.d(TAG, "测试连接结果: success=${response.isSuccessful}, code=$responseCode")
            
            if (response.isSuccessful) {
                TestResult(true, "连接成功", responseCode)
            } else {
                // 解析错误信息
                val errorMsg = when (responseCode) {
                    401 -> "API密钥无效或已过期，请检查密钥"
                    403 -> "API密钥权限不足或无访问权限"
                    429 -> "请求过于频繁，请稍后再试"
                    500, 502, 503 -> "服务器错误，请稍后重试"
                    else -> "连接失败: HTTP $responseCode"
                }
                Log.e(TAG, "连接失败详情: $responseBody")
                TestResult(false, errorMsg, responseCode)
            }
        } catch (e: Exception) {
            Log.e(TAG, "连接测试失败", e)
            val errorMsg = when {
                e.message?.contains("Unable to resolve host") == true -> "网络错误：无法连接到服务器，请检查网络"
                e.message?.contains("SSL") == true -> "SSL证书错误"
                e.message?.contains("timeout") == true -> "连接超时，请检查网络"
                else -> "连接失败: ${e.message}"
            }
            TestResult(false, errorMsg, 0)
        }
    }

    private fun buildChatRequest(provider: Provider, apiKey: String, messages: List<ChatMessage>): Request {
        return when (provider) {
            Provider.GOOGLE_GEMINI -> {
                val geminiBody = buildGeminiBody(messages)
                val body = geminiBody.toRequestBody("application/json".toMediaType())
                Request.Builder()
                    .url("$GEMINI_URL?key=$apiKey")
                    .post(body)
                    .build()
            }
            else -> {
                val chatRequest = ChatRequest(model = provider.model, messages = messages)
                val body = gson.toJson(chatRequest).toRequestBody("application/json".toMediaType())
                Request.Builder()
                    .url(provider.url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()
            }
        }
    }

    private fun buildGeminiBody(messages: List<ChatMessage>): String {
        val contents = messages.filter { it.role != "system" }.map { msg ->
            mapOf(
                "role" to if (msg.role == "assistant") "model" else "user",
                "parts" to listOf(mapOf("text" to msg.content))
            )
        }
        val systemInstruction = messages.find { it.role == "system" }?.content
        val body = mutableMapOf<String, Any>("contents" to contents)
        if (systemInstruction != null) {
            body["systemInstruction"] = mapOf("parts" to listOf(mapOf("text" to systemInstruction)))
        }
        return gson.toJson(body)
    }

    private fun parseChatResponse(responseBody: String, provider: Provider): String {
        return try {
            if (provider == Provider.GOOGLE_GEMINI) {
                val geminiResponse = gson.fromJson(responseBody, Map::class.java)
                val candidates = geminiResponse["candidates"] as? List<*>
                val firstCandidate = candidates?.firstOrNull() as? Map<*, *>
                val content = firstCandidate?.get("content") as? Map<*, *>
                val parts = content?.get("parts") as? List<*>
                val firstPart = parts?.firstOrNull() as? Map<*, *>
                firstPart?.get("text") as? String ?: "[响应无内容]"
            } else {
                val chatResponse = gson.fromJson(responseBody, ChatResponse::class.java)
                chatResponse.choices?.firstOrNull()?.message?.content ?: "[响应无内容]"
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析响应失败: $responseBody", e)
            "[解析错误]"
        }
    }

    private fun buildSystemPrompt(sceneContext: String): String {
        return "你是一个AI对话助手。你通过耳机麦克风聆听对话，并提供有帮助的、简洁的建议。" +
            "当前场景: $sceneContext。" +
            "请保持回复简短（最多1-2句话）。要有帮助且切题。请用中文回复。"
    }
}
