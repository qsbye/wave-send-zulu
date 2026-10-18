package com.airscan.qr.ui

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airscan.qr.audio.AudioReceiver
import com.airscan.qr.core.Container
import com.airscan.qr.core.FrameHeader
import com.airscan.qr.core.FrameVerdict
import com.airscan.qr.core.LTDecoder
import com.airscan.qr.core.audio.AudioSegment
import com.airscan.qr.core.classifyFrame
import com.airscan.qr.core.fnv1a
import com.airscan.qr.core.frameVerdictMessage
import com.airscan.qr.core.parseFrame
import com.airscan.qr.core.streamIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.ceil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CompletedFile(
    val name: String,
    val mimeType: String,
    val size: Long,
    val sha256Hex: String,
    val compression: String,
    val transmittedSize: Long,
    val bytes: ByteArray,
    val savedUri: Uri? = null,
    val savedPath: String? = null,
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
    // ---- 摄像头开关（纯声波接收时可关闭省电） ----
    val cameraEnabled: Boolean = true,
    // ---- 声波接收 ----
    val audioEnabled: Boolean = false,
    val audioUltraSound: Boolean = false,
    val audioReceiving: Boolean = false,
    val audioLevels: List<Float> = emptyList(),
    val audioError: String? = null,
    // ---- 预计剩余时间（-1 = 暂无估计） ----
    val etaSeconds: Int = -1,
    val audioLockEta: Int = -1,
)

class ReceiverViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ReceiverUiState())
    val state: StateFlow<ReceiverUiState> = _state.asStateFlow()

    private var identity: String? = null
    private var decoder: LTDecoder? = null
    private var expectedFnv: Long = 0

    // ---- 声波接收 ----
    private var audioReceiver: AudioReceiver? = null
    private var audioPollJob: Job? = null

    /** 切换摄像头接收；关闭后仅用声波通道，省电。 */
    fun setCameraEnabled(enabled: Boolean) {
        _state.update { it.copy(cameraEnabled = enabled) }
    }

    init {
        // 常驻节拍：估算传输剩余时间与声波锁定剩余时间
        viewModelScope.launch(Dispatchers.Default) {
            val window = ArrayDeque<Pair<Long, Int>>() // (时间戳, 已收唯一帧数)
            while (isActive) {
                delay(250)
                val now = System.currentTimeMillis()
                val st = _state.value

                // 传输 ETA：6s 滑动窗口估计唯一帧到达速率 -> 剩余帧数/速率
                window.addLast(now to st.uniqueFrames)
                while (window.size > 2 && now - window.first().first > 6000) window.removeFirst()
                var eta = -1
                if (st.locked && st.completed == null && st.k > 0) {
                    val (t0, f0) = window.first()
                    val dt = (now - t0) / 1000f
                    val rate = if (dt > 0.5f) (st.uniqueFrames - f0) / dt else 0f
                    if (rate > 0.01f) {
                        eta = ceil((st.k - st.uniqueFrames) / rate).toInt().coerceIn(1, 86400)
                    }
                }

                // 声波锁定 ETA：未锁定且声波有半成品帧时，按剩余段数 × 单段耗时估计
                var lockEta = -1
                audioReceiver?.let { rx ->
                    val p = rx.pendingFrame()
                    if (p != null && p.gotCount < p.segCount) {
                        lockEta = ceil((p.segCount - p.gotCount) * AudioSegment.SEGMENT_DURATION_SEC).toInt()
                    }
                }

                if (eta != _state.value.etaSeconds || lockEta != _state.value.audioLockEta) {
                    _state.update { it.copy(etaSeconds = eta, audioLockEta = lockEta) }
                }
            }
        }
    }

    /** 切换声波接收开关（UI 层须先确保 RECORD_AUDIO 权限已授予）。 */
    fun setAudioEnabled(enabled: Boolean) {
        if (_state.value.audioEnabled == enabled) return
        if (enabled) {
            if (!startAudioReceiver()) return
            _state.update { it.copy(audioEnabled = true) }
        } else {
            stopAudioReceiver()
            _state.update {
                it.copy(audioEnabled = false, audioReceiving = false, audioLevels = emptyList(), audioError = null)
            }
        }
    }

    /** 切换超声档位；接收中的话重启接收器以切换协议。 */
    fun setAudioUltraSound(ultra: Boolean) {
        if (_state.value.audioUltraSound == ultra) return
        _state.update { it.copy(audioUltraSound = ultra) }
        if (_state.value.audioEnabled) {
            stopAudioReceiver()
            if (!startAudioReceiver()) {
                _state.update {
                    it.copy(audioEnabled = false, audioReceiving = false, audioLevels = emptyList(), audioError = null)
                }
            }
        }
    }

    /** 启动声波接收；失败（如权限被拒/设备忙）返回 false 并给出提示。 */
    private fun startAudioReceiver(): Boolean {
        if (audioReceiver != null) return true
        return try {
            val rx = AudioReceiver(ultraSound = _state.value.audioUltraSound) { frame ->
                // 声波重组帧与二维码帧格式一致，直接复用同一入口
                onFrame(frame)
            }
            rx.start()
            audioReceiver = rx
            // 轮询：接收指示灯 + 麦克风电平波形 + 故障提示
            audioPollJob = viewModelScope.launch(Dispatchers.Default) {
                while (isActive) {
                    _state.update {
                        it.copy(
                            audioReceiving = rx.receiving,
                            audioLevels = rx.snapshotLevels(),
                            audioError = rx.error,
                        )
                    }
                    delay(120)
                }
            }
            true
        } catch (e: SecurityException) {
            audioReceiver = null
            _state.update { it.copy(hint = "缺少麦克风权限，无法开启声波接收") }
            false
        } catch (e: Exception) {
            audioReceiver = null
            _state.update { it.copy(hint = "声波接收启动失败：${e.message}") }
            false
        }
    }

    private fun stopAudioReceiver() {
        audioPollJob?.cancel()
        audioPollJob = null
        audioReceiver?.stop()
        audioReceiver = null
    }

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
        // 用 copy 而非整包重建：锁定瞬间必须保留声波/摄像头开关等 UI 状态，
        // 否则用户看到的是"麦克风莫名自动关闭"（接收线程其实还在跑）。
        _state.update {
            it.copy(
                locked = true,
                sessionId = header.sessionId,
                k = header.k,
                blockLen = header.blockLen,
                totalLen = header.totalLen,
                uniqueFrames = 0,
                dupFrames = 0,
                redundantFrames = 0,
                malformed = 0,
                progress = 0f,
                hint = null,
                completed = null,
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
            // 同样用 copy：校验失败重收时保留声波/摄像头开关状态
            _state.update {
                it.copy(
                    locked = false,
                    sessionId = 0,
                    k = 0,
                    blockLen = 0,
                    totalLen = 0,
                    uniqueFrames = 0,
                    dupFrames = 0,
                    redundantFrames = 0,
                    progress = 0f,
                    hint = "容器校验失败（FNV 不匹配），正在借轮播重新接收…",
                    completed = null,
                )
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
            // 接收完成即自动保存到系统 Downloads/AirFountainZulu（无需存储权限）
            autoSave(file)
        } catch (e: Exception) {
            _state.update { it.copy(hint = "容器解析失败：${e.message}") }
        }
    }

    /** 自动保存到系统 Downloads/AirFountainZulu（MediaStore，目录自动新建）。 */
    private fun autoSave(file: Container.OpticalFile) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val outName = "${stamp}-${file.name}"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, outName)
                    put(MediaStore.MediaColumns.MIME_TYPE, file.type)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/AirFountainZulu")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    values,
                ) ?: error("MediaStore 拒绝写入")
                resolver.openOutputStream(uri)?.use { it.write(file.bytes) }
                    ?: error("无法打开输出流")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                _state.update {
                    it.copy(
                        completed = it.completed?.copy(
                            savedUri = uri,
                            savedPath = "Download/AirFountainZulu/$outName",
                        ),
                    )
                }
            } catch (e: Exception) {
                // 自动保存失败不影响接收结果，保留手动另存入口
                _state.update { it.copy(hint = "自动保存失败：${e.message}，可使用「另存为」") }
            }
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
            _state.value = ReceiverUiState(
                audioEnabled = _state.value.audioEnabled,
                audioUltraSound = _state.value.audioUltraSound,
                audioReceiving = _state.value.audioReceiving,
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopAudioReceiver()
    }
}
