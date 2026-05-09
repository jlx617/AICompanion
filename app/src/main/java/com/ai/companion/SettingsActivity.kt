package com.ai.companion

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
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
    }

    private lateinit var binding: ActivitySettingsBinding
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val providers = AIService.Provider.values().map { it.value }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSpinner()
        loadSettings()
        setupButtons()
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

        val providerValue = prefs.getString(KEY_PROVIDER, AIService.Provider.SILICONFLOW.value)
            ?: AIService.Provider.SILICONFLOW.value
        val providerIndex = providers.indexOf(providerValue)
        if (providerIndex >= 0) {
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
            putString(KEY_PROVIDER, binding.spinnerProvider.selectedItem?.toString())
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
        binding.btnTest.text = "Testing..."

        scope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    // Save settings first so AI service can read them
                    saveSettings()
                    val aiService = AIService(this@SettingsActivity)
                    aiService.testConnection()
                } catch (e: Exception) {
                    false
                }
            }

            binding.btnTest.isEnabled = true
            binding.btnTest.text = getString(R.string.test_connection)

            if (success) {
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.connection_success,
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.connection_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
