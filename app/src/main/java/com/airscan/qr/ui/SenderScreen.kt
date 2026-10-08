package com.airscan.qr.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilePresent
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airscan.qr.util.Fmt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SenderScreen(vm: SenderViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(vm::selectFile) }

    BackHandler(enabled = state.sending) { vm.stop() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 文件选择卡
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.FilePresent, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = state.fileName ?: "未选择文件",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (state.fileName != null) {
                    Text(
                        text = buildString {
                            append("原始大小：${Fmt.bytes(state.fileSize)}")
                            append("    承载大小：${Fmt.bytes(state.packedSize)}")
                            if (state.compression == "gzip") append("    （gzip）")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "分块：${state.k} 块 × ${state.blockLen} B",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { pickFile.launch(arrayOf("*/*")) }) {
                    Text(if (state.fileName == null) "选择文件" else "更换文件")
                }
            }
        }

        // 参数卡
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("帧大小（含 22B 帧头）", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FRAME_BYTES_OPTIONS.forEach { fb ->
                        FilterChip(
                            selected = state.frameBytes == fb,
                            enabled = !state.sending,
                            onClick = { vm.setFrameBytes(fb) },
                            label = { Text("${fb} B") },
                        )
                    }
                }
                Text("播放帧率", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FPS_OPTIONS.forEach { fps ->
                        FilterChip(
                            selected = state.fps == fps,
                            enabled = !state.sending,
                            onClick = { vm.setFps(fps) },
                            label = { Text("${fps} fps") },
                        )
                    }
                }
            }
        }

        // 二维码展示区
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .align(Alignment.CenterHorizontally),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(20.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(Color.White)
                    .padding(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = state.qrBitmap
                if (bmp != null && state.sending) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "发送中的二维码",
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = "选择文件并开始后\n此处高速播放喷泉码二维码",
                        color = Color(0xFF6B7280),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // 状态 + 控制
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.sending) {
                    Text(
                        text = "会话 #%04X · 帧 %d · 周期 %d 帧".format(
                            state.sessionId, state.seq, state.k * 2,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = "接收端随时可对准屏幕，漏拍/晚加入都无需重发。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.error?.let { err ->
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Button(
                    onClick = { if (state.sending) vm.stop() else vm.start() },
                    enabled = state.fileName != null && !state.busy && state.k in 1..0xFFFF,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Icon(
                        if (state.sending) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            state.busy -> "处理中…"
                            state.sending -> "停止发送"
                            else -> "开始发送"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}
