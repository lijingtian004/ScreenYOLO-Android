package com.example.screenyolo

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class LogAdapter : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    data class LogEntry(
        val time: String,
        val tag: String,
        val message: String
    )

    private val entries = mutableListOf<LogEntry>()

    fun addEntries(newEntries: List<LogEntry>) {
        val startPos = entries.size
        entries.addAll(newEntries)
        notifyItemRangeInserted(startPos, newEntries.size)
    }

    fun clear() {
        val size = entries.size
        entries.clear()
        notifyItemRangeRemoved(0, size)
    }

    fun getAllEntries(): List<LogEntry> = entries.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log_entry, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount(): Int = entries.size

    class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val tvTag: TextView = itemView.findViewById(R.id.tvTag)
        private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)

        fun bind(entry: LogEntry) {
            tvTime.text = entry.time
            tvTag.text = entry.tag
            tvMessage.text = entry.message

            val tagColor = when (entry.tag.uppercase()) {
                "INFO" -> Color.parseColor("#4CAF50")
                "ERROR" -> Color.parseColor("#F44336")
                "WARN" -> Color.parseColor("#FFC107")
                "PERF" -> Color.parseColor("#2196F3")
                else -> Color.parseColor("#FFFFFF")
            }
            tvTag.setTextColor(tagColor)
        }
    }
}
