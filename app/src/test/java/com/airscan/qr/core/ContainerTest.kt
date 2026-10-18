package com.airscan.qr.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ContainerTest {

    @Test
    fun roundtripSmallFile() {
        val bytes = "hello 喷泉码 fountain".toByteArray()
        val packed = Container.packFile("notes.txt", "text/plain", bytes)
        assertEquals("none", packed.compression)

        val file = Container.unpackFile(packed.container)
        assertEquals("notes.txt", file.name)
        assertEquals("text/plain", file.type)
        assertArrayEquals(bytes, file.bytes)
        assertTrue(Container.verify(file))
    }

    @Test
    fun roundtripCompressibleFileUsesGzip() {
        // 高重复内容（>768B）应触发 gzip，并能完整还原。
        val bytes = ByteArray(8000) { (it % 7).toByte() }
        val packed = Container.packFile("blob.bin", "application/octet-stream", bytes)
        assertEquals("gzip", packed.compression)
        assertTrue(packed.transmittedSize < bytes.size)

        val file = Container.unpackFile(packed.container)
        assertArrayEquals(bytes, file.bytes)
        assertTrue(Container.verify(file))
    }

    @Test
    fun randomBinaryRoundtripsUncompressed() {
        val bytes = Random(7).nextBytes(5000) // 随机数据压缩无收益
        val packed = Container.packFile("random.dat", "application/octet-stream", bytes)
        assertEquals("none", packed.compression)
        assertArrayEquals(bytes, Container.unpackFile(packed.container).bytes)
    }

    @Test
    fun tamperedContentFailsVerification() {
        val bytes = "integrity check".toByteArray()
        val packed = Container.packFile("x.txt", "text/plain", bytes)
        val file = Container.unpackFile(packed.container)
        file.bytes[0] = (file.bytes[0].toInt() xor 0xFF).toByte()
        assertEquals(false, Container.verify(file))
    }

    @Test
    fun badMagicRejected() {
        val packed = Container.packFile("x.txt", "text/plain", "abc".toByteArray())
        packed.container[0] = 0
        try {
            Container.unpackFile(packed.container)
            error("应当拒绝错误 magic")
        } catch (_: IllegalArgumentException) {
            // 预期
        }
    }

    @Test
    fun safeFileNameStripsPathsAndControlChars() {
        assertEquals("x.bin", Container.safeFileName("../a/x.bin"))
        assertEquals("y.txt", Container.safeFileName("""C:\dir\y.txt"""))
        assertEquals("transfer.bin", Container.safeFileName(".."))
        assertEquals("transfer.bin", Container.safeFileName("\u0000\n"))
    }
}
