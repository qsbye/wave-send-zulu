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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airscan.qr.R
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

        // 半透明信息层（卡片多时允许上下滚动，保证完成卡完整可见）
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
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
                            stringResource(R.string.receiver_camera_permission),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text(stringResource(R.string.receiver_grant_camera))
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
                    stringResource(R.string.receiver_camera_title),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        if (enabled) R.string.receiver_camera_on else R.string.receiver_camera_off
                    ),
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
                        stringResource(R.string.receiver_audio_title),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = when {
                            !state.audioEnabled -> stringResource(R.string.receiver_audio_off)
                            state.audioReceiving && state.audioLockEta > 0 ->
                                stringResource(R.string.receiver_audio_locking, state.audioLockEta)
                            state.audioReceiving -> stringResource(R.string.receiver_audio_receiving)
                            else -> stringResource(R.string.receiver_audio_listening)
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
                        label = { Text(stringResource(R.string.audio_audible)) },
                    )
                    FilterChip(
                        selected = state.audioUltraSound,
                        onClick = { onModeChange(true) },
                        label = { Text(stringResource(R.string.audio_ultrasound)) },
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
                            stringResource(R.string.receiver_progress, (state.progress * 100).toInt()),
                            color = Color(0xFF4CD97B),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(stringResource(R.string.receiver_stat_new, state.uniqueFrames), color = Color(0xFFB9C2CC), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.receiver_stat_dup, state.dupFrames), color = Color(0xFF8A93A0), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.receiver_stat_redundant, state.redundantFrames), color = Color(0xFF8A93A0), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        stringResource(
                            R.string.receiver_session_summary,
                            (state.sessionId and 0xFFFF).toString(16).uppercase(),
                            state.k,
                            state.blockLen,
                            fmtBytes(state.totalLen),
                        ),
                        color = Color(0xFF8A93A0),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(stringResource(R.string.receiver_audio_wait_lock), color = Color(0xFF5A6470), style = MaterialTheme.typography.bodySmall)
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
                stringResource(
                    if (state.locked) R.string.receiver_locked else R.string.receiver_aim_camera
                ),
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
                        stringResource(R.string.receiver_eta, state.etaSeconds),
                        color = Color(0xFF4CD97B),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.receiver_session_detail,
                        state.sessionId,
                        state.k,
                        state.blockLen,
                        Fmt.bytes(state.totalLen),
                    ),
                    color = Color(0xFFB9C2CC),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = stringResource(
                        R.string.receiver_frame_stats,
                        state.uniqueFrames,
                        state.dupFrames,
                        state.redundantFrames,
                    ),
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
                    stringResource(R.string.receiver_complete),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                stringResource(R.string.receiver_file_name, done.name),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    if (done.compression == "gzip") R.string.receiver_file_size_gzip
                    else R.string.receiver_file_size,
                    Fmt.bytes(done.size),
                    Fmt.bytes(done.transmittedSize),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            done.savedPath?.let {
                Text(
                    stringResource(R.string.receiver_auto_saved, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF4CD97B),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(R.string.receiver_sha) + done.sha256Hex.chunked(16).joinToString(" "),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onSave, enabled = !saving) {
                    Icon(Icons.Filled.SaveAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            if (saving) R.string.receiver_saving else R.string.receiver_save_as
                        )
                    )
                }
                OutlinedButton(onClick = onReset) {
                    Text(stringResource(R.string.receiver_continue))
                }
            }
        }
    }
}
