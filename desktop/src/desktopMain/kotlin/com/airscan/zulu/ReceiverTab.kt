package com.airscan.zulu

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import com.airscan.qr.core.Container
import com.airscan.qr.core.FrameVerdict
import com.airscan.qr.core.LTDecoder
import com.airscan.qr.core.classifyFrame
import com.airscan.qr.core.frameVerdictMessage
import com.airscan.qr.core.fnv1a
import com.airscan.qr.core.parseFrame
import com.airscan.qr.core.streamIdentity
import com.github.sarxos.webcam.Webcam
import com.github.sarxos.webcam.WebcamResolution
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage
import java.io.File

private class ReceiverState {
    var running by mutableStateOf(false)
    var status by mutableStateOf("未启动")
    var preview by mutableStateOf<ImageBitmap?>(null)

    var kTotal by mutableIntStateOf(0)
    var solved by mutableIntStateOf(0)
    var progress by mutableFloatStateOf(0f)
    var framesNew by mutableIntStateOf(0)
    var framesDup by mutableIntStateOf(0)
    var unique by mutableIntStateOf(0)

    var completed by mutableStateOf<Container.OpticalFile?>(null)
    var savedTo by mutableStateOf<String?>(null)

    var webcam: Webcam? = null
    var job: Job? = null
    var decoder: LTDecoder? = null
    var identity: String? = null
    var expectedFnv: Long = 0

    fun resetProgress() {
        decoder = null
        identity = null
        kTotal = 0
        solved = 0
        progress = 0f
        framesNew = 0
        framesDup = 0
        unique = 0
        completed = null
        savedTo = null
        status = "正在搜索发送端…"
    }

    fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        resetProgress()
        job = scope.launch(Dispatchers.IO) {
            try {
                val cam = Webcam.getDefault()
                if (cam == null) {
                    status = "未检测到摄像头"
                    running = false
                    return@launch
                }
                webcam = cam
                cam.setViewSize(WebcamResolution.VGA.size)
                cam.open()
                status = "对准发送端屏幕…"

                val reader = QRCodeReader()
                val hints = mapOf(
                    // 发送端为字节模式 + ISO-8859-1：强制字符集保证 payload 逐字节还原
                    DecodeHintType.CHARACTER_SET to "ISO-8859-1",
                    DecodeHintType.TRY_HARDER to true,
                )
                while (isActive) {
                    val img: BufferedImage? = cam.image
                    if (img != null) {
                        preview = img.toComposeImageBitmap()
                        decodeQr(reader, img, hints)?.let { handleFrame(it) }
                    }
                    delay(50)
                }
            } catch (e: Exception) {
                status = "摄像头启动失败：${e.message}"
            } finally {
                try {
                    webcam?.close()
                } catch (_: Exception) {
                }
                webcam = null
                running = false
            }
        }
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null
        try {
            webcam?.close()
        } catch (_: Exception) {
        }
        webcam = null
        status = "已停止"
    }

    /** ZXing 在 CHARACTER_SET=ISO-8859-1 下，byte 段与字符 1:1 映射，可无损还原载荷。 */
    private fun decodeQr(
        reader: QRCodeReader,
        img: BufferedImage,
        hints: Map<DecodeHintType, Any>,
    ): ByteArray? {
        return try {
            val source = BufferedImageLuminanceSource(img)
            val result = reader.decode(BinaryBitmap(HybridBinarizer(source)), hints)
            result.text.toByteArray(Charsets.ISO_8859_1)
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    @Synchronized
    private fun handleFrame(raw: ByteArray) {
        when (val v = classifyFrame(raw)) {
            is FrameVerdict.Ok -> Unit
            else -> {
                frameVerdictMessage(v)?.let { status = it }
                return
            }
        }
        val (h, block) = parseFrame(raw) ?: return
        val id = streamIdentity(h)
        if (id != identity) {
            resetProgress()
            identity = id
            decoder = LTDecoder(h.k, h.blockLen, h.sessionId, h.totalLen)
            expectedFnv = h.payloadFnv
            status = "已锁定发送端，接收中…"
        }
        val d = decoder ?: return
        val dupBefore = d.framesDup
        d.addFrame(h.seq, block)
        if (d.framesDup == dupBefore) unique++
        framesNew = d.framesNew
        framesDup = d.framesDup
        kTotal = h.k
        solved = d.solvedCount
        progress = if (h.k > 0) solved.toFloat() / h.k else 0f

        if (d.isComplete && completed == null) {
            val assembled = d.assemble()
            if (assembled != null && fnv1a(assembled) == expectedFnv) {
                try {
                    val file = Container.unpackFile(assembled)
                    if (Container.verify(file)) {
                        completed = file
                        status = "接收完成，请保存文件"
                    } else {
                        status = "SHA-256 校验失败，继续接收…"
                    }
                } catch (e: Exception) {
                    status = "容器解析失败，继续接收…"
                }
            }
        }
    }

    fun saveCompleted() {
        val file = completed ?: return
        val dialog = FileDialog(null as Frame?, "保存接收到的文件", FileDialog.SAVE)
        dialog.file = file.name
        dialog.isVisible = true
        val name = dialog.file ?: return
        try {
            File(dialog.directory, name).writeBytes(file.bytes)
            savedTo = File(dialog.directory, name).absolutePath
        } catch (e: Exception) {
            status = "保存失败：${e.message}"
        }
    }
}

@Composable
fun ReceiverTab() {
    val scope = rememberCoroutineScope()
    val st = remember { ReceiverState() }

    DisposableEffect(Unit) {
        onDispose { st.stop() }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { if (st.running) st.stop() else st.start(scope) }) {
                Text(if (st.running) "停止" else "开始接收")
            }
            Text(st.status, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(8.dp))

        LinearProgressIndicator(
            progress = { st.progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "块 ${st.solved}/${st.kTotal}   新帧 ${st.framesNew}   重复 ${st.framesDup}",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(8.dp))

        Card(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val img = st.preview
                if (img != null) {
                    Image(
                        bitmap = img,
                        contentDescription = "摄像头画面",
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text("摄像头未开启", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        st.completed?.let { file ->
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(file.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${fmtBytes(file.bytes.size.toLong())} · ${file.type} · SHA-256 校验通过",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        st.savedTo?.let {
                            Text("已保存：$it", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Button(onClick = { st.saveCompleted() }) {
                        Text("保存…")
                    }
                }
            }
        }
    }
}
