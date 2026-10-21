package com.airscan.qr.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airscan.qr.AppLocale
import com.airscan.qr.R
import com.airscan.qr.core.Container
import com.airscan.qr.core.FrameHeader
import com.airscan.qr.core.LTEncoder
import com.airscan.qr.core.Wire
import com.airscan.qr.core.fnv1a
import com.airscan.qr.core.packFrame
import com.airscan.qr.core.audio.AudioSegment
import com.airscan.qr.audio.AudioSender
import com.airscan.qr.qr.QrEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.SecureRandom

/** 可选择的整帧字节数（含 22B 帧头），密度越低越容易被摄像头锁定。 */
val FRAME_BYTES_OPTIONS = intArrayOf(500, 1000, 1465)

/** 可选择的播放帧率。60Hz 屏幕建议不超过 24fps，避免拍到刷新过渡帧。 */
val FPS_OPTIONS = intArrayOf(10, 15, 24, 30)

data class SenderUiState(
    val fileName: String? = null,
    val mimeType: String = "application/octet-stream",
    val fileSize: Long = 0,
    val packedSize: Long = 0,
    val compression: String = "none",
    val k: Int = 0,
    val blockLen: Int = 0,
    val frameBytes: Int = 500,
    val fps: Int = 10,
    val sessionId: Int = 0,
    val sending: Boolean = false,
    val qrBitmap: Bitmap? = null,
    val seq: Long = 0,
    val error: String? = null,
    val busy: Boolean = false,
    // ---- 声波发送 ----
    val audioEnabled: Boolean = false,
    val audioUltraSound: Boolean = false,
    val audioLevels: List<Float> = emptyList(),
)

/** 判断当前帧尺寸是否能被声波段协议承载（超过 1600B 则禁用声波）。 */
fun audioSupported(frameBytes: Int): Boolean = AudioSegment.supportsFrame(frameBytes)

class SenderViewModel(app: Application) : AndroidViewModel(app) {

    // 按手机时区解析的中英文资源（Application 资源仍跟随系统语言）
    private val ui: Context = AppLocale.localized(app)

    private val _state = MutableStateFlow(SenderUiState())
    val state: StateFlow<SenderUiState> = _state.asStateFlow()

    private var container: ByteArray? = null
    private var sendJob: Job? = null
    private val rng = SecureRandom()

    // ---- 声波发送 ----
    private var audioSender: AudioSender? = null
    private var audioPollJob: Job? = null

    /** 切换声波发送开关。 */
    fun setAudioEnabled(enabled: Boolean) {
        if (_state.value.sending) return
        if (enabled && !audioSupported(_state.value.frameBytes)) {
            _state.update { it.copy(error = ui.getString(R.string.error_audio_frame_too_large)) }
            return
        }
        _state.update { it.copy(audioEnabled = enabled) }
    }

    /** 切换超声档位。 */
    fun setAudioUltraSound(ultra: Boolean) {
        if (_state.value.sending) return
        _state.update { it.copy(audioUltraSound = ultra) }
    }

    /** 通过 SAF 选择文件后调用。 */
    fun selectFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val app = getApplication<Application>()
                val (name, mime) = queryNameAndType(uri)
                val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error(ui.getString(R.string.error_open_file_failed))

                if (bytes.isEmpty()) {
                    _state.update { it.copy(busy = false, error = ui.getString(R.string.error_empty_file)) }
                    return@launch
                }
                if (bytes.size.toLong() > Container.MAX_FILE_BYTES) {
                    _state.update {
                        it.copy(
                            busy = false,
                            error = ui.getString(R.string.error_file_too_large, Container.MAX_FILE_LABEL),
                        )
                    }
                    return@launch
                }

                val packed = Container.packFile(name, mime, bytes)
                container = packed.container

                // 确保当前帧尺寸能让 k 放进 u16，否则自动升到最小可用档。
                val need = minimumFrameBytes(packed.container.size)
                var frameBytes = _state.value.frameBytes
                if (frameBytes < need) {
                    frameBytes = FRAME_BYTES_OPTIONS.firstOrNull { it >= need }
                        ?: FRAME_BYTES_OPTIONS.last()
                }

