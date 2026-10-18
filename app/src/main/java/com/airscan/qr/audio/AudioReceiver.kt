package com.airscan.qr.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.airscan.qr.core.audio.AudioSegment
import com.airscan.qr.core.ggwave.GGWave
import kotlin.math.abs

/**
 * 麦克风接收器：录音 -> ggwave 解调 -> 段重组 -> 产出喷泉码帧。
 *
 * 用法：
 *   val rx = AudioReceiver(onFrame = { frame -> ... })
 *   rx.start()
 *   rx.stop()
 *
 * onFrame 回调在接收线程上触发，产出与二维码接收端完全相同的原始喷泉码帧
 * （22B 头 + blockLen），可直接交给 Wire.parseFrame + LTDecoder。
 *
 * 附带：
 *  - receiving：带 1.2s 粘滞的“检测到声波信号”指示（供 UI 轮询）
 *  - error：麦克风初始化/读取故障提示（供 UI 显示，start() 时清零）
 *  - snapshotLevels()：最近麦克风电平（0..1）历史，供波形显示
 */
class AudioReceiver(
    private val ultraSound: Boolean = false,
    private val onFrame: (ByteArray) -> Unit,
) {
    private val sampleRate = GGWave.kDefaultSampleRate.toInt()

    private val ggwave = GGWave(
        GGWave.Parameters(
            payloadLength = AudioSegment.SEGMENT_BYTES,
            sampleRateInp = sampleRate.toFloat(),
            sampleRateOut = sampleRate.toFloat(),
            sampleRate = sampleRate.toFloat(),
            samplesPerFrame = GGWave.kDefaultSamplesPerFrame,
            operatingMode = GGWave.OperatingMode.RX,
        )
    ).also { g ->
        // 只保留目标协议，减少误检
        val target = if (ultraSound) GGWave.ProtocolId.ULTRASOUND_FAST.id
        else GGWave.ProtocolId.AUDIBLE_FAST.id
        g.rxProtocols.forEachIndexed { i, p -> p.enabled = (i == target) }
    }

    private val reassembler = AudioSegment.Reassembler()

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    @Volatile
    var receiving: Boolean = false
        private set

    @Volatile
    var error: String? = null
        private set

    // ---- 输入电平历史（环形，供波形显示） ----
    private val levelLock = Object()
    private val levels = FloatArray(256)
    private var levelCount = 0
    private var levelPos = 0

    @Volatile
    private var lastSignalMs = 0L

    /** 取最近电平历史（按时间升序，每个点约 21ms）。 */
    fun snapshotLevels(): List<Float> = synchronized(levelLock) {
        val out = ArrayList<Float>(levelCount)
        val start = (levelPos - levelCount + levels.size) % levels.size
        for (i in 0 until levelCount) out.add(levels[(start + i) % levels.size])
        out
    }

    private fun pushLevel(v: Float) {
        synchronized(levelLock) {
            levels[levelPos] = v
            levelPos = (levelPos + 1) % levels.size
            if (levelCount < levels.size) levelCount++
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (running) return
        running = true
        error = null
        lastSignalMs = 0L
        receiving = false
        synchronized(levelLock) { levelCount = 0; levelPos = 0 }
        ggwave.resetRx()
        reassembler.reset()

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // VOICE_RECOGNITION 不做 AGC/降噪，持续音调的保真度远好于默认 MIC 源
        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, sampleRate),
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            running = false
            error = "麦克风初始化失败（可能被其他应用占用或不支持 48kHz）"
            return
        }
        record = ar
        ar.startRecording()

        thread = Thread {
            val buf = ShortArray(GGWave.kDefaultSamplesPerFrame)
            var readFailures = 0
            while (running) {
                val n = ar.read(buf, 0, buf.size)
                if (n <= 0) {
                    if (n < 0 && ++readFailures >= 10) {
                        error = "录音读取失败（错误码 $n）"
                    }
                    continue
                }
                readFailures = 0

                // 电平（峰值归一，带固定增益便于观察）
                var peak = 0
                for (i in 0 until n) {
                    val a = abs(buf[i].toInt())
                    if (a > peak) peak = a
                }
                pushLevel(((peak / 32768f) * 2.0f).coerceIn(0f, 1f))

                if (ggwave.isReceiving()) lastSignalMs = System.currentTimeMillis()
                // 粘滞 1.2s：段与段之间允许短暂间隙，避免指示灯闪烁
                receiving = System.currentTimeMillis() - lastSignalMs <= 1200

                val payload = ggwave.decode(buf, n) ?: continue
                val seg = AudioSegment.parse(payload) ?: continue
                val frame = reassembler.feed(seg)
                if (frame != null) {
                    onFrame(frame)
                }
            }
            runCatching { ar.stop() }
            runCatching { ar.release() }
        }.also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
        record = null
        receiving = false
    }
}
