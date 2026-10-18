package com.airscan.qr.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Random
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

/**
 * 纯 Kotlin SHA-256 / DEFLATE 解压实现验证。
 * wasm 目标跑的就是这份代码，JVM 测试保证其与 JDK 行为一致。
 */
class PlatformPureTest {

    private fun randomBytes(rnd: Random, size: Int): ByteArray {
        val out = ByteArray(size)
        rnd.nextBytes(out)
        return out
    }

    private fun randomText(rnd: Random, size: Int): ByteArray {
        val abc = "the quick brown fox jumps over the lazy dog 0123456789"
        val out = ByteArray(size)
        for (i in out.indices) out[i] = abc[rnd.nextInt(abc.length)].code.toByte()
        return out
    }

    private fun jdkGzip(data: ByteArray, level: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        val gz = object : GZIPOutputStream(bos) {
            init { def.setLevel(level) }
        }
        gz.use { it.write(data) }
        return bos.toByteArray()
    }

    // ---- SHA-256 ----

    @Test
    fun sha256MatchesMessageDigest() {
        val rnd = Random(1)
        for (size in intArrayOf(0, 1, 55, 56, 63, 64, 65, 1000, 65536)) {
            val data = randomBytes(rnd, size)
            assertArrayEquals(
                "size=$size",
                MessageDigest.getInstance("SHA-256").digest(data),
                PureSha256.digest(data),
            )
        }
        // 空输入的已知答案
        assertArrayEquals(
            MessageDigest.getInstance("SHA-256").digest(ByteArray(0)),
            PureSha256.digest(ByteArray(0)),
        )
    }

    // ---- inflate：JDK gzip 往返 ----

    @Test
    fun inflateRoundTripsDynamicBlocks() {
        val rnd = Random(2)
        for (size in intArrayOf(1, 100, 4096, 100000)) {
            for (data in listOf(randomText(rnd, size), randomBytes(rnd, size))) {
                assertArrayEquals("size=$size", data, PureInflate.gunzip(jdkGzip(data, Deflater.DEFAULT_COMPRESSION), 1L shl 24))
            }
        }
    }

    @Test
    fun inflateRoundTripsStoredBlocks() {
        val rnd = Random(3)
        // level 0 生成存储块；70000 字节强制跨多个 65535 上限的块
        for (size in intArrayOf(1, 70000)) {
            val data = randomBytes(rnd, size)
            assertArrayEquals("size=$size", data, PureInflate.gunzip(jdkGzip(data, Deflater.NO_COMPRESSION), 1L shl 24))
        }
    }

    @Test
    fun inflateRoundTripsBestSpeed() {
        val rnd = Random(4)
        for (size in intArrayOf(100, 10000)) {
            val data = randomText(rnd, size)
            assertArrayEquals("size=$size", data, PureInflate.gunzip(jdkGzip(data, Deflater.BEST_SPEED), 1L shl 24))
        }
    }

    @Test
    fun gunzipHandlesFnameHeader() {
        val rnd = Random(5)
        val data = randomText(rnd, 4096)
        val gz = jdkGzip(data, Deflater.BEST_SPEED)
        // 在头部插入 FNAME 字段：置 FLG 位并在固定 10 字节头后插入文件名
        assertTrue((gz[3].toInt() and 0x08) == 0)
        gz[3] = (gz[3].toInt() or 0x08).toByte()
        val name = "hello 世界.bin".toByteArray(Charsets.UTF_8) + 0
        val out = ByteArray(gz.size + name.size)
        gz.copyInto(out, 0, 0, 10)
        name.copyInto(out, 10)
        gz.copyInto(out, 10 + name.size, 10, gz.size)
        assertArrayEquals(data, PureInflate.gunzip(out, 1L shl 20))
    }

    @Test
    fun inflateRespectsMaxOut() {
        val rnd = Random(6)
        val data = randomText(rnd, 10000)
        val gz = jdkGzip(data, Deflater.DEFAULT_COMPRESSION)
        val e = assertThrows(IllegalArgumentException::class.java) {
            PureInflate.gunzip(gz, 100)
        }
        assertEquals("inflateOverflow", e.message)
    }

    @Test
    fun inflateRejectsCorruption() {
        val rnd = Random(7)
        val data = randomText(rnd, 20000)
        val gz = jdkGzip(data, Deflater.DEFAULT_COMPRESSION)
        gz[gz.size / 2] = (gz[gz.size / 2].toInt() xor 0x5A).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            PureInflate.gunzip(gz, 1L shl 24)
        }
    }
}
