package com.lxd.camera

// 导入CameraCallback接口
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.rememberAsyncImagePainter
import com.lxd.camera.library.CameraCallback
import com.lxd.camera.library.CustomCameraActivity
import com.lxd.camera.ui.theme.MyApplicationTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) {
                    MainScreen(modifier = Modifier.padding(it))
                }
            }
        }
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val imageUri = rememberSaveable { mutableStateOf<Uri?>(null) }
    val tempImageUri = remember { mutableStateOf<Uri?>(null) }

    // 系统相机启动器
    val takePictureLauncher =
            rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.TakePicture(),
                    onResult = { success ->
                        if (success) {
                            imageUri.value = tempImageUri.value
                        } else {
                            tempImageUri.value = null
                        }
                    }
            )

    // 存储权限启动器
    val storagePermissionLauncher =
            rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = { isGranted ->
                        if (isGranted) {
                            tempImageUri.value?.let { takePictureLauncher.launch(it) }
                        } else {
                            Log.e("MainActivity", "Storage permission denied")
                        }
                    }
            )

    fun createImageUri(): Uri {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "JPEG_${timeStamp}_"
        val contentValues =
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, imageFileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/")
                }
        return context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
        )!!
    }

    fun checkStoragePermissionAndTakePhoto() {
        val storagePermission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(context, storagePermission) ==
                        PackageManager.PERMISSION_GRANTED
        ) {
            val uri = createImageUri()
            tempImageUri.value = uri
            takePictureLauncher.launch(uri)
        } else {
            // 在Android 10+上，WRITE_EXTERNAL_STORAGE权限已被废弃，但为了兼容，我们仍然请求它
            val uri = createImageUri()
            tempImageUri.value = uri
            storagePermissionLauncher.launch(storagePermission)
        }
    }

    Column(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
                onClick = { checkStoragePermissionAndTakePhoto() },
                modifier = Modifier.padding(16.dp)
        ) { Text("使用系统相机拍照") }

        Button(
                onClick = {
                    // 使用自定义相机拍照（优化版接口）
                    CustomCameraActivity.startCamera(
                            activity = context as ComponentActivity,
                            mode = "photo",
                            callback =
                                    object : CameraCallback {
                                        override fun onSuccess(path: String) {
                                            Log.i("MainActivity", "拍照成功: $path")
                                            imageUri.value = Uri.parse(path)
                                        }

                                        override fun onError(message: String) {
                                            Log.e("MainActivity", "拍照失败: $message")
                                        }
                                    }
                    )
                },
                modifier = Modifier.padding(16.dp)
        ) { Text("使用自定义相机拍照") }

        Button(
                onClick = {
                    // 使用自定义相机录像（优化版接口）
                    CustomCameraActivity.startCamera(
                            activity = context as ComponentActivity,
                            mode = "video",
                            callback =
                                    object : CameraCallback {
                                        override fun onSuccess(path: String) {
                                            Log.i("MainActivity", "录像成功: $path")
                                            imageUri.value = Uri.parse(path)
                                        }

                                        override fun onError(message: String) {
                                            Log.e("MainActivity", "录像失败: $message")
                                        }
                                    }
                    )
                },
                modifier = Modifier.padding(16.dp)
        ) { Text("使用自定义相机录像 (60秒)") }

        // 照片/视频显示区域
        imageUri.value?.let {
            Image(
                    painter = rememberAsyncImagePainter(it),
                    contentDescription = if (it.toString().contains("video")) "录制的视频" else "拍摄的照片",
                    modifier =
                            Modifier.fillMaxWidth().padding(16.dp).clickable {
                                // 点击播放视频或查看照片详情
                                val intent =
                                        Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(
                                                    it,
                                                    if (it.toString().contains("video")) "video/*"
                                                    else "image/*"
                                            )
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                context.startActivity(intent)
                            },
                    contentScale = ContentScale.Fit
            )
        }
                ?: run { Text(text = "暂无照片或视频", modifier = Modifier.padding(16.dp)) }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    MyApplicationTheme { MainScreen() }
}
