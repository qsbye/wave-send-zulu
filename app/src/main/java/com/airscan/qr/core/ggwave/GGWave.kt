package com.airscan.qr.core.ggwave

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * ggwave 声波数据调制/解调引擎，纯 Kotlin 全量移植。
 *
 * 移植自 ggwave src/ggwave.cpp（ggerganov/ggwave，MIT）。实现了：
 *  - 12 种协议（audible / ultrasound / DT / MT × Normal/Fast/Fastest）
 *  - 固定帧长（fixed payload）与变长（variable payload）两种工作模式
 *  - Reed-Solomon 前向纠错、可选 DSS 直扩、16 进制音调 FSK 调制
 *  - 发送：payload -> 音频波形（I16 样本）
 *  - 接收：音频波形 -> payload（流式，逐帧喂入）
 *
 * 与 C 版保持线格式兼容：同一 payload 经两端各自编码/解码结果一致。
 */
class GGWave(parameters: Parameters) {

    // ---- 协议 id ----
    enum class ProtocolId(val id: Int) {
        AUDIBLE_NORMAL(0),
        AUDIBLE_FAST(1),
        AUDIBLE_FASTEST(2),
        ULTRASOUND_NORMAL(3),
        ULTRASOUND_FAST(4),
        ULTRASOUND_FASTEST(5),
        DT_NORMAL(6),
        DT_FAST(7),
        DT_FASTEST(8),
        MT_NORMAL(9),
        MT_FAST(10),
        MT_FASTEST(11),
        COUNT(12);
    }

    data class Protocol(
        val name: String,
        val freqStart: Int,      // 最低频率的 FFT bin
        val framesPerTx: Int,    // 每个数据块的音频帧数
        val bytesPerTx: Int,     // 每个数据块的字节数
        val extra: Int,          // 1=多音（每字节双音调），2=单音
        var enabled: Boolean = true,
    ) {
        fun nTones(): Int = (2 * bytesPerTx) / extra
        fun nDataBitsPerTx(): Int = 8 * bytesPerTx
    }

    /** 工作模式标志。 */
    object OperatingMode {
        const val RX = 1 shl 1
        const val TX = 1 shl 2
        const val RX_AND_TX = RX or TX
        const val TX_ONLY_TONES = 1 shl 3
        const val USE_DSS = 1 shl 4
    }

    data class Parameters(
        var payloadLength: Int = -1,          // <=0 变长；>0 固定帧长
        var sampleRateInp: Float = 48000f,
        var sampleRateOut: Float = 48000f,
        var sampleRate: Float = 48000f,
        var samplesPerFrame: Int = 1024,
        var soundMarkerThreshold: Float = 3.0f,
        var operatingMode: Int = OperatingMode.RX_AND_TX,
    )

    companion object {
        const val kDefaultSampleRate = 48000f
        const val kDefaultSamplesPerFrame = 1024
        const val kDefaultMarkerFrames = 16
        const val kDefaultEncodedDataOffset = 3
        const val kMaxSamplesPerFrame = 1024
        const val kMaxLengthVariable = 140
        const val kMaxLengthFixed = 64
        const val kMaxSpectrumHistory = 4
        const val kMaxRecordedFrames = 2048

        private const val kDSSMagicSize = 64
        private val kDSSMagic = intArrayOf(
            0x96, 0x9f, 0xb4, 0xaf, 0x1b, 0x91, 0xde, 0xc5, 0x45, 0x75, 0xe8, 0x2e, 0x0f, 0x32, 0x4a, 0x5f,
            0xb4, 0x56, 0x95, 0xcb, 0x7f, 0x6a, 0x54, 0x6a, 0x48, 0xf2, 0x0b, 0x7b, 0xcd, 0xfb, 0x93, 0x6d,
            0x3c, 0x77, 0x5e, 0xc3, 0x33, 0x47, 0xc0, 0xf1, 0x71, 0x32, 0x33, 0x27, 0x35, 0x68, 0x47, 0x1f,
            0x4e, 0xac, 0x23, 0x42, 0x5f, 0x00, 0x37, 0xa4, 0x50, 0x6d, 0x48, 0x24, 0x91, 0x7c, 0xa1, 0x4e,
        )

        private fun dssMagic(i: Int): Int = kDSSMagic[i % kDSSMagicSize]

        /** 默认协议表，与 C 版 Protocols::kDefault() 完全一致。 */
        fun defaultProtocols(): Array<Protocol> = arrayOf(
            Protocol("Normal",          40,  9, 3, 1),
            Protocol("Fast",            40,  6, 3, 1),
            Protocol("Fastest",         40,  3, 3, 1),
            Protocol("[U] Normal",      320, 9, 3, 1),
            Protocol("[U] Fast",        320, 6, 3, 1),
            Protocol("[U] Fastest",     320, 3, 3, 1),
            Protocol("[DT] Normal",     24,  9, 1, 1),
            Protocol("[DT] Fast",       24,  6, 1, 1),
            Protocol("[DT] Fastest",    24,  3, 1, 1),
            Protocol("[MT] Normal",     24,  9, 1, 2),
            Protocol("[MT] Fast",       24,  6, 1, 2),
            Protocol("[MT] Fastest",    24,  3, 1, 2),
        )

        internal fun eccBytesForLength(len: Int): Int = if (len < 4) 2 else max(4, 2 * (len / 5))
    }

