package com.airscan.qr.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * JVM（Android/桌面）平台实现：直接使用 JDK 的摘要与 gzip 流。
 * wasm 目标不编译本文件（web 模块构建时排除），改用 PlatformWasm。
 */
internal object Platform {

    fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    fun gzipCompress(bytes: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(bytes.size / 2)
        GZIPOutputStream(bos).use { it.write(bytes) }
        return bos.toByteArray()
    }

    /** 解压带硬上限：gzip 尾部长度不可信，超量即中止，防止 zip-bomb。 */
    fun gzipDecompress(bytes: ByteArray, maxBytes: Long): ByteArray {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val out = ByteArrayOutputStream(minOf(maxBytes, 1L shl 20).toInt())
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                require(total <= maxBytes) { "inflateOverflow" }
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    /** 常数时间比较，避免计时侧信道。 */
    fun ctEqual(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}
