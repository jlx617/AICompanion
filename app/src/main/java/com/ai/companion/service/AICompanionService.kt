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
import com.ai.companion.ai.AIService
import com.ai.companion.ai.ContentAnalyzer
import com.ai.companion.audio.OnlineSpeechRecognizer
import com.ai.companion.ui.FloatingWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * AI陪伴助手 v6.0 - 使用在线语音识别
 *
 * 特点：
 * - 不需要下载模型
 * - 使用 SiliconFlow Whisper API
 * - 识别准确率高
 * - 支持华为/荣耀手机
 */
class AICompanionService : Service() {

    companion object {
        private const val TAG = "AICompanionService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "ai_companion_channel"
        private const val PREFS_NAME = "ai_companion_prefs"
        private const val KEY_TTS_ENABLED = "tts_enabled"

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

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var speechRecognizer: OnlineSpeechRecognizer? = null
    private var contentAnalyzer: ContentAnalyzer? = null
    private var aiService: AIService? = null
    private var floatingWindow: FloatingWindow? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val notificationManager: NotificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate 开始")

        // 立即启动前台服务
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("AI陪伴助手启动中..."))
        Log.d(TAG, "前台服务已启动")

        // 初始化组件
        serviceScope.launch {
            try {
                updateNotification("正在初始化...")

                // 初始化TTS
                tts = TextToSpeech(this@AICompanionService) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        tts?.setLanguage(Locale.CHINESE)
                        isTtsReady = true
                        Log.d(TAG, "TTS初始化成功")
                    }
                }

                // 初始化其他组件
                contentAnalyzer = ContentAnalyzer(this@AICompanionService)
                aiService = AIService(this@AICompanionService)
                floatingWindow = FloatingWindow(this@AICompanionService)

                // 初始化语音识别
                initSpeechRecognizer()

            } catch (e: Exception) {
                Log.e(TAG, "初始化失败", e)
                updateNotification("初始化失败: ${e.message}")
            }
        }
    }

    private fun initSpeechRecognizer() {
        speechRecognizer = OnlineSpeechRecognizer(this)
        speechRecognizer?.setListener(object : OnlineSpeechRecognizer.SpeechRecognitionListener {
            override fun onReady() {
                Log.d(TAG, "语音识别就绪")
                sendStatusBroadcast("ready", "准备就绪")
                updateNotification("聆听中... 请说话")
            }

            override fun onSpeechStart() {
                Log.d(TAG, "检测到说话")
                sendStatusBroadcast("beginning", "检测到说话")
                updateNotification("🎤 正在聆听...")
            }

            override fun onSpeechEnd() {
                Log.d(TAG, "说话结束")
                sendStatusBroadcast("end", "说话结束")
                updateNotification("正在识别...")
            }

            override fun onResult(text: String) {
                Log.d(TAG, "识别结果: $text")
                sendStatusBroadcast("results", text)
                updateNotification("识别到: $text")
                processTranscribedText(text)
            }

            override fun onError(error: String) {
                Log.e(TAG, "识别错误: $error")
                sendStatusBroadcast("error", error)
                updateNotification("错误: $error")
            }
        })

        // 开始监听
        val started = speechRecognizer?.startListening() ?: false
        if (started) {
            Log.d(TAG, "语音识别已启动")
        } else {
            Log.e(TAG, "语音识别启动失败")
            updateNotification("语音识别启动失败")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        speechRecognizer?.destroy()
        floatingWindow?.dismiss()
        tts?.stop()
        tts?.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun processTranscribedText(text: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val analyzer = contentAnalyzer ?: return@launch
                val ai = aiService ?: return@launch
                val window = floatingWindow ?: return@launch

                val analysis = analyzer.analyze(text)
                Log.d(TAG, "分析: score=${analysis.score}, important=${analysis.isImportant}")

                if (analysis.isImportant) {
                    val suggestion = ai.generateSuggestion(
                        transcript = text,
                        sceneContext = "你聆听周围对话，提供简洁建议。请用中文回复，最多2句话。"
                    )

                    if (suggestion.isNotEmpty() && !suggestion.startsWith("[")) {
                        window.setOnTapCallback {
                            generateDeepExplanation(text)
                        }
                        window.show(suggestion)
                    }
                }

                updateNotification("聆听中... 请说话")
            } catch (e: Exception) {
                Log.e(TAG, "处理文本异常", e)
            }
        }
    }

    private fun generateDeepExplanation(text: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val ai = aiService ?: return@launch
                val window = floatingWindow ?: return@launch
                val analyzer = contentAnalyzer ?: return@launch

                val explanation = ai.generateSuggestion(
                    transcript = "用户对以下内容感兴趣，希望获得详细解释: \"$text\"",
                    sceneContext = "提供详细、深入的中文解释。"
                )

                if (explanation.isNotEmpty() && !explanation.startsWith("[")) {
                    window.show(explanation)

                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    if (prefs.getBoolean(KEY_TTS_ENABLED, false) && isTtsReady) {
                        tts?.speak(explanation, TextToSpeech.QUEUE_ADD, null, "deep")
                    }
                }

                analyzer.recordUserInteraction(text)
            } catch (e: Exception) {
                Log.e(TAG, "深度解释异常", e)
            }
        }
    }

    private fun sendStatusBroadcast(status: String, message: String) {
        val intent = Intent("com.ai.companion.STATUS_UPDATE").apply {
            putExtra("status", status)
            putExtra("message", message)
        }
        sendBroadcast(intent)
    }

    private fun updateNotification(text: String) {
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI陪伴助手",
                NotificationManager.IMPORTANCE_LOW
            )
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
                .setContentTitle("AI陪伴助手")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pendingIntent)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("AI陪伴助手")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pendingIntent)
                .build()
        }
    }
}
