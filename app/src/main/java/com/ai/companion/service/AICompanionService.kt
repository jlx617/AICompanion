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
import com.ai.companion.audio.SpeechToTextService
import com.ai.companion.audio.VoiceActivityDetector
import com.ai.companion.scene.SceneType
import com.ai.companion.ui.FloatingWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * AI陪伴助手 - 核心前台服务 (v2.0)
 *
 * 架构: AudioRecorder -> VoiceActivityDetector -> SpeechToTextService -> ContentAnalyzer -> AIService -> FloatingWindow
 *
 * 核心行为:
 * - AI默认静默，不主动说话，仅在浮动窗口中显示文字
 * - 使用SiliconFlow SenseVoice API进行真实语音识别
 * - 智能过滤，仅对重要内容（问题、事实、情感内容）做出响应
 * - 用户点击浮动窗口可获取深度分析
 */
class AICompanionService : Service(), VoiceActivityDetector.SpeechSegmentListener {

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

    private val notificationManager: NotificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    // ==================== Service 生命周期 ====================

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("AI陪伴助手聆听中..."))

        // 初始化各组件
        audioRecorder = AudioRecorder()
        audioRouter = AudioRouter(this)
        speechToTextService = SpeechToTextService(this)
        contentAnalyzer = ContentAnalyzer(this)
        aiService = AIService(this)
        floatingWindow = FloatingWindow(this)
        userPreferences = UserPreferences(this)
        voiceActivityDetector = VoiceActivityDetector(this)

        // 初始化中文TTS（仅在用户主动请求深度分析时使用，不自动播放）
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "中文TTS不可用，使用默认语言")
                    tts?.setLanguage(Locale.getDefault())
                }
                isTtsReady = true
                Log.d(TAG, "TTS初始化成功（仅在用户主动请求时使用）")
            } else {
                Log.e(TAG, "TTS初始化失败")
            }
        }

        // 设置音频采集回调，将音频数据送入VAD进行语音活动检测
        audioRecorder.setCallback(object : AudioRecorder.AudioCallback {
            override fun onAudioData(data: ByteArray) {
                voiceActivityDetector.processAudioData(data, SAMPLE_RATE)
            }

            override fun onError(error: String) {
                Log.e(TAG, "音频错误: $error")
            }
        })

        Log.d(TAG, "服务已创建 (v2.0)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        audioRouter.routeToHeadset()
        val started = audioRecorder.startRecording()
        if (started) {
            Log.d(TAG, "服务已启动 - 正在录音并监听语音")
        } else {
            Log.e(TAG, "录音启动失败")
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

    // ==================== VoiceActivityDetector.SpeechSegmentListener ====================

    /**
     * 当VAD检测到一段完整语音片段时回调
     * 处理流程: STT转录 -> 内容分析 -> AI建议 -> 显示
     */
    override fun onSpeechSegment(audioData: ByteArray) {
        Log.d(TAG, "收到语音片段: ${audioData.size} 字节")
        updateNotification("正在分析...")

        serviceScope.launch {
            try {
                // 1. 语音转文字
                val result = speechToTextService.transcribe(audioData)
                if (!result.success || result.text.isBlank()) {
                    Log.d(TAG, "转录失败或结果为空: ${result.error}")
                    updateNotification("AI陪伴助手聆听中...")
                    return@launch
                }

                val transcribedText = result.text.trim()
                Log.d(TAG, "转录结果: $transcribedText")

                // 2. 分析内容重要性
                val analysis = contentAnalyzer.analyze(transcribedText)
                Log.d(TAG, "内容分析: score=${analysis.score}, important=${analysis.isImportant}, reason=${analysis.reason}")

                if (analysis.isImportant) {
                    // 3. 构建上下文并请求AI建议
                    val sceneContext = buildContextForAI()
                    val suggestion = aiService.generateSuggestion(
                        transcript = transcribedText,
                        sceneContext = sceneContext
                    )

                    if (suggestion.isNotEmpty() && !suggestion.startsWith("[")) {
                        // 仅在浮动窗口中显示文字，不使用TTS
                        showSuggestion(suggestion, isDeepExplanation = false)
                        Log.d(TAG, "AI建议: $suggestion")
                    }
                } else {
                    Log.d(TAG, "内容不重要，跳过")
                }

                updateNotification("AI陪伴助手聆听中...")
            } catch (e: Exception) {
                Log.e(TAG, "处理语音片段时发生异常", e)
                updateNotification("AI陪伴助手聆听中...")
            }
        }
    }

    /**
     * 当VAD检测到语音开始时回调
     */
    override fun onSpeechStarted() {
        Log.d(TAG, "检测到语音开始")
        updateNotification("正在聆听...")
    }

    /**
     * 当VAD检测到语音结束时回调
     */
    override fun onSpeechEnded() {
        Log.d(TAG, "检测到语音结束")
        updateNotification("AI陪伴助手聆听中...")
    }

    // ==================== 公开方法 ====================

    /**
     * 生成深度解释 - 供FloatingWindow点击时调用
     *
     * 当用户点击浮动窗口时，表示他们想要更详细的信息：
     * 1. 调用AIService获取详细解释
     * 2. 在浮动窗口中显示结果
     * 3. 如果用户开启了TTS，则朗读结果
     * 4. 记录用户交互以学习偏好
     *
     * @param text 原始转录文本或需要深入解释的内容
     */
    fun generateDeepExplanation(text: String) {
        Log.d(TAG, "用户请求深度解释: $text")
        updateNotification("正在深度分析...")

        serviceScope.launch {
            try {
                val sceneContext = buildContextForAI()
                val detailedPrompt = "用户对以下内容感兴趣，希望获得详细解释。请提供深入、全面的分析。" +
                    "当前场景: $sceneContext。" +
                    "原始内容: \"$text\"。" +
                    "请用中文详细解释，可以包含背景知识、相关概念和实用建议。"

                val explanation = aiService.generateSuggestion(
                    transcript = detailedPrompt,
                    sceneContext = sceneContext
                )

                if (explanation.isNotEmpty() && !explanation.startsWith("[")) {
                    showSuggestion(explanation, isDeepExplanation = true)
                    Log.d(TAG, "深度解释: $explanation")
                }

                // 记录用户交互，学习偏好
                contentAnalyzer.recordUserInteraction(text)

                updateNotification("AI陪伴助手聆听中...")
            } catch (e: Exception) {
                Log.e(TAG, "生成深度解释时发生异常", e)
                updateNotification("AI陪伴助手聆听中...")
            }
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 显示建议文本
     *
     * @param text 要显示的建议文本
     * @param isDeepExplanation 是否为深度解释（深度解释时可根据用户设置使用TTS）
     */
    private fun showSuggestion(text: String, isDeepExplanation: Boolean) {
        // 始终在浮动窗口中显示
        floatingWindow.show(text)

        // 仅在深度解释且用户开启TTS时才使用语音朗读
        if (isDeepExplanation) {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val ttsEnabled = prefs.getBoolean(KEY_TTS_ENABLED, false)
            if (ttsEnabled && isTtsReady) {
                tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "deep_explanation")
                Log.d(TAG, "使用TTS朗读深度解释")
            }
        }
    }

    /**
     * 构建AI请求的场景上下文
     */
    private fun buildContextForAI(): String {
        return "当前场景: 日常陪伴。" +
            "你通过耳机麦克风聆听周围对话，提供简洁有用的建议。" +
            "请保持回复简短（1-2句话），用中文回复。"
    }

    /**
     * 更新前台通知文本
     */
    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 创建通知渠道 (Android O+)
     */
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

    /**
     * 构建前台通知
     */
    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
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