    // ---- 派生参数 ----
    private val sampleRateInp = parameters.sampleRateInp
    private val sampleRateOut = parameters.sampleRateOut
    private val sampleRate = parameters.sampleRate
    private val samplesPerFrame = parameters.samplesPerFrame
    private val isamplesPerFrame = 1.0f / samplesPerFrame
    private val hzPerSample = sampleRate / samplesPerFrame
    private val ihzPerSample = 1.0f / hzPerSample
    private val freqDeltaBin = 1
    private val freqDeltaHz = 2 * hzPerSample
    private val nBitsInMarker = 16
    private val isFixedPayloadLength = parameters.payloadLength > 0
    private val payloadLength = parameters.payloadLength
    private val nMarkerFrames = if (isFixedPayloadLength) 0 else kDefaultMarkerFrames
    private val encodedDataOffset = if (isFixedPayloadLength) 0 else kDefaultEncodedDataOffset
    private val soundMarkerThreshold = parameters.soundMarkerThreshold
    private val isRxEnabled = parameters.operatingMode and OperatingMode.RX != 0
    private val isTxEnabled = parameters.operatingMode and OperatingMode.TX != 0
    private val needResampling = sampleRateInp != sampleRate || sampleRateOut != sampleRate
    private val isDSSEnabled = parameters.operatingMode and OperatingMode.USE_DSS != 0

    private val maxLength = if (isFixedPayloadLength) payloadLength else kMaxLengthVariable

    // 协议表（可在外部修改 enabled 后再使用）
    val rxProtocols: Array<Protocol> = defaultProtocols()
    val txProtocols: Array<Protocol> = defaultProtocols()

    // FFT 工作区
    private val rdft = Rdft(samplesPerFrame)
    private val resampler = Resampler()

    // ---- 共享编码缓冲 ----
    private val dataEncoded = ByteArray(maxLength + eccBytesForLength(maxLength) + encodedDataOffset + 8)

    // ---- Tx 状态 ----
    private var txHasData = false
    private var txDataLength = 0
    private var txSendVolume = 0f
    private var txProtocol: Protocol = txProtocols[ProtocolId.AUDIBLE_FAST.id]
    private val txData = ByteArray(maxLength + 1) // 第 0 字节存长度

    // ---- Rx 状态 ----
    private var rxReceiving = false
    private var rxAnalyzing = false
    private var rxSamplesNeeded = samplesPerFrame
    private var rxFramesToRecord = 0
    private var rxFramesLeftToRecord = 0
    private var rxFramesToAnalyze = 0
    private var rxRecvDurationFrames = 0
    private var rxDataLength = 0
    private var rxMarkerFreqStart = 0
    private var rxNMarkersSuccess = 0
    private var rxHistoryId = 0
    private var rxHistoryIdFixed = 0

    private val rxData = ByteArray(maxLength + 1)
    private val rxSpectrum = FloatArray(samplesPerFrame)
    private val rxAmplitude = FloatArray(samplesPerFrame)
    private val rxAmplitudeResampled = FloatArray(if (needResampling) 8 * samplesPerFrame else samplesPerFrame)
    private val rxFftOut = FloatArray(2 * samplesPerFrame)

    // 输入 FIFO（Short 样本），支持任意大小分块的流式解码
    private val inFifo = ShortArray(2 * samplesPerFrame)
    private var inFifoLen = 0

    // 固定帧长：频谱历史（uint8 量化）
    private val fixedTotalTxs = if (isFixedPayloadLength)
        (maxLength + eccBytesForLength(maxLength) + minBytesPerTx(rxProtocols) - 1) / minBytesPerTx(rxProtocols) else 0
    private val spectrumHistoryFixedLen = if (isFixedPayloadLength)
        fixedTotalTxs * maxFramesPerTx(rxProtocols, false) else 1
    private val spectrumHistoryFixed = Array(max(1, spectrumHistoryFixedLen)) { FloatArray(samplesPerFrame) }
    private val detectedBins = IntArray(2 * (maxLength + eccBytesForLength(maxLength)) + 8)
    private val detectedTones = IntArray(2 * 16 * maxBytesPerTx(rxProtocols) + 32)

