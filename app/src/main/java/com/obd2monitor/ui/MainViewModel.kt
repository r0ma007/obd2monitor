package com.obd2monitor.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.obd2monitor.model.MqttConfig
import com.obd2monitor.model.OBD2Data
import com.obd2monitor.mqtt.MqttManager
import com.obd2monitor.service.OBD2Service
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private var obd2Service: OBD2Service? = null
    private var isBound = false
    val mqttManager = MqttManager(application)

    private val _obd2Data = MutableStateFlow(OBD2Data())
    val obd2Data: StateFlow<OBD2Data> = _obd2Data

    private val _connectionStatus = MutableStateFlow("מנותק")
    val connectionStatus: StateFlow<String> = _connectionStatus

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    // MQTT publish status — shown in UI
    private val _mqttStatus = MutableStateFlow("MQTT: מנותק")
    val mqttStatus: StateFlow<String> = _mqttStatus

    // Publish stats for UI display
    private val _publishStats = MutableStateFlow(PublishStats())
    val publishStats: StateFlow<PublishStats> = _publishStats

    // Countdown seconds until next publish (0 = publishing now)
    private val _nextPublishIn = MutableStateFlow(0)
    val nextPublishIn: StateFlow<Int> = _nextPublishIn

    private var mqttPublishJob: Job? = null
    private var config = MqttConfig()

    // Last snapshot sent — used for change detection
    private var lastPublishedData: OBD2Data? = null

    // ── Service connection ────────────────────────────────────────────

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            obd2Service = (binder as OBD2Service.OBD2Binder).getService()
            isBound = true
            viewModelScope.launch { obd2Service?.obd2Data?.collect { _obd2Data.value = it } }
            viewModelScope.launch { obd2Service?.connectionStatus?.collect { _connectionStatus.value = it } }
            viewModelScope.launch { obd2Service?.isConnected?.collect { _isConnected.value = it } }
        }
        override fun onServiceDisconnected(name: ComponentName?) { obd2Service = null; isBound = false }
    }

    fun bindService(context: Context) {
        val intent = Intent(context, OBD2Service::class.java)
        context.startForegroundService(intent)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun unbindService(context: Context) {
        if (isBound) { context.unbindService(serviceConnection); isBound = false }
    }

    val watchdogRemaining: StateFlow<Int> get() = obd2Service?.watchdogRemaining ?: MutableStateFlow(-1)

    fun connectToDevice(address: String) = obd2Service?.connectToDevice(address)
    fun disconnect() = obd2Service?.disconnect()

    // ── MQTT ─────────────────────────────────────────────────────────

    fun configureMqtt(mqttConfig: MqttConfig) {
        config = mqttConfig
        mqttManager.configure(mqttConfig)
        mqttManager.onConnectionStatusChanged = { connected, msg ->
            _mqttStatus.value = if (connected) "MQTT ✓ מחובר" else "MQTT: $msg"
        }
        mqttManager.connect()
        startMqttPublishing()
    }

    private fun startMqttPublishing() {
        mqttPublishJob?.cancel()
        mqttPublishJob = viewModelScope.launch {
            val intervalSec = config.publishIntervalSec.coerceAtLeast(1)

            while (isActive) {
                // Countdown ticker — updates every second
                for (remaining in intervalSec downTo 1) {
                    _nextPublishIn.value = remaining
                    delay(1000L)
                }
                _nextPublishIn.value = 0

                val data = _obd2Data.value
                if (!data.isConnected || !mqttManager.isConnected) continue

                // Change detection — skip if nothing meaningful changed
                val shouldPublish = if (config.publishOnlyOnChange) {
                    hasSignificantChange(data, lastPublishedData)
                } else {
                    true  // always publish on interval
                }

                if (shouldPublish) {
                    mqttManager.publishData(data)
                    lastPublishedData = data
                    _publishStats.value = _publishStats.value.incrementSent()
                    LiveLogger.i("MQTT שלח: speed=${data.speed} rpm=${data.rpm} fuel=${data.fuelLevel}%")
                } else {
                    _publishStats.value = _publishStats.value.incrementSkipped()
                    LiveLogger.d("MQTT דילג (אין שינוי)")
                }
            }
        }
    }

    /**
     * Returns true if data changed enough to be worth publishing.
     * Small fluctuations (1 km/h, 50 RPM) are ignored.
     */
    private fun hasSignificantChange(current: OBD2Data, prev: OBD2Data?): Boolean {
        if (prev == null) return true
        return Math.abs(current.speed - prev.speed) >= 3 ||
               Math.abs(current.rpm - prev.rpm) >= 100 ||
               Math.abs(current.engineTemp - prev.engineTemp) >= 2 ||
               Math.abs(current.fuelLevel - prev.fuelLevel) >= 1 ||
               Math.abs(current.batteryVoltage - prev.batteryVoltage) >= 0.1f ||
               current.odometer != prev.odometer
    }

    override fun onCleared() {
        super.onCleared()
        mqttPublishJob?.cancel()
        mqttManager.cleanup()
    }
}

// ── Publish stats data class ──────────────────────────────────────────

data class PublishStats(
    val sent: Int = 0,
    val skipped: Int = 0,
    val lastSentTime: Long = 0L
) {
    fun incrementSent() = copy(sent = sent + 1, lastSentTime = System.currentTimeMillis())
    fun incrementSkipped() = copy(skipped = skipped + 1)

    val lastSentFormatted: String get() {
        if (lastSentTime == 0L) return "טרם נשלח"
        val secAgo = (System.currentTimeMillis() - lastSentTime) / 1000
        return "לפני ${secAgo}ש'"
    }
}
