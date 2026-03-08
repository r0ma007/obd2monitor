package com.obd2monitor.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.obd2monitor.databinding.ActivitySettingsBinding
import com.obd2monitor.service.AppPreferences

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = "הגדרות MQTT"
            setDisplayHomeAsUpEnabled(true)
        }

        loadSettings()

        binding.btnSave.setOnClickListener {
            saveSettings()
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("mqtt_config", MODE_PRIVATE)
        binding.etBrokerUrl.setText(prefs.getString("broker_url", "tcp://192.168.1.100:1883"))
        binding.etUsername.setText(prefs.getString("username", ""))
        binding.etPassword.setText(prefs.getString("password", ""))
        binding.etTopicPrefix.setText(prefs.getString("topic_prefix", "car/obd2"))
        binding.etPublishInterval.setText(prefs.getInt("publish_interval", 30).toString())
        binding.switchOnlyOnChange.isChecked = prefs.getBoolean("only_on_change", true)

        // Background / watchdog settings
        binding.etWatchdogMinutes.setText(AppPreferences.getWatchdogMinutes(this).toString())
        binding.switchAutoConnect.isChecked = AppPreferences.isAutoConnect(this)

        val savedName = AppPreferences.getSavedName(this)
        val savedAddress = AppPreferences.getSavedAddress(this)
        if (savedAddress != null) {
            binding.tvSavedDevice.text = "מכשיר שמור: $savedName ($savedAddress)"
            binding.btnForgetDevice.visibility = android.view.View.VISIBLE
        } else {
            binding.tvSavedDevice.text = "אין מכשיר שמור — יש להתחבר פעם ראשונה מהמסך הראשי"
            binding.btnForgetDevice.visibility = android.view.View.GONE
        }

        binding.btnForgetDevice.setOnClickListener {
            AppPreferences.clearDevice(this)
            binding.tvSavedDevice.text = "אין מכשיר שמור"
            binding.btnForgetDevice.visibility = android.view.View.GONE
            Toast.makeText(this, "מכשיר נשכח", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveSettings() {
        val brokerUrl = binding.etBrokerUrl.text.toString().trim()
        if (brokerUrl.isEmpty()) { binding.etBrokerUrl.error = "שדה חובה"; return }

        val interval = binding.etPublishInterval.text.toString().toIntOrNull() ?: 30
        val watchdog = binding.etWatchdogMinutes.text.toString().toIntOrNull() ?: 10

        getSharedPreferences("mqtt_config", MODE_PRIVATE).edit().apply {
            putString("broker_url", brokerUrl)
            putString("username", binding.etUsername.text.toString())
            putString("password", binding.etPassword.text.toString())
            putString("topic_prefix", binding.etTopicPrefix.text.toString().ifEmpty { "car/obd2" })
            putInt("publish_interval", interval.coerceIn(5, 300))
            putBoolean("only_on_change", binding.switchOnlyOnChange.isChecked)
            apply()
        }

        AppPreferences.setWatchdogMinutes(this, watchdog.coerceIn(0, 60))
        AppPreferences.setAutoConnect(this, binding.switchAutoConnect.isChecked)

        Toast.makeText(this, "הגדרות נשמרו", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
