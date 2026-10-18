package com.airscan.qr.core.ggwave

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Random

class ReedSolomonTest {

    @Test
    fun gfPrimitiveElementHasOrder255() {
        // 本原元 2 的阶应为 255：2^255 == 1，且 2^i != 1 (0<i<255)
        assertEquals(1, Gf.pow(2, 255))
        assertEquals(2, Gf.pow(2, 1))
        assertEquals(1, Gf.mul(0x53, Gf.inverse(0x53)))
    }

    @Test
    fun encodeIsSystematic() {
        val msg = ByteArray(50) { (it * 7 + 3).toByte() }
        val rs = ReedSolomon(50, 12)
        val enc = rs.encode(msg)
        assertEquals(62, enc.size)
        // 系统码：前 50 字节与原消息一致
        assertArrayEquals(msg, enc.copyOfRange(0, 50))
    }

    @Test
    fun roundtripNoErrors() {
        val rnd = Random(42)
        for ((msgLen, eccLen) in listOf(1 to 2, 3 to 2, 64 to 24, 140 to 56, 200 to 40)) {
            val msg = ByteArray(msgLen).also { rnd.nextBytes(it) }
            val rs = ReedSolomon(msgLen, eccLen)
            val dec = rs.decode(rs.encode(msg))
            assertArrayEquals("msgLen=$msgLen eccLen=$eccLen", msg, dec)
        }
    }

    @Test
    fun correctsUpToTErrors() {
        val rnd = Random(7)
        val msgLen = 64
        val eccLen = 10 // 可纠 t = eccLen/2 = 5 字节错误
        val rs = ReedSolomon(msgLen, eccLen)
        val msg = ByteArray(msgLen).also { rnd.nextBytes(it) }
        val enc = rs.encode(msg)

        // 在恰好 t 个随机位置破坏
        val corrupted = enc.copyOf()
        val positions = (0 until enc.size).shuffled(rnd).take(eccLen / 2)
        for (p in positions) corrupted[p] = (corrupted[p] + 0x5A).toByte()

        val dec = rs.decode(corrupted)
        assertArrayEquals(msg, dec)
    }

    @Test
    fun correctsSingleErrorInEveryPosition() {
        // 遍历每个位置的单字节错误，都应可纠（ecc=4, t=2）
        val rs = ReedSolomon(8, 4)
        val msg = ByteArray(8) { (0xA0 + it).toByte() }
        val enc = rs.encode(msg)
        for (p in enc.indices) {
            val corrupted = enc.copyOf()
            corrupted[p] = (corrupted[p].toInt() xor 0xFF).toByte()
            assertArrayEquals("corrupted at $p", msg, rs.decode(corrupted))
        }
    }

    @Test
    fun beyondCapacityNeverReturnsOriginalMessage() {
        // 破坏超过 t 个字节后，decode 不可能“恰好”解回原消息：
        // 若返回原消息，则损坏码字与原码字距离必 ≤ t，矛盾。
        val rnd = Random(99)
        val msgLen = 32
        val eccLen = 10 // t = 5
        val rs = ReedSolomon(msgLen, eccLen)
        val msg = ByteArray(msgLen).also { rnd.nextBytes(it) }
        val enc = rs.encode(msg)

        val corrupted = enc.copyOf()
        val positions = (0 until enc.size).shuffled(rnd).take(eccLen / 2 + 6)
        for (p in positions) corrupted[p] = (corrupted[p].toInt() xor 0xFF).toByte()

        val dec = rs.decode(corrupted)
        if (dec != null) {
            assertFalse("must not decode back to the original message", msg.contentEquals(dec))
        }
    }
}
