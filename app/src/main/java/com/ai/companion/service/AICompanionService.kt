package com.ai.companion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import com.ai.companion.MainActivity
import com.ai.companion.R
import com.ai.companion.ai.AIService
import com.ai.companion.ai.ContentAnalyzer
import com.ai.companion.ai.UserPreferences
import com.ai.companion.audio.AudioRecorder
import com.ai.companion.audio.AudioRouter
import com.ai.companion.audio.SpeechSegmentListener
import com.ai.companion.audio.SpeechToTextService
import com.ai.companion.audio.VoiceActivityDetector
import com.ai.companion.ui.FloatingWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

class AICompanionService : Service(), SpeechSegmentListener {

    companion object {
        private const val TAG = "AICompanionService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "ai_companion_channel"
        private const val PREFS_NAME = "ai_companion_prefs"
        private const val KEY_TTS_ENABLED = "tts_enabled"
        private const val SAMPLE_RATE = 16000

        fun start(context: Context) {
            val intent = Intent(context, AICompanionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AICompanionService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    private lateinit var audioRecorder: AudioRecorder
    private lateinit var audioRouter: AudioRouter
    private lateinit var speechToTextService: SpeechToTextService
    private lateinit var contentAnalyzer: ContentAnalyzer
    private lateinit var aiService: AIService
    private lateinit var floatingWindow: FloatingWindow
    private lateinit var userPreferences: UserPreferences
    private lateinit var voiceActivityDetector: VoiceActivityDetector

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    @Volatile
    private var lastTranscribedText: String = ""

    private val notificationManager: NotificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("AI陪伴助手启动中..."))

        audioRecorder = AudioRecorder()
        audioRouter = AudioRouter(this)
        speechToTextService = SpeechToTextService(this)
        contentAnalyzer = ContentAnalyzer(this)
        aiService = AIService(this)
        floatingWindow = FloatingWindow(this)
        userPreferences = UserPreferences(this)
        voiceActivityDetector = VoiceActivityDetector(this)

        // 设置 RMS 实时更新回调
        voiceActivityDetector.onRmsUpdate = { rms ->
            val speaking = voiceActivityDetector.isCurrentlySpeaking
            val status = if (speaking) {
                "🎤 说话中 (音量:${"%.0f".format(rms)})"
            } else {
                "聆听中 (音量:${"%.0f".format(rms)})"
            }
            updateNotification(status)
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.getDefault())
                }
                isTtsReady = true
            }
        }

        audioRecorder.setCallback(object : AudioRecorder.AudioCallback {
            override fun onAudioData(data: ByteArray) {
                voiceActivityDetector.processAudioData(data, SAMPLE_RATE)
            }

            override fun onError(error: String) {
                Log.e(TAG, "音频错误: $error")
                updateNotification("音频错误: $error")
            }
        })

        Log.d(TAG, "服务已创建 (v2.1.2)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        audioRouter.routeToHeadset()
        val started = audioRecorder.startRecording()
        if (started) {
            Log.d(TAG, "服务已启动 - 正在录音")
            updateNotification("AI陪伴助手聆听中...")
        } else {
            Log.e(TAG, "录音启动失败")
            updateNotification("录音启动失败，请检查麦克风权限")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        audioRecorder.stopRecording()
        voiceActivityDetector.reset()
        floatingWindow.dismiss()
        tts?.stop()
        tts?.shutdown()
        serviceScope.cancel()
        Log.d(TAG, "服务已销毁")
        super.onDestroy()
    }

    // ==================== SpeechSegmentListener ====================

    override fun onSpeechSegment(audioData: ByteArray) {
        Log.d(TAG, "收到语音片段: ${audioData.size} 字节")
        updateNotification("正在识别语音...")

        serviceScope.launch {
            try {
                val result = speechToTextService.transcribe(audioData)
                if (!result.success || result.text.isBlank()) {
                    Log.d(TAG, "转录失败: ${result.error}")
                    return@launch
                }

                val transcribedText = result.text.trim()
                lastTranscribedText = transcribedText
                Log.d(TAG, "转录结果: $transcribedText")

                val analysis = contentAnalyzer.analyze(transcribedText)
                Log.d(TAG, "内容分析: score=${analysis.score}, important=${analysis.isImportant}")

                if (analysis.isImportant) {
                    val sceneContext = buildContextForAI()
                    val suggestion = aiService.generateSuggestion(
                        transcript = transcribedText,
                        sceneContext = sceneContext
                    )

                    if (suggestion.isNotEmpty() && !suggestion.startsWith("[")) {
                        showSuggestion(suggestion, isDeepExplanation = false)
                        Log.d(TAG, "AI建议: $suggestion")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "处理语音片段异常", e)
            }
        }
    }

    override fun onSpeechStarted() {
        Log.d(TAG, "检测到语音开始")
    }

    override fun onSpeechEnded() {
        Log.d(TAG, "检测到语音结束")
    }

    // ==================== 公开方法 ====================

    fun generateDeepExplanation(text: String) {
        Log.d(TAG, "用户请求深度解释: $text")
        updateNotification("正在深度分析...")

        serviceScope.launch {
            try {
                val sceneContext = buildContextForAI()
                val detailedPrompt = "用户对以下内容感兴趣，希望获得详细解释。\n原始内容: \"$text\"\n请用中文详细解释。"

                val explanation = aiService.generateSuggestion(
                    transcript = detailedPrompt,
                    sceneContext = sceneContext
                )

                if (explanation.isNotEmpty() && !explanation.startsWith("[")) {
                    showSuggestion(explanation, isDeepExplanation = true)
                }

                contentAnalyzer.recordUserInteraction(text)
            } catch (e: Exception) {
                Log.e(TAG, "生成深度解释异常", e)
            }
        }
    }

    // ==================== 私有方法 ====================

    private fun showSuggestion(text: String, isDeepExplanation: Boolean) {
        floatingWindow.setOnTapCallback {
            generateDeepExplanation(text)
        }
        floatingWindow.show(text)

        if (isDeepExplanation) {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val ttsEnabled = prefs.getBoolean(KEY_TTS_ENABLED, false)
            if (ttsEnabled && isTtsReady) {
                tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "deep_explanation")
            }
        }
    }

    private fun buildContextForAI(): String {
        return "当前场景: 日常陪伴。你聆听周围对话，提供简洁建议。请用中文回复，最多2句话。"
    }

    private fun updateNotification(text: String) {
        try {
            val notification = buildNotification(text)
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "更新通知失败", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pendingIntent)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pendingIntent)
                .build()
        }
    }
}
