package com.ai.companion

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ai.companion.databinding.ActivityMainBinding
import com.ai.companion.service.AICompanionService
import com.ai.companion.update.AutoUpdateManager

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val PREFS_NAME = "ai_companion_prefs"
        private const val KEY_API_KEY = "api_key"
    }

    private lateinit var binding: ActivityMainBinding
    private var isServiceRunning = false
    private lateinit var autoUpdateManager: AutoUpdateManager

    private val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO
    )

    /** 用于接收服务状态广播 */
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra("status") ?: return
            val message = intent.getStringExtra("message") ?: ""
            runOnUiThread {
                updateStatusDisplay(status, message)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        autoUpdateManager = AutoUpdateManager(this)

        setupButtons()
        updateUI()

        // 注册状态接收器
        val filter = IntentFilter("com.ai.companion.STATUS_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statusReceiver)
    }

    private fun setupButtons() {
        binding.btnStartStop.setOnClickListener {
            if (isServiceRunning) {
                stopService()
            } else {
                if (!isApiKeyConfigured()) {
                    Toast.makeText(this, R.string.api_key_not_configured, Toast.LENGTH_LONG).show()
                    openSettings()
                    return@setOnClickListener
                }
                
                if (checkAndRequestPermissions()) {
                    startService()
                }
            }
        }

        binding.btnSetupApi.setOnClickListener {
            openSettings()
        }

        binding.btnRules.setOnClickListener {
            startActivity(Intent(this, RulesActivity::class.java))
        }

        binding.btnSettings.setOnClickListener {
            openSettings()
        }

        binding.btnCheckUpdate.setOnClickListener {
            autoUpdateManager.checkForUpdate(showNoUpdateDialog = true)
        }
    }

    private fun updateStatusDisplay(status: String, message: String) {
        val displayText = when (status) {
            "ready" -> "🟢 语音识别就绪"
            "beginning" -> "🎤 检测到说话..."
            "results" -> "✅ 识别到: $message"
            "error" -> "❌ 错误: $message"
            "end" -> "⏸️ 说话结束，准备下一次..."
            else -> "🔄 $message"
        }
        binding.tvStatusLog.text = displayText
    }

    private fun isApiKeyConfigured(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        return apiKey.isNotBlank()
    }

    private fun updateUI() {
        if (!hasAllPermissions()) {
            binding.tvInstructions.text = getString(R.string.permission_required)
        } else if (!isApiKeyConfigured()) {
            binding.tvInstructions.text = getString(R.string.api_key_not_configured)
        } else {
            binding.tvInstructions.text = getString(R.string.usage_instructions)
        }
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkAndRequestPermissions(): Boolean {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, PERMISSION_REQUEST_CODE + 1)
                Toast.makeText(this, R.string.please_grant_overlay_permission, Toast.LENGTH_LONG).show()
                return false
            }
            return true
        }

        ActivityCompat.requestPermissions(
            this,
            missingPermissions.toTypedArray(),
            PERMISSION_REQUEST_CODE
        )
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivityForResult(intent, PERMISSION_REQUEST_CODE + 1)
                } else {
                    startService()
                }
            } else {
                Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PERMISSION_REQUEST_CODE + 1) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startService()
            } else {
                Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startService() {
        AICompanionService.start(this)
        isServiceRunning = true
        binding.btnStartStop.text = getString(R.string.stop_service)
        binding.tvInstructions.text = getString(R.string.service_running)
        binding.tvStatusLog.text = "🔄 正在启动语音识别..."
        Toast.makeText(this, R.string.service_started, Toast.LENGTH_SHORT).show()
    }

    private fun stopService() {
        AICompanionService.stop(this)
        isServiceRunning = false
        binding.btnStartStop.text = getString(R.string.start_service)
        binding.tvInstructions.text = getString(R.string.usage_instructions)
        binding.tvStatusLog.text = "已停止"
        Toast.makeText(this, R.string.service_stopped, Toast.LENGTH_SHORT).show()
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }
}