    // 变长：幅值历史 / 录制缓冲
    private val amplitudeHistory = Array(kMaxSpectrumHistory) { FloatArray(samplesPerFrame) }
    private val amplitudeAverage = FloatArray(samplesPerFrame)
    private val amplitudeRecorded = FloatArray(if (isFixedPayloadLength) 0 else kMaxRecordedFrames * samplesPerFrame)

    // ---- 协议工具 ----
    private fun maxFramesPerTx(protocols: Array<Protocol>, excludeMT: Boolean): Int {
        var res = 0
        for (p in protocols) {
            if (!p.enabled) continue
            if (excludeMT && p.extra > 1) continue
            res = max(res, p.framesPerTx * p.extra)
        }
        return res
    }

    private fun minBytesPerTx(protocols: Array<Protocol>): Int {
        var res = 1
        for (p in protocols) if (p.enabled) res = min(res, p.bytesPerTx)
        return res
    }

    private fun maxBytesPerTx(protocols: Array<Protocol>): Int {
        var res = 1
        for (p in protocols) if (p.enabled) res = max(res, p.bytesPerTx)
        return res
    }

    private fun minFreqStart(protocols: Array<Protocol>): Int {
        var res = samplesPerFrame
        for (p in protocols) if (p.enabled) res = min(res, p.freqStart)
        return res
    }

    private fun bitFreq(p: Protocol, bit: Int): Double =
        (hzPerSample * p.freqStart + freqDeltaHz * bit).toDouble()

    // =====================================================================
    // Tx：payload -> 音频波形
    // =====================================================================

