package com.airscan.qr.core.ggwave

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 采样率转换器（sinc 插值），移植自 ggwave GGWave::Resampler。
 *
 * 当采集/播放采样率与内部工作采样率不一致时进行重采样。
 * 本工程通常令三者相等（48kHz），此时不会用到；为与 ggwave 全量对齐仍完整移植。
 */
internal class Resampler {

    companion object {
        const val kWidth = 64
        const val kSamplesPerZeroCrossing = 32
        const val kDelaySize = 140
    }

    private val sincTable = FloatArray(kWidth * kSamplesPerZeroCrossing)
    private val delayBuffer = FloatArray(3 * kWidth)
    private val edgeSamples = FloatArray(kWidth)
    private var samplesInp = FloatArray(4096)

    private var timeNow = 0.0
    private var timeLast = 0
    private var timeInt = 0
    private var nSamplesTotal = 0

    init {
        makeSinc()
        reset()
    }

    fun reset() {
        timeNow = 0.0
        timeLast = 0
        timeInt = 0
        nSamplesTotal = 0
        edgeSamples.fill(0f)
        delayBuffer.fill(0f)
        samplesInp.fill(0f)
    }

    fun nSamplesTotal(): Int = nSamplesTotal

    /**
     * 重采样。
     * @param factor   输出/输入 采样率比
     * @param nSamples 输入样本数
     * @param src      输入样本
     * @param dst      输出样本；为 null 时仅估算输出样本数（不改变内部状态）
     * @return 输出样本数
     */
    fun resample(factor: Float, nSamples: Int, src: FloatArray, dst: FloatArray?): Int {
        var idxInp = -1
        var idxOut = 0
        var notDone = true
        var dataIn: Float

        val stateSave = State(timeNow, timeLast, timeInt, nSamplesTotal)

        nSamplesTotal += nSamples

        var samples: FloatArray = src
        if (dst != null) {
            require(nSamples > kWidth) { "nSamples must be > kWidth" }
            if (samplesInp.size < nSamples + kWidth) {
                samplesInp = FloatArray(nSamples + kWidth)
            }
            for (i in 0 until kWidth) {
                samplesInp[i] = edgeSamples[i]
                edgeSamples[i] = src[nSamples - kWidth + i]
            }
            for (i in 0 until nSamples) {
                samplesInp[i + kWidth] = src[i]
            }
            samples = samplesInp
        }

        while (notDone) {
            while (timeLast < timeInt) {
                if (++idxInp >= nSamples) {
                    notDone = false
                    break
                } else {
                    dataIn = samples[idxInp]
                }
                if (dst != null) newData(dataIn)
                timeLast += 1
            }

            if (!notDone) break

            var temp1 = 0.0
            var leftLimit = timeNow.toInt() - kWidth + 1
            var rightLimit = timeNow.toInt() + kWidth
            if (leftLimit < 0) leftLimit = 0
            if (rightLimit > nSamplesTotal + kWidth) rightLimit = nSamplesTotal + kWidth
            val dataOut: Float
            if (factor < 1.0f) {
                var j = leftLimit
                while (j < rightLimit) {
                    temp1 += getData(j - timeInt) * sinc(timeNow - j).toFloat()
                    j++
                }
                dataOut = temp1.toFloat()
            } else {
                val oneOverFactor = 1.0 / factor
                var j = leftLimit
                while (j < rightLimit) {
                    temp1 += getData(j - timeInt) * oneOverFactor * sinc(oneOverFactor * (timeNow - j))
                    j++
                }
                dataOut = temp1.toFloat()
            }

            if (dst != null) {
                dst[idxOut] = dataOut
            }
            ++idxOut

            timeNow += factor
            timeLast = timeInt
            timeInt = timeNow.toInt()
            while (timeLast < timeInt) {
                if (++idxInp >= nSamples) {
                    notDone = false
                    break
                } else {
                    dataIn = samples[idxInp]
                }
                if (dst != null) newData(dataIn)
                timeLast += 1
            }
        }

        if (dst == null) {
            timeNow = stateSave.timeNow
            timeLast = stateSave.timeLast
            timeInt = stateSave.timeInt
            nSamplesTotal = stateSave.nSamplesTotal
        }

        return idxOut
    }

    private data class State(val timeNow: Double, val timeLast: Int, val timeInt: Int, val nSamplesTotal: Int)

    private fun getData(j: Int): Float = delayBuffer[j + kWidth]

    private fun newData(data: Float) {
        for (i in 0 until kDelaySize - 5) {
            delayBuffer[i] = delayBuffer[i + 1]
        }
        delayBuffer[kDelaySize - 5] = data
    }

    private fun makeSinc() {
        val winFreq = PI / kWidth / kSamplesPerZeroCrossing
        sincTable[0] = 1.0f
        for (i in 1 until kWidth * kSamplesPerZeroCrossing) {
            val temp = i.toDouble() * PI / kSamplesPerZeroCrossing
            var v = sin(temp) / temp
            val win = 0.5 + 0.5 * cos(winFreq * i)
            v *= win
            sincTable[i] = v.toFloat()
        }
    }

    private fun sinc(x: Double): Double {
        if (abs(x) >= kWidth - 1) {
            return 0.0
        }
        val temp = abs(x) * kSamplesPerZeroCrossing.toDouble()
        val low = temp.toInt()
        val delta = temp - low
        // linear interpolation
        return sincTable[low] + (sincTable[low + 1] - sincTable[low]) * delta
    }
}
