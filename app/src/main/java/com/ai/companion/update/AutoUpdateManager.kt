package com.ai.companion.update

import android.app.DownloadManager
import android.app.ProgressDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL

class AutoUpdateManager(private val context: Context) {
    
    companion object {
        private const val TAG = "AutoUpdate"
        private const val GITHUB_API_URL = "https://api.github.com/repos/jlx617/AICompanion/releases/latest"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
    
    private val downloadManager by lazy { context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager }
    private var downloadId: Long = -1
    
    // Current app version - should match SettingsActivity.CURRENT_VERSION
    private val currentVersion = "4.0.0"
    
    fun checkForUpdate(showNoUpdateDialog: Boolean = false) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Checking for updates...")
                val latestVersion = fetchLatestVersion()
                
                withContext(Dispatchers.Main) {
                    if (latestVersion != null && isNewerVersion(latestVersion)) {
                        showUpdateDialog(latestVersion)
                    } else if (showNoUpdateDialog) {
                        AlertDialog.Builder(context)
                            .setTitle("已是最新版本")
                            .setMessage("当前版本 $currentVersion 已是最新")
                            .setPositiveButton("确定", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for updates", e)
                if (showNoUpdateDialog) {
                    withContext(Dispatchers.Main) {
                        AlertDialog.Builder(context)
                            .setTitle("检查更新失败")
                            .setMessage("无法连接到更新服务器: ${e.message}")
                            .setPositiveButton("确定", null)
                            .show()
                    }
                }
            }
        }
    }
    
    private suspend fun fetchLatestVersion(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(GITHUB_API_URL)
                val connection = url.openConnection()
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                
                val tagName = json.getString("tag_name")
                Log.d(TAG, "Latest version from GitHub: $tagName")
                tagName
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching version", e)
                null
            }
        }
    }
    
    private fun isNewerVersion(latest: String): Boolean {
        // Remove 'v' prefix if present
        val latestClean = latest.removePrefix("v")
        val currentClean = currentVersion.removePrefix("v")
        
        return try {
            val latestParts = latestClean.split(".").map { it.toInt() }
            val currentParts = currentClean.split(".").map { it.toInt() }
            
            for (i in 0 until minOf(latestParts.size, currentParts.size)) {
                if (latestParts[i] > currentParts[i]) return true
                if (latestParts[i] < currentParts[i]) return false
            }
            latestParts.size > currentParts.size
        } catch (e: Exception) {
            false
        }
    }
    
    private fun showUpdateDialog(latestVersion: String) {
        AlertDialog.Builder(context)
            .setTitle("发现新版本")
            .setMessage("当前版本: $currentVersion\n最新版本: $latestVersion\n\n是否立即下载更新？")
            .setPositiveButton("立即下载") { _, _ ->
                downloadUpdate(latestVersion)
            }
            .setNegativeButton("稍后", null)
            .show()
    }
    
    private fun downloadUpdate(version: String) {
        val downloadUrl = "https://github.com/jlx617/AICompanion/releases/download/$version/app-debug.apk"
        
        Log.d(TAG, "Downloading from: $downloadUrl")
        
        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("AI陪伴助手更新")
            .setDescription("正在下载版本 $version...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "AICompanion-$version.apk")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        
        downloadId = downloadManager.enqueue(request)
        
        // Register receiver for download completion
        context.registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        
        AlertDialog.Builder(context)
            .setTitle("下载已开始")
            .setMessage("新版本正在后台下载，完成后将自动提示安装。")
            .setPositiveButton("确定", null)
            .show()
    }
    
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == downloadId) {
                installApk()
                context.unregisterReceiver(this)
            }
        }
    }
    
    private fun installApk() {
        try {
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = downloadManager.query(query)
            
            if (cursor.moveToFirst()) {
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    val uri = downloadManager.getUriForDownloadedFile(downloadId)
                    if (uri != null) {
                        promptInstall(uri)
                    }
                }
            }
            cursor.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error installing APK", e)
        }
    }
    
    private fun promptInstall(apkUri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, APK_MIME_TYPE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        
        AlertDialog.Builder(context)
            .setTitle("下载完成")
            .setMessage("新版本已下载完成，是否立即安装？")
            .setPositiveButton("立即安装") { _, _ ->
                context.startActivity(intent)
            }
            .setNegativeButton("稍后") { _, _ -> }
            .show()
    }
}
