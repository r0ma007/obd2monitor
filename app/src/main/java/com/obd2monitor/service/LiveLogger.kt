package com.obd2monitor.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

enum class LogLevel { DEBUG, INFO, SEND, RECV, WARN, ERROR, SUCCESS }

/**
 * Log mode controls how verbose the logger is.
 *
 * QUIET   – only WARN / ERROR / SUCCESS + significant value changes
 * NORMAL  – events + connection lifecycle, no per-poll noise
 * VERBOSE – everything including every send/recv during polling
 */
enum class LogMode { QUIET, NORMAL, VERBOSE }

data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val message: String,
    val raw: String? = null,
    val suppressed: Int = 0   // how many identical messages were collapsed into this one
)

/**
 * Smart singleton logger for OBD2 BT communication.
 *
 * Features:
 *  - Three verbosity modes (QUIET / NORMAL / VERBOSE)
 *  - De-duplication: consecutive identical messages are collapsed
 *  - Ring buffer with configurable size (default 600)
 *  - Polling suppression: routine send/recv during polling never shown unless VERBOSE
 *  - Full export as plain text for sharing
 */
object LiveLogger {

    private const val DEFAULT_MAX = 600
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    // ── public state ────────────────────────────────────────────────

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    private val _mode = MutableStateFlow(LogMode.NORMAL)
    val mode: StateFlow<LogMode> = _mode

    // ── internal state ───────────────────────────────────────────────

    private val buffer = CopyOnWriteArrayList<LogEntry>()
    private var maxEntries = DEFAULT_MAX

    /** True while the polling loop is running — suppresses routine send/recv */
    var pollingActive = false

    // de-dup tracking
    private var lastMessage = ""
    private var lastLevel = LogLevel.DEBUG
    private var suppressCount = 0

    // last emitted data snapshot for change-detection
    private val lastValues = mutableMapOf<String, String>()

    // ── public API ───────────────────────────────────────────────────

    fun setMode(m: LogMode) { _mode.value = m }
    fun setMaxEntries(n: Int) { maxEntries = n.coerceIn(50, 5000) }

    /** Core log — respects mode and dedup */
    fun log(level: LogLevel, message: String, raw: String? = null, forceShow: Boolean = false) {
        val mode = _mode.value

        // Filter by mode
        val show = forceShow || when (mode) {
            LogMode.QUIET   -> level in listOf(LogLevel.WARN, LogLevel.ERROR, LogLevel.SUCCESS)
            LogMode.NORMAL  -> level != LogLevel.DEBUG &&
                               !(pollingActive && level in listOf(LogLevel.SEND, LogLevel.RECV))
            LogMode.VERBOSE -> true
        }
        if (!show) return

        // De-duplication: collapse identical consecutive messages
        if (message == lastMessage && level == lastLevel) {
            suppressCount++
            // Update the last entry's suppressed count in-place
            if (buffer.isNotEmpty()) {
                val updated = buffer.last().copy(
                    suppressed = suppressCount,
                    timestamp = fmt.format(Date())
                )
                buffer[buffer.size - 1] = updated
                _entries.value = buffer.toList()
            }
            return
        }

        lastMessage = message
        lastLevel = level
        suppressCount = 0

        val entry = LogEntry(
            timestamp = fmt.format(Date()),
            level = level,
            message = message,
            raw = raw
        )

        buffer.add(entry)

        // Trim to max size — remove oldest 10% when full for efficiency
        if (buffer.size > maxEntries) {
            val trimCount = (maxEntries * 0.1).toInt().coerceAtLeast(1)
            repeat(trimCount) { if (buffer.isNotEmpty()) buffer.removeAt(0) }
        }

        _entries.value = buffer.toList()
    }

    // ── convenience methods ──────────────────────────────────────────

    fun d(msg: String) = log(LogLevel.DEBUG, msg)
    fun i(msg: String) = log(LogLevel.INFO, msg)
    fun warn(msg: String) = log(LogLevel.WARN, msg, forceShow = true)
    fun error(msg: String) = log(LogLevel.ERROR, msg, forceShow = true)
    fun success(msg: String) = log(LogLevel.SUCCESS, msg, forceShow = true)

    fun send(cmd: String) = log(LogLevel.SEND, "→ $cmd")
    fun recv(response: String, raw: String? = null) = log(LogLevel.RECV, "← ${response.take(100)}", raw)

    /**
     * Log a sensor value only when it changes beyond a threshold.
     * Use this during normal polling to log significant events.
     */
    fun valueChange(key: String, value: String, threshold: Double = 0.0) {
        val prev = lastValues[key]
        val changed = when {
            prev == null -> true
            threshold > 0.0 -> {
                val pv = prev.toDoubleOrNull() ?: return
                val nv = value.toDoubleOrNull() ?: return
                Math.abs(nv - pv) >= threshold
            }
            else -> prev != value
        }
        if (changed) {
            lastValues[key] = value
            log(LogLevel.INFO, "[$key] $prev → $value", forceShow = _mode.value != LogMode.QUIET)
        }
    }

    // ── export ───────────────────────────────────────────────────────

    fun clear() {
        buffer.clear()
        lastValues.clear()
        lastMessage = ""
        suppressCount = 0
        _entries.value = emptyList()
    }

    /**
     * Full export as plain text — suitable for email/share.
     * Includes a header with timestamp and stats.
     */
    fun exportText(): String {
        val sb = StringBuilder()
        sb.appendLine("╔══════════════════════════════════════╗")
        sb.appendLine("║        OBD2 Monitor — Log Export     ║")
        sb.appendLine("╚══════════════════════════════════════╝")
        sb.appendLine("Generated : ${dateFmt.format(Date())}")
        sb.appendLine("Mode      : ${_mode.value.name}")
        sb.appendLine("Entries   : ${buffer.size}")
        sb.appendLine("─".repeat(50))

        buffer.forEach { entry ->
            val suppStr = if (entry.suppressed > 0) " (×${entry.suppressed + 1})" else ""
            sb.appendLine("[${entry.timestamp}] [${entry.level.name.padEnd(7)}] ${entry.message}$suppStr")
            if (entry.raw != null) sb.appendLine("  RAW: ${entry.raw}")
        }

        sb.appendLine("─".repeat(50))
        sb.appendLine("End of log — ${buffer.size} entries")
        return sb.toString()
    }

    /** Summary stats for UI display */
    fun stats(): String {
        val errors = buffer.count { it.level == LogLevel.ERROR }
        val warns = buffer.count { it.level == LogLevel.WARN }
        return "${buffer.size} entries | $errors errors | $warns warnings"
    }
}