                _state.update {
                    it.copy(
                        busy = false,
                        fileName = name,
                        mimeType = mime,
                        fileSize = bytes.size.toLong(),
                        packedSize = packed.container.size.toLong(),
                        compression = packed.compression,
                        frameBytes = frameBytes,
                    )
                }
                refreshBlockStats()
            } catch (e: Exception) {
                _state.update {
                    it.copy(busy = false, error = ui.getString(R.string.error_read_pack_failed, e.message))
                }
            }
        }
    }

    fun setFrameBytes(frameBytes: Int) {
        if (_state.value.sending) return
        _state.update { it.copy(frameBytes = frameBytes) }
        refreshBlockStats()
    }

    fun setFps(fps: Int) {
        _state.update { it.copy(fps = fps) }
    }

    fun start() {
        if (_state.value.sending) return
        val data = container ?: run {
            _state.update { it.copy(error = ui.getString(R.string.error_select_file_first)) }
            return
        }
        val blockLen = _state.value.frameBytes - Wire.HEADER_LEN
        val k = (data.size + blockLen - 1) / blockLen
        if (k > 0xFFFF) {
            _state.update { it.copy(error = ui.getString(R.string.error_too_many_blocks)) }
            return
        }

        // 修复帧子集派生自 sessionId，因此编码器在每次开始时按新会话构建。
        val sessionId = rng.nextInt() and 0xFFFF
        val encoder = LTEncoder(data, blockLen, sessionId)
        val fnv = fnv1a(data)
        val fps = _state.value.fps
        val audioOn = _state.value.audioEnabled && audioSupported(_state.value.frameBytes)
        val ultra = _state.value.audioUltraSound

        _state.update {
            it.copy(
                sending = true,
                sessionId = sessionId,
                k = k,
                blockLen = blockLen,
                seq = 0,
                error = null,
            )
        }

        // 启动声波发送器（与二维码并行）
        if (audioOn) {
            val sender = AudioSender(ultraSound = ultra, volume = 60)
            audioSender = sender
            sender.start()
            // 轮询发送电平，驱动波形显示
            audioPollJob = viewModelScope.launch(Dispatchers.Default) {
                while (isActive) {
                    _state.update { it.copy(audioLevels = sender.snapshotLevels()) }
                    delay(120)
                }
            }
        }

        sendJob = viewModelScope.launch(Dispatchers.Default) {
            val periodMs = 1000L / fps
            var seq = 0L
            while (isActive) {
                val seqU = seq and 0xFFFFFFFFL
                val block = encoder.encode(seqU.toUInt())
                val frame = packFrame(
                    FrameHeader(
                        flags = 0,
                        sessionId = sessionId,
                        seq = seqU,
                        k = k,
                        blockLen = blockLen,
                        totalLen = data.size.toLong(),
                        payloadFnv = fnv,
                    ),
                    block,
                )
                val bmp = QrEncoder.encode(frame)
                _state.update { it.copy(qrBitmap = bmp, seq = seqU) }
                // 同时喂给声波发送器
                audioSender?.offer(sessionId, seqU, frame)
                seq = (seq + 1) and 0xFFFFFFFFL
                delay(periodMs)
            }
        }
    }

    fun stop() {
        sendJob?.cancel()
        sendJob = null
        audioPollJob?.cancel()
        audioPollJob = null
        audioSender?.stop()
        audioSender = null
        _state.update { it.copy(sending = false, audioLevels = emptyList()) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    override fun onCleared() {
        super.onCleared()
        stop()
    }

    private fun refreshBlockStats() {
        val data = container ?: return
        val blockLen = _state.value.frameBytes - Wire.HEADER_LEN
        val k = (data.size + blockLen - 1) / blockLen
        _state.update {
            it.copy(
                blockLen = blockLen,
                k = k,
                error = if (k > 0xFFFF) ui.getString(R.string.error_too_many_blocks) else it.error,
            )
        }
    }

    private fun queryNameAndType(uri: Uri): Pair<String, String> {
        val app = getApplication<Application>()
        var name = "transfer.bin"
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.takeIf { s -> s.isNotBlank() }?.let { name = it }
                }
            }
        name = Container.safeFileName(name)
        val type = app.contentResolver.getType(uri) ?: "application/octet-stream"
        return name to type
    }

    /** 承载该 payload 所需的最小整帧字节数（k 是 u16）。 */
    private fun minimumFrameBytes(payloadBytes: Int): Int =
        ((payloadBytes + 0xFFFF - 1) / 0xFFFF) + Wire.HEADER_LEN
}
