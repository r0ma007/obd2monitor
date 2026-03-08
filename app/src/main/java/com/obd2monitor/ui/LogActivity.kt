package com.obd2monitor.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.obd2monitor.R
import com.obd2monitor.databinding.ActivityLogBinding
import com.obd2monitor.service.LiveLogger
import com.obd2monitor.service.LogEntry
import com.obd2monitor.service.LogLevel
import com.obd2monitor.service.LogMode
import kotlinx.coroutines.launch
import java.io.File

class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private lateinit var adapter: LogAdapter
    private var autoScroll = true
    private var filterLevel: LogLevel? = null  // null = show all

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply { title = "לוג תקשורת OBD2"; setDisplayHomeAsUpEnabled(true) }

        setupRecyclerView()
        setupModeSpinner()
        setupFilterButtons()
        setupActionButtons()
        observeLog()
    }

    private fun setupRecyclerView() {
        adapter = LogAdapter()
        binding.rvLog.apply {
            layoutManager = LinearLayoutManager(this@LogActivity).apply { stackFromEnd = true }
            adapter = this@LogActivity.adapter
            itemAnimator = null // faster updates
        }
    }

    private fun setupModeSpinner() {
        val modes = listOf("QUIET — שינויים ושגיאות בלבד", "NORMAL — אירועים (ברירת מחדל)", "VERBOSE — הכל")
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerMode.adapter = spinnerAdapter

        // Sync to current mode
        binding.spinnerMode.setSelection(LiveLogger.mode.value.ordinal)

        binding.spinnerMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val newMode = LogMode.entries[pos]
                LiveLogger.setMode(newMode)
                Toast.makeText(this@LogActivity, "מצב לוג: ${newMode.name}", Toast.LENGTH_SHORT).show()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupFilterButtons() {
        // Toggle-style level filter buttons
        val filterButtons = mapOf(
            binding.btnFilterAll     to null,
            binding.btnFilterError   to LogLevel.ERROR,
            binding.btnFilterWarn    to LogLevel.WARN,
            binding.btnFilterSuccess to LogLevel.SUCCESS,
            binding.btnFilterComm    to LogLevel.SEND
        )

        filterButtons.forEach { (btn, level) ->
            btn.setOnClickListener {
                filterLevel = level
                // Update button states
                filterButtons.keys.forEach { b -> b.isSelected = false }
                btn.isSelected = true
                applyFilter()
            }
        }
        binding.btnFilterAll.isSelected = true
    }

    private fun setupActionButtons() {
        binding.switchAutoScroll.isChecked = true
        binding.switchAutoScroll.setOnCheckedChangeListener { _, checked -> autoScroll = checked }

        binding.btnClearLog.setOnClickListener {
            LiveLogger.clear()
            Toast.makeText(this, "לוג נוקה", Toast.LENGTH_SHORT).show()
        }

        // Android Share Sheet — the right way to share on Android
        binding.btnShare.setOnClickListener {
            shareLog()
        }

        binding.btnCopyLog.setOnClickListener {
            val text = LiveLogger.exportText()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("OBD2 Log", text))
            Toast.makeText(this, "הועתק (${LiveLogger.entries.value.size} שורות)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeLog() {
        lifecycleScope.launch {
            LiveLogger.entries.collect { entries ->
                val stats = LiveLogger.stats()
                binding.tvLogStats.text = stats

                applyFilter(entries)
            }
        }

        lifecycleScope.launch {
            LiveLogger.mode.collect { mode ->
                binding.spinnerMode.setSelection(mode.ordinal)
            }
        }
    }

    private fun applyFilter(entries: List<LogEntry> = LiveLogger.entries.value) {
        val filtered = when (val f = filterLevel) {
            null -> entries
            LogLevel.SEND -> entries.filter { it.level in listOf(LogLevel.SEND, LogLevel.RECV) }
            else -> entries.filter { it.level == f }
        }

        adapter.submitList(filtered) {
            if (autoScroll && filtered.isNotEmpty()) {
                binding.rvLog.scrollToPosition(filtered.size - 1)
            }
        }
    }

    /**
     * Share log via Android Share Sheet.
     * Writes to a temp file (FileProvider) so any app (Gmail, WhatsApp, Drive) can receive it.
     */
    private fun shareLog() {
        try {
            val logText = LiveLogger.exportText()
            if (logText.isBlank()) {
                Toast.makeText(this, "הלוג ריק", Toast.LENGTH_SHORT).show()
                return
            }

            // Write to cache dir (FileProvider accessible)
            val cacheDir = File(cacheDir, "logs").also { it.mkdirs() }
            val logFile = File(cacheDir, "obd2_log_${System.currentTimeMillis()}.txt")
            logFile.writeText(logText)

            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                logFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "OBD2 Log — ${android.text.format.DateFormat.format("dd/MM/yyyy HH:mm", System.currentTimeMillis())}")
                putExtra(Intent.EXTRA_TEXT, "לוג תקשורת OBD2 מהאפליקציה.\n${LiveLogger.stats()}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "שתף לוג OBD2"))

        } catch (e: Exception) {
            Toast.makeText(this, "שגיאה בשיתוף: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }
}

// ────────────────────────────────────────────────────────────────────────
// RecyclerView Adapter
// ────────────────────────────────────────────────────────────────────────

class LogAdapter : ListAdapter<LogEntry, LogAdapter.ViewHolder>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<LogEntry>() {
            override fun areItemsTheSame(a: LogEntry, b: LogEntry) =
                a.timestamp == b.timestamp && a.message == b.message
            override fun areContentsTheSame(a: LogEntry, b: LogEntry) = a == b
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTimestamp: TextView = view.findViewById(R.id.tvLogTimestamp)
        val tvLevel: TextView = view.findViewById(R.id.tvLogLevel)
        val tvMessage: TextView = view.findViewById(R.id.tvLogMessage)
        val tvSuppressed: TextView = view.findViewById(R.id.tvSuppressed)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_log_entry, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position)
        holder.tvTimestamp.text = entry.timestamp
        holder.tvLevel.text = entry.level.name.take(4)
        holder.tvMessage.text = entry.message

        if (entry.suppressed > 0) {
            holder.tvSuppressed.visibility = View.VISIBLE
            holder.tvSuppressed.text = "×${entry.suppressed + 1}"
        } else {
            holder.tvSuppressed.visibility = View.GONE
        }

        val ctx = holder.itemView.context
        val color = when (entry.level) {
            LogLevel.SUCCESS -> ctx.getColor(R.color.accent_green)
            LogLevel.ERROR   -> ctx.getColor(R.color.accent_red)
            LogLevel.WARN    -> ctx.getColor(R.color.fuel_warning)
            LogLevel.SEND    -> ctx.getColor(R.color.accent_blue)
            LogLevel.RECV    -> ctx.getColor(R.color.text_primary)
            LogLevel.INFO    -> ctx.getColor(R.color.text_secondary)
            LogLevel.DEBUG   -> ctx.getColor(R.color.text_secondary)
        }
        holder.tvMessage.setTextColor(color)
        holder.tvLevel.setTextColor(color)
    }
}
