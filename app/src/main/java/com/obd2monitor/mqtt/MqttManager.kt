package com.obd2monitor.mqtt

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.obd2monitor.model.MqttConfig
import com.obd2monitor.model.OBD2Data
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import kotlinx.coroutines.*

class MqttManager(private val context: Context) {

    companion object {
        private const val TAG = "MqttManager"
        const val QOS = 1
    }

    private var mqttClient: MqttAsyncClient? = null
    private var config: MqttConfig = MqttConfig()
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var onConnectionStatusChanged: ((Boolean, String) -> Unit)? = null

    fun configure(mqttConfig: MqttConfig) {
        config = mqttConfig
    }

    fun connect() {
        scope.launch {
            try {
                mqttClient?.disconnect()

                mqttClient = MqttAsyncClient(
                    config.brokerUrl,
                    config.clientId + "_" + System.currentTimeMillis(),
                    MemoryPersistence()
                )

                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 60
                    isAutomaticReconnect = true

                    if (config.username.isNotEmpty()) {
                        userName = config.username
                        password = config.password.toCharArray()
                    }

                    // Last Will - sent when app disconnects unexpectedly
                    setWill(
                        "${config.topicPrefix}/status",
                        """{"status":"offline"}""".toByteArray(),
                        QOS,
                        true
                    )
                }

                mqttClient?.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        Log.d(TAG, "MQTT Connected to $serverURI (reconnect=$reconnect)")
                        publishOnlineStatus()
                        // Auto-discover entities in Home Assistant
                        publishHomeAssistantDiscovery()
                        onConnectionStatusChanged?.invoke(true, "מחובר ל-MQTT")
                    }

                    override fun connectionLost(cause: Throwable?) {
                        Log.w(TAG, "MQTT connection lost: ${cause?.message}")
                        onConnectionStatusChanged?.invoke(false, "MQTT: חיבור אבד")
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {}

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                mqttClient?.connect(options)
                Log.d(TAG, "MQTT Connecting to ${config.brokerUrl}...")

            } catch (e: MqttException) {
                Log.e(TAG, "MQTT Connect error: ${e.message}")
                onConnectionStatusChanged?.invoke(false, "MQTT שגיאה: ${e.message}")
            }
        }
    }

    /**
     * Publish OBD2 data to MQTT topics
     * Topics follow HA sensor format
     */
    fun publishData(data: OBD2Data) {
        if (mqttClient?.isConnected != true) return

        scope.launch {
            try {
                val prefix = config.topicPrefix

                // Publish individual sensor topics (for HA template sensors)
                publishMessage("$prefix/speed", data.speed.toString())
                publishMessage("$prefix/rpm", data.rpm.toString())
                publishMessage("$prefix/engine_temp", data.engineTemp.toString())
                publishMessage("$prefix/fuel_level", data.fuelLevel.toString())
                publishMessage("$prefix/fuel_rate", String.format("%.1f", data.fuelRate))
                publishMessage("$prefix/battery_voltage", String.format("%.2f", data.batteryVoltage))
                publishMessage("$prefix/throttle", data.throttlePos.toString())

                if (data.odometer > 0) {
                    publishMessage("$prefix/odometer", data.odometer.toString())
                }

                // Also publish as JSON payload
                val json = buildJsonPayload(data)
                publishMessage("$prefix/state", json)

            } catch (e: Exception) {
                Log.e(TAG, "Publish error: ${e.message}")
            }
        }
    }

    private fun buildJsonPayload(data: OBD2Data): String {
        return gson.toJson(mapOf(
            "speed" to data.speed,
            "rpm" to data.rpm,
            "engine_temp" to data.engineTemp,
            "fuel_level" to data.fuelLevel,
            "fuel_rate" to String.format("%.1f", data.fuelRate),
            "battery_voltage" to String.format("%.2f", data.batteryVoltage),
            "throttle" to data.throttlePos,
            "odometer" to data.odometer,
            "timestamp" to data.timestamp
        ))
    }

    /**
     * Home Assistant MQTT Discovery
     * Auto-creates sensors in HA without manual YAML config
     */
    private fun publishHomeAssistantDiscovery() {
        val prefix = config.topicPrefix
        val deviceId = "obd2_android_car"

        val deviceInfo = """
            "device": {
                "identifiers": ["$deviceId"],
                "name": "רכב OBD2",
                "model": "ELM327 BT",
                "manufacturer": "OBD2 Monitor App"
            }
        """.trimIndent()

        data class SensorConfig(
            val name: String,
            val uniqueId: String,
            val stateTopic: String,
            val unit: String,
            val icon: String,
            val deviceClass: String? = null
        )

        val sensors = listOf(
            SensorConfig("מהירות", "car_speed", "$prefix/speed", "km/h", "mdi:speedometer"),
            SensorConfig("סל\"ד מנוע", "car_rpm", "$prefix/rpm", "RPM", "mdi:engine"),
            SensorConfig("טמפרטורת מנוע", "car_engine_temp", "$prefix/engine_temp", "°C", "mdi:thermometer", "temperature"),
            SensorConfig("רמת דלק", "car_fuel_level", "$prefix/fuel_level", "%", "mdi:gas-station"),
            SensorConfig("צריכת דלק", "car_fuel_rate", "$prefix/fuel_rate", "L/100km", "mdi:fuel"),
            SensorConfig("מתח סוללה", "car_battery_voltage", "$prefix/battery_voltage", "V", "mdi:battery", "voltage"),
            SensorConfig("מיקום מצערת", "car_throttle", "$prefix/throttle", "%", "mdi:car-speed-limiter"),
            SensorConfig("אודומטר", "car_odometer", "$prefix/odometer", "km", "mdi:counter")
        )

        for (sensor in sensors) {
            val deviceClassLine = if (sensor.deviceClass != null)
                """"device_class": "${sensor.deviceClass}",""" else ""

            val config = """
                {
                    "name": "${sensor.name}",
                    "unique_id": "${sensor.uniqueId}",
                    "state_topic": "${sensor.stateTopic}",
                    "unit_of_measurement": "${sensor.unit}",
                    "icon": "${sensor.icon}",
                    $deviceClassLine
                    $deviceInfo
                }
            """.trimIndent()

            // HA discovery topic format: homeassistant/sensor/<device_id>/<sensor_id>/config
            publishMessage(
                "homeassistant/sensor/$deviceId/${sensor.uniqueId}/config",
                config,
                retain = true
            )
        }

        Log.d(TAG, "Published HA discovery for ${sensors.size} sensors")
    }

    private fun publishOnlineStatus() {
        publishMessage("${config.topicPrefix}/status", """{"status":"online"}""", retain = true)
    }

    private fun publishMessage(topic: String, payload: String, retain: Boolean = false) {
        try {
            val message = MqttMessage(payload.toByteArray()).apply {
                qos = QOS
                isRetained = retain
            }
            mqttClient?.publish(topic, message)
        } catch (e: MqttException) {
            Log.e(TAG, "Publish failed [$topic]: ${e.message}")
        }
    }

    fun disconnect() {
        scope.launch {
            try {
                publishMessage("${config.topicPrefix}/status", """{"status":"offline"}""", retain = true)
                mqttClient?.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Disconnect error: ${e.message}")
            }
        }
    }

    val isConnected: Boolean
        get() = mqttClient?.isConnected == true

    fun cleanup() {
        disconnect()
        scope.cancel()
    }
}
