package com.ai.companion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import com.ai.companion.MainActivity
import com.ai.companion.R
import com.ai.companion.ai.AIService
import com.ai.companion.ai.ContentAnalyzer
import com.ai.companion.ui.FloatingWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * AI陪伴助手 v4.0 - 修复服务启动问题
 *
 * 关键修复:
 * 1. startForeground() 立即调用，避免ANR
 * 2. SpeechRecognizer 延迟初始化，避免阻塞主线程
 * 3. 详细的错误处理和状态报告
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

    private var speechRecognizer: SpeechRecognizer? = null
    private var contentAnalyzer: ContentAnalyzer? = null
    private var aiService: AIService? = null
    private var floatingWindow: FloatingWindow? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    @Volatile private var isListening = false

    private val notificationManager: NotificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate 开始")

        // 第一步：立即创建通知渠道并启动前台服务（必须在5秒内完成）
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("AI陪伴助手启动中..."))
        Log.d(TAG, "前台服务已启动")

        // 第二步：在后台协程中初始化其他组件
        serviceScope.launch {
            try {
                Log.d(TAG, "开始初始化组件...")
                updateNotification("正在初始化...")

                // 初始化TTS
                tts = TextToSpeech(this@AICompanionService) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        tts?.setLanguage(Locale.CHINESE)
                        isTtsReady = true
                        Log.d(TAG, "TTS初始化成功")
                    } else {
                        Log.e(TAG, "TTS初始化失败: $status")
                    }
                }

                // 初始化其他组件
                contentAnalyzer = ContentAnalyzer(this@AICompanionService)
                aiService = AIService(this@AICompanionService)
                floatingWindow = FloatingWindow(this@AICompanionService)

                Log.d(TAG, "组件初始化完成，准备初始化SpeechRecognizer...")
                updateNotification("正在启动语音识别...")

                // 初始化SpeechRecognizer
                initSpeechRecognizer()

            } catch (e: Exception) {
                Log.e(TAG, "初始化失败", e)
                updateNotification("初始化失败: ${e.message}")
            }
        }

        Log.d(TAG, "onCreate 完成")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand 被调用")
        // 服务已经在onCreate中启动，这里不需要额外操作
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy 被调用")
        isListening = false
        stopListening()
        floatingWindow?.dismiss()
        tts?.stop()
        tts?.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun initSpeechRecognizer() {
        try {
            Log.d(TAG, "创建SpeechRecognizer...")

            // 检查SpeechRecognizer是否可用
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                Log.e(TAG, "SpeechRecognizer 在此设备上不可用")
                updateNotification("错误: 设备不支持语音识别")
                return
            }

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

            if (speechRecognizer == null) {
                Log.e(TAG, "SpeechRecognizer 创建失败")
                updateNotification("错误: 无法创建语音识别器")
                return
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "语音识别就绪")
                    sendStatusBroadcast("ready", "准备就绪")
                    updateNotification("聆听中... 请说话")
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "检测到说话开始")
                    sendStatusBroadcast("beginning", "检测到说话")
                    updateNotification("🎤 正在聆听...")
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // 音量变化，可用于UI动画
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    Log.d(TAG, "说话结束")
                    sendStatusBroadcast("end", "说话结束")
                    // 延迟后自动重启
                    serviceScope.launch {
                        delay(1000)
                        if (isListening) startListening()
                    }
                }

                override fun onError(error: Int) {
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "音频错误"
                        SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足(请检查麦克风权限)"
                        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                        SpeechRecognizer.ERROR_NO_MATCH -> "未能识别(请再说一遍)"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙碌"
                        SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时(未检测到语音)"
                        else -> "未知错误: $error"
                    }
                    Log.e(TAG, "语音识别错误: $errorMsg (code=$error)")
                    sendStatusBroadcast("error", errorMsg)

                    // 客户端错误不需要重启
                    if (error == SpeechRecognizer.ERROR_CLIENT) {
                        return
                    }

                    // 延迟后自动重启
                    serviceScope.launch {
                        delay(2000)
                        if (isListening) startListening()
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0]
                        Log.d(TAG, "识别结果: $text")
                        sendStatusBroadcast("results", text)
                        updateNotification("识别到: $text")
                        processTranscribedText(text)
                    } else {
                        Log.d(TAG, "识别结果为空")
                        sendStatusBroadcast("error", "识别结果为空")
                        updateNotification("未能识别，请重试")
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        updateNotification("聆听中: ${matches[0]}")
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            Log.d(TAG, "SpeechRecognizer 初始化成功，开始监听...")
            isListening = true
            startListening()

        } catch (e: Exception) {
            Log.e(TAG, "初始化SpeechRecognizer异常", e)
            updateNotification("错误: ${e.message}")
        }
    }

    private fun startListening() {
        val recognizer = speechRecognizer
        if (recognizer == null) {
            Log.e(TAG, "SpeechRecognizer 为空，无法启动监听")
            return
        }

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            }
            recognizer.startListening(intent)
            Log.d(TAG, "开始监听语音")
        } catch (e: Exception) {
            Log.e(TAG, "启动监听失败", e)
            updateNotification("启动监听失败: ${e.message}")
        }
    }

    private fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            Log.e(TAG, "停止监听异常", e)
        }
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
                        Log.d(TAG, "AI建议: $suggestion")
                    }
                }

                updateNotification("聆听中... 请说话")
            } catch (e: Exception) {
                Log.e(TAG, "处理转录文本异常", e)
                updateNotification("处理异常: ${e.message}")
            }
        }
    }

    private fun generateDeepExplanation(text: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val ai = aiService ?: return@launch
                val window = floatingWindow ?: return@launch
                val analyzer = contentAnalyzer ?: return@launch

                val prompt = "用户对以下内容感兴趣，希望获得详细解释。\n原始内容: \"$text\"\n请用中文详细解释。"
                val explanation = ai.generateSuggestion(
                    transcript = prompt,
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
                updateNotification("聆听中... 请说话")
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
            ).apply {
                description = "语音监听服务"
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
