package com.nordic.tagmobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
    private var selectionMode = false
    private val selected = linkedSetOf<String>()

    private val adapter = HistoryAdapter(
        onData = {
            if (it.dataFile.exists()) {
                val mime = if (it.dataFile.name.endsWith(".xlsx"))
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                else "text/csv"
                shareFiles(listOf(it.dataFile), mime)
            } else Toast.makeText(this, "Data file missing", Toast.LENGTH_SHORT).show()
        },
        onVideo = {
            if (it.videoFile?.exists() == true) shareFiles(listOf(it.videoFile), "video/*")
            else Toast.makeText(this, "Video file missing", Toast.LENGTH_SHORT).show()
        },
        onInfo = {
            AlertDialog.Builder(this)
                .setTitle("Session Info")
                .setMessage(
                    "Session: ${it.baseName}\nStatus: ${it.status}\nPackets: ${it.packetCount}\n" +
                        "Samples: ${it.sampleCount}\nData: ${if (it.dataFile.exists()) "Yes" else "No"}\n" +
                        "Video: ${if (it.videoFile?.exists() == true) "Yes" else "No"}\n" +
                        "Folder: ${it.sessionDir?.name ?: "legacy (flat files)"}",
                )
                .setPositiveButton("OK", null)
                .show()
        },
        onSync = {
            Toast.makeText(this, "Syncing ${it.baseName} to cloud...", Toast.LENGTH_SHORT).show()
        },
        onShare = { item ->
            val files = buildList {
                if (item.dataFile.exists()) add(item.dataFile)
                item.videoFile?.takeIf { it.exists() }?.let { add(it) }
            }
            if (files.isEmpty()) {
                Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
            } else {
                shareFiles(files, "*/*")
            }
        },
        isSelectionMode = { selectionMode },
        isSelected = { selected.contains(it.baseName) },
        onToggleSelect = { item, checked ->
            if (checked) selected.add(item.baseName) else selected.remove(item.baseName)
            updateDeleteHint()
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backBtn.setOnClickListener {
            if (selectionMode) exitSelectionMode() else finish()
        }
        binding.menuBtn.setOnClickListener { showMenu(it) }
        binding.cancelDeleteBtn.setOnClickListener { exitSelectionMode() }
        binding.confirmDeleteBtn.setOnClickListener { confirmPermanentDelete() }
        binding.historyList.layoutManager = LinearLayoutManager(this)
        binding.historyList.adapter = adapter
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.delete))
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> {
                    enterSelectionMode()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun enterSelectionMode() {
        selectionMode = true
        selected.clear()
        binding.deleteBar.visibility = View.VISIBLE
        updateDeleteHint()
        adapter.notifyDataSetChanged()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selected.clear()
        binding.deleteBar.visibility = View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun updateDeleteHint() {
        binding.deleteHint.text = getString(R.string.selected_count, selected.size)
    }

    private fun confirmPermanentDelete() {
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.select_to_delete, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_permanently_title)
            .setMessage(getString(R.string.delete_permanently_message, selected.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                val items = RecordingStore.listHistory(this).filter { selected.contains(it.baseName) }
                items.forEach { RecordingStore.deleteEntry(this, it) }
                Toast.makeText(this, R.string.deleted_permanently, Toast.LENGTH_SHORT).show()
                exitSelectionMode()
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        private val onShare: (HistoryEntry) -> Unit,
        private val isSelectionMode: () -> Boolean,
        private val isSelected: (HistoryEntry) -> Boolean,
        private val onToggleSelect: (HistoryEntry, Boolean) -> Unit,
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
            holder.shareBtn.setOnClickListener { onShare(item) }

            val selecting = isSelectionMode()
            holder.shareBtn.visibility = if (selecting) View.GONE else View.VISIBLE
            holder.selectCheck.visibility = if (selecting) View.VISIBLE else View.GONE
            holder.selectCheck.setOnCheckedChangeListener(null)
            holder.selectCheck.isChecked = isSelected(item)
            holder.selectCheck.setOnCheckedChangeListener { _, checked ->
                onToggleSelect(item, checked)
            }
            holder.itemView.setOnClickListener {
                if (selecting) {
                    holder.selectCheck.isChecked = !holder.selectCheck.isChecked
                }
            }
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
            val shareBtn: ImageButton = view.findViewById(R.id.shareBtn)
            val selectCheck: CheckBox = view.findViewById(R.id.selectCheck)
        }
    }
}