    /** 编码结果为 16-bit 样本，便于直接送 AudioTrack / SourceDataLine。 */
    fun encode(payload: ByteArray, protocolId: ProtocolId, volume: Int): ShortArray {
        require(isTxEnabled) { "Tx is disabled" }
        require(volume in 0..100) { "Invalid volume: $volume" }

        var dataSize = payload.size
        if (dataSize > maxLength) dataSize = maxLength

        val protocol = txProtocols[protocolId.id]
        require(protocol.enabled) { "Protocol not enabled" }
        require(!(protocol.extra == 2 && !isFixedPayloadLength)) { "Mono-tone protocols need fixed length" }

        txProtocol = protocol
        txDataLength = if (isFixedPayloadLength) payloadLength else dataSize
        txSendVolume = volume / 100.0f

        txData.fill(0)
        dataEncoded.fill(0)
        txData[0] = txDataLength.toByte()
        for (i in 0 until txDataLength) {
            var b = if (i < dataSize) payload[i].toInt() and 0xFF else 0
            if (isDSSEnabled) b = b xor dssMagic(i)
            txData[i + 1] = b.toByte()
        }

        // ---- RS 编码 ----
        val nECCBytesPerTx = eccBytesForLength(txDataLength)
        val sendDataLength = txDataLength + encodedDataOffset
        val totalBytes = sendDataLength + nECCBytesPerTx
        val totalDataFrames = protocol.extra *
            ((totalBytes + protocol.bytesPerTx - 1) / protocol.bytesPerTx) * protocol.framesPerTx

        if (!isFixedPayloadLength) {
            // 长度字段本身也做 RS（1 + (offset-1) 字节）
            val rsLength = ReedSolomon(1, encodedDataOffset - 1)
            val encLen = rsLength.encode(byteArrayOf(txData[0]))
            encLen.copyInto(dataEncoded, 0, 0, 0 + encodedDataOffset)
        }

        val rsData = ReedSolomon(txDataLength, nECCBytesPerTx)
        val body = ByteArray(txDataLength)
        txData.copyInto(body, 0, 1, 1 + txDataLength)
        val encBody = rsData.encode(body)
        encBody.copyInto(dataEncoded, encodedDataOffset, 0, 0 + txDataLength + nECCBytesPerTx)

        // ---- 预计算每个数据 bit 的双音调幅值表 ----
        val nDataBits = 2 * 16 * protocol.bytesPerTx
        val phaseOffsets = FloatArray(nDataBits)
        for (k in 0 until nDataBits) {
            phaseOffsets[k] = (PI * k / protocol.nDataBitsPerTx()).toFloat()
        }
        val bit0 = Array(nDataBits) { FloatArray(samplesPerFrame) }
        val bit1 = Array(nDataBits) { FloatArray(samplesPerFrame) }
        for (k in 0 until nDataBits) {
            val freq = bitFreq(protocol, k)
            val phaseOffset = phaseOffsets[k]
            val curHzPerSample = hzPerSample.toDouble()
            val curIHz = 1.0 / curHzPerSample
            for (i in 0 until samplesPerFrame) {
                bit1[k][i] = sin((2.0 * PI) * (i * isamplesPerFrame.toDouble()) * (freq * curIHz) + phaseOffset).toFloat()
            }
            for (i in 0 until samplesPerFrame) {
                bit0[k][i] = sin((2.0 * PI) * (i * isamplesPerFrame.toDouble()) * ((freq + hzPerSample * freqDeltaBin) * curIHz) + phaseOffset).toFloat()
            }
        }

        // ---- 逐帧合成 ----
        val output = FloatArray(samplesPerFrame)
        val totalFrames = nMarkerFrames + totalDataFrames + nMarkerFrames
        val samplesPerFrameOut = samplesPerFrame // 本工程重采样率相等
        val out = ShortArray(totalFrames * samplesPerFrameOut)
        val dataBits = IntArray(2 * 16 * protocol.bytesPerTx)

        var frameId = 0
        var offset = 0
        while (frameId < totalFrames) {
            output.fill(0f)
            var nFreq = 0

            when {
                frameId < nMarkerFrames -> {
                    nFreq = nBitsInMarker
                    for (i in 0 until nBitsInMarker) {
                        if (i % 2 == 0) {
                            addAmplitudeSmooth(bit1[i], output, txSendVolume, frameId, nMarkerFrames)
                        } else {
                            addAmplitudeSmooth(bit0[i], output, txSendVolume, frameId, nMarkerFrames)
                        }
                    }
                }
                frameId < nMarkerFrames + totalDataFrames -> {
                    var dataOffset = frameId - nMarkerFrames
                    val cycleModMain = dataOffset % protocol.framesPerTx
                    dataOffset /= protocol.framesPerTx
                    dataOffset *= protocol.bytesPerTx

                    dataBits.fill(0)
                    for (j in 0 until protocol.bytesPerTx) {
                        if (protocol.extra == 1) {
                            val d0 = dataEncoded[dataOffset + j].toInt() and 15
                            dataBits[(2 * j + 0) * 16 + d0] = 1
                            val d1 = dataEncoded[dataOffset + j].toInt() and 240
                            dataBits[(2 * j + 1) * 16 + (d1 shr 4)] = 1
                        } else {
                            if (dataOffset % protocol.extra == 0) {
                                val d = dataEncoded[dataOffset / protocol.extra + j].toInt() and 15
                                dataBits[(2 * j + 0) * 16 + d] = 1
                            } else {
                                val d = dataEncoded[dataOffset / protocol.extra + j].toInt() and 240
                                dataBits[(2 * j + 0) * 16 + (d shr 4)] = 1
                            }
                        }
                    }

                    for (k in 0 until 2 * protocol.bytesPerTx * 16) {
                        if (dataBits[k] == 0) continue
                        ++nFreq
                        if (k % 2 == 1) {
                            addAmplitudeSmooth(bit0[k / 2], output, txSendVolume, cycleModMain, protocol.framesPerTx)
                        } else {
                            addAmplitudeSmooth(bit1[k / 2], output, txSendVolume, cycleModMain, protocol.framesPerTx)
                        }
                    }
                }
                else -> {
                    nFreq = nBitsInMarker
                    val fId = frameId - (nMarkerFrames + totalDataFrames)
                    for (i in 0 until nBitsInMarker) {
                        if (i % 2 == 0) {
                            addAmplitudeSmooth(bit0[i], output, txSendVolume, fId, nMarkerFrames)
                        } else {
                            addAmplitudeSmooth(bit1[i], output, txSendVolume, fId, nMarkerFrames)
                        }
                    }
                }
            }

            if (nFreq == 0) nFreq = 1
            val scale = 1.0f / nFreq
            for (i in 0 until samplesPerFrame) {
                val v = output[i] * scale
                out[offset + i] = (32768f * v).toInt().coerceIn(-32768, 32767).toShort()
            }

            ++frameId
            offset += samplesPerFrameOut
        }

        return out
    }

