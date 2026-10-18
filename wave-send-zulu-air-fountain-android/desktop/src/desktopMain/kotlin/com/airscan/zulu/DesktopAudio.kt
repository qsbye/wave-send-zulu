package com.airscan.zulu

import com.airscan.qr.core.audio.AudioSegment
import com.airscan.qr.core.ggwave.GGWave
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/**
 * 桌面端声波发送器：喷泉码帧 -> 64B 声波段 -> ggwave 调制 -> 扬声器。
 *
 * 与 Android 端 AudioSender 行为一致：单槽队列（新帧覆盖未播旧帧），
 * 契合喷泉码"任意帧都有用"的模型。协议档位 AUDIBLE_FAST / ULTRASOUND_FAST。
 */
class DesktopAudioSender(
    private val ultraSound: Boolean = false,
    private val volume: Int = 60,
) {
    private val sampleRate = GGWave.kDefaultSampleRate.toInt()
    private val protocolId =
        if (ultraSound) GGWave.ProtocolId.ULTRASOUND_FAST else GGWave.ProtocolId.AUDIBLE_FAST

    private val ggwave = GGWave(
        GGWave.Parameters(
            payloadLength = AudioSegment.SEGMENT_BYTES,
            operatingMode = GGWave.OperatingMode.TX,
        )
    )

    private class Pending(val sessionId: Int, val seq: Long, val frame: ByteArray)

    private val lock = Object()
    private var pending: Pending? = null

    @Volatile
    private var running = false
    private var thread: Thread? = null

    /** 喂入一个喷泉码帧；若上一帧还没播完则被覆盖。超长帧直接丢弃。 */
    fun offer(sessionId: Int, seq: Long, frame: ByteArray) {
        if (!AudioSegment.supportsFrame(frame.size)) return
        synchronized(lock) {
            pending = Pending(sessionId, seq, frame)
            lock.notifyAll()
        }
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
            val line = AudioSystem.getSourceDataLine(format)
            try {
                line.open(format, sampleRate * 2)
                line.start()
                while (running) {
                    val item = synchronized(lock) {
                        var p = pending
                        if (p == null) {
                            runCatching { lock.wait(500) }
                            p = pending
                        }
                        pending = null
                        p
                    } ?: continue
                    for (seg in AudioSegment.split(item.sessionId, item.seq, item.frame)) {
                        if (!running) break
                        val samples = ggwave.encode(seg, protocolId, volume)
                        val bytes = ByteArray(samples.size * 2)
                        for (i in samples.indices) {
                            val v = samples[i].toInt()
                            bytes[2 * i] = (v and 0xFF).toByte()
                            bytes[2 * i + 1] = ((v ushr 8) and 0xFF).toByte()
                        }
                        line.write(bytes, 0, bytes.size)
                    }
                }
            } finally {
                runCatching { line.stop() }
                runCatching { line.close() }
            }
        }.apply {
            isDaemon = true
            name = "DesktopAudioSender"
            start()
        }
    }

    fun stop() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        thread?.join(1500)
        thread = null
    }
}

/**
 * 桌面端声波接收器：麦克风 -> ggwave 解调 -> 段重组 -> 喷泉码帧回调。
 *
 * onFrame 在接收线程触发，产出与二维码完全相同的原始帧（22B 头 + blockLen）。
 */
class DesktopAudioReceiver(
    private val ultraSound: Boolean = false,
    private val onFrame: (ByteArray) -> Unit,
) {
    private val sampleRate = GGWave.kDefaultSampleRate.toInt()

    private val ggwave = GGWave(
        GGWave.Parameters(
            payloadLength = AudioSegment.SEGMENT_BYTES,
            operatingMode = GGWave.OperatingMode.RX,
        )
    ).also { g ->
        val target = if (ultraSound) GGWave.ProtocolId.ULTRASOUND_FAST.id
        else GGWave.ProtocolId.AUDIBLE_FAST.id
        g.rxProtocols.forEachIndexed { i, p -> p.enabled = (i == target) }
    }

    private val reassembler = AudioSegment.Reassembler()

    @Volatile
    private var running = false
    private var thread: Thread? = null

    @Volatile
    var receiving: Boolean = false
        private set

    fun start() {
        if (running) return
        running = true
        ggwave.resetRx()
        reassembler.reset()
        thread = Thread {
            val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
            val line = AudioSystem.getTargetDataLine(format)
            try {
                line.open(format, sampleRate * 2)
                line.start()
                val bytes = ByteArray(GGWave.kDefaultSamplesPerFrame * 2)
                val shorts = ShortArray(GGWave.kDefaultSamplesPerFrame)
                while (running) {
                    val n = line.read(bytes, 0, bytes.size)
                    if (n <= 0) continue
                    val cnt = n / 2
                    for (i in 0 until cnt) {
                        shorts[i] = ((bytes[2 * i].toInt() and 0xFF) or
                            (bytes[2 * i + 1].toInt() shl 8)).toShort()
                    }
                    receiving = ggwave.isReceiving()
                    val payload = ggwave.decode(shorts, cnt) ?: continue
                    val seg = AudioSegment.parse(payload) ?: continue
                    val frame = reassembler.feed(seg) ?: continue
                    onFrame(frame)
                }
            } finally {
                runCatching { line.stop() }
                runCatching { line.close() }
            }
        }.apply {
            isDaemon = true
            name = "DesktopAudioReceiver"
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
        receiving = false
    }
}
