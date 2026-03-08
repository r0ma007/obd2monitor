package com.obd2monitor.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.obd2monitor.R
import com.obd2monitor.databinding.ActivityDiagnosticBinding
import com.obd2monitor.service.OBD2Service
import com.obd2monitor.service.PidScanResult
import kotlinx.coroutines.launch

class DiagnosticActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticBinding
    private var obd2Service: OBD2Service? = null
    private var isBound = false
    private lateinit var adapter: PidResultAdapter

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            obd2Service = (binder as OBD2Service.OBD2Binder).getService()
            isBound = true
            observeService()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            obd2Service = null; isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply { title = "סריקת PIDs"; setDisplayHomeAsUpEnabled(true) }

        adapter = PidResultAdapter()
        binding.rvPidResults.layoutManager = LinearLayoutManager(this)
        binding.rvPidResults.adapter = adapter

        bindService(Intent(this, OBD2Service::class.java), serviceConnection, Context.BIND_AUTO_CREATE)

        binding.btnStartScan.setOnClickListener {
            val svc = obd2Service ?: run {
                Toast.makeText(this, "לא מחובר ל-OBD2", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (svc.isScanning.value) {
                svc.stopScan()
            } else {
                binding.tvScanStatus.text = "סורק..."
                svc.startPidScan()
            }
        }

        binding.btnOpenLog.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
    }

    private fun observeService() {
        val svc = obd2Service ?: return

        lifecycleScope.launch {
            svc.isScanning.collect { scanning ->
                binding.btnStartScan.text = if (scanning) "עצור סריקה" else "סרוק PIDs"
                binding.progressBar.visibility = if (scanning) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launch {
            svc.scanProgress.collect { progress ->
                binding.progressBar.progress = progress
                if (progress > 0 && progress < 100)
                    binding.tvScanStatus.text = "סורק... $progress%"
            }
        }

        lifecycleScope.launch {
            svc.scanResults.collect { results ->
                val supported = results.count { it.supported }
                val total = results.size

                if (results.isEmpty()) {
                    binding.tvScanStatus.text = "לחץ 'סרוק PIDs' לגלות מה הרכב תומך"
                    binding.tvSupportedCount.visibility = View.GONE
                } else {
                    binding.tvSupportedCount.visibility = View.VISIBLE
                    binding.tvSupportedCount.text = "$supported נתמכים מתוך $total שנבדקו"
                    if (!svc.isScanning.value) {
                        binding.tvScanStatus.text = "סריקה הסתיימה ✓"
                    }
                }

                // Show only supported PIDs by default (toggle shows all)
                val filtered = if (binding.switchShowAll.isChecked) results else results.filter { it.supported }
                adapter.submitList(filtered)
            }
        }

        binding.switchShowAll.setOnCheckedChangeListener { _, _ ->
            lifecycleScope.launch {
                val results = svc.scanResults.value
                val filtered = if (binding.switchShowAll.isChecked) results else results.filter { it.supported }
                adapter.submitList(filtered)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) { unbindService(serviceConnection); isBound = false }
    }
}

// ────────────────────────────────────────────
// RecyclerView Adapter
// ────────────────────────────────────────────

class PidResultAdapter : ListAdapter<PidScanResult, PidResultAdapter.ViewHolder>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<PidScanResult>() {
            override fun areItemsTheSame(a: PidScanResult, b: PidScanResult) = a.pidInfo.pid == b.pidInfo.pid
            override fun areContentsTheSame(a: PidScanResult, b: PidScanResult) = a == b
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvPid: TextView = view.findViewById(R.id.tvPid)
        val tvName: TextView = view.findViewById(R.id.tvPidName)
        val tvValue: TextView = view.findViewById(R.id.tvPidValue)
        val tvRaw: TextView = view.findViewById(R.id.tvPidRaw)
        val tvTime: TextView = view.findViewById(R.id.tvResponseTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_pid_result, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.tvPid.text = item.pidInfo.pid
        holder.tvName.text = "${item.pidInfo.name}\n${item.pidInfo.nameEn}"
        holder.tvValue.text = item.parsedValue
        holder.tvRaw.text = "RAW: ${item.rawResponse.take(40)}"
        holder.tvTime.text = "${item.responseTimeMs}ms"

        val ctx = holder.itemView.context
        if (item.supported) {
            holder.tvPid.setTextColor(ctx.getColor(R.color.accent_green))
            holder.tvValue.setTextColor(ctx.getColor(R.color.text_primary))
            holder.itemView.alpha = 1f
        } else {
            holder.tvPid.setTextColor(ctx.getColor(R.color.text_secondary))
            holder.tvValue.setTextColor(ctx.getColor(R.color.text_secondary))
            holder.itemView.alpha = 0.5f
        }
    }
}
