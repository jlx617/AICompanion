package com.ai.companion

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.ai.companion.ai.AIService
import com.ai.companion.databinding.ActivitySettingsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "ai_companion_prefs"
        private const val KEY_PROVIDER = "api_provider"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_TTS_ENABLED = "tts_enabled"
        private const val KEY_FLOATING_WINDOW = "floating_window_enabled"
        private const val KEY_SCENE_DETECTION = "scene_detection_enabled"
        
        // 当前版本号，每次发布新版本时更新
        const val CURRENT_VERSION = "5.0.0"
        const val GITHUB_RELEASES_URL = "https://github.com/jlx617/AICompanion/releases"
    }

    private lateinit var binding: ActivitySettingsBinding
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val providers = listOf(
        "SiliconFlow (硅基流动)",
        "DeepSeek",
        "Google Gemini",
        "Groq"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSpinner()
        loadSettings()
        setupButtons()
        checkForUpdate()
    }

    private fun setupSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            providers
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerProvider.adapter = adapter
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        val providerIndex = prefs.getInt(KEY_PROVIDER, 0)
        if (providerIndex in providers.indices) {
            binding.spinnerProvider.setSelection(providerIndex)
        }

        binding.etApiKey.setText(prefs.getString(KEY_API_KEY, ""))
        binding.switchTts.isChecked = prefs.getBoolean(KEY_TTS_ENABLED, false)
        binding.switchFloatingWindow.isChecked = prefs.getBoolean(KEY_FLOATING_WINDOW, true)
        binding.switchSceneDetection.isChecked = prefs.getBoolean(KEY_SCENE_DETECTION, true)
    }

    private fun setupButtons() {
        binding.btnSave.setOnClickListener {
            saveSettings()
        }

        binding.btnTest.setOnClickListener {
            testConnection()
        }
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().apply {
            putInt(KEY_PROVIDER, binding.spinnerProvider.selectedItemPosition)
            putString(KEY_API_KEY, binding.etApiKey.text?.toString()?.trim() ?: "")
            putBoolean(KEY_TTS_ENABLED, binding.switchTts.isChecked)
            putBoolean(KEY_FLOATING_WINDOW, binding.switchFloatingWindow.isChecked)
            putBoolean(KEY_SCENE_DETECTION, binding.switchSceneDetection.isChecked)
            apply()
        }
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }

    private fun testConnection() {
        binding.btnTest.isEnabled = false
        binding.btnTest.text = getString(R.string.testing)

        scope.launch {
            // 先保存设置
            saveSettings()
            
            val result = withContext(Dispatchers.IO) {
                try {
                    val aiService = AIService(this@SettingsActivity)
                    aiService.testConnection()
                } catch (e: Exception) {
                    AIService.TestResult(false, "测试异常: ${e.message}")
                }
            }

            binding.btnTest.isEnabled = true
            binding.btnTest.text = getString(R.string.test_connection)

            // 显示详细结果
            if (result.success) {
                Toast.makeText(
                    this@SettingsActivity,
                    "✅ ${result.message}",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                // 失败时显示详细错误信息
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("连接失败")
                    .setMessage(result.message)
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    private fun checkForUpdate() {
        // 简化的版本检查，实际项目中可以从服务器获取最新版本
        // 这里只是演示框架，每次打开设置页面检查
        scope.launch {
            try {
                // 模拟从GitHub获取最新版本（实际应该调用API）
                // 这里为了演示，假设有新版本
                val hasUpdate = false // 实际应该通过网络请求获取
                val latestVersion = "1.0.4" // 示例
                
                if (hasUpdate && latestVersion != CURRENT_VERSION) {
                    showUpdateDialog(latestVersion)
                }
            } catch (e: Exception) {
                // 忽略检查失败
            }
        }
    }

    private fun showUpdateDialog(latestVersion: String) {
        AlertDialog.Builder(this)
            .setTitle("发现新版本")
            .setMessage("当前版本: $CURRENT_VERSION\n最新版本: $latestVersion\n\n建议更新以获得更好的体验。")
            .setPositiveButton("立即更新") { _, _ ->
                openUpdateUrl()
            }
            .setNegativeButton("稍后提醒", null)
            .show()
    }

    private fun openUpdateUrl() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_RELEASES_URL))
        startActivity(intent)
    }
}
