package com.airscan.qr.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airscan.qr.core.Container
import com.airscan.qr.core.FrameHeader
import com.airscan.qr.core.LTEncoder
import com.airscan.qr.core.Wire
import com.airscan.qr.core.fnv1a
import com.airscan.qr.core.packFrame
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
    val frameBytes: Int = 1000,
    val fps: Int = 15,
    val sessionId: Int = 0,
    val sending: Boolean = false,
    val qrBitmap: Bitmap? = null,
    val seq: Long = 0,
    val error: String? = null,
    val busy: Boolean = false,
)

class SenderViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(SenderUiState())
    val state: StateFlow<SenderUiState> = _state.asStateFlow()

    private var container: ByteArray? = null
    private var sendJob: Job? = null
    private val rng = SecureRandom()

    /** 通过 SAF 选择文件后调用。 */
    fun selectFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val app = getApplication<Application>()
                val (name, mime) = queryNameAndType(uri)
                val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("无法读取所选文件")

                if (bytes.isEmpty()) {
                    _state.update { it.copy(busy = false, error = "文件为空") }
                    return@launch
                }
                if (bytes.size.toLong() > Container.MAX_FILE_BYTES) {
                    _state.update {
                        it.copy(
                            busy = false,
                            error = "文件超过 ${Container.MAX_FILE_LABEL} 上限",
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
                _state.update { it.copy(busy = false, error = "读取/打包失败：${e.message}") }
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
            _state.update { it.copy(error = "请先选择文件") }
            return
        }
        val blockLen = _state.value.frameBytes - Wire.HEADER_LEN
        val k = (data.size + blockLen - 1) / blockLen
        if (k > 0xFFFF) {
            _state.update { it.copy(error = "块数超过 65535，请改用更大帧尺寸") }
            return
        }

        // 修复帧子集派生自 sessionId，因此编码器在每次开始时按新会话构建。
        val sessionId = rng.nextInt() and 0xFFFF
        val encoder = LTEncoder(data, blockLen, sessionId)
        val fnv = fnv1a(data)
        val fps = _state.value.fps

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
                seq = (seq + 1) and 0xFFFFFFFFL
                delay(periodMs)
            }
        }
    }

    fun stop() {
        sendJob?.cancel()
        sendJob = null
        _state.update { it.copy(sending = false) }
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
                error = if (k > 0xFFFF) "块数超过 65535，请改用更大帧尺寸" else it.error,
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