    private fun addAmplitudeSmooth(
        src: FloatArray, dst: FloatArray, scalar: Float, cycleMod: Int, nPerCycle: Int,
    ) {
        val startId = 0
        val finalId = samplesPerFrame
        val nTotal = nPerCycle * finalId
        val frac = 0.15f
        val ds = frac * nTotal
        val ids = 1.0f / ds
        val nBegin = frac * nTotal
        val nEnd = (1.0f - frac) * nTotal

        for (i in startId until finalId) {
            val k = cycleMod * finalId + i
            dst[i] += when {
                k < nBegin -> scalar * src[i] * (k * ids)
                k > nEnd -> scalar * src[i] * ((nTotal - k) * ids)
                else -> scalar * src[i]
            }
        }
    }

    // =====================================================================
    // Rx：音频波形 -> payload（流式）
    // =====================================================================

    /**
     * 喂入一段 16-bit 样本。返回解码出的 payload；无完整结果返回 null。
     * 可在任意大小的块上反复调用。
     */
    fun decode(samples: ShortArray, count: Int = samples.size): ByteArray? {
        if (!isRxEnabled) return null

        // 输入 FIFO：任意长度的输入先缓冲，按 samplesPerFrame 对齐逐帧解码
        val scale = 1.0f / 32768f
        var pos = 0
        while (pos < count) {
            val n = minOf(count - pos, inFifo.size - inFifoLen)
            for (i in 0 until n) inFifo[inFifoLen + i] = samples[pos + i]
            inFifoLen += n
            pos += n
            if (inFifoLen < samplesPerFrame) break

            // 取出一个完整帧
            for (i in 0 until samplesPerFrame) {
                rxAmplitude[i] = inFifo[i] * scale
            }
            val leftover = inFifoLen - samplesPerFrame
            if (leftover > 0) inFifo.copyInto(inFifo, 0, samplesPerFrame, samplesPerFrame + leftover)
            inFifoLen = leftover

            if (isFixedPayloadLength) decodeFixed() else decodeVariable()
        }

        if (rxDataLength > 0) {
            val res = ByteArray(rxDataLength)
            rxData.copyInto(res, 0, 0, 0 + rxDataLength)
            rxDataLength = 0
            return res
        }
        return null
    }

    /** 重置接收状态（开始新一轮监听前调用）。 */
    fun resetRx() {
        rxReceiving = false
        rxAnalyzing = false
        rxSamplesNeeded = samplesPerFrame
        rxFramesToRecord = 0
        rxFramesLeftToRecord = 0
        rxFramesToAnalyze = 0
        rxRecvDurationFrames = 0
        rxDataLength = 0
        rxHistoryId = 0
        rxHistoryIdFixed = 0
        inFifoLen = 0
        rxSpectrum.fill(0f)
        rxAmplitude.fill(0f)
        rxData.fill(0)
        for (h in spectrumHistoryFixed) h.fill(0f)
        for (h in amplitudeHistory) h.fill(0f)
    }

    fun isReceiving(): Boolean = rxReceiving

