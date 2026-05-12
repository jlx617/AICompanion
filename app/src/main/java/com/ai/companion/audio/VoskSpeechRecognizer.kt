package com.ai.companion.audio

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.File
import java.io.IOException

/**
 * Vosk 离线语音识别管理器
 *
 * 特点：
 * - 完全离线，不需要网络
 * - 不需要 Google 服务（适合华为手机）
 * - 不需要 API 密钥
 */
class VoskSpeechRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "VoskSTT"
        // 中文小模型（约50MB），首次使用需要下载
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
        private const val MODEL_NAME = "vosk-model-small-cn-0.22"
    }

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var listener: SpeechRecognitionListener? = null

    interface SpeechRecognitionListener {
        fun onReady()
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(error: String)
    }

    fun setListener(l: SpeechRecognitionListener) {
        listener = l
    }

    /**
     * 初始化模型（异步）
     * 首次使用会从assets解压模型文件
     */
    fun initModel(onResult: (Boolean, String) -> Unit) {
        try {
            Log.d(TAG, "开始初始化Vosk模型...")

            // 检查模型是否已存在
            val modelDir = File(context.filesDir, MODEL_NAME)
            if (modelDir.exists() && modelDir.isDirectory) {
                Log.d(TAG, "模型已存在，直接加载: ${modelDir.absolutePath}")
                loadModel(modelDir.absolutePath, onResult)
            } else {
                Log.d(TAG, "模型不存在，需要下载或解压")
                // 尝试从assets解压（如果打包了模型）
                // 或者提示用户下载
                onResult(false, "需要下载语音模型")
            }
        } catch (e: Exception) {
            Log.e(TAG, "初始化模型失败", e)
            onResult(false, "初始化失败: ${e.message}")
        }
    }

    private fun loadModel(modelPath: String, onResult: (Boolean, String) -> Unit) {
        try {
            model = Model(modelPath)
            Log.d(TAG, "Vosk模型加载成功")
            onResult(true, "模型加载成功")
        } catch (e: Exception) {
            Log.e(TAG, "加载模型失败", e)
            onResult(false, "加载模型失败: ${e.message}")
        }
    }

    /**
     * 开始语音识别
     */
    fun startListening(): Boolean {
        val m = model
        if (m == null) {
            Log.e(TAG, "模型未初始化")
            listener?.onError("模型未初始化")
            return false
        }

        try {
            val rec = Recognizer(m, 16000f)

            speechService = SpeechService(rec, 16000f)
            speechService?.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    if (hypothesis != null && hypothesis.isNotEmpty()) {
                        try {
                            val json = JSONObject(hypothesis)
                            val partial = json.optString("partial", "")
                            if (partial.isNotEmpty()) {
                                Log.d(TAG, "部分结果: $partial")
                                listener?.onPartialResult(partial)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "解析部分结果失败", e)
                        }
                    }
                }

                override fun onResult(hypothesis: String?) {
                    if (hypothesis != null && hypothesis.isNotEmpty()) {
                        try {
                            val json = JSONObject(hypothesis)
                            val text = json.optString("text", "")
                            if (text.isNotEmpty()) {
                                Log.d(TAG, "最终结果: $text")
                                listener?.onFinalResult(text)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "解析结果失败", e)
                        }
                    }
                }

                override fun onFinalResult(hypothesis: String?) {
                    onResult(hypothesis)
                }

                override fun onError(e: Exception?) {
                    Log.e(TAG, "识别错误", e)
                    listener?.onError(e?.message ?: "未知错误")
                }

                override fun onTimeout() {
                    Log.d(TAG, "识别超时")
                    listener?.onError("识别超时")
                }
            })

            Log.d(TAG, "开始监听")
            listener?.onReady()
            return true

        } catch (e: Exception) {
            Log.e(TAG, "启动监听失败", e)
            listener?.onError("启动监听失败: ${e.message}")
            return false
        }
    }

    /**
     * 停止语音识别
     */
    fun stopListening() {
        try {
            speechService?.stop()
            speechService = null
            Log.d(TAG, "停止监听")
        } catch (e: Exception) {
            Log.e(TAG, "停止监听失败", e)
        }
    }

    /**
     * 释放资源
     */
    fun destroy() {
        stopListening()
        model?.close()
        model = null
        Log.d(TAG, "资源已释放")
    }
}
