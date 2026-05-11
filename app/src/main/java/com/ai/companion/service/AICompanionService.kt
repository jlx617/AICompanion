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
import com.ai.companion.audio.AudioRecorder
import com.ai.companion.audio.AudioRouter
import com.ai.companion.audio.SpeechToTextService
import com.ai.companion.ui.FloatingWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * AI陪伴助手 v2.2 - 去掉VAD，改用定时采集方案
 *
 * 架构: AudioRecorder → 每5秒采集音频 → STT识别 → 内容分析 → AI建议 → 悬浮窗
 */
class AICompanionService : Service() {

    companion object {
        private const val TAG = "AICompanionService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "ai_companion_channel"
        private const val PREFS_NAME = "ai_companion_prefs"
        private const val KEY_TTS_ENABLED = "tts_enabled"
        private const val SAMPLE_RATE = 16000
        private const val CAPTURE_INTERVAL_MS = 5000L

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

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var captureJob: Job? = null

    private val audioBuffer = mutableListOf<ByteArray>()
    @Volatile private var isCapturing = false

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

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale.CHINESE)
                isTtsReady = true
            }
        }

        audioRecorder.setCallback(object : AudioRecorder.AudioCallback {
            override fun onAudioData(data: ByteArray) {
                if (isCapturing) {
                    synchronized(audioBuffer) {
                        audioBuffer.add(data)
                    }
                }
            }

            override fun onError(error: String) {
                Log.e(TAG, "音频错误: $error")
            }
        })

        Log.d(TAG, "服务已创建 (v2.2)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        audioRouter.routeToHeadset()
        val started = audioRecorder.startRecording()
        if (started) {
            Log.d(TAG, "录音已启动")
            isCapturing = true
            startPeriodicCapture()
            updateNotification("聆听中 (每5秒识别一次)")
        } else {
            Log.e(TAG, "录音启动失败")
            updateNotification("录音启动失败，请检查麦克风权限")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        captureJob?.cancel()
        isCapturing = false
        audioRecorder.stopRecording()
        floatingWindow.dismiss()
        tts?.stop()
        tts?.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startPeriodicCapture() {
        captureJob = serviceScope.launch {
            var captureCount = 0
            while (true) {
                delay(CAPTURE_INTERVAL_MS)
                captureCount++

                val audioData: ByteArray
                var shouldSkip = false

                synchronized(audioBuffer) {
                    if (audioBuffer.isEmpty()) {
                        Log.d(TAG, "第${captureCount}次采集: 无音频数据")
                        shouldSkip = true
                        audioData = ByteArray(0)
                    } else {
                        val totalSize = audioBuffer.sumOf { it.size }
                        audioData = ByteArray(totalSize)
                        var offset = 0
                        for (chunk in audioBuffer) {
                            System.arraycopy(chunk, 0, audioData, offset, chunk.size)
                            offset += chunk.size
                        }
                        audioBuffer.clear()
                    }
                }

                if (shouldSkip) continue

                Log.d(TAG, "第${captureCount}次采集: ${audioData.size} 字节，发送STT...")
                updateNotification("正在识别语音...")
                processAudioSegment(audioData, captureCount)
            }
        }
    }

    private fun processAudioSegment(audioData: ByteArray, captureNum: Int) {
        serviceScope.launch {
            try {
                val result = speechToTextService.transcribe(audioData)

                if (!result.success || result.text.isNullOrBlank()) {
                    Log.d(TAG, "第${captureNum}次: 无语音 (${result.error})")
                    updateNotification("聆听中 (每5秒识别一次)")
                    return@launch
                }

                val text = result.text.trim()
                if (text.length < 2) {
                    Log.d(TAG, "第${captureNum}次: 结果太短 '$text'")
                    updateNotification("聆听中 (每5秒识别一次)")
                    return@launch
                }

                Log.d(TAG, "第${captureNum}次: 识别到 \"$text\"")
                updateNotification("识别到: $text")

                val analysis = contentAnalyzer.analyze(text)
                Log.d(TAG, "分析: score=${analysis.score}, important=${analysis.isImportant}")

                if (analysis.isImportant) {
                    val suggestion = aiService.generateSuggestion(
                        transcript = text,
                        sceneContext = "你聆听周围对话，提供简洁建议。请用中文回复，最多2句话。"
                    )

                    if (suggestion.isNotEmpty() && !suggestion.startsWith("[")) {
                        showSuggestion(suggestion)
                        Log.d(TAG, "AI建议: $suggestion")
                    }
                }

                updateNotification("聆听中 (每5秒识别一次)")
            } catch (e: Exception) {
                Log.e(TAG, "第${captureNum}次异常", e)
                updateNotification("聆听中 (每5秒识别一次)")
            }
        }
    }

    private fun showSuggestion(text: String) {
        floatingWindow.setOnTapCallback {
            generateDeepExplanation(text)
        }
        floatingWindow.show(text)
    }

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
                    floatingWindow.show(explanation)

                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    if (prefs.getBoolean(KEY_TTS_ENABLED, false) && isTtsReady) {
                        tts?.speak(explanation, TextToSpeech.QUEUE_ADD, null, "deep")
                    }
                }

                contentAnalyzer.recordUserInteraction(text)
                updateNotification("聆听中 (每5秒识别一次)")
            } catch (e: Exception) {
                Log.e(TAG, "深度解释异常", e)
                updateNotification("聆听中 (每5秒识别一次)")
            }
        }
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
