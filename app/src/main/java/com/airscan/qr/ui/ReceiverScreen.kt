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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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

    // 麦克风权限（声波接收）
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        micGranted = ok
        if (ok) vm.setAudioEnabled(true)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0D10))
    ) {
        if (granted && state.cameraEnabled) {
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
            }

            // 摄像头开关：纯声波接收时关闭省电
            if (granted) {
                CameraToggleCard(enabled = state.cameraEnabled, onToggle = vm::setCameraEnabled)
            }

            // 声波接收卡：与摄像头通道并行，无摄像头权限时也可单独使用
            AudioReceiverCard(
                state = state,
                onToggle = { want ->
                    if (want && !micGranted) {
                        micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        vm.setAudioEnabled(want)
                    }
                },
                onModeChange = vm::setAudioUltraSound,
            )

            if (granted && state.completed == null) {
                Spacer(Modifier.weight(1f))
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

/** 字节数人性化显示。 */
private fun fmtBytes(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576f)
    bytes >= 1 shl 10 -> "%.1f KB".format(bytes / 1024f)
    else -> "${bytes}B"
}

/** 摄像头接收开关卡：关闭后仅用声波通道省电。 */
@Composable
private fun CameraToggleCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xCC101418)),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "摄像头接收",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (enabled) "扫码 + 声波双通道并行" else "已关闭：纯声波接收，更省电",
                    color = Color(0xFFB9C2CC),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun AudioReceiverCard(
    state: ReceiverUiState,
    onToggle: (Boolean) -> Unit,
    onModeChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xCC101418),
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "声波接收（麦克风）",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = when {
                            !state.audioEnabled -> "与摄像头同时接收，双通道互补"
                            state.audioReceiving && state.audioLockEta > 0 ->
                                "正在接收声波信号…预计 ${state.audioLockEta}s 后锁定（需收齐一帧全部段，每段约 3.8s）"
                            state.audioReceiving -> "正在接收声波信号…"
                            else -> "监听中，未检测到声波信号"
                        },
                        color = Color(0xFFB9C2CC),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = state.audioEnabled, onCheckedChange = onToggle)
            }
            if (state.audioEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !state.audioUltraSound,
                        onClick = { onModeChange(false) },
                        label = { Text("可听声波") },
                    )
                    FilterChip(
                        selected = state.audioUltraSound,
                        onClick = { onModeChange(true) },
                        label = { Text("超声波") },
                    )
                }
                state.audioError?.let {
                    Text(it, color = Color(0xFFFF7B72), style = MaterialTheme.typography.bodySmall)
                }
                AudioWave(levels = state.audioLevels, active = state.audioReceiving)
                // 接收进度详情（二维码与声波共享同一路喷泉码流）
                if (state.locked) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "进度 ${(state.progress * 100).toInt()}%",
                            color = Color(0xFF4CD97B),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("新帧 ${state.uniqueFrames}", color = Color(0xFFB9C2CC), style = MaterialTheme.typography.bodySmall)
                        Text("重复 ${state.dupFrames}", color = Color(0xFF8A93A0), style = MaterialTheme.typography.bodySmall)
                        Text("冗余 ${state.redundantFrames}", color = Color(0xFF8A93A0), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "会话 0x${(state.sessionId and 0xFFFF).toString(16).uppercase()}" +
                            " · 块 ${state.k} × ${state.blockLen}B · 共 ${fmtBytes(state.totalLen)}",
                        color = Color(0xFF8A93A0),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text("等待锁定数据流…", color = Color(0xFF5A6470), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ReceiveStatusCard(state: ReceiverUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xCC101418),
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
                if (state.etaSeconds > 0 && state.completed == null) {
                    Text(
                        "预计剩余约 ${state.etaSeconds} 秒",
                        color = Color(0xFF4CD97B),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
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
