# ScreenYOLO - 手机屏幕实时物体检测

## 这是什么？

一个 Android App，可以实时捕捉你的手机屏幕，并用 YOLO 模型检测屏幕上的物体（人、手机、椅子、猫狗等 80 类），在屏幕上画出绿色检测框。

## 需要什么？

1. 一台 Windows 电脑
2. Android Studio（免费，官网下载）
3. 一部 Android 手机（Android 10 以上）
4. 一个 YOLO TFLite 模型文件（.tflite）

## 第一步：获取 YOLO 模型

模型需要你自己准备，因为模型文件比较大（约 12MB），不适合打包进代码。

### 方式 A：让别人帮你导出（最简单）

把下面这段文字发给我（或任何懂代码的人）：

> 帮我导出 yolov8n.tflite 模型文件，我需要安卓用的。

对方会用 Python 运行：
```bash
pip install ultralytics
yolo export model=yolov8n.pt format=tflite
```

然后会把生成的 `yolov8n_float32.tflite` 文件发给你。

### 方式 B：自己用 Windows 电脑导出

1. 安装 Python（官网 python.org，下载 3.10+，安装时勾选 "Add to PATH"）
2. 打开 CMD 或 PowerShell，运行：
```bash
pip install ultralytics
python -c "from ultralytics import YOLO; YOLO('yolov8n.pt').export(format='tflite')"
```
3. 等待几分钟，在文件夹里找到 `yolov8n_float32.tflite`

### 方式 C：直接下载别人导好的

在网上搜索 "yolov8n tflite download"，有些论坛或网盘会分享现成的文件。

## 第二步：用 Android Studio 打开项目

1. 下载本项目压缩包，解压到桌面
2. 安装 Android Studio（https://developer.android.com/studio）
3. 打开 Android Studio → "Open" → 选择解压后的 `ScreenYOLO` 文件夹
4. 等待 Gradle 同步（第一次会下载一些东西，约 5-10 分钟）
5. 如果有提示升级 Gradle 或 AGP，点 "Upgrade"

## 第三步：编译并安装到手机

1. 用 USB 线连接手机和电脑
2. 手机上开启"开发者选项"和"USB 调试"（设置 → 关于手机 → 连续点版本号 7 次 → 返回 → 开发者选项 → 打开 USB 调试）
3. Android Studio 顶部工具栏选择你的手机设备
4. 点击绿色三角形 "Run" 按钮
5. 等待编译（第一次约 5-10 分钟），App 会自动安装到手机

## 第四步：使用 App

1. 手机上打开 ScreenYOLO
2. 点击 "选择模型文件 (.tflite)"，从文件管理器里选择你准备好的 `yolov8n_float32.tflite`
3. 点击 "开始屏幕检测"
4. 按提示授予三个权限：
   - **录屏权限**（系统弹窗，点"允许"）
   - **悬浮窗权限**（跳转到设置，找到 ScreenYOLO，打开"允许显示在其他应用上层"）
   - **通知权限**（用于前台服务，保持检测在后台运行）
5. 回到 App，再次点击 "开始屏幕检测"
6. 现在你可以打开任何 App 或游戏，屏幕上会实时显示绿色检测框
7. 想停止时，回到 ScreenYOLO，点击 "停止检测"

## 常见问题

**Q: 检测框位置不准？**
A: 这是正常的。YOLO 模型输入是 640x640 正方形，手机屏幕是长方形，图像被拉伸了。检测大物体没问题，小物体可能偏移。

**Q: 很卡？**
A: YOLOv8-nano 在旗舰手机上约 5-10 FPS，中低端手机可能更慢。这是手机 CPU/GPU 性能限制。

**Q: 可以检测游戏里的敌人吗？**
A: 可以，但前提是"敌人"属于 COCO 80 类中的某一类（比如 person）。游戏角色通常被识别为 person。

**Q: 我想换其他模型？**
A: 只要是 YOLOv8/YOLOv5/YOLO11 导出的 TFLite 模型（80 类 COCO），格式兼容就可以用。把模型文件导入即可。

## 技术说明

- 最小 Android 版本：Android 10 (API 29)
- 使用 MediaProjection API 捕获屏幕
- 使用 TensorFlow Lite 推理
- 悬浮窗用 WindowManager + TYPE_APPLICATION_OVERLAY
- 推理帧率限制在 5 FPS，避免过热和卡顿
