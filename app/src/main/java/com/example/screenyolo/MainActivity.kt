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
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream

/**
 * 主界面 Activity
 * 负责模型导入、权限申请、类别过滤设置和检测控制
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_MEDIA_PROJECTION = 1001
        const val REQUEST_OVERLAY = 1002
        const val REQUEST_PICK_MODEL = 1003
        const val PREFS_NAME = "ScreenYOLO_Prefs"
        const val PREF_ENABLED_CLASSES = "enabled_classes"
    }

    private lateinit var btnPickModel: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnFilter: Button
    private lateinit var tvModelStatus: TextView
    private lateinit var tvFilterStatus: TextView

    private var mediaProjectionResultCode: Int = 0
    private var mediaProjectionData: Intent? = null

    // 当前启用的类别集合
    private var enabledClasses: MutableSet<String> = mutableSetOf()

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
                Toast.makeText(context, "未找到模型，请先导入模型", Toast.LENGTH_LONG).show()
                updateModelStatus()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnPickModel = findViewById(R.id.btnPickModel)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnFilter = findViewById(R.id.btnFilter)
        tvModelStatus = findViewById(R.id.tvModelStatus)
        tvFilterStatus = findViewById(R.id.tvFilterStatus)

        btnPickModel.setOnClickListener { pickModelFile() }
        btnStart.setOnClickListener { startDetection() }
        btnStop.setOnClickListener { stopDetection() }
        btnFilter.setOnClickListener { showClassFilterDialog() }

        registerReceiver(modelMissingReceiver, IntentFilter("com.example.screenyolo.MODEL_MISSING"),
            Context.RECEIVER_EXPORTED)

        // 加载保存的类别过滤设置
        loadEnabledClasses()
        updateModelStatus()
        updateFilterStatus()
    }

    /**
     * 更新模型状态显示
     * 支持 TFLite (.tflite) 和 ncnn (.param + .bin) 格式
     */
    private fun updateModelStatus() {
        // 检查 TFLite 模型
        val tfliteFile = File(filesDir, "model.tflite")
        if (tfliteFile.exists()) {
            tvModelStatus.text = "模型已导入: TFLite (${tfliteFile.length() / 1024} KB)"
            btnStart.isEnabled = true
            return
        }

        // 检查 ncnn 模型
        val paramFile = File(filesDir, "model.param")
        val binFile = File(filesDir, "model.bin")
        if (paramFile.exists() && binFile.exists()) {
            val totalSize = (paramFile.length() + binFile.length()) / 1024
            tvModelStatus.text = "模型已导入: ncnn (${totalSize} KB)"
            btnStart.isEnabled = true
            return
        }

        tvModelStatus.text = "未导入模型"
        btnStart.isEnabled = false
    }

    /**
     * 更新类别过滤状态显示
     */
    private fun updateFilterStatus() {
        if (enabledClasses.isEmpty()) {
            tvFilterStatus.text = "检测类别: 全部 (${Detector.LABELS.size} 类)"
        } else {
            tvFilterStatus.text = "检测类别: ${enabledClasses.size} 类 (点击修改)"
        }
    }

    /**
     * 显示类别过滤选择对话框
     */
    private fun showClassFilterDialog() {
        val labels = Detector.LABELS
        val checkedItems = BooleanArray(labels.size) { index ->
            enabledClasses.isEmpty() || enabledClasses.contains(labels[index])
        }

        AlertDialog.Builder(this)
            .setTitle("选择要检测的类别")
            .setMultiChoiceItems(labels, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("确定") { _, _ ->
                enabledClasses.clear()
                for (i in labels.indices) {
                    if (checkedItems[i]) {
                        enabledClasses.add(labels[i])
                    }
                }
                // 如果全选，则清空集合表示不过滤
                if (enabledClasses.size == labels.size) {
                    enabledClasses.clear()
                }
                saveEnabledClasses()
                updateFilterStatus()
                Toast.makeText(this, "类别过滤已更新", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("全选") { dialog, _ ->
                // 重新创建对话框并全选
                dialog.dismiss()
                enabledClasses.clear()
                saveEnabledClasses()
                updateFilterStatus()
                Toast.makeText(this, "已选择全部类别", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * 保存类别过滤设置到 SharedPreferences
     */
    private fun saveEnabledClasses() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(PREF_ENABLED_CLASSES, enabledClasses)
            .apply()
    }

    /**
     * 从 SharedPreferences 加载类别过滤设置
     */
    private fun loadEnabledClasses() {
        val saved = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(PREF_ENABLED_CLASSES, emptySet())
        enabledClasses.clear()
        enabledClasses.addAll(saved ?: emptySet())
    }

    /**
     * 选择模型文件
     * 支持 TFLite (.tflite) 和 ncnn (.param / .bin) 格式
     */
    private fun pickModelFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/octet-stream",
                "application/tflite",
                "text/plain"  // .param 文件通常是 text/plain
            ))
        }
        startActivityForResult(intent, REQUEST_PICK_MODEL)
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
                    val intent = Intent(this, ScreenCaptureService::class.java).apply {
                        putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(ScreenCaptureService.EXTRA_DATA, data)
                        // 传递类别过滤设置
                        putExtra(ScreenCaptureService.EXTRA_ENABLED_CLASSES, ArrayList(enabledClasses))
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    Toast.makeText(this, "检测已启动", Toast.LENGTH_SHORT).show()
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

    /**
     * 从 URI 复制模型文件到应用目录
     * 支持 TFLite (.tflite) 和 ncnn (.param / .bin) 格式
     */
    private fun copyModelFromUri(uri: Uri?) {
        if (uri == null) return

        try {
            // 获取文件名
            val fileName = getFileNameFromUri(uri)

            when {
                // TFLite 模型
                fileName.endsWith(".tflite", ignoreCase = true) -> {
                    copyFile(uri, "model.tflite")
                    Toast.makeText(this, "TFLite 模型导入成功", Toast.LENGTH_SHORT).show()
                }
                // ncnn param 文件
                fileName.endsWith(".param", ignoreCase = true) -> {
                    copyFile(uri, "model.param")
                    Toast.makeText(this, "ncnn param 文件导入成功，请继续选择 .bin 文件", Toast.LENGTH_LONG).show()
                }
                // ncnn bin 文件
                fileName.endsWith(".bin", ignoreCase = true) -> {
                    copyFile(uri, "model.bin")
                    Toast.makeText(this, "ncnn bin 文件导入成功", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    Toast.makeText(this, "不支持的文件格式: $fileName", Toast.LENGTH_LONG).show()
                    return
                }
            }

            updateModelStatus()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 从 URI 获取文件名
     */
    private fun getFileNameFromUri(uri: Uri): String {
        var result = ""
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    result = cursor.getString(index) ?: ""
                }
            }
        }
        return result
    }

    /**
     * 复制文件到应用目录
     */
    private fun copyFile(uri: Uri, destFileName: String) {
        contentResolver.openInputStream(uri)?.use { input ->
            val outFile = File(filesDir, destFileName)
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(modelMissingReceiver)
    }
}
