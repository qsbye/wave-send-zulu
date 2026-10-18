package com.airscan.qr.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FountainTest {

    private fun runScenario(
        payload: ByteArray,
        blockLen: Int,
        sessionId: Int,
        lossRate: Double,
        startSeq: Long,
        seed: Long,
    ) {
        val enc = LTEncoder(payload, blockLen, sessionId)
        val dec = LTDecoder(enc.k, blockLen, sessionId, payload.size.toLong())
        val rnd = Random(seed)
        val cycle = Fountain.cycleLength(enc.k)
        var seq = startSeq
        val maxFrames = 200L * cycle
        var fed = 0L
        while (!dec.isComplete && fed < maxFrames) {
            if (rnd.nextDouble() >= lossRate) {
                dec.addFrame(seq, enc.encode(seq.toUInt()))
            }
            seq = (seq + 1) and 0xFFFFFFFFL
            fed++
        }
        assertTrue(
            "decoder did not finish: solved=${dec.solvedCount}/${enc.k}, fed=$fed",
            dec.isComplete,
        )
        assertArrayEquals(payload, dec.assemble())
    }

    @Test
    fun noLoss_completesInOneSweep() {
        val data = Random(1).nextBytes(12345)
        val enc = LTEncoder(data, 200, 12345)
        val dec = LTDecoder(enc.k, 200, 12345, data.size.toLong())
        // 一整轮系统化帧（0 until k）后必须零冗余完成。
        for (seq in 0 until enc.k.toLong()) {
            dec.addFrame(seq, enc.encode(seq.toUInt()))
        }
        assertEquals(enc.k, dec.solvedCount)
        assertArrayEquals(data, dec.assemble())
    }

    @Test
    fun loss30Percent_recoversThroughCarousel() {
        runScenario(
            payload = Random(2).nextBytes(12345),
            blockLen = 200,
            sessionId = 42,
            lossRate = 0.30,
            startSeq = 0,
            seed = 99,
        )
    }

    @Test
    fun lateJoinAndHeavyLoss50() {
        runScenario(
            payload = Random(3).nextBytes(8000),
            blockLen = 197, // 故意不是 4 的倍数
            sessionId = 65535,
            lossRate = 0.50,
            startSeq = 137, // 中途加入
            seed = 7,
        )
    }

    @Test
    fun tinyPayloadSingleBlock() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        runScenario(data, 64, 7, lossRate = 0.2, startSeq = 3, seed = 11)
    }

    @Test
    fun duplicateFramesAreCountedAndIgnored() {
        val data = Random(4).nextBytes(1000)
        val enc = LTEncoder(data, 128, 9)
        val dec = LTDecoder(enc.k, 128, 9, data.size.toLong())
        repeat(3) {
            for (seq in 0 until enc.k.toLong()) {
                dec.addFrame(seq, enc.encode(seq.toUInt()))
            }
        }
        assertArrayEquals(data, dec.assemble())
        // 第一遍 k 个新帧，其余全部是重复帧。
        assertEquals(enc.k.toLong(), dec.framesNew.toLong())
        assertEquals(enc.k * 2L, dec.framesDup.toLong())
    }

    @Test
    fun differentSessionProducesDifferentRepairSubsets() {
        val k = 40
        val a = Fountain.frameComposition(k, 1, k.toUInt())
        val b = Fountain.frameComposition(k, 2, k.toUInt())
        // 同为修复帧时，不同 session 大概率得到不同子集。
        assertTrue(!a.contentEquals(b) || a.size != b.size)
    }
}
