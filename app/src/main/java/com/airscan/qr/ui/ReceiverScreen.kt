package com.airscan.qr.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airscan.qr.camera.CameraPreview
import com.airscan.qr.util.Fmt

@Composable
fun ReceiverScreen(vm: ReceiverViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> granted = ok }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let(vm::saveTo) }

    Box(Modifier.fillMaxSize()) {
        if (granted) {
            CameraPreview(modifier = Modifier.fillMaxSize(), onFrame = vm::onFrame)
        }

        // 半透明信息层
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!granted) {
                Spacer(Modifier.height(48.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                                        Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Filled.Cameraswitch, contentDescription = null)
                        Text(
                            "需要摄像头权限以扫描发送端屏幕上的二维码",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("授予摄像头权限")
                        }
                    }
                }
            } else {
                ReceiveStatusCard(state = state)

                if (state.completed == null) {
                    Spacer(Modifier.weight(1f))
                }
            }

            state.completed?.let { done ->
                CompletedCard(
                    done = done,
                    saving = state.saving,
                    onSave = { saveLauncher.launch(done.name) },
                    onReset = vm::reset,
                )
            }
        }
    }
}

@Composable
private fun ReceiveStatusCard(state: ReceiverUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xEE101418),
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (state.locked) "已锁定喷泉码数据流" else "将摄像头对准发送端屏幕",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (state.locked) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
                Text(
                    text = buildString {
                        append("会话 #%04X".format(state.sessionId))
                        append("　块数 ${state.k}")
                        append("　帧载荷 ${state.blockLen}B")
                        append("　文件 ${Fmt.bytes(state.totalLen)}")
                    },
                    color = Color(0xFFB9C2CC),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "新帧 ${state.uniqueFrames}　重复 ${state.dupFrames}" +
                        "　冗余 ${state.redundantFrames}",
                    color = Color(0xFFB9C2CC),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            state.hint?.let { h ->
                Text(
                    text = h,
                    color = Color(0xFFFFB4AB),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CompletedCard(
    done: CompletedFile,
    saving: Boolean,
    onSave: () -> Unit,
    onReset: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF2E7D32),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "接收完成并通过完整性校验",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text("文件名：${done.name}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "大小：${Fmt.bytes(done.size)}（光学承载 ${Fmt.bytes(done.transmittedSize)}" +
                    if (done.compression == "gzip") "，gzip 解压）" else "）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "SHA-256：\n" + done.sha256Hex.chunked(8).joinToString(" "),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onSave, enabled = !saving && done.savedUri == null) {
                    Icon(Icons.Filled.SaveAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (done.savedUri != null) "已保存" else if (saving) "保存中…" else "保存文件")
                }
                OutlinedButton(onClick = onReset) {
                    Text("继续接收")
                }
            }
        }
    }
}
