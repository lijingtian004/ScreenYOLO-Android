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

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_MEDIA_PROJECTION = 1001
        const val REQUEST_OVERLAY = 1002
    }

    private lateinit var spinnerEngine: Spinner
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvModelStatus: TextView

    private var mediaProjectionResultCode: Int = 0
    private var mediaProjectionData: Intent? = null

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
                updateModelStatus()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerEngine = findViewById(R.id.spinnerEngine)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvModelStatus = findViewById(R.id.tvModelStatus)

        // Setup engine spinner
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            EngineType.entries.map { it.displayName }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerEngine.adapter = adapter

        btnStart.setOnClickListener { startDetection() }
        btnStop.setOnClickListener { stopDetection() }

        registerReceiver(
            modelMissingReceiver,
            IntentFilter("com.example.screenyolo.MODEL_MISSING"),
            Context.RECEIVER_EXPORTED
        )

        updateModelStatus()
    }

    private fun updateModelStatus() {
        val engineType = EngineType.fromOrdinal(spinnerEngine.selectedItemPosition)
        tvModelStatus.text = "就绪 | 引擎: ${engineType.displayName}"
    }

    private fun startDetection() {
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
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(modelMissingReceiver)
    }
}
