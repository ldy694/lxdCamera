package com.lxd.camera.library

import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Camera callback interface for block-based callbacks
interface CameraCallback {
    fun onSuccess(uri: Uri)
    fun onError(message: String, exception: Exception? = null)
}

// 简化的回调接口，用于 UTS 插件，避免类型导入问题
interface SimpleCameraCallback {
    fun onSuccess(uriString: String)
    fun onError(errorMessage: String)
}

class CustomCameraActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private var mode = "photo"
    private var videoDurationLimit = 60 // 默认60秒

    // 静态变量用于存储回调
    companion object {
        private var cameraCallback: CameraCallback? = null

        // 静态方法，用于从外部启动相机并设置回调
        @JvmStatic
        fun startCamera(
                activity: ComponentActivity,
                mode: String = "photo",
                callback: CameraCallback,
                videoDurationLimit: Int = 60
        ) {
            cameraCallback = callback
            val intent = Intent(activity, CustomCameraActivity::class.java)
            intent.putExtra("mode", mode)
            intent.putExtra("videoDurationLimit", videoDurationLimit)
            activity.startActivity(intent)
        }

        // UTS 插件专用的启动方法，使用简化的回调接口
        @JvmStatic
        fun startCameraWithSimpleCallback(
                activity: ComponentActivity,
                mode: String = "photo",
                callback: SimpleCameraCallback,
                videoDurationLimit: Int = 60
        ) {
            // 将 SimpleCameraCallback 适配为 CameraCallback
            val adaptedCallback =
                    object : CameraCallback {
                        override fun onSuccess(uri: Uri) {
                            callback.onSuccess(uri.toString())
                        }

                        override fun onError(message: String, exception: Exception?) {
                            val errorMsg =
                                    if (exception != null) {
                                        "$message: ${exception.message ?: exception.toString()}"
                                    } else {
                                        message
                                    }
                            callback.onError(errorMsg)
                        }
                    }
            startCamera(activity, mode, adaptedCallback, videoDurationLimit)
        }

        // UTS插件需要的静态方法，用于获取回调
        @JvmStatic
        fun getCameraCallback(): CameraCallback? {
            return cameraCallback
        }

        // UTS插件需要的静态方法，用于清除回调
        @JvmStatic
        fun clearCameraCallback() {
            cameraCallback = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // 从Intent获取拍摄模式参数
        mode = intent.getStringExtra("mode") ?: "photo" // "photo" or "video"
        // 从Intent获取录像时长限制参数
        videoDurationLimit = intent.getIntExtra("videoDurationLimit", 60) // 默认60秒

        setContent {
            MaterialTheme {
                CustomCameraScreen(
                        onSuccess = { uri ->
                            Log.i("CustomCamera", "onSuccess called with URI: $uri")
                            cameraCallback?.onSuccess(uri)
                            clearCameraCallback()
                            finish()
                        },
                        onError = { message, exception ->
                            Log.e("CustomCamera", "onError called: $message", exception)
                            cameraCallback?.onError(message, exception)
                            clearCameraCallback()
                            finish()
                        },
                        mode = mode,
                        videoDurationLimit = videoDurationLimit
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        // 防止内存泄漏
        clearCameraCallback()
    }
}

@Composable
fun CustomCameraScreen(
        onSuccess: (Uri) -> Unit,
        onError: (String, Exception?) -> Unit,
        mode: String,
        videoDurationLimit: Int
) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val imageCapture = remember { ImageCapture.Builder().build() }

    // Video recording setup
    val recorder = remember {
        Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.SD)).build()
    }
    val videoCapture = remember { VideoCapture.withOutput(recorder) } as VideoCapture<Recorder>
    var camera by remember { mutableStateOf<Camera?>(null) }
    var isFlashOn by remember { mutableStateOf(false) }
    var currentCameraSelector by remember { mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA) }

    // Recording state
    var isRecording by remember { mutableStateOf(false) }
    var recordingDuration by remember { mutableStateOf(0L) } // in seconds
    var remainingTime by remember { mutableStateOf(videoDurationLimit.toLong()) } // 剩余录制时间（秒）
    var recording by remember { mutableStateOf<Recording?>(null) }
    var videoUri by remember { mutableStateOf<Uri?>(null) } // 保存视频URI
    var countdownScope by remember { mutableStateOf<CoroutineScope?>(null) } // 倒计时协程作用域

    // 设置相机控制（对焦、缩放）
    fun setupCameraControls(cameraInstance: Camera?) {
        camera = cameraInstance
        val cameraControl = cameraInstance?.cameraControl
        if (cameraControl != null) {
            val gestureDetector =
                    GestureDetector(
                            context,
                            object : GestureDetector.SimpleOnGestureListener() {
                                override fun onDown(e: MotionEvent): Boolean {
                                    return true
                                }

                                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                                    // 自动对焦到点击位置
                                    val meteringPointFactory = previewView.meteringPointFactory
                                    val meteringPoint = meteringPointFactory.createPoint(e.x, e.y)
                                    val focusAction =
                                            FocusMeteringAction.Builder(meteringPoint).build()
                                    cameraControl.startFocusAndMetering(focusAction)
                                    return true
                                }
                            }
                    )

            val scaleGestureDetector =
                    ScaleGestureDetector(
                            context,
                            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                                override fun onScale(detector: ScaleGestureDetector): Boolean {
                                    val cameraInfo = cameraInstance.cameraInfo
                                    val currentZoomRatio =
                                            cameraInfo.zoomState.value?.zoomRatio ?: 1f
                                    val scaleFactor = detector.scaleFactor
                                    val newZoomRatio = currentZoomRatio * scaleFactor

                                    // 确保缩放比例在相机支持的范围内
                                    val minZoom = cameraInfo.zoomState.value?.minZoomRatio ?: 1f
                                    val maxZoom = cameraInfo.zoomState.value?.maxZoomRatio ?: 10f
                                    val boundedZoomRatio = newZoomRatio.coerceIn(minZoom, maxZoom)

                                    cameraControl.setZoomRatio(boundedZoomRatio)
                                    return true
                                }
                            }
                    )

            previewView.setOnTouchListener { v, event ->
                scaleGestureDetector.onTouchEvent(event)
                gestureDetector.onTouchEvent(event)
                return@setOnTouchListener true
            }
        }
    }

    // 请求麦克风权限（录像需要）
    val audioPermissionLauncher =
            rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = { isGranted ->
                        if (isGranted) {
                            bindCameraUseCases(
                                    cameraProviderFuture,
                                    previewView,
                                    imageCapture,
                                    videoCapture,
                                    context as ComponentActivity,
                                    currentCameraSelector,
                                    mode
                            ) { setupCameraControls(it) }
                        } else {
                            Log.e("CustomCamera", "Audio permission denied")
                            Toast.makeText(context, "麦克风权限被拒绝，无法录制视频", Toast.LENGTH_SHORT).show()
                        }
                    }
            )

    // 请求相机权限
    val cameraPermissionLauncher =
            rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = { isGranted ->
                        if (isGranted) {
                            if (mode == "video") {
                                // 如果是录像模式，还需要请求麦克风权限
                                audioPermissionLauncher.launch(
                                        android.Manifest.permission.RECORD_AUDIO
                                )
                            } else {
                                // 拍照模式直接绑定相机用例
                                bindCameraUseCases(
                                        cameraProviderFuture,
                                        previewView,
                                        imageCapture,
                                        videoCapture,
                                        context as ComponentActivity,
                                        currentCameraSelector,
                                        mode
                                ) { setupCameraControls(it) }
                            }
                        } else {
                            Log.e("CustomCamera", "Camera permission denied")
                            Toast.makeText(context, "相机权限被拒绝，无法使用相机", Toast.LENGTH_SHORT).show()
                        }
                    }
            )

    fun takePhoto() {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "JPEG_${timeStamp}_"
        val contentValues =
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, imageFileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/")
                }

        val outputFileOptions =
                ImageCapture.OutputFileOptions.Builder(
                                context.contentResolver,
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                contentValues
                        )
                        .build()

        imageCapture.takePicture(
                outputFileOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        val savedUri = outputFileResults.savedUri
                        if (savedUri != null) {
                            onSuccess(savedUri)
                        } else {
                            Log.e("CustomCamera", "Saved URI is null")
                            onError("保存失败: URI为空", null)
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(
                                "CustomCamera",
                                "Photo capture failed: ${exception.message}",
                                exception
                        )
                        val errorMessage = "拍照失败: ${exception.message}"
                        Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
                        onError(errorMessage, exception)
                    }
                }
        )
    }

    fun startRecording() {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val videoFileName = "VID_${timeStamp}_"
        val contentValues =
                ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, videoFileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/")
                }

        // 先插入内容到MediaStore获取URI
        videoUri =
                context.contentResolver.insert(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        contentValues
                )

        if (videoUri != null) {
            Log.i("CustomCamera", "Pre-created video URI: $videoUri")
        } else {
            Log.e("CustomCamera", "Failed to pre-create video URI")
            Toast.makeText(context, "无法创建视频文件", Toast.LENGTH_SHORT).show()
            return
        }

        // 创建MediaStoreOutputOptions，使用预创建的URI
        val outputOptions =
                MediaStoreOutputOptions.Builder(
                                context.contentResolver,
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        )
                        .setContentValues(contentValues)
                        .build()

        // 开始录制
        val recordingSession =
                recorder.prepareRecording(context, outputOptions).apply { withAudioEnabled() }

        recording =
                recordingSession.start(ContextCompat.getMainExecutor(context)) { event ->
                    // 使用更可靠的类型检查方式处理录制事件
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            isRecording = true
                            recordingDuration = 0L
                            remainingTime = videoDurationLimit.toLong()
                            Log.i(
                                    "CustomCamera",
                                    "Video recording started, duration limit: $videoDurationLimit seconds"
                            )

                            // 启动倒计时协程
                            countdownScope = CoroutineScope(Dispatchers.Main)
                            countdownScope?.launch {
                                while (isRecording && remainingTime > 0) {
                                    delay(1000)
                                    remainingTime--
                                    recordingDuration++

                                    if (remainingTime <= 0) {
                                        // 时间到，自动停止录制
                                        Log.i(
                                                "CustomCamera",
                                                "Video recording duration limit reached, stopping recording"
                                        )
                                        recording?.stop()
                                        // 直接在这里处理录制结束逻辑
                                        isRecording = false
                                        recording = null
                                        recordingDuration = 0L // 重置录制时间
                                        remainingTime = videoDurationLimit.toLong() // 重置剩余时间

                                        // 取消倒计时协程
                                        countdownScope?.cancel()
                                        countdownScope = null

                                        Log.i("CustomCamera", "Recording stopped due to time limit")
                                        Log.i("CustomCamera", "Video saved at: $videoUri")

                                        // 确保videoUri不为null
                                        if (videoUri != null) {
                                            Log.i(
                                                    "CustomCamera",
                                                    "Calling onSuccess with URI: $videoUri"
                                            )
                                            onSuccess(videoUri!!)
                                        } else {
                                            Log.e("CustomCamera", "Failed to get video URI")
                                            val errorMessage = "录像保存失败"
                                            Toast.makeText(
                                                            context,
                                                            errorMessage,
                                                            Toast.LENGTH_SHORT
                                                    )
                                                    .show()
                                            onError(errorMessage, null)
                                        }
                                    }
                                }
                            }
                        }
                        is VideoRecordEvent.Finalize -> {
                            Log.i("CustomCamera", "Finalize event received!")
                            // 只记录事件，不再处理完成逻辑（已在stopRecording中处理）
                            Log.i("CustomCamera", "Video recording completed successfully")
                            Log.i("CustomCamera", "Video saved at: $videoUri")
                        }
                        else -> {
                            Log.d(
                                    "CustomCamera",
                                    "Video recording event: ${event.javaClass.simpleName}"
                            )
                            // 添加更多调试信息
                            Log.d("CustomCamera", "Event class: ${event.javaClass.name}")
                            Log.d("CustomCamera", "Event toString: $event")
                            // 检查是否是错误事件
                            if (event.javaClass.simpleName.contains("Error", ignoreCase = true) ||
                                            event.javaClass.simpleName.contains(
                                                    "Failure",
                                                    ignoreCase = true
                                            )
                            ) {
                                isRecording = false
                                recording = null
                                // 取消倒计时协程
                                countdownScope?.cancel()
                                countdownScope = null
                                val errorMessage = "录像失败: ${event.javaClass.simpleName}"
                                Log.e("CustomCamera", errorMessage)
                                Toast.makeText(context, "录像失败", Toast.LENGTH_SHORT).show()
                                onError(errorMessage, null)
                            }
                        }
                    }
                }
    }

    fun stopRecording() {
        recording?.stop()
        // 直接在这里处理录制结束逻辑，不再依赖Finalize事件
        isRecording = false
        recording = null
        recordingDuration = 0L // 重置录制时间
        remainingTime = videoDurationLimit.toLong() // 重置剩余时间

        // 取消倒计时协程
        countdownScope?.cancel()
        countdownScope = null

        Log.i("CustomCamera", "Recording stopped directly in stopRecording()")
        Log.i("CustomCamera", "Video saved at: $videoUri")

        // 确保videoUri不为null
        if (videoUri != null) {
            Log.i("CustomCamera", "Calling onSuccess with URI: $videoUri")
            onSuccess(videoUri!!)
        } else {
            Log.e("CustomCamera", "Failed to get video URI")
            val errorMessage = "录像保存失败"
            Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
            onError(errorMessage, null)
        }
    }

    // 处理缩放和自动对焦功能 - 已被setupCameraControls替代，保留此函数以保持兼容性
    fun setupZoomControls() {
        val cameraInstance = camera
        if (cameraInstance != null) {
            setupCameraControls(cameraInstance)
        }
    }

    LaunchedEffect(Unit) {
        // 检查并请求相机权限
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) !=
                        PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        } else {
            if (mode == "video") {
                // 如果是录像模式，还需要请求麦克风权限
                if (ContextCompat.checkSelfPermission(
                                context,
                                android.Manifest.permission.RECORD_AUDIO
                        ) != PackageManager.PERMISSION_GRANTED
                ) {
                    audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                } else {
                    bindCameraUseCases(
                            cameraProviderFuture,
                            previewView,
                            imageCapture,
                            videoCapture,
                            context as ComponentActivity,
                            currentCameraSelector,
                            mode
                    ) { setupCameraControls(it) }
                }
            } else {
                // 拍照模式直接绑定相机用例
                bindCameraUseCases(
                        cameraProviderFuture,
                        previewView,
                        imageCapture,
                        videoCapture,
                        context as ComponentActivity,
                        currentCameraSelector,
                        mode
                ) { setupCameraControls(it) }
            }
        }
    }

    // 录制时间计时器
    LaunchedEffect(isRecording) {
        if (isRecording) {
            val startTime = System.currentTimeMillis()
            while (isRecording) {
                delay(1000)
                recordingDuration = (System.currentTimeMillis() - startTime) / 1000
            }
        } else {
            recordingDuration = 0L
        }
    }

    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    // 计算4:3比例的取景框尺寸，尽可能占满屏幕
    val aspectRatio = 4f / 3f
    val (previewWidth, previewHeight) =
            if (screenWidth > screenHeight * aspectRatio) {
                // 以高度为基准计算宽度
                Pair(screenHeight * aspectRatio, screenHeight)
            } else {
                // 以宽度为基准计算高度
                Pair(screenWidth, screenWidth / aspectRatio)
            }

    // 格式化录制时间为MM:SS
    val formattedDuration =
            remember(recordingDuration) {
                String.format("%02d:%02d", recordingDuration / 60, recordingDuration % 60)
            }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 红色相机预览
        AndroidView(
                factory = { previewView },
                modifier = Modifier.size(previewWidth, previewHeight).align(Alignment.Center)
        )

        // 取景框（与预览尺寸相同）
        Box(
                modifier =
                        Modifier.size(previewWidth, previewHeight)
                                .align(Alignment.Center)
                                .drawBehind {
                                    // 绘制取景框边框
                                    drawRoundRect(
                                            color = Color.White,
                                            style = Stroke(width = 4f),
                                            cornerRadius = CornerRadius(16f)
                                    )
                                    // 清除取景框内的红色滤镜
                                    drawRoundRect(
                                            color = Color.Transparent,
                                            size = Size(size.width - 8f, size.height - 8f),
                                            topLeft = Offset(4f, 4f),
                                            cornerRadius = CornerRadius(12f)
                                    )
                                }
        )

        // 录制时间显示（仅在录像模式且录制中显示）
        if (mode == "video" && isRecording) {
            Text(
                    text = formattedDuration,
                    color = Color.Red,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
            )
        }

        // 拍照按钮
        Box(modifier = Modifier.fillMaxSize().padding(end = 32.dp)) {
            Column(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 取消按钮
                TextButton(
                        onClick = { (context as android.app.Activity).finish() },
                        modifier = Modifier
                ) { Text("取消", color = Color.White) }

                // 拍摄按钮（根据模式和状态显示不同样式）
                Box(
                        modifier =
                                Modifier.size(80.dp)
                                        .border(
                                                width = 4.dp,
                                                color =
                                                        if (mode == "video") Color.White
                                                        else Color.Transparent,
                                                shape =
                                                        androidx.compose.foundation.shape
                                                                .CircleShape
                                        )
                ) {
                    Button(
                            onClick = {
                                if (mode == "video") {
                                    if (isRecording) {
                                        // 停止录制
                                        stopRecording()
                                    } else {
                                        // 开始录制
                                        startRecording()
                                    }
                                } else {
                                    // 拍照模式
                                    takePhoto()
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                            shape = androidx.compose.foundation.shape.CircleShape,
                            colors =
                                    ButtonDefaults.buttonColors(
                                            containerColor =
                                                    if (mode == "video" && !isRecording) Color.Red
                                                    else Color.White,
                                            contentColor =
                                                    if (mode == "video") Color.White
                                                    else Color.Black
                                    )
                    ) {
                        if (mode == "video" && isRecording) {
                            // iOS风格录制状态：中间红色正方形
                            Box(modifier = Modifier.size(30.dp).background(Color.Red))
                        } else {
                            // 普通状态：显示文字
                            Text(if (mode == "video") "录像" else "拍照")
                        }
                    }
                }

                // 闪光灯按钮
                TextButton(
                        onClick = {
                            isFlashOn = !isFlashOn
                            val cameraControl = camera?.cameraControl
                            if (cameraControl != null) {
                                // 设置ImageCapture的闪光灯模式
                                val flashMode =
                                        if (isFlashOn) {
                                            ImageCapture.FLASH_MODE_ON
                                        } else {
                                            ImageCapture.FLASH_MODE_OFF
                                        }
                                imageCapture.flashMode = flashMode

                                // 同时通过CameraControl控制手电筒
                                cameraControl.enableTorch(isFlashOn)
                            }
                        },
                        modifier = Modifier
                ) { Text(if (isFlashOn) "关闪光" else "开闪光", color = Color.White) }

                // 前后摄切换按钮（录制时禁用）
                TextButton(
                        onClick = {
                            // 切换摄像头
                            currentCameraSelector =
                                    if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA
                                    ) {
                                        CameraSelector.DEFAULT_FRONT_CAMERA
                                    } else {
                                        CameraSelector.DEFAULT_BACK_CAMERA
                                    }

                            // 重新绑定相机用例
                            bindCameraUseCases(
                                    cameraProviderFuture,
                                    previewView,
                                    imageCapture,
                                    videoCapture,
                                    context as ComponentActivity,
                                    currentCameraSelector,
                                    mode
                            ) { camera = it }
                        },
                        modifier = Modifier.alpha(if (isRecording) 0.5f else 1.0f),
                        enabled = !isRecording
                ) {
                    val buttonText =
                            if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) "切换到前摄"
                            else "切换到后摄"
                    Text(buttonText, color = Color.White)
                }
            }
        }
    }
}

fun bindCameraUseCases(
        cameraProviderFuture: ListenableFuture<ProcessCameraProvider>,
        previewView: PreviewView,
        imageCapture: ImageCapture,
        videoCapture: VideoCapture<Recorder>,
        lifecycleOwner: ComponentActivity,
        cameraSelector: CameraSelector,
        mode: String,
        onCameraReady: (Camera?) -> Unit
) {
    cameraProviderFuture.addListener(
            {
                val cameraProvider = cameraProviderFuture.get()
                val preview =
                        Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                try {
                    cameraProvider.unbindAll()
                    val camera =
                            if (mode == "video") {
                                cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        cameraSelector,
                                        preview,
                                        videoCapture
                                )
                            } else {
                                cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        cameraSelector,
                                        preview,
                                        imageCapture
                                )
                            }
                    onCameraReady(camera)
                } catch (exc: Exception) {
                    Log.e("CustomCamera", "Use case binding failed", exc)
                    onCameraReady(null)
                }
            },
            ContextCompat.getMainExecutor(lifecycleOwner)
    )
}
