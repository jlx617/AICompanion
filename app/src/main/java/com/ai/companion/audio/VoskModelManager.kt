package com.ai.companion.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Vosk 模型下载和管理
 */
object VoskModelManager {

    private const val TAG = "VoskModel"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
    private const val MODEL_NAME = "vosk-model-small-cn-0.22"
    private const val MODEL_DIR_NAME = "vosk-model-small-cn-0.22"

    /**
     * 检查模型是否已下载
     */
    fun isModelDownloaded(context: Context): Boolean {
        val modelDir = File(context.filesDir, MODEL_DIR_NAME)
        val amFile = File(modelDir, "am/final.mdl")
        return amFile.exists()
    }

    /**
     * 获取模型路径
     */
    fun getModelPath(context: Context): String {
        return File(context.filesDir, MODEL_DIR_NAME).absolutePath
    }

    /**
     * 下载并解压模型（挂起函数）
     */
    suspend fun downloadModel(context: Context, onProgress: (Int) -> Unit): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "开始下载模型: $MODEL_URL")
            onProgress(0)

            val zipFile = File(context.cacheDir, "model.zip")

            // 下载
            val url = URL(MODEL_URL)
            val connection = url.openConnection()
            connection.connectTimeout = 30000
            connection.readTimeout = 60000

            val totalSize = connection.contentLength
            var downloadedSize = 0L

            connection.getInputStream().use { input ->
                FileOutputStream(zipFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedSize += read
                        if (totalSize > 0) {
                            val progress = (downloadedSize * 100 / totalSize).toInt()
                            onProgress(progress)
                        }
                    }
                }
            }

            Log.d(TAG, "下载完成，开始解压")
            onProgress(100)

            // 解压
            unzipModel(context, zipFile)

            // 删除zip文件
            zipFile.delete()

            Log.d(TAG, "模型准备完成")
            Result.success(getModelPath(context))

        } catch (e: Exception) {
            Log.e(TAG, "下载模型失败", e)
            Result.failure(e)
        }
    }

    private fun unzipModel(context: Context, zipFile: File) {
        val targetDir = context.filesDir

        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val file = File(targetDir, entry.name)

                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { fos ->
                        zis.copyTo(fos)
                    }
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        Log.d(TAG, "解压完成")
    }
}