    // ---- 固定帧长解码 ----
    private fun decodeFixed() {
        // FFT
        rxAmplitude.copyInto(rxFftOut, 0, 0, 0 + samplesPerFrame)
        rdft.rdft(rxFftOut)

        var amax = 0.0f
        val minFreq = minFreqStart(rxProtocols)
        for (i in 0 until samplesPerFrame) {
            rxSpectrum[i] = rxFftOut[2 * i] * rxFftOut[2 * i] + rxFftOut[2 * i + 1] * rxFftOut[2 * i + 1]
        }
        for (i in 1 until samplesPerFrame / 2) {
            rxSpectrum[i] += rxSpectrum[samplesPerFrame - i]
            if (i >= minFreq) amax = max(amax, rxSpectrum[i])
        }

        // 量化到 0..255 写入历史
        val norm = 255.0f / (if (amax == 0.0f) 1.0f else amax)
        val hist = spectrumHistoryFixed[rxHistoryIdFixed]
        for (i in 0 until samplesPerFrame) {
            hist[i] = min(255.0f, max(0.0f, round(rxSpectrum[i] * norm)))
        }
        if (++rxHistoryIdFixed >= spectrumHistoryFixed.size) rxHistoryIdFixed = 0

        // 本帧历史窗内是否有任一协议检出有效音调（用于“正在接收”指示）
        var anySignal = false

        for (protocolId in rxProtocols.indices) {
            val protocol = rxProtocols[protocolId]
            if (!protocol.enabled) continue

            val binStart = protocol.freqStart
            val binDelta = 16
            val binOffset = if (protocol.extra == 1) binDelta else 0
            if (binStart > samplesPerFrame) continue

            val totalLength = payloadLength + eccBytesForLength(payloadLength)
            val totalTxs = protocol.extra * ((totalLength + protocol.bytesPerTx - 1) / protocol.bytesPerTx)

            var historyStartId = rxHistoryIdFixed - totalTxs * protocol.framesPerTx
            if (historyStartId < 0) historyStartId += spectrumHistoryFixed.size

            val nTones = 2 * protocol.bytesPerTx
            detectedBins.fill(0)

            var txNeededTotal = 0
            var txDetectedTotal = 0
            var detectedSignal = true

            for (k in 0 until totalTxs) {
                if (k % protocol.extra == 0) {
                    for (i in 0 until 16 * nTones) detectedTones[i] = 0
                }

                for (i in 0 until protocol.framesPerTx) {
                    var historyId = historyStartId + k * protocol.framesPerTx + i
                    if (historyId >= spectrumHistoryFixed.size) historyId -= spectrumHistoryFixed.size
                    val histFrame = spectrumHistoryFixed[historyId]

                    for (j in 0 until protocol.bytesPerTx) {
                        var f0bin = 0
                        var f0max = histFrame[binStart + 2 * j * binDelta]
                        for (b in 1 until 16) {
                            val v = histFrame[binStart + 2 * j * binDelta + b]
                            if (f0max <= v) { f0max = v; f0bin = b }
                        }

                        var f1bin = 0
                        if (protocol.extra == 1) {
                            var f1max = histFrame[binStart + 2 * j * binDelta + binOffset]
                            for (b in 1 until 16) {
                                val v = histFrame[binStart + 2 * j * binDelta + binOffset + b]
                                if (f1max <= v) { f1max = v; f1bin = b }
                            }
                        } else {
                            f1bin = f0bin
                        }

                        if ((k + 0) % protocol.extra == 0) detectedTones[(2 * j + 0) * 16 + f0bin]++
                        if ((k + 1) % protocol.extra == 0) detectedTones[(2 * j + 1) * 16 + f1bin]++
                    }
                }

                if (protocol.extra > 1 && (k % protocol.extra == 0)) continue

                var txNeeded = 0
                var txDetected = 0
                for (j in 0 until protocol.bytesPerTx) {
                    if ((k / protocol.extra) * protocol.bytesPerTx + j >= totalLength) break
                    txNeeded += 2
                    for (b in 0 until 16) {
                        if (detectedTones[(2 * j + 0) * 16 + b] > protocol.framesPerTx / 2) {
                            detectedBins[2 * ((k / protocol.extra) * protocol.bytesPerTx + j) + 0] = b
                            txDetected++
                        }
                        if (detectedTones[(2 * j + 1) * 16 + b] > protocol.framesPerTx / 2) {
                            detectedBins[2 * ((k / protocol.extra) * protocol.bytesPerTx + j) + 1] = b
                            txDetected++
                        }
                    }
                }
                txDetectedTotal += txDetected
                txNeededTotal += txNeeded
            }

            if (txDetectedTotal < 0.75 * txNeededTotal) detectedSignal = false

            if (detectedSignal) {
                anySignal = true
                val rsData = ReedSolomon(payloadLength, eccBytesForLength(payloadLength))
                val encoded = ByteArray(totalLength)
                for (j in 0 until totalLength) {
                    encoded[j] = ((detectedBins[2 * j + 1] shl 4) + detectedBins[2 * j + 0]).toByte()
                }
                val decoded = rsData.decode(encoded)
                if (decoded != null) {
                    if (isDSSEnabled) {
                        for (i in 0 until payloadLength) {
                            decoded[i] = (decoded[i].toInt() xor dssMagic(i)).toByte()
                        }
                    }
                    rxDataLength = payloadLength
                    decoded.copyInto(rxData, 0, 0, 0 + payloadLength)
                    break
                }
            }
        }

        // C++ 版仅在变长模式置位 receiving；固定帧长模式据此给出信号指示
        rxReceiving = anySignal
    }

