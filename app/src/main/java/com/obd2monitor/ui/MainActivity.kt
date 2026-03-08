package com.obd2monitor.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.obd2monitor.R
import com.obd2monitor.databinding.ActivityMainBinding
import com.obd2monitor.model.MqttConfig
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            showBluetoothDeviceSelector()
        } else {
            Toast.makeText(this, "נדרשות הרשאות Bluetooth", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        viewModel.bindService(this)
        setupObservers()
        setupClickListeners()

        // Load saved MQTT config and connect
        loadAndApplyMqttConfig()
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.obd2Data.collect { data ->
                updateDashboard(data)
            }
        }

        lifecycleScope.launch {
            viewModel.watchdogRemaining.collect { sec ->
                if (sec > 0) {
                    val min = sec / 60
                    val s = sec % 60
                    binding.tvConnectionStatus.text = "ממתין לחיבור... סגירה בעוד %d:%02d".format(min, s)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.isConnected.collect { connected ->
                binding.btnConnect.text = if (connected) "נתק" else "התחבר ל-OBD2"
                binding.btnConnect.setIconResource(
                    if (connected) R.drawable.ic_disconnect else R.drawable.ic_bluetooth
                )
                updateConnectionIndicator(connected)
            }
        }

        lifecycleScope.launch {
            viewModel.mqttStatus.collect { status ->
                binding.tvMqttStatus.text = status
                val connected = status.contains("✓")
                binding.layoutMqttStats.visibility = if (connected) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launch {
            viewModel.publishStats.collect { stats ->
                binding.tvPublishStats.text = "📤 ${stats.sent} נשלחו | ⏭ ${stats.skipped} דולגו | ${stats.lastSentFormatted}"
            }
        }

        lifecycleScope.launch {
            viewModel.nextPublishIn.collect { sec ->
                binding.tvNextPublish.text = if (sec == 0) "שולח..." else "הבא: ${sec}ש'"
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnConnect.setOnClickListener {
            if (viewModel.isConnected.value) {
                viewModel.disconnect()
            } else {
                checkPermissionsAndConnect()
            }
        }
    }

    private fun updateDashboard(data: com.obd2monitor.model.OBD2Data) {
        // Speed
        binding.tvSpeed.text = "${data.speed}"
        binding.speedGauge.progress = minOf(data.speed, 220)

        // RPM
        binding.tvRpm.text = "${data.rpm}"
        binding.rpmGauge.progress = minOf(data.rpm / 100, 80)

        // Fuel Level
        binding.tvFuelLevel.text = "${data.fuelLevel}%"
        binding.fuelLevelBar.progress = data.fuelLevel
        binding.fuelLevelBar.setIndicatorColor(
            when {
                data.fuelLevel < 15 -> getColor(R.color.fuel_critical)
                data.fuelLevel < 30 -> getColor(R.color.fuel_warning)
                else -> getColor(R.color.fuel_ok)
            }
        )

        // Fuel Rate
        if (data.fuelRate > 0) {
            binding.tvFuelRate.text = String.format("%.1f L/100km", data.fuelRate)
        } else {
            binding.tvFuelRate.text = "-- L/100km"
        }

        // Engine Temp
        binding.tvEngineTemp.text = "${data.engineTemp}°C"
        binding.engineTempBar.progress = minOf(data.engineTemp + 40, 160)

        // Battery Voltage
        if (data.batteryVoltage > 0) {
            binding.tvBatteryVoltage.text = String.format("%.2f V", data.batteryVoltage)
            binding.tvBatteryVoltage.setTextColor(
                when {
                    data.batteryVoltage < 11.5f -> getColor(R.color.fuel_critical)
                    data.batteryVoltage < 12.4f -> getColor(R.color.fuel_warning)
                    else -> getColor(R.color.fuel_ok)
                }
            )
        } else {
            binding.tvBatteryVoltage.text = "-- V"
        }

        // Odometer
        if (data.odometer > 0) {
            binding.tvOdometer.text = String.format("%,d ק\"מ", data.odometer)
        } else {
            binding.tvOdometer.text = "לא זמין"
        }

        // Throttle
        binding.tvThrottle.text = "${data.throttlePos}%"
    }

    private fun updateConnectionIndicator(connected: Boolean) {
        binding.connectionIndicator.setColorFilter(
            getColor(if (connected) R.color.connected_green else R.color.disconnected_red)
        )
    }

    private fun checkPermissionsAndConnect() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }

        val needsRequest = permissions.any {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needsRequest) {
            requestPermissions.launch(permissions)
        } else {
            showBluetoothDeviceSelector()
        }
    }

    @SuppressLint("MissingPermission")
    private fun showBluetoothDeviceSelector() {
        val btAdapter = bluetoothAdapter ?: run {
            Toast.makeText(this, "Bluetooth לא נתמך", Toast.LENGTH_SHORT).show()
            return
        }

        if (!btAdapter.isEnabled) {
            Toast.makeText(this, "אנא הפעל Bluetooth", Toast.LENGTH_SHORT).show()
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        val pairedDevices: Set<BluetoothDevice> = btAdapter.bondedDevices
        if (pairedDevices.isEmpty()) {
            Toast.makeText(this, "אין מכשירים Bluetooth מקושרים", Toast.LENGTH_LONG).show()
            return
        }

        val deviceList = pairedDevices.filter {
            it.name?.contains("OBD", ignoreCase = true) == true ||
            it.name?.contains("ELM", ignoreCase = true) == true ||
            it.name?.contains("OBDII", ignoreCase = true) == true ||
            true // Show all devices as fallback
        }.toList()

        val names = deviceList.map { "${it.name}\n${it.address}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("בחר מתאם OBD2")
            .setItems(names) { _, index ->
                val device = deviceList[index]
                Toast.makeText(this, "מתחבר ל-${device.name}...", Toast.LENGTH_SHORT).show()
                viewModel.connectToDevice(device.address)
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun loadAndApplyMqttConfig() {
        val prefs = getSharedPreferences("mqtt_config", MODE_PRIVATE)
        val config = MqttConfig(
            brokerUrl = prefs.getString("broker_url", "tcp://192.168.1.100:1883") ?: "tcp://192.168.1.100:1883",
            username = prefs.getString("username", "") ?: "",
            password = prefs.getString("password", "") ?: "",
            topicPrefix = prefs.getString("topic_prefix", "car/obd2") ?: "car/obd2",
            publishIntervalSec = prefs.getInt("publish_interval", 30),
            publishOnlyOnChange = prefs.getBoolean("only_on_change", true)
        )
        viewModel.configureMqtt(config)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_diagnostic -> {
                startActivity(Intent(this, DiagnosticActivity::class.java))
                true
            }
            R.id.action_log -> {
                startActivity(Intent(this, LogActivity::class.java))
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.unbindService(this)
    }
}
