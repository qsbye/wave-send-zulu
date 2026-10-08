package com.airscan.qr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WireTest {

    @Test
    fun fnv1aKnownVectors() {
        assertEquals(0x811c9dc5L, fnv1a(ByteArray(0)))
        assertEquals(0xe40c292cL, fnv1a("a".toByteArray()))
        assertEquals(0xbf9cf968L, fnv1a("foobar".toByteArray()))
    }

    @Test
    fun packAndParseRoundtrip() {
        val block = ByteArray(100) { (it * 31).toByte() }
        val h = FrameHeader(
            flags = 0,
            sessionId = 0xBEEF,
            seq = 0x12345678L,
            k = 1234,
            blockLen = 100,
            totalLen = 12340,
            payloadFnv = 0xCAFEBABEL,
        )
        val raw = packFrame(h, block)
        assertEquals(Wire.HEADER_LEN + 100, raw.size)
        assertEquals(FrameVerdict.Ok::class, classifyFrame(raw)::class)

        val parsed = parseFrame(raw)
        assertNotNull(parsed)
        val (ph, pb) = parsed!!
        assertEquals(h.sessionId, ph.sessionId)
        assertEquals(h.seq, ph.seq)
        assertEquals(h.k, ph.k)
        assertEquals(h.blockLen, ph.blockLen)
        assertEquals(h.totalLen, ph.totalLen)
        assertEquals(h.payloadFnv, ph.payloadFnv)
        assert(block.contentEquals(pb))
    }

    @Test
    fun classifyForeignAndVersionMismatch() {
        assertNull(parseFrame(ByteArray(0)))
        assertNull(parseFrame(ByteArray(30) { 0x41 }))

        val foreign = ByteArray(30).also {
            it[0] = Wire.MAGIC0.toByte()
            it[1] = 0x55
        }
        assertEquals(FrameVerdict.Foreign::class, classifyFrame(foreign)::class)

        val newer = ByteArray(30).also {
            it[0] = Wire.MAGIC0.toByte()
            it[1] = Wire.MAGIC1.toByte()
            it[2] = 99
        }
        assertEquals(FrameVerdict.NewerSender::class, classifyFrame(newer)::class)

        val older = ByteArray(30).also {
            it[0] = Wire.MAGIC0.toByte()
            it[1] = 0x0D
        }
        assertEquals(FrameVerdict.OlderSender::class, classifyFrame(older)::class)
    }

    @Test
    fun malformedWhenPayloadLengthMismatch() {
        // 头部声明 blockLen=100，但实际只给 50。
        val raw = ByteArray(Wire.HEADER_LEN + 50)
        raw[0] = Wire.MAGIC0.toByte()
        raw[1] = Wire.MAGIC1.toByte()
        raw[2] = Wire.WIRE_VERSION.toByte()
        raw[12] = 100
        assertEquals(FrameVerdict.Malformed::class, classifyFrame(raw)::class)
    }

    @Test
    fun streamIdentityChangesOnAnyHeaderField() {
        val base = FrameHeader(0, 1, 0, 10, 100, 1000, 111)
        val other = base.copy(sessionId = 2)
        assert(streamIdentity(base) != streamIdentity(other))
        val sameFileRestarted = base
        assertEquals(streamIdentity(base), streamIdentity(sameFileRestarted))
    }
}
