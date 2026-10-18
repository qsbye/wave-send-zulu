package com.airscan.qr.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.airscan.qr.core.audio.AudioSegment
import com.airscan.qr.core.ggwave.GGWave
import kotlin.math.abs

/**
 * 扬声器发送器：把喷泉码帧分段后用 ggwave 调制成音频循环播放。
 *
 * 用法：
 *   val tx = AudioSender(protocolId, volume)
 *   tx.start()                       // 启动播放线程
 *   tx.offer(sessionId, seq, frame)  // 入队一帧（非阻塞，满则丢弃）
 *   tx.stop()
 *
 * 播放循环从队列取帧；队列为空时短暂静默后重试（喷泉码由发送端按节拍喂帧，
 * 因此这里通常每帧到点恰好有一个新帧可播）。
 */
class AudioSender(
    private val protocolId: GGWave.ProtocolId = GGWave.ProtocolId.AUDIBLE_FAST,
    private val volume: Int = 50,
    private val ultraSound: Boolean = false,
) {
    private val sampleRate = GGWave.kDefaultSampleRate.toInt()

    private val ggwave = GGWave(
        GGWave.Parameters(
            payloadLength = AudioSegment.SEGMENT_BYTES, // 固定 64B 段
            sampleRateInp = sampleRate.toFloat(),
            sampleRateOut = sampleRate.toFloat(),
            sampleRate = sampleRate.toFloat(),
            samplesPerFrame = GGWave.kDefaultSamplesPerFrame,
            operatingMode = GGWave.OperatingMode.TX,
        )
    )

    private val proto = if (ultraSound) GGWave.ProtocolId.ULTRASOUND_FAST else protocolId

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    // 单槽帧队列（保留最新帧，配合喷泉码"任意帧都行"）
    private val lock = Object()
    private var pending: Triple<Int, Long, ByteArray>? = null

    // ---- 发送波形电平历史（环形，供 UI 显示） ----
    private val levelLock = Object()
    private val levels = FloatArray(256)
    private var levelCount = 0
    private var levelPos = 0

    /** 取最近播放电平历史（按时间升序；静默期记 0）。 */
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

    /** 入队一帧（覆盖未播的旧帧）。 */
    fun offer(sessionId: Int, seq: Long, frame: ByteArray) {
        if (!AudioSegment.supportsFrame(frame.size)) return
        synchronized(lock) {
            pending = Triple(sessionId, seq, frame)
            lock.notify()
        }
    }

    fun start() {
        if (running) return
        running = true
        synchronized(levelLock) { levelCount = 0; levelPos = 0 }
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val at = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, sampleRate * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC, sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, sampleRate * 2), AudioTrack.MODE_STREAM,
            )
        }
        track = at
        at.play()

        thread = Thread {
            while (running) {
                val item: Triple<Int, Long, ByteArray>?
                synchronized(lock) {
                    item = pending
                    pending = null
                    if (item == null) {
                        try {
                            lock.wait(200)
                        } catch (_: InterruptedException) {
                        }
                    }
                }
                if (!running) break
                if (item == null) {
                    pushLevel(0f) // 静默期
                    continue
                }
                val (sessionId, seq, frame) = item
                val segments = AudioSegment.split(sessionId, seq, frame)
                for (seg in segments) {
                    if (!running) break
                    val wave = ggwave.encode(seg, proto, volume)
                    // 峰值包络（每段 128 点），供波形显示
                    val buckets = 128
                    val per = wave.size / buckets
                    if (per > 0) {
                        for (b in 0 until buckets) {
                            var m = 0
                            for (i in b * per until (b + 1) * per) {
                                val a = abs(wave[i].toInt())
                                if (a > m) m = a
                            }
                            pushLevel(((m / 32768f) * 1.4f).coerceIn(0f, 1f))
                        }
                    }
                    at.write(wave, 0, wave.size)
                }
            }
            runCatching { at.stop() }
            runCatching { at.release() }
        }.also { it.start() }
    }

    fun stop() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        thread?.join(1500)
        thread = null
        track = null
    }
}
