package com.nordic.tagmobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nordic.tagmobile.databinding.ActivityHistoryBinding
import com.nordic.tagmobile.storage.HistoryEntry
import com.nordic.tagmobile.storage.RecordingStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val adapter = HistoryAdapter(
        onData = {
            if (it.dataFile.exists()) shareFiles(listOf(it.dataFile), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            else Toast.makeText(this, "Data file missing", Toast.LENGTH_SHORT).show()
        },
        onVideo = {
            if (it.videoFile?.exists() == true) shareFiles(listOf(it.videoFile), "video/*")
            else Toast.makeText(this, "Video file missing", Toast.LENGTH_SHORT).show()
        },
        onInfo = {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Session Info")
                .setMessage("Name: ${it.baseName}\nStatus: ${it.status}\nPackets: ${it.packetCount}\nSamples: ${it.sampleCount}\nData: ${if (it.dataFile.exists()) "Yes" else "No"}\nVideo: ${if (it.videoFile?.exists() == true) "Yes" else "No"}")
                .setPositiveButton("OK", null)
                .show()
        },
        onSync = {
            Toast.makeText(this, "Syncing ${it.baseName} to cloud...", Toast.LENGTH_SHORT).show()
        },
        onDelete = { item ->
            val options = arrayOf("Delete Data Only", "Delete Video Only", "Delete Both")
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Delete ${item.baseName}")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> { item.dataFile.delete(); Toast.makeText(this, "Data deleted", Toast.LENGTH_SHORT).show() }
                        1 -> { item.videoFile?.delete(); Toast.makeText(this, "Video deleted", Toast.LENGTH_SHORT).show() }
                        2 -> { RecordingStore.deleteEntry(this, item); Toast.makeText(this, "Deleted entirely", Toast.LENGTH_SHORT).show() }
                    }
                    reload()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backBtn.setOnClickListener { finish() }
        binding.historyList.layoutManager = LinearLayoutManager(this)
        binding.historyList.adapter = adapter
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val items = RecordingStore.listHistory(this)
        adapter.submit(items)
        binding.emptyHistory.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun shareFiles(files: List<java.io.File>, type: String) {
        val uris = ArrayList<android.net.Uri>()
        files.forEach { file ->
            if (!file.exists()) return@forEach
            uris.add(
                FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    file,
                ),
            )
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                this.type = type
                putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                this.type = type
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private class HistoryAdapter(
        private val onData: (HistoryEntry) -> Unit,
        private val onVideo: (HistoryEntry) -> Unit,
        private val onInfo: (HistoryEntry) -> Unit,
        private val onSync: (HistoryEntry) -> Unit,
        private val onDelete: (HistoryEntry) -> Unit,
    ) : RecyclerView.Adapter<HistoryAdapter.Holder>() {

        private var items: List<HistoryEntry> = emptyList()
        private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        fun submit(list: List<HistoryEntry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.title.text = item.baseName
            holder.meta.text =
                "${dateFmt.format(Date(item.savedAtMs))} · " +
                    "Packets: ${item.packetCount} · Samples: ${item.sampleCount}"
            holder.status.text = item.status
            holder.dataBtn.setOnClickListener { onData(item) }
            holder.videoBtn.setOnClickListener { onVideo(item) }
            holder.infoBtn.setOnClickListener { onInfo(item) }
            holder.syncBtn.setOnClickListener { onSync(item) }
            holder.deleteBtn.setOnClickListener { onDelete(item) }
        }

        override fun getItemCount(): Int = items.size

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.itemTitle)
            val meta: TextView = view.findViewById(R.id.itemMeta)
            val status: TextView = view.findViewById(R.id.itemStatus)
            val dataBtn: Button = view.findViewById(R.id.dataBtn)
            val videoBtn: Button = view.findViewById(R.id.videoBtn)
            val infoBtn: Button = view.findViewById(R.id.infoBtn)
            val syncBtn: Button = view.findViewById(R.id.syncBtn)
            val deleteBtn: android.widget.ImageButton = view.findViewById(R.id.deleteBtn)
        }
    }
}
