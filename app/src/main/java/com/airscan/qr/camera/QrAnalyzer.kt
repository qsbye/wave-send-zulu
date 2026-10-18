package com.airscan.qr.camera

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
 * 检出二维码的四角点坐标（"摆正后"帧画面，像素）。
 * 四点按 ML Kit 顺序（顺时针，起于左上），跟随二维码的旋转与透视畸变，
 * 可直接连成多边形；frameWidth/frameHeight 供 FILL_CENTER 预览映射使用。
 */
data class QrQuad(
    /** [x0,y0, x1,y1, x2,y2, x3,y3]，顺序：左上/右上/右下/左下 */
    val points: FloatArray,
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
    private val onQuad: (QrQuad?) -> Unit = {},
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
                var quad: QrQuad? = null
                for (code in barcodes) {
                    val raw = code.rawBytes ?: continue
                    if (quad == null) {
                        // 四角点跟随二维码旋转/透视；缺失时退化为轴对齐包围盒
                        val pts = code.cornerPoints
                        if (pts != null && pts.size == 4 && uprightW > 0 && uprightH > 0) {
                            quad = QrQuad(
                                points = floatArrayOf(
                                    pts[0].x.toFloat(), pts[0].y.toFloat(),
                                    pts[1].x.toFloat(), pts[1].y.toFloat(),
                                    pts[2].x.toFloat(), pts[2].y.toFloat(),
                                    pts[3].x.toFloat(), pts[3].y.toFloat(),
                                ),
                                frameWidth = uprightW,
                                frameHeight = uprightH,
                            )
                        } else {
                            val bb = code.boundingBox
                            if (bb != null && uprightW > 0 && uprightH > 0) {
                                quad = QrQuad(
                                    points = floatArrayOf(
                                        bb.left.toFloat(), bb.top.toFloat(),
                                        bb.right.toFloat(), bb.top.toFloat(),
                                        bb.right.toFloat(), bb.bottom.toFloat(),
                                        bb.left.toFloat(), bb.bottom.toFloat(),
                                    ),
                                    frameWidth = uprightW,
                                    frameHeight = uprightH,
                                )
                            }
                        }
                    }
                    onFrame(raw)
                }
                // null = 本帧未检出二维码 → UI 清除锁定框
                onQuad(quad)
            }
            .addOnCompleteListener { imageProxy.close() }
    }
}