    // ---- 变长解码 ----
    private fun decodeVariable() {
        // 滑动平均幅值 -> FFT -> 谱
        amplitudeHistory[rxHistoryId].let { rxAmplitude.copyInto(it, 0, 0, 0 + samplesPerFrame) }
        if (++rxHistoryId >= kMaxSpectrumHistory) rxHistoryId = 0

        if (rxHistoryId == 0 || rxReceiving) {
            amplitudeAverage.fill(0f)
            for (h in amplitudeHistory) {
                for (i in 0 until samplesPerFrame) amplitudeAverage[i] += h[i]
            }
            val norm = 1.0f / kMaxSpectrumHistory
            for (i in 0 until samplesPerFrame) amplitudeAverage[i] *= norm

            amplitudeAverage.copyInto(rxFftOut, 0, 0, 0 + samplesPerFrame)
            rdft.rdft(rxFftOut)
            for (i in 0 until samplesPerFrame) {
                rxSpectrum[i] = rxFftOut[2 * i] * rxFftOut[2 * i] + rxFftOut[2 * i + 1] * rxFftOut[2 * i + 1]
            }
            for (i in 1 until samplesPerFrame / 2) {
                rxSpectrum[i] += rxSpectrum[samplesPerFrame - i]
            }
        }

        // 录制
        if (rxFramesLeftToRecord > 0) {
            val dst = (rxFramesToRecord - rxFramesLeftToRecord) * samplesPerFrame
            if (dst + samplesPerFrame <= amplitudeRecorded.size) {
                rxAmplitude.copyInto(amplitudeRecorded, dst, 0, 0 + samplesPerFrame)
            }
            if (--rxFramesLeftToRecord <= 0) rxAnalyzing = true
        }

        // 分析
        if (rxAnalyzing) {
            analyzeVariable()
        }

        // 检测起始/结束标记
        if (!rxReceiving) {
            detectStartMarker()
        } else {
            detectEndMarker()
        }
    }

    private fun detectStartMarker() {
        var isReceiving = false
        for (protocol in rxProtocols) {
            if (!protocol.enabled) continue
            var nDetectedMarkerBits = nBitsInMarker
            for (i in 0 until nBitsInMarker) {
                val freq = bitFreq(protocol, i)
                val bin = (freq * ihzPerSample).roundToInt()
                if (i % 2 == 0) {
                    if (rxSpectrum[bin] <= soundMarkerThreshold * rxSpectrum[bin + freqDeltaBin]) --nDetectedMarkerBits
                } else {
                    if (rxSpectrum[bin] >= soundMarkerThreshold * rxSpectrum[bin + freqDeltaBin]) --nDetectedMarkerBits
                }
            }
            if (nDetectedMarkerBits == nBitsInMarker) {
                rxMarkerFreqStart = protocol.freqStart
                isReceiving = true
                break
            }
        }

        if (isReceiving) {
            if (++rxNMarkersSuccess >= 1) {
                // ok
            } else {
                isReceiving = false
            }
        } else {
            rxNMarkersSuccess = 0
        }

        if (isReceiving) {
            rxReceiving = true
            rxData.fill(0)
            rxRecvDurationFrames = 2 * nMarkerFrames +
                maxFramesPerTx(rxProtocols, true) *
                ((kMaxLengthVariable + eccBytesForLength(kMaxLengthVariable)) / minBytesPerTx(rxProtocols) + 1)
            rxNMarkersSuccess = 0
            rxFramesToRecord = rxRecvDurationFrames
            rxFramesLeftToRecord = rxRecvDurationFrames
        }
    }

    private fun detectEndMarker() {
        var isEnded = false
        for (protocol in rxProtocols) {
            if (!protocol.enabled) continue
            var nDetectedMarkerBits = nBitsInMarker
            for (i in 0 until nBitsInMarker) {
                val freq = bitFreq(protocol, i)
                val bin = (freq * ihzPerSample).roundToInt()
                if (i % 2 == 0) {
                    if (rxSpectrum[bin] >= soundMarkerThreshold * rxSpectrum[bin + freqDeltaBin]) nDetectedMarkerBits--
                } else {
                    if (rxSpectrum[bin] <= soundMarkerThreshold * rxSpectrum[bin + freqDeltaBin]) nDetectedMarkerBits--
                }
            }
            if (nDetectedMarkerBits == nBitsInMarker) {
                isEnded = true
                break
            }
        }

        if (isEnded) {
            if (++rxNMarkersSuccess >= 1) {
                // ok
            } else {
                isEnded = false
            }
        } else {
            rxNMarkersSuccess = 0
        }

        if (isEnded && rxFramesToRecord > 1) {
            rxRecvDurationFrames -= rxFramesLeftToRecord - 1
            rxNMarkersSuccess = 0
            rxFramesLeftToRecord = 1
        }
    }

