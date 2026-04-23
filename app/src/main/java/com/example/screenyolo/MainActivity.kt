package com.example.screenyolo

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.screenyolo.engine.EngineType
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_MEDIA_PROJECTION = 1001
        const val REQUEST_OVERLAY = 1002
        const val REQUEST_PICK_MODEL = 1003
    }

    private lateinit var spinnerEngine: Spinner
    private lateinit var btnPickModel: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvModelStatus: TextView

    private var mediaProjectionResultCode: Int = 0
    private var mediaProjectionData: Intent? = null
    private var customModelFile: File? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            checkOverlayPermission()
        } else {
            Toast.makeText(this, "需要权限才能运行", Toast.LENGTH_LONG).show()
        }
    }

    private val modelMissingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runOnUiThread {
                Toast.makeText(context, "模型加载失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerEngine = findViewById(R.id.spinnerEngine)
        btnPickModel = findViewById(R.id.btnPickModel)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvModelStatus = findViewById(R.id.tvModelStatus)

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            EngineType.entries.map { it.displayName }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerEngine.adapter = adapter

        btnPickModel.setOnClickListener { pickModelFile() }
        btnStart.setOnClickListener { startDetection() }
        btnStop.setOnClickListener { stopDetection() }

        registerReceiver(
            modelMissingReceiver,
            IntentFilter("com.example.screenyolo.MODEL_MISSING"),
            Context.RECEIVER_EXPORTED
        )

        refreshModelStatus()
    }

    private fun refreshModelStatus() {
        val engineType = EngineType.fromOrdinal(spinnerEngine.selectedItemPosition)
        if (engineType == EngineType.NCNN) {
            val dir = File(filesDir, "ncnn_model")
            val hasParam = dir.listFiles { _, name -> name.endsWith(".param") }?.isNotEmpty() == true
            val hasBin = dir.listFiles { _, name -> name.endsWith(".bin") }?.isNotEmpty() == true
            if (hasParam && hasBin) {
                tvModelStatus.text = "NCNN 模型已导入"
                btnStart.isEnabled = true
            } else {
                tvModelStatus.text = "未导入 NCNN 模型 (.zip)"
                btnStart.isEnabled = false
            }
        } else {
            customModelFile = File(filesDir, "custom_model")
            if (customModelFile?.exists() == true) {
                val size = customModelFile?.length()?.div(1024) ?: 0
                tvModelStatus.text = "已导入: ${customModelFile?.name} (${if (size < 1024) "${size}KB" else "${size / 1024}MB"})"
                btnStart.isEnabled = true
            } else {
                tvModelStatus.text = "未导入模型"
                btnStart.isEnabled = false
            }
        }
    }

    private fun pickModelFile() {
        val engineType = EngineType.fromOrdinal(spinnerEngine.selectedItemPosition)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            val mimeTypes = if (engineType == EngineType.NCNN) {
                arrayOf("application/zip", "application/x-zip-compressed")
            } else {
                arrayOf("application/octet-stream", "application/tflite")
            }
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        }
        startActivityForResult(intent, REQUEST_PICK_MODEL)
    }

    private fun startDetection() {
        val engineType = EngineType.fromOrdinal(spinnerEngine.selectedItemPosition)
        val modelReady = if (engineType == EngineType.NCNN) {
            val dir = File(filesDir, "ncnn_model")
            dir.listFiles { _, name -> name.endsWith(".param") }?.isNotEmpty() == true &&
            dir.listFiles { _, name -> name.endsWith(".bin") }?.isNotEmpty() == true
        } else {
            File(filesDir, "custom_model").exists()
        }
        if (!modelReady) {
            Toast.makeText(this, "请先导入模型文件", Toast.LENGTH_SHORT).show()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_OVERLAY)
            return
        }

        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(android.Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION)
            }
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
            return
        }

        requestMediaProjection()
    }

    private fun requestMediaProjection() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_OVERLAY)
        } else {
            requestMediaProjection()
        }
    }

    private fun stopDetection() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        Toast.makeText(this, "检测已停止", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_MEDIA_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    mediaProjectionResultCode = resultCode
                    mediaProjectionData = data
                    val engineType = EngineType.fromOrdinal(spinnerEngine.selectedItemPosition)
                    val intent = Intent(this, ScreenCaptureService::class.java).apply {
                        putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(ScreenCaptureService.EXTRA_DATA, data)
                        putExtra(ScreenCaptureService.EXTRA_ENGINE_TYPE, engineType.ordinal)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    Toast.makeText(this, "检测已启动 [${engineType.displayName}]", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "需要录屏权限才能运行", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) {
                    requestMediaProjection()
                } else {
                    Toast.makeText(this, "需要悬浮窗权限", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_PICK_MODEL -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    copyModelFromUri(data.data)
                }
            }
        }
    }

    private fun copyModelFromUri(uri: Uri?) {
        if (uri == null) return
        val engineType = EngineType.fromOrdinal(spinnerEngine.selectedItemPosition)
        try {
            if (engineType == EngineType.NCNN) {
                // Extract zip to ncnn_model/
                val dir = File(filesDir, "ncnn_model")
                dir.deleteRecursively()
                dir.mkdirs()
                contentResolver.openInputStream(uri)?.use { input ->
                    java.util.zip.ZipInputStream(input).use { zis ->
                        var entry: java.util.zip.ZipEntry?
                        while (zis.nextEntry.also { entry = it } != null) {
                            entry ?: continue
                            val outFile = File(dir, entry!!.name)
                            if (entry!!.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { fos ->
                                    zis.copyTo(fos)
                                }
                            }
                            zis.closeEntry()
                        }
                    }
                }
            } else {
                contentResolver.openInputStream(uri)?.use { input ->
                    val outFile = File(filesDir, "custom_model")
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            Toast.makeText(this, "模型导入成功", Toast.LENGTH_SHORT).show()
            refreshModelStatus()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(modelMissingReceiver)
    }
}
