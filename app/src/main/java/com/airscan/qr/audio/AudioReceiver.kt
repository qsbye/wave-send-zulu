package com.airscan.qr.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.airscan.qr.core.audio.AudioSegment
import com.airscan.qr.core.ggwave.GGWave

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

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (running) return
        running = true
        ggwave.resetRx()
        reassembler.reset()

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val ar = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, sampleRate),
        )
        record = ar
        ar.startRecording()

        thread = Thread {
            val buf = ShortArray(GGWave.kDefaultSamplesPerFrame)
            while (running) {
                val n = ar.read(buf, 0, buf.size)
                if (n <= 0) continue
                receiving = ggwave.isReceiving()
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
    }
}
