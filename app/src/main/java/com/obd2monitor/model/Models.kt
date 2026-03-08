package com.obd2monitor.model

data class OBD2Data(
    val speed: Int = 0,           // km/h
    val rpm: Int = 0,             // rev/min
    val engineTemp: Int = 0,      // °C
    val fuelLevel: Int = 0,       // %
    val fuelRate: Float = 0f,     // L/100km (calculated)
    val batteryVoltage: Float = 0f, // Volts
    val odometer: Long = 0L,      // km (if available)
    val throttlePos: Int = 0,     // %
    val timestamp: Long = System.currentTimeMillis(),
    val isConnected: Boolean = false
)

data class BluetoothDevice(
    val name: String,
    val address: String
)

data class MqttConfig(
    val brokerUrl: String = "tcp://192.168.1.100:1883",
    val username: String = "",
    val password: String = "",
    val clientId: String = "obd2_android",
    val topicPrefix: String = "car/obd2",
    val publishIntervalSec: Int = 30,
    val publishOnlyOnChange: Boolean = true   // skip publish if data hasn't changed
)
