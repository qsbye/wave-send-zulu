package com.airscan.qr.core.ggwave

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ggwave 调制/解调端到端测试：TX 合成波形直接喂给 RX，无声道噪声。
 */
class GGWaveTest {

    private fun makeTx(payloadLength: Int, dss: Boolean = false) = GGWave(
        GGWave.Parameters(
            payloadLength = payloadLength,
            operatingMode = GGWave.OperatingMode.TX or
                (if (dss) GGWave.OperatingMode.USE_DSS else 0),
        )
    )

    private fun makeRx(
        payloadLength: Int,
        only: GGWave.ProtocolId? = null,
        dss: Boolean = false,
    ) = GGWave(
        GGWave.Parameters(
            payloadLength = payloadLength,
            operatingMode = GGWave.OperatingMode.RX or
                (if (dss) GGWave.OperatingMode.USE_DSS else 0),
        )
    ).also { g ->
        if (only != null) {
            g.rxProtocols.forEachIndexed { i, p -> p.enabled = (i == only.id) }
        }
    }

    private fun testPayload(len: Int) = ByteArray(len) { (it * 37 + 11).toByte() }

    private fun roundtripFixed(
        protocol: GGWave.ProtocolId,
        dss: Boolean = false,
        blindRx: Boolean = false,
    ) {
        val payload = testPayload(64)
        val samples = makeTx(64, dss).encode(payload, protocol, 60)
        assertTrue(samples.isNotEmpty())
        assertEquals(0, samples.size % GGWave.kDefaultSamplesPerFrame)

        val rx = makeRx(64, only = if (blindRx) null else protocol, dss = dss)
        val decoded = rx.decode(samples)
        assertNotNull("protocol=$protocol dss=$dss blind=$blindRx", decoded)
        assertArrayEquals(payload, decoded)
    }

    @Test fun fixedAudibleNormal() = roundtripFixed(GGWave.ProtocolId.AUDIBLE_NORMAL)
    @Test fun fixedAudibleFast() = roundtripFixed(GGWave.ProtocolId.AUDIBLE_FAST)
    @Test fun fixedAudibleFastest() = roundtripFixed(GGWave.ProtocolId.AUDIBLE_FASTEST)
    @Test fun fixedUltrasoundNormal() = roundtripFixed(GGWave.ProtocolId.ULTRASOUND_NORMAL)
    @Test fun fixedUltrasoundFast() = roundtripFixed(GGWave.ProtocolId.ULTRASOUND_FAST)
    @Test fun fixedUltrasoundFastest() = roundtripFixed(GGWave.ProtocolId.ULTRASOUND_FASTEST)
    @Test fun fixedDtNormal() = roundtripFixed(GGWave.ProtocolId.DT_NORMAL)
    @Test fun fixedDtFastest() = roundtripFixed(GGWave.ProtocolId.DT_FASTEST)

    /** RX 不预知协议（全部启用），也应能解出。 */
    @Test fun fixedBlindRxAudibleFast() =
        roundtripFixed(GGWave.ProtocolId.AUDIBLE_FAST, blindRx = true)

    @Test fun fixedBlindRxUltrasoundFast() =
        roundtripFixed(GGWave.ProtocolId.ULTRASOUND_FAST, blindRx = true)

    /** DSS 直扩开/关两端一致时可往返。 */
    @Test fun fixedDssAudibleFast() =
        roundtripFixed(GGWave.ProtocolId.AUDIBLE_FAST, dss = true)

    /** 固定帧长：payload 不足 64B 补零，解码恒为 64B。 */
    @Test
    fun fixedShortPayloadPaddedTo64() {
        val payload = testPayload(10)
        val samples = makeTx(64).encode(payload, GGWave.ProtocolId.AUDIBLE_FAST, 60)
        val decoded = makeRx(64, GGWave.ProtocolId.AUDIBLE_FAST).decode(samples)
        assertNotNull(decoded)
        assertEquals(64, decoded!!.size)
        assertArrayEquals(payload, decoded.copyOfRange(0, 10))
    }

    @Test
    fun variableAudibleFast() {
        val payload = testPayload(37)
        val samples = makeTx(-1).encode(payload, GGWave.ProtocolId.AUDIBLE_FAST, 60)
        val decoded = makeRx(-1, GGWave.ProtocolId.AUDIBLE_FAST).decode(samples)
        assertNotNull(decoded)
        assertArrayEquals(payload, decoded)
    }

    @Test
    fun variableUltrasoundFast() {
        val payload = testPayload(100)
        val samples = makeTx(-1).encode(payload, GGWave.ProtocolId.ULTRASOUND_FAST, 60)
        val decoded = makeRx(-1, GGWave.ProtocolId.ULTRASOUND_FAST).decode(samples)
        assertNotNull(decoded)
        assertArrayEquals(payload, decoded)
    }

    @Test
    fun variableMaxLength() {
        val payload = testPayload(GGWave.kMaxLengthVariable)
        val samples = makeTx(-1).encode(payload, GGWave.ProtocolId.AUDIBLE_FAST, 60)
        val decoded = makeRx(-1, GGWave.ProtocolId.AUDIBLE_FAST).decode(samples)
        assertNotNull(decoded)
        assertArrayEquals(payload, decoded)
    }

    /** 流式喂入：把波形按小块切碎喂，结果应与一次喂入一致。 */
    @Test
    fun streamingDecodeInChunks() {
        val payload = testPayload(64)
        val samples = makeTx(64).encode(payload, GGWave.ProtocolId.AUDIBLE_FAST, 60)
        val rx = makeRx(64, GGWave.ProtocolId.AUDIBLE_FAST)
        var decoded: ByteArray? = null
        var off = 0
        val chunk = 700 // 非 1024 整数倍，跨帧边界
        while (off < samples.size && decoded == null) {
            val n = minOf(chunk, samples.size - off)
            decoded = rx.decode(samples.copyOfRange(off, off + n), n)
            off += n
        }
        assertNotNull(decoded)
        assertArrayEquals(payload, decoded)
    }

    /** 全零（静音）样本不应解出任何数据。 */
    @Test
    fun silenceDecodesNothing() {
        val rx = makeRx(64)
        val silence = ShortArray(64 * GGWave.kDefaultSamplesPerFrame)
        assertNull(rx.decode(silence))
    }

    /** 同一波形重复播放（喷泉码轮播场景），每次传输都应能独立解出。 */
    @Test
    fun repeatedTransmissionDecodesAgain() {
        val payload = testPayload(64)
        val samples = makeTx(64).encode(payload, GGWave.ProtocolId.AUDIBLE_FAST, 60)
        val rx = makeRx(64, GGWave.ProtocolId.AUDIBLE_FAST)

        val first = rx.decode(samples)
        assertNotNull(first)
        assertArrayEquals(payload, first)

        rx.resetRx()
        val second = rx.decode(samples)
        assertNotNull(second)
        assertArrayEquals(payload, second)
    }
}
