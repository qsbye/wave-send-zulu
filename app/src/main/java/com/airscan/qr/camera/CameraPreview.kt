package com.airscan.qr.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 后置摄像头取景 + 二维码帧分析。
 * 分析线程为单线程池，保证送给解码器的帧有序串行处理。
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onFrame: (ByteArray) -> Unit,
) {
    val context: Context = LocalContext.current
    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    // 离开界面后异步绑定完成时需要能感知，避免相机被泄漏绑定。
    val disposed = remember { booleanArrayOf(false) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            disposed[0] = true
            analysisExecutor.shutdown()
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                { runCatching { future.get().unbindAll() } },
                ContextCompat.getMainExecutor(context),
            )
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                if (disposed[0]) return@addListener
                val provider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(
                            analysisExecutor,
                            QrAnalyzer(analysisExecutor, onFrame),
                        )
                    }

                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                } catch (_: Exception) {
                    // 无摄像头或被占用时保持空白预览。
                }
            }, ContextCompat.getMainExecutor(context))
            previewView
        },
    )
}
