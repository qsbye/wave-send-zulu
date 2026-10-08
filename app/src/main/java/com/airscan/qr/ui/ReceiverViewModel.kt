package com.airscan.qr.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airscan.qr.core.Container
import com.airscan.qr.core.FrameHeader
import com.airscan.qr.core.FrameVerdict
import com.airscan.qr.core.LTDecoder
import com.airscan.qr.core.classifyFrame
import com.airscan.qr.core.fnv1a
import com.airscan.qr.core.frameVerdictMessage
import com.airscan.qr.core.parseFrame
import com.airscan.qr.core.streamIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CompletedFile(
    val name: String,
    val mimeType: String,
    val size: Long,
    val sha256Hex: String,
    val compression: String,
    val transmittedSize: Long,
    val bytes: ByteArray,
    val savedUri: Uri? = null,
)

data class ReceiverUiState(
    val locked: Boolean = false,
    val sessionId: Int = 0,
    val k: Int = 0,
    val blockLen: Int = 0,
    val totalLen: Long = 0,
    val uniqueFrames: Int = 0,
    val dupFrames: Int = 0,
    val redundantFrames: Int = 0,
    val malformed: Int = 0,
    val progress: Float = 0f,
    val hint: String? = null,
    val completed: CompletedFile? = null,
    val saving: Boolean = false,
)

class ReceiverViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ReceiverUiState())
    val state: StateFlow<ReceiverUiState> = _state.asStateFlow()

    private var identity: String? = null
    private var decoder: LTDecoder? = null
    private var expectedFnv: Long = 0

    /** 摄像头每解出一个二维码回调一次（分析线程），内部串行化。 */
    fun onFrame(bytes: ByteArray) {
        synchronized(this) {
            val verdict = classifyFrame(bytes)
            if (verdict !is FrameVerdict.Ok) {
                val msg = frameVerdictMessage(verdict)
                if (msg != null) _state.update { it.copy(hint = msg) }
                if (verdict is FrameVerdict.Malformed) {
                    _state.update { it.copy(malformed = it.malformed + 1) }
                }
                return
            }

            val parsed = parseFrame(bytes) ?: return
            val (header, block) = parsed

            val id = streamIdentity(header)
            if (id != identity) {
                resetStreamLocked(header, id)
            }
            val dec = decoder ?: return
            val done = _state.value.completed
            if (done != null) return

            dec.addFrame(header.seq, block)
            val progress = (dec.framesNew.toFloat() / dec.k).coerceIn(0f, 1f)
            _state.update {
                it.copy(
                    locked = true,
                    hint = null,
                    uniqueFrames = dec.framesNew,
                    dupFrames = dec.framesDup,
                    redundantFrames = dec.framesRedundant,
                    progress = progress,
                )
            }

            if (dec.isComplete) {
                finalizeLocked(dec, header)
            }
        }
    }

    private fun resetStreamLocked(header: FrameHeader, id: String) {
        identity = id
        expectedFnv = header.payloadFnv
        decoder = LTDecoder(
            k = header.k,
            blockLen = header.blockLen,
            sessionId = header.sessionId,
            totalLen = header.totalLen,
        )
        _state.update {
            ReceiverUiState(
                locked = true,
                sessionId = header.sessionId,
                k = header.k,
                blockLen = header.blockLen,
                totalLen = header.totalLen,
            )
        }
    }

    private fun finalizeLocked(dec: LTDecoder, header: FrameHeader) {
        val assembled = dec.assemble()
        if (assembled == null || fnv1a(assembled) != expectedFnv) {
            // 完整解出但容器校验不过：丢弃本次进度，下一帧会按相同流身份
            // 自动重建解码器，借助轮播重新收集。
            identity = null
            decoder = null
            _state.update {
                ReceiverUiState(hint = "容器校验失败（FNV 不匹配），正在借轮播重新接收…")
            }
            return
        }
        try {
            val file = Container.unpackFile(assembled)
            if (!Container.verify(file)) {
                _state.update { it.copy(hint = "SHA-256 校验失败，文件可能损坏，请重新接收") }
                return
            }
            _state.update {
                it.copy(
                    progress = 1f,
                    completed = CompletedFile(
                        name = file.name,
                        mimeType = file.type,
                        size = file.bytes.size.toLong(),
                        sha256Hex = com.airscan.qr.util.Fmt.hex(file.sha256),
                        compression = file.compression,
                        transmittedSize = file.transmittedSize,
                        bytes = file.bytes,
                    ),
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(hint = "容器解析失败：${e.message}") }
        }
    }

    /** 通过 SAF CreateDocument 返回的 uri 保存文件。 */
    fun saveTo(uri: Uri) {
        val done = _state.value.completed ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(saving = true) }
            try {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(done.bytes)
                }
                _state.update {
                    it.copy(
                        saving = false,
                        completed = it.completed?.copy(savedUri = uri),
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, hint = "保存失败：${e.message}") }
            }
        }
    }

    /** 清空当前接收结果，准备下一次传输（解码器在新流首帧时自动重建）。 */
    fun reset() {
        synchronized(this) {
            identity = null
            decoder = null
            _state.value = ReceiverUiState()
        }
    }
}
