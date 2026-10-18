package com.airscan.zulu

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.dp
import com.airscan.qr.core.Container
import com.airscan.qr.core.FrameHeader
import com.airscan.qr.core.LTEncoder
import com.airscan.qr.core.Wire
import com.airscan.qr.core.fnv1a
import com.airscan.qr.core.packFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

private val FRAME_BYTES_OPTIONS = listOf(500, 1000, 1465)
private val FPS_OPTIONS = listOf(10, 15, 24, 30)

private class SenderState {
    var fileName by mutableStateOf<String?>(null)
    var fileInfo by mutableStateOf("")
    var picking by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    var blockLen by mutableIntStateOf(500)
    var fps by mutableIntStateOf(10)

    var sending by mutableStateOf(false)
    var seq by mutableLongStateOf(0L)
    var sessionId by mutableIntStateOf(0)
    var k by mutableIntStateOf(0)
    var frame by mutableStateOf<ByteArray?>(null)

    var encoder: LTEncoder? = null
    var payloadFnv: Long = 0
    var totalLen: Long = 0
    var job: Job? = null

    /** 异步选择文件：JS 侧弹文件框，此处轮询结果。 */
    fun pickFile(scope: CoroutineScope) {
        if (picking || sending) return
        picking = true
        error = null
        Js.startPickFile()
        scope.launch {
            while (picking && isActive) {
                val b64 = Js.takePickedFile()
                if (b64 != null) {
                    picking = false
                    if (b64.isNotEmpty()) {
                        loadBytes(Js.pickedFileName(), B64.decode(b64))
                    }
                    break
                }
                delay(100)
            }
        }
    }

    private fun loadBytes(name: String, bytes: ByteArray) {
        stop()
        try {
            if (bytes.isEmpty()) {
                error = "文件为空"
                return
            }
            if (bytes.size.toLong() > Container.MAX_FILE_BYTES) {
                error = "文件超过 ${Container.MAX_FILE_LABEL} 上限"
                return
            }
            val packed = Container.packFile(name, "application/octet-stream", bytes)
            sessionId = Random.nextInt(0x10000)
            encoder = LTEncoder(packed.container, blockLen, sessionId)
            payloadFnv = fnv1a(packed.container)
            totalLen = packed.container.size.toLong()
            k = encoder!!.k
            seq = 0
            fileName = name
            fileInfo = "${fmtBytes(packed.originalSize)} → 容器 ${fmtBytes(packed.transmittedSize)}" +
                "（${packed.compression}），$k 块 × $blockLen B"
            error = null
        } catch (e: Exception) {
            error = when (e.message) {
                "fileEmpty" -> "文件为空"
                "fileOverLimit" -> "文件超过 ${Container.MAX_FILE_LABEL} 上限"
                else -> "读取文件失败：${e.message}"
            }
        }
    }

    fun start(scope: CoroutineScope) {
        if (sending) return
        val enc = encoder ?: return
        sending = true
        job = scope.launch {
            while (isActive) {
                val s = seq and 0xFFFFFFFFL
                val block = enc.encode(s.toUInt())
                frame = packFrame(
                    FrameHeader(
                        flags = 0,
                        sessionId = sessionId,
                        seq = s,
                        k = k,
                        blockLen = blockLen,
                        totalLen = totalLen,
                        payloadFnv = payloadFnv,
                    ),
                    block,
                )
                seq = seq + 1
                delay((1000L / fps).coerceAtLeast(5))
            }
        }
    }

    fun stop() {
        sending = false
        job?.cancel()
        job = null
    }
}

@Composable
fun SenderTab() {
    val scope = rememberCoroutineScope()
    val st = remember { SenderState() }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { st.pickFile(scope) }, enabled = !st.sending && !st.picking) {
                Text(if (st.picking) "等待选择…" else "选择文件…")
            }
            Column(Modifier.weight(1f)) {
                Text(st.fileName ?: "未选择文件", style = MaterialTheme.typography.titleMedium)
                if (st.fileInfo.isNotEmpty()) {
                    Text(st.fileInfo, style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(
                onClick = { if (st.sending) st.stop() else st.start(scope) },
                enabled = st.encoder != null && !st.picking,
            ) {
                Text(if (st.sending) "停止" else "开始发送")
            }
        }

        st.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(4.dp))
        }

        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("帧大小")
            FRAME_BYTES_OPTIONS.forEach { opt ->
                FilterChip(
                    selected = st.blockLen == opt,
                    onClick = { if (!st.sending) st.blockLen = opt },
                    label = { Text("$opt B") },
                    enabled = !st.sending,
                )
            }
            Spacer(Modifier.weight(1f))
            Text("帧率")
            FPS_OPTIONS.forEach { opt ->
                FilterChip(
                    selected = st.fps == opt,
                    onClick = { st.fps = opt },
                    label = { Text("$opt") },
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        Card(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val f = st.frame
                if (f != null && st.sending) {
                    QrView(f, Modifier.fillMaxSize().padding(12.dp))
                } else {
                    Text(
                        if (st.encoder == null) "选择文件后点击「开始发送」" else "已就绪，点击「开始发送」",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            if (st.sending) {
                "seq=${st.seq}  会话=${st.sessionId}  块数=${st.k}  ${st.fps} fps"
            } else {
                "Web 版暂不支持声波通道；帧率修改即时生效，帧大小修改后需重新选择文件"
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
