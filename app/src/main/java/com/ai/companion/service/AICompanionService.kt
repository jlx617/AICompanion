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
        
        // 主动询问间隔（毫秒）
        private const val PROACTIVE_INTERVAL_MS = 30000L // 30秒

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
    
    // 简化的音频文本缓冲区（模拟语音识别结果）
    private val audioTextBuffer = StringBuilder()
    private var currentScene: SceneType = SceneType.UNKNOWN
    private var lastInterventionTime = 0L
    private var proactiveJob: Job? = null

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

        // 音频采集回调 - 用于检测音量和简单关键词
        audioRecorder?.setCallback(object : AudioRecorder.AudioCallback {
            override fun onAudioData(data: ByteArray) {
                // 检测音量，如果音量足够大，说明有人在说话
                val volume = calculateVolume(data)
                if (volume > 500) { // 音量阈值
                    onSpeechDetected()
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
            startProactiveSuggestions()
        } else {
            Log.e(TAG, "录音启动失败")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        proactiveJob?.cancel()
        audioRecorder?.stopRecording()
        floatingWindow?.dismiss()
        tts?.stop()
        tts?.shutdown()
        sceneDetector?.reset()
        serviceScope.cancel()
        Log.d(TAG, "服务已销毁")
        super.onDestroy()
    }
    
    // 计算音频音量
    private fun calculateVolume(data: ByteArray): Double {
        var sum = 0.0
        for (i in data.indices step 2) {
            if (i + 1 < data.size) {
                val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
                sum += kotlin.math.abs(sample)
            }
        }
        return sum / (data.size / 2)
    }
    
    // 检测到语音时的处理
    private fun onSpeechDetected() {
        // 记录当前时间，用于判断对话间隔
        val currentTime = System.currentTimeMillis()
        
        // 如果距离上次建议超过10秒，且检测到语音，模拟一些对话文本
        if (currentTime - lastInterventionTime > 10000) {
            // 模拟检测到的对话内容（实际应该由STT提供）
            val simulatedPhrases = listOf(
                "我不知道该怎么办",
                "这个问题有点难",
                "你能帮我吗",
                "我不太明白",
                "有什么建议吗",
                "怎么说呢",
                "我不太确定"
            )
            
            // 随机选择一句话模拟检测到的内容
            val detectedText = simulatedPhrases.random()
            audioTextBuffer.append(" ").append(detectedText)
            
            // 检查是否需要干预
            checkIntervention(detectedText)
        }
    }
    
    // 启动主动建议循环
    private fun startProactiveSuggestions() {
        proactiveJob = serviceScope.launch {
            while (true) {
                delay(PROACTIVE_INTERVAL_MS)
                
                // 每30秒主动询问一次
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastInterventionTime > PROACTIVE_INTERVAL_MS) {
                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val sceneDetectionEnabled = prefs.getBoolean(KEY_SCENE_DETECTION, true)
                    
                    if (sceneDetectionEnabled) {
                        currentScene = sceneDetector?.detectScene(audioTextBuffer.toString()) ?: SceneType.UNKNOWN
                    } else {
                        currentScene = SceneType.DAILY_CHAT
                    }
                    
                    // 主动询问用户是否需要帮助
                    val context = buildContextForAI()
                    val suggestion = aiService?.generateSuggestion(
                        transcript = "用户正在对话中，请主动询问是否需要帮助",
                        sceneContext = context
                    )
                    
                    if (suggestion != null && !suggestion.contains("[未配置") && !suggestion.contains("[API")) {
                        showSuggestion("💡 $suggestion")
                        lastInterventionTime = currentTime
                    }
                }
                
                // 清空缓冲区
                if (audioTextBuffer.length > 500) {
                    audioTextBuffer.clear()
                }
            }
        }
    }
    
    // 检查是否需要干预
    private fun checkIntervention(text: String) {
        val intervention = interventionEngine?.evaluate(text)
        if (intervention != null && intervention.shouldIntervene) {
            Log.d(TAG, "检测到干预信号: ${intervention.reason}, 分数: ${intervention.score}")
            
            serviceScope.launch {
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val sceneDetectionEnabled = prefs.getBoolean(KEY_SCENE_DETECTION, true)
                
                if (sceneDetectionEnabled) {
                    currentScene = sceneDetector?.detectScene(text) ?: SceneType.UNKNOWN
                }
                
                val context = buildContextForAI()
                val suggestion = aiService?.generateSuggestion(
                    transcript = text,
                    sceneContext = context
                )
                
                if (suggestion != null && !suggestion.contains("[未配置") && !suggestion.contains("[API")) {
                    showSuggestion(suggestion)
                    lastInterventionTime = System.currentTimeMillis()
                }
            }
        }
    }
    
    private fun buildContextForAI(): String {
        return "当前场景: ${getSceneName(currentScene)}。" +
            "用户正在${getSceneDescription(currentScene)}。" +
            "请提供简洁有用的建议（1-2句话），用中文回复。"
    }

    private fun getSceneName(scene: SceneType): String {
        return when (scene) {
            SceneType.DAILY_CHAT -> "日常对话"
            SceneType.MEETING -> "会议"
            SceneType.CLASS -> "上课"
            SceneType.SHOPPING -> "购物"
            SceneType.MUSEUM -> "参观博物馆"
            SceneType.UNKNOWN -> "日常"
        }
    }

    private fun getSceneDescription(scene: SceneType): String {
        return when (scene) {
            SceneType.DAILY_CHAT -> "聊天交流"
            SceneType.MEETING -> "开会讨论"
            SceneType.CLASS -> "听课学习"
            SceneType.SHOPPING -> "购物逛街"
            SceneType.MUSEUM -> "参观游览"
            SceneType.UNKNOWN -> "日常活动"
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
