package com.ai.companion

import android.Manifest
import android.content.Intent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        autoUpdateManager = AutoUpdateManager(this)
        setupButtons()
        updateUI()
    }

    private fun setupButtons() {
        binding.btnStartStop.setOnClickListener {
            if (isServiceRunning) {
                stopService()
            } else {
                if (!isApiKeyConfigured()) {
                    Toast.makeText(this, "请先配置API密钥", Toast.LENGTH_LONG).show()
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

        binding.btnSettings.setOnClickListener {
            openSettings()
        }

        binding.btnCheckUpdate.setOnClickListener {
            autoUpdateManager.checkForUpdate(showNoUpdateDialog = true)
        }
    }

    private fun isApiKeyConfigured(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        return apiKey.isNotBlank()
    }

    private fun updateUI() {
        if (!hasAllPermissions()) {
            binding.tvInstructions.text = "需要麦克风权限才能正常运行"
            binding.tvStatusLog.text = "请授予权限"
        } else if (!isApiKeyConfigured()) {
            binding.tvInstructions.text = "请先在设置中配置API密钥"
            binding.tvStatusLog.text = "未配置API密钥"
        } else {
            binding.tvInstructions.text = "点击开始聆听即可启动AI陪伴助手"
            binding.tvStatusLog.text = "准备就绪"
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
                Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_LONG).show()
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
                Toast.makeText(this, "需要权限才能运行", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PERMISSION_REQUEST_CODE + 1) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startService()
            } else {
                Toast.makeText(this, "需要悬浮窗权限", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startService() {
        AICompanionService.start(this)
        isServiceRunning = true
        binding.btnStartStop.text = "停止聆听"
        binding.tvInstructions.text = "AI陪伴助手正在聆听中..."
        binding.tvStatusLog.text = "正在启动..."
        Toast.makeText(this, "服务已启动", Toast.LENGTH_SHORT).show()
    }

    private fun stopService() {
        AICompanionService.stop(this)
        isServiceRunning = false
        binding.btnStartStop.text = "开始聆听"
        binding.tvInstructions.text = "点击开始聆听即可启动AI陪伴助手"
        binding.tvStatusLog.text = "已停止"
        Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show()
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
