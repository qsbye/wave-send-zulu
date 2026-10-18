package com.airscan.qr.camera

import android.graphics.Rect
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor

/**
 * 检出二维码在"摆正后"帧画面中的包围盒（像素坐标）。
 * frameWidth/frameHeight 为摆正后帧尺寸，供 FILL_CENTER 预览映射使用。
 */
data class QrBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val frameWidth: Int,
    val frameHeight: Int,
)

/**
 * CameraX 帧分析器：ML Kit 解出二维码后，把字节模式载荷（rawBytes）
 * 原样回调给接收逻辑；同时回调检出包围盒（null = 本帧未检出）供 UI
 * 绘制蓝色虚线锁定框。STRATEGY_KEEP_ONLY_LATEST：卡顿/速度不匹配时
 * 直接丢弃待分析的旧帧，符合喷泉码"任意帧都行"的接收模型。
 */
class QrAnalyzer(
    private val callbackExecutor: Executor,
    private val onFrame: (ByteArray) -> Unit,
    private val onBox: (QrBox?) -> Unit = {},
) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        // 摆正后的帧尺寸（旋转 90/270 时宽高互换），包围盒坐标基于该空间
        val uprightW = if (rotation % 180 == 0) mediaImage.width else mediaImage.height
        val uprightH = if (rotation % 180 == 0) mediaImage.height else mediaImage.width
        val image = InputImage.fromMediaImage(
            mediaImage,
            rotation,
        )
        scanner.process(image)
            .addOnSuccessListener(callbackExecutor) { barcodes ->
                var box: QrBox? = null
                for (code in barcodes) {
                    val raw = code.rawBytes ?: continue
                    if (box == null) {
                        val bb: Rect? = code.boundingBox
                        if (bb != null && uprightW > 0 && uprightH > 0) {
                            box = QrBox(
                                left = bb.left.toFloat(),
                                top = bb.top.toFloat(),
                                right = bb.right.toFloat(),
                                bottom = bb.bottom.toFloat(),
                                frameWidth = uprightW,
                                frameHeight = uprightH,
                            )
                        }
                    }
                    onFrame(raw)
                }
                // null = 本帧未检出二维码 → UI 清除锁定框
                onBox(box)
            }
            .addOnCompleteListener { imageProxy.close() }
    }
}
