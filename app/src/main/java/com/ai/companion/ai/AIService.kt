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

        private const val SILICONFLOW_URL = "https://api.siliconflow.cn/v1/chat/completions"
        private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent"
        private const val DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions"
        private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
    }

    enum class Provider(val value: String) {
        SILICONFLOW("SiliconFlow"),
        GOOGLE_GEMINI("Google Gemini"),
        DEEPSEEK("DeepSeek"),
        GROQ("Groq")
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    private fun getProvider(): Provider {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = prefs.getString(KEY_PROVIDER, Provider.SILICONFLOW.value) ?: Provider.SILICONFLOW.value
        return Provider.values().find { it.value == value } ?: Provider.SILICONFLOW
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
            return@withContext "[No API key configured]"
        }

        // Use the chat API to process audio context
        // In a real implementation, you would use a speech-to-text API
        // This is a placeholder that sends audio context to the AI
        try {
            val provider = getProvider()
            val prompt = "Transcribe the following audio data context. " +
                "The audio is base64 encoded PCM 16kHz mono 16-bit. " +
                "Provide a transcription of what was said."

            val request = buildChatRequest(provider, apiKey, listOf(
                ChatMessage("system", "You are a speech-to-text transcription service."),
                ChatMessage("user", prompt)
            ))

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext "[Empty response]"

            parseChatResponse(responseBody, provider)
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            "[Transcription error: ${e.message}]"
        }
    }

    suspend fun generateSuggestion(
        transcript: String,
        sceneContext: String
    ): String = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            return@withContext "[No API key configured]"
        }

        try {
            val provider = getProvider()
            val systemPrompt = buildSystemPrompt(sceneContext)

            val messages = listOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", "Based on this conversation: \"$transcript\", " +
                    "provide a helpful, concise suggestion (max 2 sentences).")
            )

            val request = buildChatRequest(provider, apiKey, messages)
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext "[Empty response]"

            parseChatResponse(responseBody, provider)
        } catch (e: Exception) {
            Log.e(TAG, "Suggestion generation failed", e)
            "[Suggestion error: ${e.message}]"
        }
    }

    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) return@withContext false

        try {
            val provider = getProvider()
            val messages = listOf(
                ChatMessage("user", "Say 'Connection successful' in one word.")
            )
            val request = buildChatRequest(provider, apiKey, messages)
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed", e)
            false
        }
    }

    private fun buildChatRequest(provider: Provider, apiKey: String, messages: List<ChatMessage>): Request {
        val json = gson.toJson(messages)

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
                val model = when (provider) {
                    Provider.SILICONFLOW -> "Qwen/Qwen2.5-7B-Instruct"
                    Provider.DEEPSEEK -> "deepseek-chat"
                    Provider.GROQ -> "llama3-8b-8192"
                    Provider.GOOGLE_GEMINI -> "gemini-pro"
                }
                val chatRequest = ChatRequest(model = model, messages = messages)
                val body = gson.toJson(chatRequest).toRequestBody("application/json".toMediaType())
                val url = when (provider) {
                    Provider.SILICONFLOW -> SILICONFLOW_URL
                    Provider.DEEPSEEK -> DEEPSEEK_URL
                    Provider.GROQ -> GROQ_URL
                    Provider.GOOGLE_GEMINI -> GEMINI_URL
                }
                Request.Builder()
                    .url(url)
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
                firstPart?.get("text") as? String ?: "[No content in response]"
            } else {
                val chatResponse = gson.fromJson(responseBody, ChatResponse::class.java)
                chatResponse.choices?.firstOrNull()?.message?.content ?: "[No content in response]"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse response: $responseBody", e)
            "[Parse error]"
        }
    }

    private fun buildSystemPrompt(sceneContext: String): String {
        return "You are an AI conversation assistant. You listen to conversations through " +
            "an earphone microphone and provide helpful, concise suggestions. " +
            "Current scene context: $sceneContext. " +
            "Keep responses brief (1-2 sentences max). Be helpful and relevant."
    }
}