    private fun analyzeVariable() {
        val stepsPerFrame = 16
        val step = samplesPerFrame / stepsPerFrame
        var isValid = false

        for (protocolId in rxProtocols.indices) {
            val protocol = rxProtocols[protocolId]
            if (!protocol.enabled) continue
            if (protocol.extra == 2) continue
            if (protocol.freqStart != rxMarkerFreqStart) continue

            rxSpectrum.fill(0f)
            rxFramesToAnalyze = nMarkerFrames * stepsPerFrame

            var ii = nMarkerFrames * stepsPerFrame - 1
            while (ii >= 0) {
                var knownLength = false
                var decodedLength = 0
                val offsetStart = ii

                var itx = 0
                while (itx < 1024) {
                    val offsetTx = offsetStart + itx * protocol.framesPerTx * stepsPerFrame
                    if (offsetTx >= rxRecvDurationFrames * stepsPerFrame ||
                        (itx + 1) * protocol.bytesPerTx >= dataEncoded.size) break

                    // 累加 framesPerTx 个帧的时域样本后 FFT
                    amplitudeRecorded.copyInto(rxFftOut, 0, offsetTx * step, offsetTx * step + samplesPerFrame)
                    for (k in 1 until protocol.framesPerTx) {
                        val base = (offsetTx + k * stepsPerFrame) * step
                        for (i in 0 until samplesPerFrame) {
                            rxFftOut[i] += amplitudeRecorded[base + i]
                        }
                    }
                    rdft.rdft(rxFftOut)
                    for (i in 0 until samplesPerFrame) {
                        rxSpectrum[i] = rxFftOut[2 * i] * rxFftOut[2 * i] + rxFftOut[2 * i + 1] * rxFftOut[2 * i + 1]
                    }
                    for (i in 1 until samplesPerFrame / 2) {
                        rxSpectrum[i] += rxSpectrum[samplesPerFrame - i]
                    }

                    var curByte = 0
                    for (i in 0 until 2 * protocol.bytesPerTx) {
                        val freq = hzPerSample * protocol.freqStart
                        val bin = (freq * ihzPerSample).roundToInt() + 16 * i
                        var kmax = 0
                        var amax = 0.0f
                        for (k in 0 until 16) {
                            if (rxSpectrum[bin + k] > amax) {
                                kmax = k
                                amax = rxSpectrum[bin + k]
                            }
                        }
                        if (i % 2 == 1) {
                            curByte += (kmax shl 4)
                            dataEncoded[itx * protocol.bytesPerTx + i / 2] = curByte.toByte()
                            curByte = 0
                        } else {
                            curByte = kmax
                        }
                    }

                    if (itx * protocol.bytesPerTx > encodedDataOffset && !knownLength) {
                        val rsLength = ReedSolomon(1, encodedDataOffset - 1)
                        val lenBytes = rsLength.decode(dataEncoded.copyOfRange(0, encodedDataOffset))
                        if (lenBytes != null && lenBytes[0].toInt() and 0xFF in 1..kMaxLengthVariable) {
                            knownLength = true
                            decodedLength = lenBytes[0].toInt() and 0xFF

                            val nTotalBytesExpected = encodedDataOffset + decodedLength + eccBytesForLength(decodedLength)
                            val nTotalFramesExpected = 2 * nMarkerFrames +
                                ((nTotalBytesExpected + protocol.bytesPerTx - 1) / protocol.bytesPerTx) * protocol.framesPerTx
                            if (rxRecvDurationFrames > nTotalFramesExpected ||
                                rxRecvDurationFrames < nTotalFramesExpected - 2 * nMarkerFrames) {
                                knownLength = false
                                break
                            }
                        } else {
                            break
                        }
                    }

                    if (knownLength) {
                        val nTotalBytesExpected = encodedDataOffset + decodedLength + eccBytesForLength(decodedLength)
                        if (itx * protocol.bytesPerTx > nTotalBytesExpected + 1) break
                    }
                    itx++
                }

                if (knownLength) {
                    val rsData = ReedSolomon(decodedLength, eccBytesForLength(decodedLength))
                    val body = dataEncoded.copyOfRange(encodedDataOffset, encodedDataOffset + decodedLength + eccBytesForLength(decodedLength))
                    val decoded = rsData.decode(body)
                    if (decoded != null && decodedLength > 0) {
                        if (isDSSEnabled) {
                            for (i in 0 until decodedLength) {
                                decoded[i] = (decoded[i].toInt() xor dssMagic(i)).toByte()
                            }
                        }
                        isValid = true
                        rxDataLength = decodedLength
                        decoded.copyInto(rxData, 0, 0, 0 + decodedLength)
                    }
                }

                if (isValid) break
                ii--
            }

            if (isValid) break
        }

        rxFramesToRecord = 0
        if (!isValid) {
            rxDataLength = 0
            rxFramesToRecord = -1
        }
        rxReceiving = false
        rxAnalyzing = false
        rxSpectrum.fill(0f)
        rxFramesToAnalyze = 0
    }
}
