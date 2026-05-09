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
import com.ai.companion.ai.InterventionEngine
import com.ai.companion.ai.SceneDetector
import com.ai.companion.audio.AudioRecorder
import com.ai.companion.audio.AudioRouter
import com.ai.companion.scene.SceneType
import com.ai.companion.ui.FloatingWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class AICompanionService : Service() {

    companion object {
        private const val TAG = "AICompanionService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "ai_companion_channel"
        private const val PREFS_NAME = "ai_companion_prefs"
        private const val KEY_TTS_ENABLED = "tts_enabled"
        private const val KEY_FLOATING_WINDOW = "floating_window_enabled"
        private const val KEY_SCENE_DETECTION = "scene_detection_enabled"

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
    private var audioRecorder: AudioRecorder? = null
    private var audioRouter: AudioRouter? = null
    private var aiService: AIService? = null
    private var sceneDetector: SceneDetector? = null
    private var interventionEngine: InterventionEngine? = null
    private var floatingWindow: FloatingWindow? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val audioChunks = mutableListOf<ByteArray>()
    private var currentScene: SceneType = SceneType.UNKNOWN

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        audioRecorder = AudioRecorder()
        audioRouter = AudioRouter(this)
        aiService = AIService(this)
        sceneDetector = SceneDetector()
        interventionEngine = InterventionEngine()
        floatingWindow = FloatingWindow(this)

        // 初始化中文语音合成
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // 设置为中文
                val result = tts?.setLanguage(Locale.CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "中文TTS不可用，使用默认语言")
                    tts?.setLanguage(Locale.getDefault())
                }
                isTtsReady = true
                Log.d(TAG, "TTS初始化成功")
            } else {
                Log.e(TAG, "TTS初始化失败")
            }
        }

        audioRecorder?.setCallback(object : AudioRecorder.AudioCallback {
            override fun onAudioData(data: ByteArray) {
                audioChunks.add(data)
                // 每5秒处理一次音频
                if (audioChunks.size >= 5) {
                    processAudio()
                }
            }

            override fun onError(error: String) {
                Log.e(TAG, "音频错误: $error")
            }
        })

        Log.d(TAG, "服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        audioRouter?.routeToHeadset()
        val started = audioRecorder?.startRecording() ?: false
        if (started) {
            Log.d(TAG, "服务已启动 - 正在录音")
        } else {
            Log.e(TAG, "录音启动失败")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        audioRecorder?.stopRecording()
        floatingWindow?.dismiss()
        tts?.stop()
        tts?.shutdown()
        sceneDetector?.reset()
        serviceScope.cancel()
        Log.d(TAG, "服务已销毁")
        super.onDestroy()
    }

    private fun processAudio() {
        val chunks = audioChunks.toList()
        audioChunks.clear()

        serviceScope.launch {
            try {
                // 合并音频块
                val totalSize = chunks.sumOf { it.size }
                val combined = ByteArray(totalSize)
                var offset = 0
                for (chunk in chunks) {
                    System.arraycopy(chunk, 0, combined, offset, chunk.size)
                    offset += chunk.size
                }

                Log.d(TAG, "处理音频: ${totalSize} 字节")

                // 获取设置
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val sceneDetectionEnabled = prefs.getBoolean(KEY_SCENE_DETECTION, true)

                if (sceneDetectionEnabled) {
                    currentScene = sceneDetector?.detectScene("[音频片段]") ?: SceneType.UNKNOWN
                } else {
                    currentScene = SceneType.DAILY_CHAT
                }

                val sceneContext = "场景: ${getSceneName(currentScene)}。" +
                    "用户正在${getSceneDescription(currentScene)}的环境中。"

                // 调用AI生成建议
                val suggestion = aiService?.generateSuggestion(
                    transcript = "[已捕获音频: ${totalSize} 字节]",
                    sceneContext = sceneContext
                ) ?: "正在聆听..."

                Log.d(TAG, "AI建议: $suggestion")

                // 评估是否需要干预
                val intervention = interventionEngine?.evaluate(suggestion)
                if (intervention != null && intervention.shouldIntervene) {
                    showSuggestion(suggestion)
                }

            } catch (e: Exception) {
                Log.e(TAG, "处理音频时出错", e)
            }
        }
    }

    private fun getSceneName(scene: SceneType): String {
        return when (scene) {
            SceneType.DAILY_CHAT -> "日常对话"
            SceneType.MEETING -> "会议"
            SceneType.CLASS -> "上课"
            SceneType.SHOPPING -> "购物"
            SceneType.MUSEUM -> "参观博物馆"
            SceneType.UNKNOWN -> "未知"
        }
    }

    private fun getSceneDescription(scene: SceneType): String {
        return when (scene) {
            SceneType.DAILY_CHAT -> "日常聊天"
            SceneType.MEETING -> "开会"
            SceneType.CLASS -> "听课"
            SceneType.SHOPPING -> "逛街购物"
            SceneType.MUSEUM -> "参观博物馆"
            SceneType.UNKNOWN -> "未知"
        }
    }

    private fun showSuggestion(text: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val floatingWindowEnabled = prefs.getBoolean(KEY_FLOATING_WINDOW, true)
        val ttsEnabled = prefs.getBoolean(KEY_TTS_ENABLED, false)

        if (floatingWindowEnabled) {
            floatingWindow?.show(text)
        }

        if (ttsEnabled && isTtsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "suggestion")
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

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
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
                .setContentText(getString(R.string.service_running))
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pendingIntent)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.service_running))
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pendingIntent)
                .build()
        }
    }
}
