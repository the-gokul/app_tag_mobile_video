package com.nordic.tagmobile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nordic.tagmobile.databinding.ActivityLogViewerBinding
import com.nordic.tagmobile.log.TagLogger

class LogViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogViewerBinding
    private val adapter = LogAdapter()
    private val listener: (String) -> Unit = { line ->
        runOnUiThread {
            adapter.append(line)
            binding.logList.scrollToPosition(adapter.itemCount - 1)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backBtn.setOnClickListener { finish() }
        binding.logList.layoutManager = LinearLayoutManager(this)
        binding.logList.adapter = adapter
        adapter.setAll(TagLogger.snapshot())
        if (adapter.itemCount > 0) {
            binding.logList.scrollToPosition(adapter.itemCount - 1)
        }
        TagLogger.addListener(listener)
    }

    override fun onDestroy() {
        TagLogger.removeListener(listener)
        super.onDestroy()
    }

    private class LogAdapter : RecyclerView.Adapter<LogAdapter.Holder>() {
        private val items = mutableListOf<String>()

        fun setAll(lines: List<String>) {
            items.clear()
            items.addAll(lines)
            notifyDataSetChanged()
        }

        fun append(line: String) {
            items.add(line)
            notifyItemInserted(items.size - 1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val tv = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
            tv.setTextIsSelectable(true)
            tv.textSize = 11f
            tv.setTextColor(0xFF111827.toInt())
            return Holder(tv)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.text.text = items[position]
        }

        override fun getItemCount(): Int = items.size

        class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
    }
}
