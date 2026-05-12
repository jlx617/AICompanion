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
 * AI陪伴助手 v3.0 - 使用Android内置SpeechRecognizer
 *
 * 根本修复: 旧版本使用第三方STT API(SiliconFlow)，用户配置DeepSeek密钥但STT调用SiliconFlow导致始终失败。
 * 新架构: Android SpeechRecognizer(系统内置, 免费, 无需API密钥) -> 转录文本 -> ContentAnalyzer -> AIService(DeepSeek等) -> 悬浮窗
 */
class AICompanionService : Service() {

    companion object {
        private const val TAG = "AICompanionService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "ai_companion_channel"
        private const val PREFS_NAME = "ai_companion_prefs"
        private const val KEY_TTS_ENABLED = "tts_enabled"

        /** 语音结束后重新开始监听的延迟(ms) */
        private const val RESTART_DELAY_AFTER_END_MS = 1000L

        /** 错误后重新开始监听的延迟(ms) */
        private const val RESTART_DELAY_AFTER_ERROR_MS = 2000L

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

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var contentAnalyzer: ContentAnalyzer
    private lateinit var aiService: AIService
    private lateinit var floatingWindow: FloatingWindow

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    /** 当前是否正在监听（用于控制自动重启） */
    @Volatile
    private var isListening = false

    /** 最后一次识别到的文本，用于深度解释 */
    @Volatile
    private var lastSuggestionText: String = ""

    private val notificationManager: NotificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    // ==================== Service 生命周期 ====================

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("AI陪伴助手启动中..."))

        // 初始化组件（不再需要 AudioRecorder, AudioRouter, SpeechToTextService, VoiceActivityDetector）
        contentAnalyzer = ContentAnalyzer(this)
        aiService = AIService(this)
        floatingWindow = FloatingWindow(this)

