package com.airscan.qr.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSegmentTest {

    private fun testFrame(len: Int) = ByteArray(len) { (it * 13 + 7).toByte() }

    @Test
    fun supportsFrameBounds() {
        assertTrue(AudioSegment.supportsFrame(1))
        assertTrue(AudioSegment.supportsFrame(AudioSegment.MAX_FRAME_BYTES))
        assertFalse(AudioSegment.supportsFrame(0))
        assertFalse(AudioSegment.supportsFrame(AudioSegment.MAX_FRAME_BYTES + 1))

        assertEquals(1, AudioSegment.segmentCount(1))
        assertEquals(1, AudioSegment.segmentCount(AudioSegment.PAYLOAD_BYTES))
        assertEquals(2, AudioSegment.segmentCount(AudioSegment.PAYLOAD_BYTES + 1))
        assertEquals(AudioSegment.MAX_SEGMENTS, AudioSegment.segmentCount(AudioSegment.MAX_FRAME_BYTES))
    }

    @Test
    fun splitParseRoundtrip() {
        val frame = testFrame(522) // 500B 块 + 22B 帧头
        val segs = AudioSegment.split(0x1234, 0xCAFEBABEL, frame)
        assertEquals(11, segs.size)
        segs.forEachIndexed { i, raw ->
            assertEquals(AudioSegment.SEGMENT_BYTES, raw.size)
            val seg = AudioSegment.parse(raw)
            assertNotNull(seg)
            seg!!
            assertEquals(0x1234, seg.sessionId)
            assertEquals(0xCAFEBABEL, seg.seq)
            assertEquals(frame.size, seg.frameLen)
            assertEquals(segs.size, seg.segCount)
            assertEquals(i, seg.segIndex)
        }
    }

    @Test
    fun reassembleInOrder() {
        val frame = testFrame(1022) // 1000B 帧
        val re = AudioSegment.Reassembler()
        var out: ByteArray? = null
        for (raw in AudioSegment.split(7, 42, frame)) {
            val done = re.feed(AudioSegment.parse(raw)!!)
            if (done != null) out = done
        }
        assertNotNull(out)
        assertArrayEquals(frame, out)
    }

    @Test
    fun reassembleOutOfOrder() {
        val frame = testFrame(1022)
        val segs = AudioSegment.split(7, 42, frame)
            .map { AudioSegment.parse(it)!! }
            .shuffled(java.util.Random(5))
        val re = AudioSegment.Reassembler()
        var out: ByteArray? = null
        for (seg in segs) {
            val done = re.feed(seg)
            if (done != null) out = done
        }
        assertNotNull(out)
        assertArrayEquals(frame, out)
    }

    @Test
    fun duplicateSegmentsAreIdempotent() {
        val frame = testFrame(522)
        val segs = AudioSegment.split(1, 2, frame).map { AudioSegment.parse(it)!! }
        val re = AudioSegment.Reassembler()
        var out: ByteArray? = null
        // 每个段喂两遍
        for (seg in segs + segs) {
            val done = re.feed(seg)
            if (done != null) out = done
        }
        assertNotNull(out)
        assertArrayEquals(frame, out)
    }

    @Test
    fun seqChangeDiscardsPartialFrame() {
        val frameA = testFrame(522)
        val frameB = testFrame(522).also { it[100] = 0x55 }
        val segsA = AudioSegment.split(9, 100, frameA).map { AudioSegment.parse(it)!! }
        val segsB = AudioSegment.split(9, 101, frameB).map { AudioSegment.parse(it)!! }

        val re = AudioSegment.Reassembler()
        // 帧 A 只喂一半就切到帧 B
        for (seg in segsA.take(segsA.size / 2)) assertNull(re.feed(seg))
        var out: ByteArray? = null
        for (seg in segsB) {
            val done = re.feed(seg)
            if (done != null) out = done
        }
        assertNotNull(out)
        assertArrayEquals(frameB, out)

        // 再回来喂帧 A 的剩余段：应被视为新帧的部分段，不会产出
        for (seg in segsA.drop(segsA.size / 2).dropLast(1)) assertNull(re.feed(seg))
    }

    @Test
    fun incompleteFrameYieldsNothing() {
        val frame = testFrame(522)
        val segs = AudioSegment.split(9, 100, frame).map { AudioSegment.parse(it)!! }
        val re = AudioSegment.Reassembler()
        for (seg in segs.dropLast(1)) assertNull(re.feed(seg))
    }

    @Test
    fun parseRejectsInvalidSegments() {
        val good = AudioSegment.split(1, 1, testFrame(100)).first()

        // 长度不足
        assertNull(AudioSegment.parse(good.copyOfRange(0, 10)))
        // 坏 magic
        assertNull(AudioSegment.parse(good.copyOf().also { it[0] = 0x00 }))
        assertNull(AudioSegment.parse(good.copyOf().also { it[1] = 0x00 }))
        // 坏版本
        assertNull(AudioSegment.parse(good.copyOf().also { it[2] = 99 }))
        // segIndex >= segCount（flags 存 segCount-1，故 segCount = it[3] + 1）
        val badIndex = good.copyOf().also { it[12] = (it[3] + 1).toByte() }
        assertNull(AudioSegment.parse(badIndex))
        // frameLen 超界
        assertNull(AudioSegment.parse(good.copyOf().also { it[10] = 0; it[11] = 0 }))
        assertNull(AudioSegment.parse(good.copyOf().also {
            it[10] = ((AudioSegment.MAX_FRAME_BYTES + 1) and 0xFF).toByte()
            it[11] = (((AudioSegment.MAX_FRAME_BYTES + 1) ushr 8) and 0xFF).toByte()
        }))
    }
}
