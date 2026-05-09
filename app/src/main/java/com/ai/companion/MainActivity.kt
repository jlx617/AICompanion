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

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    private lateinit var binding: ActivityMainBinding
    private var isServiceRunning = false

    private val requiredPermissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
        checkPermissions()
    }

    private fun setupButtons() {
        binding.btnStartStop.setOnClickListener {
            if (isServiceRunning) {
                stopService()
            } else {
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
    }

    private fun checkPermissions() {
        if (!hasAllPermissions()) {
            binding.tvInstructions.text = getString(R.string.permission_required)
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
            // Check overlay permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, PERMISSION_REQUEST_CODE + 1)
                Toast.makeText(this, "Please grant overlay permission", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "Permissions are required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PERMISSION_REQUEST_CODE + 1) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startService()
            } else {
                Toast.makeText(this, "Overlay permission is required for floating window", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startService() {
        AICompanionService.start(this)
        isServiceRunning = true
        binding.btnStartStop.text = getString(R.string.stop_service)
        binding.tvInstructions.text = getString(R.string.usage_instructions)
        Toast.makeText(this, "Service started", Toast.LENGTH_SHORT).show()
    }

    private fun stopService() {
        AICompanionService.stop(this)
        isServiceRunning = false
        binding.btnStartStop.text = getString(R.string.start_service)
        Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show()
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!hasAllPermissions()) {
            binding.tvInstructions.text = getString(R.string.permission_required)
        }
    }
}
