package com.obd2monitor.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.obd2monitor.model.OBD2Data
import com.obd2monitor.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class PidScanResult(
    val pidInfo: PidInfo,
    val supported: Boolean,
    val rawResponse: String,
    val parsedValue: String,
    val responseTimeMs: Long
)

class OBD2Service : Service() {

    companion object {
        private const val TAG = "OBD2Service"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "obd2_channel"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val POLL_INTERVAL_MS = 1000L

        // Intent actions
        const val ACTION_AUTO_START = "com.obd2monitor.AUTO_START"
        const val ACTION_STOP       = "com.obd2monitor.STOP"
    }

    private val binder = OBD2Binder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // WakeLock — keeps CPU running when screen is off
    private var wakeLock: PowerManager.WakeLock? = null

    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private var pollingJob: Job? = null
    private var scanJob: Job? = null
    private var watchdogJob: Job? = null

    var isInitialized = false
        private set

    val supportedPids = mutableSetOf<String>()

    // ── State flows ───────────────────────────────────────────────────

    private val _obd2Data = MutableStateFlow(OBD2Data())
    val obd2Data: StateFlow<OBD2Data> = _obd2Data

    private val _connectionStatus = MutableStateFlow("מנותק")
    val connectionStatus: StateFlow<String> = _connectionStatus

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _scanProgress = MutableStateFlow(0)
    val scanProgress: StateFlow<Int> = _scanProgress

    private val _scanResults = MutableStateFlow<List<PidScanResult>>(emptyList())
    val scanResults: StateFlow<List<PidScanResult>> = _scanResults

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    /** Seconds remaining in watchdog countdown. -1 = watchdog off */
    private val _watchdogRemaining = MutableStateFlow(-1)
    val watchdogRemaining: StateFlow<Int> = _watchdogRemaining

    var onDataUpdate: ((OBD2Data) -> Unit)? = null
    var onStatusUpdate: ((String, Boolean) -> Unit)? = null

    inner class OBD2Binder : Binder() {
        fun getService(): OBD2Service = this@OBD2Service
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onBind(intent: Intent): IBinder = binder
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {

            ACTION_STOP -> {
                LiveLogger.i("קיבל פקודת עצירה")
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_AUTO_START -> {
                // Started by BootReceiver or "הפעל ברקע" button — connect immediately
                startForeground(NOTIFICATION_ID, buildNotification(NotifState.SEARCHING))
                LiveLogger.i("הפעלה אוטומטית מאתחול")
                autoConnectToSavedDevice()
            }

            else -> {
                // Normal start from MainActivity
                startForeground(NOTIFICATION_ID, buildNotification(NotifState.SEARCHING))
            }
        }
        return START_STICKY
    }

    // ── Auto-connect ──────────────────────────────────────────────────

    private fun autoConnectToSavedDevice() {
        val address = AppPreferences.getSavedAddress(this)
        if (address == null) {
            LiveLogger.warn("אין מכשיר שמור — נדרש הגדרה ראשונית")
            updateStatus("נדרש הגדרה ראשונית", false)
            startWatchdog()   // will shut down after timeout if not connected
            return
        }
        LiveLogger.i("מתחבר אוטומטית ל-${AppPreferences.getSavedName(this)} ($address)")
        connectToDevice(address)
    }

    // ── Watchdog ──────────────────────────────────────────────────────

    /**
     * Starts countdown. If not connected within watchdogMinutes → shutdown.
     * Cancelled immediately when connection is established.
     */
    private fun startWatchdog() {
        val minutes = AppPreferences.getWatchdogMinutes(this)
        if (minutes <= 0) {
            LiveLogger.d("Watchdog מושבת")
            _watchdogRemaining.value = -1
            return
        }

        watchdogJob?.cancel()
        val totalSeconds = minutes * 60
        LiveLogger.i("Watchdog: סגירה אוטומטית בעוד $minutes דקות אם לא יהיה חיבור")
        updateNotification(NotifState.WATCHDOG(minutes))

        watchdogJob = serviceScope.launch {
            for (remaining in totalSeconds downTo 0) {
                _watchdogRemaining.value = remaining

                // Update notification every minute
                if (remaining % 60 == 0 && remaining > 0) {
                    val minsLeft = remaining / 60
                    updateNotification(NotifState.WATCHDOG(minsLeft))
                }

                if (remaining == 0) {
                    LiveLogger.warn("Watchdog: לא נמצא חיבור תוך $minutes דקות — סוגר")
                    shutdown()
                    return@launch
                }
                delay(1000)
            }
        }
    }

    private fun cancelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
        _watchdogRemaining.value = -1
    }