        // 初始化TTS
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale.CHINESE)
                isTtsReady = true
            }
        }

        // 创建并配置 SpeechRecognizer
        initSpeechRecognizer()

        Log.d(TAG, "服务已创建 (v3.0 - Android SpeechRecognizer)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "收到启动命令，开始监听")
        isListening = true
        startListening()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "服务销毁，停止监听")
        isListening = false
        stopListening()
        floatingWindow.dismiss()
        tts?.stop()
        tts?.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ==================== SpeechRecognizer 初始化 ====================

    private fun initSpeechRecognizer() {
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {

                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "语音识别就绪")
                    updateNotification("聆听中...")
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "检测到说话")
                    updateNotification("正在聆听...")
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // rmsdB 是分贝值，负数。-10到0表示较大的语音
                    // 可用于未来添加音量可视化
                }

                override fun onBufferReceived(buffer: ByteArray?) {
                    // 不需要处理音频缓冲区
                }

                override fun onEndOfSpeech() {
                    Log.d(TAG, "说话结束")
                    // 自动延迟后重新开始监听
                    serviceScope.launch {
                        delay(RESTART_DELAY_AFTER_END_MS)
                        if (isListening) {
                            startListening()
                        }
                    }
                }

                override fun onError(error: Int) {
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "音频错误"
                        SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
                        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                        SpeechRecognizer.ERROR_NO_MATCH -> "没有匹配结果"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙碌"
                        SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时(未检测到语音)"
                        else -> "未知错误: $error"
                    }
                    Log.e(TAG, "语音识别错误: $errorMsg ($error)")

                    // 某些错误不需要重启（如正在说话时又调用了startListening）
                    if (error == SpeechRecognizer.ERROR_CLIENT) {
                        return
                    }

                    // 自动延迟后重新开始监听
                    serviceScope.launch {
                        delay(RESTART_DELAY_AFTER_ERROR_MS)
                        if (isListening) {
                            startListening()
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0]
                        Log.d(TAG, "识别结果: $text")
                        updateNotification("识别到: $text")
                        processTranscribedText(text)
                    } else {
                        Log.d(TAG, "识别结果为空")
                        updateNotification("聆听中...")
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        updateNotification("聆听中: ${matches[0]}")
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {
                    // 预留事件处理
                }
            })
            Log.d(TAG, "SpeechRecognizer 初始化成功")
        } catch (e: Exception) {
            Log.e(TAG, "SpeechRecognizer 初始化失败", e)
            updateNotification("语音识别初始化失败，请检查权限")
        }
    }

    // ==================== 监听控制 ====================

    private fun startListening() {
        val recognizer = speechRecognizer
        if (recognizer == null) {
            Log.e(TAG, "SpeechRecognizer 未初始化，尝试重新创建")
            initSpeechRecognizer()
            return
        }

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // 延长静默等待时间，避免过早结束
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    2000
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    1500
                )
            }
            recognizer.startListening(intent)
            Log.d(TAG, "开始监听语音")
        } catch (e: Exception) {
            Log.e(TAG, "启动监听失败", e)
            updateNotification("语音监听启动失败")
            // 尝试延迟后重启
            serviceScope.launch {
                delay(RESTART_DELAY_AFTER_ERROR_MS)
                if (isListening) {
                    startListening()
                }
            }
        }
    }

    private fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "停止监听异常", e)
        }
    }

    // ==================== 文本处理流程 ====================

    /**
     * 处理语音识别结果
     *
     * 流程: 识别文本 -> ContentAnalyzer分析重要性 -> 如果重要则调用AIService生成建议 -> 悬浮窗显示
     */
    private fun processTranscribedText(text: String) {
        serviceScope.launch {
            try {
                val analysis = contentAnalyzer.analyze(text)
                Log.d(TAG, "分析: score=${analysis.score}, important=${analysis.isImportant}, reason=${analysis.reason}")

                if (analysis.isImportant) {
                    val suggestion = aiService.generateSuggestion(
                        transcript = text,
                        sceneContext = "你聆听周围对话，提供简洁建议。请用中文回复，最多2句话。"
                    )

                    if (suggestion.isNotEmpty() && !suggestion.startsWith("[")) {
                        lastSuggestionText = suggestion
                        showSuggestion(suggestion)
                        Log.d(TAG, "AI建议: $suggestion")
                    } else if (suggestion.isNotEmpty()) {
                        Log.d(TAG, "AI返回错误信息，跳过显示: $suggestion")
                    }
                }

                updateNotification("聆听中...")
            } catch (e: Exception) {
                Log.e(TAG, "处理转录文本异常", e)
                updateNotification("聆听中...")
            }
        }
    }

    // ==================== 悬浮窗与深度解释 ====================

    private fun showSuggestion(text: String) {
        floatingWindow.setOnTapCallback {
            generateDeepExplanation(text)
        }
        floatingWindow.show(text)
    }

    /**
     * 生成深度解释 - 用户点击悬浮窗时触发
     */
    private fun generateDeepExplanation(text: String) {
        updateNotification("正在深度分析...")
        serviceScope.launch {
            try {
                val prompt = "用户对以下内容感兴趣，希望获得详细解释。\n原始内容: \"$text\"\n请用中文详细解释。"
                val explanation = aiService.generateSuggestion(
                    transcript = prompt,
                    sceneContext = "提供详细、深入的中文解释。"
                )

                if (explanation.isNotEmpty() && !explanation.startsWith("[")) {
                    lastSuggestionText = explanation
                    floatingWindow.show(explanation)

                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    if (prefs.getBoolean(KEY_TTS_ENABLED, false) && isTtsReady) {
                        tts?.speak(explanation, TextToSpeech.QUEUE_ADD, null, "deep")
                    }
                }

                contentAnalyzer.recordUserInteraction(text)
                updateNotification("聆听中...")
            } catch (e: Exception) {
                Log.e(TAG, "深度解释异常", e)
                updateNotification("聆听中...")
            }
        }
    }

    // ==================== 通知管理 ====================

    private fun updateNotification(text: String) {
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {
            // 通知更新失败时静默处理
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
