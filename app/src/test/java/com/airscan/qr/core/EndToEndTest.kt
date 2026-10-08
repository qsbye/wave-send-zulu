package com.airscan.qr.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * 模拟真实收发链路：发送端 packFrame 出整帧字节，网络（光学信道）随机丢帧，
 * 接收端 classifyFrame/parseFrame 后按流身份构建解码器，最终解容器并校验。
 */
class EndToEndTest {

    @Test
    fun fullPipeline_with35PercentLoss_lateJoin() {
        val original = Random(5).nextBytes(5432)
        val packed = Container.packFile("e2e.bin", "application/octet-stream", original)
        val payload = packed.container

        val sessionId = 0xABCD
        val blockLen = 193 // 非 4 对齐
        val k = (payload.size + blockLen - 1) / blockLen
        val fnv = fnv1a(payload)
        val enc = LTEncoder(payload, blockLen, sessionId)

        var decoder: LTDecoder? = null
        var identity: String? = null
        val rnd = Random(123)
        var seq = 7L // 中途加入
        val maxFrames = 400L * (2L * k)
        var fed = 0L

        while (fed < maxFrames) {
            fed++
            if (rnd.nextDouble() < 0.35) { // 模拟漏拍
                seq = (seq + 1) and 0xFFFFFFFFL
                continue
            }
            val seqU = seq and 0xFFFFFFFFL
            val raw = packFrame(
                FrameHeader(
                    flags = 0,
                    sessionId = sessionId,
                    seq = seqU,
                    k = k,
                    blockLen = blockLen,
                    totalLen = payload.size.toLong(),
                    payloadFnv = fnv,
                ),
                enc.encode(seqU.toUInt()),
            )
            assertEquals(FrameVerdict.Ok::class, classifyFrame(raw)::class)
            val (header, block) = parseFrame(raw)!!
            val id = streamIdentity(header)
            if (id != identity) {
                identity = id
                decoder = LTDecoder(k, blockLen, sessionId, payload.size.toLong())
            }
            decoder!!.addFrame(header.seq, block)

            if (decoder.isComplete) {
                val assembled = decoder.assemble()!!
                assertEquals(fnv, fnv1a(assembled))
                val file = Container.unpackFile(assembled)
                assertEquals(true, Container.verify(file))
                assertArrayEquals(original, file.bytes)
                assertEquals("e2e.bin", file.name)
                return
            }
            seq = (seq + 1) and 0xFFFFFFFFL
        }
        fail("端到端传输未能在限定帧数内完成")
    }
}
