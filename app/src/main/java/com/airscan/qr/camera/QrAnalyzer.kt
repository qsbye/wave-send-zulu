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
 * CameraX 帧分析器：ML Kit 解出二维码后，把字节模式载荷（rawBytes）
 * 原样回调给接收逻辑。STRATEGY_KEEP_ONLY_LATEST：卡顿/速度不匹配时
 * 直接丢弃待分析的旧帧，符合喷泉码“任意帧都行”的接收模型。
 */
class QrAnalyzer(
    private val callbackExecutor: Executor,
    private val onFrame: (ByteArray) -> Unit,
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
        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )
        scanner.process(image)
            .addOnSuccessListener(callbackExecutor) { barcodes ->
                for (code in barcodes) {
                    code.rawBytes?.let(onFrame)
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }
}
