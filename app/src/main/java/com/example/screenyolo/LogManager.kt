package com.example.screenyolo

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

class LogManager private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: LogManager? = null

        fun getInstance(context: Context): LogManager {
            return instance ?: synchronized(this) {
                instance ?: LogManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val appContext = context.applicationContext
    private val logDir: File = appContext.getExternalFilesDir(null) ?: appContext.filesDir
    private val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val executor = Executors.newSingleThreadExecutor()
    private val writeQueue = ConcurrentLinkedQueue<String>()

    init {
        ensureLogDirExists()
    }

    private fun ensureLogDirExists() {
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
    }

    private fun getCurrentLogFile(): File {
        ensureLogDirExists()
        val dateStr = dateFormat.format(Date())
        return File(logDir, "screenyolo_log_$dateStr.txt")
    }

    fun logEvent(tag: String, message: String) {
        val timestamp = timeFormat.format(Date())
        val line = "[$timestamp] [$tag] $message\n"
        executor.execute {
            try {
                val file = getCurrentLogFile()
                file.appendText(line)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getLogFiles(): List<File> {
        ensureLogDirExists()
        return logDir.listFiles { _, name ->
            name.startsWith("screenyolo_log_") && name.endsWith(".txt")
        }?.sortedByDescending { it.name } ?: emptyList()
    }

    fun readLog(file: File): String {
        return if (file.exists() && file.canRead()) {
            file.readText()
        } else {
            ""
        }
    }

    fun clearLogs() {
        executor.execute {
            getLogFiles().forEach { file ->
                try {
                    file.delete()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun exportLatestLog(): Uri? {
        val latestFile = getLogFiles().firstOrNull() ?: return null
        return try {
            FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                latestFile
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