    // ── Connection ────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun connectToDevice(deviceAddress: String) {
        serviceScope.launch {
            try {
                LiveLogger.i("מתחיל חיבור Bluetooth...")
                updateStatus("מתחבר...", false)
                disconnectInternal()

                val btAdapter = BluetoothAdapter.getDefaultAdapter()
                    ?: run { LiveLogger.error("Bluetooth לא זמין"); updateStatus("Bluetooth לא זמין", false); return@launch }

                val device: BluetoothDevice = btAdapter.getRemoteDevice(deviceAddress)
                LiveLogger.i("מתחבר ל-${device.name} (${device.address})")
                updateStatus("מתחבר ל-${device.name}...", false)
                updateNotification(NotifState.CONNECTING(device.name ?: deviceAddress))

                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                btAdapter.cancelDiscovery()
                bluetoothSocket?.connect()
                inputStream = bluetoothSocket?.inputStream
                outputStream = bluetoothSocket?.outputStream
                LiveLogger.success("Bluetooth מחובר!")

                updateStatus("מאתחל ELM327...", false)
                delay(500)

                if (initializeELM327()) {
                    // Save device for next auto-connect
                    AppPreferences.saveDevice(this@OBD2Service, deviceAddress, device.name ?: deviceAddress)

                    cancelWatchdog()   // ← connected → kill the countdown
                    updateStatus("מחובר ל-${device.name}", true)
                    updateNotification(NotifState.CONNECTED(device.name ?: deviceAddress))
                    startPolling()
                } else {
                    updateStatus("שגיאה באתחול ELM327", false)
                    disconnectInternal()
                    startWatchdog()   // restart countdown after failed init
                }
            } catch (e: IOException) {
                LiveLogger.error("חיבור נכשל: ${e.message}")
                updateStatus("חיבור נכשל: ${e.message}", false)
                disconnectInternal()
                startWatchdog()   // restart countdown after failed connection
            }
        }
    }

    // ── ELM327 init ───────────────────────────────────────────────────

    private suspend fun initializeELM327(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                LiveLogger.i("=== אתחול ELM327 ===")
                sendCommand(OBD2Commands.RESET, waitMs = 1500, log = false)
                    .also { LiveLogger.recv("ATZ → $it") }

                listOf(
                    OBD2Commands.ECHO_OFF to "ATE0",
                    OBD2Commands.LINE_FEED_OFF to "ATL0",
                    OBD2Commands.SPACES_OFF to "ATS0",
                    OBD2Commands.HEADERS_OFF to "ATH0"
                ).forEach { (cmd, label) ->
                    sendCommand(cmd, log = false).also { LiveLogger.recv("$label → $it") }
                }

                sendCommand(OBD2Commands.PROTOCOL_AUTO, waitMs = 1000, log = false)
                    .also { LiveLogger.recv("ATSP0 → $it") }
                sendCommand(OBD2Commands.DESCRIBE_PROTOCOL, waitMs = 500, log = false)
                    .also { LiveLogger.success("פרוטוקול: $it") }

                LiveLogger.i("סורק PIDs נתמכים...")
                discoverSupportedPids()
                LiveLogger.success("אתחול הושלם. ${supportedPids.size} PIDs נתמכים")

                isInitialized = true
                true
            } catch (e: Exception) {
                LiveLogger.error("אתחול נכשל: ${e.message}")
                false
            }
        }
    }

    private fun discoverSupportedPids() {
        val ranges = listOf("0100", "0120", "0140", "0160", "0180", "01A0", "01C0")
        for (bitmaskPid in ranges) {
            val resp = sendCommand(bitmaskPid, waitMs = 1000, log = false) ?: break
            if (resp.contains("NODATA") || resp.contains("?")) break
            val cleaned = resp.replace("\\s".toRegex(), "").uppercase()
            val prefix = "41" + bitmaskPid.substring(2)
            val dataStart = cleaned.indexOf(prefix)
            if (dataStart < 0) break
            val dataHex = cleaned.substring(dataStart + prefix.length)
            if (dataHex.length < 8) break
            try {
                val basePid = bitmaskPid.substring(2).toInt(16)
                for (i in 0..3) {
                    val byte = dataHex.substring(i * 2, i * 2 + 2).toInt(16)
                    for (bit in 7 downTo 0) {
                        val pidNum = basePid + (i * 8) + (7 - bit) + 1
                        if ((byte and (1 shl bit)) != 0) {
                            supportedPids.add("01" + pidNum.toString(16).uppercase().padStart(2, '0'))
                        }
                    }
                }
                if ((dataHex.substring(6, 8).toInt(16) and 0x01) == 0) break
            } catch (e: Exception) { LiveLogger.warn("שגיאה בbitmask: ${e.message}"); break }
        }
        LiveLogger.i("PIDs: ${supportedPids.sorted().joinToString(", ")}")
    }

    // ── PID Scan ──────────────────────────────────────────────────────

    fun startPidScan() {
        if (!isInitialized) { LiveLogger.warn("לא מחובר, לא ניתן לסרוק"); return }
        scanJob?.cancel()
        _scanResults.value = emptyList(); _scanProgress.value = 0; _isScanning.value = true

        scanJob = serviceScope.launch {
            pollingJob?.cancel()
            LiveLogger.i("=== סריקת PIDs מלאה ===")
            val results = mutableListOf<PidScanResult>()

            PidCatalog.SCANNABLE_PIDS.forEachIndexed { index, pidInfo ->
                _scanProgress.value = ((index + 1) * 100) / PidCatalog.SCANNABLE_PIDS.size
                val t0 = System.currentTimeMillis()
                val response = sendCommand(pidInfo.pid, waitMs = 400, log = false) ?: "NULL"
                val elapsed = System.currentTimeMillis() - t0
                val isSupported = response.isNotBlank() && response != "NULL" &&
                        !response.contains("NODATA", true) && !response.contains("ERROR", true) && !response.contains("?")
                val parsed = if (isSupported) tryParseValue(pidInfo, response) else "לא נתמך"
                results.add(PidScanResult(pidInfo, isSupported, response, parsed, elapsed))
                _scanResults.value = results.toList()
                if (isSupported) LiveLogger.success("✓ ${pidInfo.pid} [${pidInfo.nameEn}]: $parsed  (${elapsed}ms)")
                else LiveLogger.d("✗ ${pidInfo.pid} [${pidInfo.nameEn}]")
                delay(60)
            }
            val ok = results.count { it.supported }
            LiveLogger.success("=== סריקה הסתיימה: $ok/${PidCatalog.SCANNABLE_PIDS.size} ===")
            _isScanning.value = false; _scanProgress.value = 100
            startPolling()
        }
    }

    fun stopScan() { scanJob?.cancel(); _isScanning.value = false; startPolling() }

    private fun tryParseValue(pidInfo: PidInfo, raw: String): String {
        return try {
            when (pidInfo.pid) {
                "010C" -> "${OBD2Parser.parseRPM(raw)} RPM"
                "010D" -> "${OBD2Parser.parseSpeed(raw)} km/h"
                "0105" -> "${OBD2Parser.parseCoolantTemp(raw)}°C"
                "012F" -> "${OBD2Parser.parseFuelLevel(raw)}%"
                "0111" -> "${OBD2Parser.parseThrottlePos(raw)}%"
                "015E" -> "${OBD2Parser.parseFuelRate(raw)} L/h"
                "0142" -> "${OBD2Parser.parseControlVoltage(raw)} V"
                "01A6" -> "${OBD2Parser.parseOdometer(raw)} km"
                else -> {
                    val bytes = OBD2Parser.parseResponse(raw, pidInfo.pid)
                    if (bytes != null && bytes.isNotEmpty()) bytes.joinToString(" ") { "%02X".format(it) }
                    else raw.take(30)
                }
            }
        } catch (e: Exception) { raw.take(30) }
    }

    // ── Polling ───────────────────────────────────────────────────────

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            LiveLogger.pollingActive = true
            LiveLogger.i("Polling התחיל")
            while (isActive && isInitialized) {
                try {
                    val speed = sendCommand(OBD2Commands.VEHICLE_SPEED, log = false)?.let { OBD2Parser.parseSpeed(it) } ?: 0
                    val rpm = sendCommand(OBD2Commands.ENGINE_RPM, log = false)?.let { OBD2Parser.parseRPM(it) } ?: 0
                    val engineTemp = sendCommand(OBD2Commands.COOLANT_TEMP, log = false)?.let { OBD2Parser.parseCoolantTemp(it) } ?: 0
                    val fuelLevel = sendCommand(OBD2Commands.FUEL_LEVEL, log = false)?.let { OBD2Parser.parseFuelLevel(it) } ?: 0
                    val throttle = sendCommand(OBD2Commands.THROTTLE_POS, log = false)?.let { OBD2Parser.parseThrottlePos(it) } ?: 0

                    val fuelRateLH = if ("015E" in supportedPids)
                        sendCommand(OBD2Commands.FUEL_RATE, log = false)?.let { OBD2Parser.parseFuelRate(it) } ?: 0f else 0f

                    val voltage = if ("0142" in supportedPids)
                        sendCommand(OBD2Commands.CONTROL_VOLTAGE, log = false)?.let { OBD2Parser.parseControlVoltage(it) } ?: 0f else 0f

                    val odometer = if ("01A6" in supportedPids)
                        sendCommand(OBD2Commands.ODOMETER, log = false)?.let { OBD2Parser.parseOdometer(it) } ?: 0L else 0L

                    LiveLogger.valueChange("speed",   speed.toString(),     threshold = 5.0)
                    LiveLogger.valueChange("rpm",     rpm.toString(),       threshold = 200.0)
                    LiveLogger.valueChange("temp",    engineTemp.toString(), threshold = 2.0)
                    LiveLogger.valueChange("fuel",    fuelLevel.toString(), threshold = 1.0)
                    if (voltage > 0f) LiveLogger.valueChange("voltage", "%.2f".format(voltage), threshold = 0.1)

                    val data = OBD2Data(
                        speed = speed, rpm = rpm, engineTemp = engineTemp,
                        fuelLevel = fuelLevel, fuelRate = OBD2Parser.calculateFuelConsumption(fuelRateLH, speed),
                        batteryVoltage = voltage, odometer = odometer,
                        throttlePos = throttle, isConnected = true
                    )
                    _obd2Data.value = data
                    onDataUpdate?.invoke(data)

                    // Update notification with live speed (unobtrusive)
                    updateNotification(NotifState.RUNNING(speed, fuelLevel))

                } catch (e: Exception) {
                    LiveLogger.error("שגיאת polling: ${e.message}")
                    if (e is IOException) {
                        updateStatus("חיבור אבד", false)
                        updateNotification(NotifState.SEARCHING)
                        disconnectInternal()
                        startWatchdog()   // restart countdown after lost connection
                        break
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
            LiveLogger.pollingActive = false
        }
    }

    // ── Raw command ───────────────────────────────────────────────────

    fun sendRawCommand(command: String, callback: (String) -> Unit) {
        serviceScope.launch {
            val response = sendCommand(command, waitMs = 600, log = true) ?: "NULL"
            withContext(Dispatchers.Main) { callback(response) }
        }
    }

    // ── I/O ───────────────────────────────────────────────────────────

    private fun sendCommand(command: String, waitMs: Long = 300, log: Boolean = true): String? {
        return try {
            if (log) LiveLogger.send(command)
            outputStream?.write("$command\r".toByteArray())
            outputStream?.flush()
            Thread.sleep(waitMs)
            val resp = readResponse()
            if (log) LiveLogger.recv(resp)
            resp
        } catch (e: IOException) {
            LiveLogger.error("שליחה נכשלה [$command]: ${e.message}")
            null
        }
    }

    private fun readResponse(): String {
        val buffer = StringBuilder()
        val buf = ByteArray(1024)
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 1000) {
            val avail = inputStream?.available() ?: 0
            if (avail > 0) {
                val n = inputStream?.read(buf, 0, minOf(avail, buf.size)) ?: 0
                buffer.append(String(buf, 0, n))
                if (buffer.contains(">")) break
            } else Thread.sleep(50)
        }
        return buffer.toString().replace(">", "").trim()
    }

    // ── Disconnect / Shutdown ─────────────────────────────────────────

    private fun disconnectInternal() {
        pollingJob?.cancel(); scanJob?.cancel()
        isInitialized = false; supportedPids.clear()
        try { inputStream?.close(); outputStream?.close(); bluetoothSocket?.close() }
        catch (e: IOException) { Log.e(TAG, "disconnect: ${e.message}") }
        inputStream = null; outputStream = null; bluetoothSocket = null
        _isConnected.value = false; _isScanning.value = false
        _obd2Data.value = OBD2Data(isConnected = false)
        LiveLogger.i("מנותק מה-OBD2")
    }

    fun disconnect() {
        disconnectInternal()
        cancelWatchdog()
    }

    /**
     * Full shutdown — disconnect + stop foreground service.
     * Called by watchdog timeout or explicit stop action.
     */
    private fun shutdown() {
        LiveLogger.i("כיבוי שירות OBD2...")
        disconnectInternal()
        cancelWatchdog()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateStatus(message: String, connected: Boolean) {
        _connectionStatus.value = message
        _isConnected.value = connected
        onStatusUpdate?.invoke(message, connected)
    }

    // ── Notifications ─────────────────────────────────────────────────

    sealed class NotifState {
        object SEARCHING : NotifState()
        data class CONNECTING(val deviceName: String) : NotifState()
        data class CONNECTED(val deviceName: String) : NotifState()
        data class RUNNING(val speed: Int, val fuel: Int) : NotifState()
        data class WATCHDOG(val minutesLeft: Int) : NotifState()
    }

    private fun buildNotification(state: NotifState): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop action button in notification
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, OBD2Service::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val (title, text) = when (state) {
            is NotifState.SEARCHING       -> "OBD2 Monitor" to "מחפש מתאם..."
            is NotifState.CONNECTING      -> "OBD2 Monitor" to "מתחבר ל-${state.deviceName}..."
            is NotifState.CONNECTED       -> "OBD2 Monitor" to "מחובר ל-${state.deviceName} ✓"
            is NotifState.RUNNING         -> "OBD2 🚗 ${state.speed} קמ\"ש" to "דלק: ${state.fuel}%"
            is NotifState.WATCHDOG        -> "OBD2 Monitor" to "ממתין לחיבור — סגירה בעוד ${state.minutesLeft} דק'"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "עצור", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(state: NotifState) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "OBD2 Monitor", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "OBD2 monitoring service"; setShowBadge(false) }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OBD2Monitor::PollingWakeLock"
        ).apply {
            acquire(12 * 60 * 60 * 1000L) // max 12h safety cap
        }
        LiveLogger.d("WakeLock נרכש")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                LiveLogger.d("WakeLock שוחרר")
            }
        }
        wakeLock = null
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectInternal()
        cancelWatchdog()
        releaseWakeLock()
        serviceScope.cancel()
    }
}
