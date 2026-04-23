package com.example.screenyolo

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class LogActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var btnBack: ImageButton
    private lateinit var btnClear: ImageButton
    private lateinit var btnExport: ImageButton
    private lateinit var adapter: LogAdapter
    private lateinit var logManager: LogManager

    private val pageSize = 100
    private var currentPage = 0
    private var allEntries = listOf<LogAdapter.LogEntry>()
    private var isLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        logManager = LogManager.getInstance(this)

        recyclerView = findViewById(R.id.recyclerViewLogs)
        btnBack = findViewById(R.id.btnBack)
        btnClear = findViewById(R.id.btnClear)
        btnExport = findViewById(R.id.btnExport)

        adapter = LogAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnBack.setOnClickListener { finish() }
        btnClear.setOnClickListener { showClearConfirmDialog() }
        btnExport.setOnClickListener { exportLatestLog() }

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (!isLoading && lastVisible >= adapter.itemCount - 5) {
                    loadMoreEntries()
                }
            }
        })

        loadAllEntries()
    }

    private fun loadAllEntries() {
        val files = logManager.getLogFiles()
        if (files.isEmpty()) {
            return
        }

        val parsedEntries = mutableListOf<LogAdapter.LogEntry>()
        files.forEach { file ->
            val content = logManager.readLog(file)
            content.lineSequence().forEach { line ->
                val entry = parseLogLine(line)
                if (entry != null) {
                    parsedEntries.add(entry)
                }
            }
        }

        allEntries = parsedEntries.reversed()
        currentPage = 0
        adapter.clear()
        loadMoreEntries()
    }

    private fun parseLogLine(line: String): LogAdapter.LogEntry? {
        if (line.isBlank()) return null
        val regex = Regex("""^\[(\d{2}:\d{2}:\d{2}\.\d{3})\]\s*\[(\w+)\]\s*(.*)$""")
        val match = regex.find(line.trim())
        return if (match != null) {
            val (_, time, tag, message) = match.groupValues
            LogAdapter.LogEntry(time, tag, message)
        } else {
            LogAdapter.LogEntry("", "RAW", line)
        }
    }

    private fun loadMoreEntries() {
        if (isLoading) return
        isLoading = true

        val start = currentPage * pageSize
        if (start >= allEntries.size) {
            isLoading = false
            return
        }

        val end = minOf(start + pageSize, allEntries.size)
        val pageEntries = allEntries.subList(start, end)
        adapter.addEntries(pageEntries)
        currentPage++
        isLoading = false
    }

    private fun showClearConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("确认清空")
            .setMessage("确定要删除所有日志文件吗？")
            .setPositiveButton("清空") { _, _ ->
                logManager.clearLogs()
                adapter.clear()
                allEntries = emptyList()
                currentPage = 0
                Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportLatestLog() {
        val uri = logManager.exportLatestLog()
        if (uri == null) {
            Toast.makeText(this, "没有可导出的日志", Toast.LENGTH_SHORT).show()
            return
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "导出日志"))
    }
}
