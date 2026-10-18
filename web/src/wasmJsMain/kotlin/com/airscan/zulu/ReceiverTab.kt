package com.airscan.zulu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.airscan.qr.core.Container
import com.airscan.qr.core.FrameVerdict
import com.airscan.qr.core.LTDecoder
import com.airscan.qr.core.classifyFrame
import com.airscan.qr.core.fnv1a
import com.airscan.qr.core.frameVerdictMessage
import com.airscan.qr.core.parseFrame
import com.airscan.qr.core.streamIdentity
import kotlinx.coroutines.delay

private class ReceiverState {
    var camOn by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    var locked by mutableStateOf(false)
    var sessionId by mutableIntStateOf(0)
    var k by mutableIntStateOf(0)
    var blockLen by mutableIntStateOf(0)
    var totalLen by mutableLongStateOf(0L)
    var unique by mutableIntStateOf(0)
    var dup by mutableIntStateOf(0)
    var progress by mutableStateOf(0f)
    var hint by mutableStateOf<String?>(null)

    var completedName by mutableStateOf<String?>(null)
    var completedMime by mutableStateOf("")
    var completedSize by mutableLongStateOf(0L)
    var completedSha by mutableStateOf("")
    var completedB64 by mutableStateOf<String?>(null)

    var identity: String? = null
    var decoder: LTDecoder? = null
    var expectedFnv: Long = 0

    fun onFrame(bytes: ByteArray) {
        val verdict = classifyFrame(bytes)
        if (verdict !is FrameVerdict.Ok) {
            val msg = frameVerdictMessage(verdict)
            if (msg != null) hint = msg
            return
        }
        val parsed = parseFrame(bytes) ?: return
        val (header, block) = parsed

        val id = streamIdentity(header)
        if (id != identity) {
            identity = id
            expectedFnv = header.payloadFnv
            decoder = LTDecoder(
                k = header.k,
                blockLen = header.blockLen,
                sessionId = header.sessionId,
                totalLen = header.totalLen,
            )
            locked = true
            sessionId = header.sessionId
            k = header.k
            blockLen = header.blockLen
            totalLen = header.totalLen
            hint = null
            completedName = null
            completedB64 = null
        }
        val dec = decoder ?: return
        if (completedName != null) return

        dec.addFrame(header.seq, block)
        progress = (dec.framesNew.toFloat() / dec.k).coerceIn(0f, 1f)
        unique = dec.framesNew
        dup = dec.framesDup

        if (dec.isComplete) {
            val assembled = dec.assemble()
            if (assembled == null || fnv1a(assembled) != expectedFnv) {
                identity = null
                decoder = null
                hint = "容器校验失败（FNV 不匹配），正在借轮播重新接收…"
                return
            }
            try {
                val file = Container.unpackFile(assembled)
                if (!Container.verify(file)) {
                    hint = "SHA-256 校验失败，文件可能损坏，请重新接收"
                    return
                }
                completedName = file.name
                completedMime = file.type
                completedSize = file.bytes.size.toLong()
                completedSha = hex(file.sha256)
                completedB64 = B64.encode(file.bytes)
                hint = null
            } catch (e: Exception) {
                hint = "容器解析失败：${e.message}"
            }
        }
    }

    fun reset() {
        identity = null
        decoder = null
        locked = false
        unique = 0
        dup = 0
        progress = 0f
        hint = null
        completedName = null
        completedB64 = null
    }
}

@Composable
fun ReceiverTab() {
    val st = remember { ReceiverState() }

    // 摄像头开启时轮询检测帧
    LaunchedEffect(st.camOn) {
        if (st.camOn) {
            Js.startCam()
            while (st.camOn) {
                Js.camError()?.let { st.error = it; st.camOn = false; Js.stopCam() }
                val b64 = Js.takeFrame()
                if (b64 != null) {
                    try {
                        st.onFrame(B64.decode(b64))
                    } catch (e: Exception) {
                        st.error = "帧处理异常：${e.message}"
                    }
                }
                delay(40)
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(
                checked = st.camOn,
                onCheckedChange = {
                    if (!it) Js.stopCam()
                    st.camOn = it
                    st.error = null
                },
            )
            Text(if (st.camOn) "摄像头接收中（浏览器需支持 BarcodeDetector）" else "摄像头已关闭")
            Spacer(Modifier.weight(1f))
            Button(onClick = { st.reset() }) { Text("重置") }
        }

        st.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        st.hint?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }

        if (st.locked && st.completedName == null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("已锁定喷泉码数据流", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
                    Text("进度 ${(st.progress * 100).toInt()}%  新帧 ${st.unique}  重复 ${st.dup}")
                    Text(
                        "会话 #${st.sessionId}  块 ${st.k} × ${st.blockLen}B  共 ${fmtBytes(st.totalLen)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (st.completedName != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("接收完成", style = MaterialTheme.typography.titleMedium)
                    Text(st.completedName ?: "", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${fmtBytes(st.completedSize)}  ${st.completedMime}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "SHA-256 ${st.completedSha}",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val b64 = st.completedB64
                    if (b64 != null) {
                        Button(onClick = { Js.download(b64, st.completedName ?: "file.bin") }) {
                            Text("保存到下载目录")
                        }
                    }
                }
            }
        }

        if (!st.locked && st.completedName == null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "等待数据流…\n\n用手机/桌面端发送二维码，本页开启摄像头即可接收。\n摄像头画面显示在页面右下角。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
