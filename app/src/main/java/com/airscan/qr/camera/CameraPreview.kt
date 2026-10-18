package com.airscan.qr.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * 进程级分析线程池：QrAnalyzer 的 ML Kit Task 回调绑定在此执行器上。
 * 生命周期与进程相同、永不 shutdown——若在离开页面时关闭它，
 * 在途的 Task 完成回调会被调度到已终止的线程池，触发
 * RejectedExecutionException 导致应用崩溃（单例复用同时避免线程累积）。
 */
object CameraExecutors {
    val analysis: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "qr-analysis")
    }
}

/**
 * 后置摄像头取景 + 二维码帧分析。
 * 分析线程为单线程池，保证送给解码器的帧有序串行处理。
 * 检出二维码时在预览上叠加蓝色虚线锁定框。
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onFrame: (ByteArray) -> Unit,
) {
    val context: Context = LocalContext.current
    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor: ExecutorService = remember { CameraExecutors.analysis }
    // 最新检出的二维码四角点（null = 当前未锁定），由分析线程回调更新
    var qrBox by remember { mutableStateOf<QrQuad?>(null) }
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
            // 注意：不要 shutdown analysisExecutor（原因见 CameraExecutors 注释）
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                { runCatching { future.get().unbindAll() } },
                ContextCompat.getMainExecutor(context),
            )
        }
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
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
                                QrAnalyzer(
                                    analysisExecutor,
                                    onFrame,
                                ) { quad -> qrBox = quad },
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
        QRBoxOverlay(box = qrBox, modifier = Modifier.matchParentSize())
    }
}

/**
 * 检出二维码时叠加蓝色虚线锁定框。
 * 分析帧坐标按 PreviewView FILL_CENTER 规则映射（等比放大至铺满视口，
 * 短边居中裁剪）；框体为四角点连成的多边形，跟随二维码的旋转与透视畸变。
 */
@Composable
private fun QRBoxOverlay(box: QrQuad?, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val b = box ?: return@Canvas
        if (b.frameWidth <= 0 || b.frameHeight <= 0 || b.points.size < 8) return@Canvas
        val scale = max(size.width / b.frameWidth, size.height / b.frameHeight)
        val dx = (size.width - b.frameWidth * scale) / 2f
        val dy = (size.height - b.frameHeight * scale) / 2f
        val path = Path().apply {
            moveTo(b.points[0] * scale + dx, b.points[1] * scale + dy)
            for (i in 1 until 4) {
                lineTo(b.points[i * 2] * scale + dx, b.points[i * 2 + 1] * scale + dy)
            }
            close()
        }
        drawPath(
            path = path,
            color = Color(0xFF3D8BFF),
            style = Stroke(
                width = 6f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(28f, 18f)),
            ),
        )
    }
}
