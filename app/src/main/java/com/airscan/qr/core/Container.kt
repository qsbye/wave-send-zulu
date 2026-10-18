package com.airscan.qr.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * DCF2 文件容器（与 decimen 容器格式兼容）。
 *
 * 喷泉码载荷不是裸文件，而是一个自描述容器：保留文件名、MIME 类型、
 * 可选 gzip（仅在确实变小时启用）以及原始字节的 SHA-256。
 * 接收端在还原并校验 SHA-256 通过前，不应向用户提供文件。
 *
 * 头部 49 字节（小端）：
 *   0  4B   magic "DCF2" = 44 43 46 32
 *   4  u8   compression  0=none 1=gzip
 *   5  u16  nameLength
 *   7  u16  typeLength
 *   9  u32  fileLength        原始文件长度
 *  13  u32  transmittedLength 实际承载长度（压缩后或原始）
 *  17  32B  sha256(原始文件)
 *  之后：name UTF-8、type UTF-8、transmitted bytes
 */
object Container {

    const val MAX_FILE_BYTES: Long = 64L * 1024 * 1024
    const val MAX_FILE_LABEL = "64 MB"

    private const val HEADER_LEN = 49
    private val MAGIC = byteArrayOf(0x44, 0x43, 0x46, 0x32) // "DCF2"

    private val PRECOMPRESSED_TYPES = setOf(
        "application/gzip",
        "application/java-archive",
        "application/vnd.rar",
        "application/x-7z-compressed",
        "application/x-brotli",
        "application/x-bzip",
        "application/x-bzip2",
        "application/x-gzip",
        "application/x-lzma",
        "application/x-rar-compressed",
        "application/x-xz",
        "application/x-zip-compressed",
        "application/zip",
        "application/zstd",
    )

    private val COMPRESSIBLE_IMAGES =
        Regex("^image/(bmp|x-ms-bmp|svg\\+xml|tiff|x-icon|vnd\\.microsoft\\.icon)$")
    private val COMPRESSIBLE_AUDIO =
        Regex("^audio/(wav|x-wav|wave|vnd\\.wave|aiff|x-aiff|basic|l16)$")

    /** 字节已经是熵编码（媒体/压缩包）的 MIME，gzip 只是浪费时间。 */
    fun isPrecompressedType(typeRaw: String): Boolean {
        val media = typeRaw.substringBefore(';').trim().lowercase()
        if (media.startsWith("video/")) return true
        if (media.startsWith("image/")) return !COMPRESSIBLE_IMAGES.matches(media)
        if (media.startsWith("audio/")) return !COMPRESSIBLE_AUDIO.matches(media)
        if (media.startsWith("application/vnd.openxmlformats-officedocument.")) return true
        if (media.startsWith("application/vnd.oasis.opendocument.")) return true
        if (media.endsWith("+zip")) return true
        return PRECOMPRESSED_TYPES.contains(media)
    }

    /** 只保留纯文件名，并剔除控制字符与相对路径名；名字来自光学信道，不可信。 */
    fun safeFileName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.filter { it.code > 0x1F && it.code != 0x7F }.trim()
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "transfer.bin" else cleaned
    }

    fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    data class Packed(
        val container: ByteArray,
        val compression: String, // "none" | "gzip"
        val originalSize: Long,
        val transmittedSize: Long,
    )

    data class OpticalFile(
        val name: String,
        val type: String,
        val bytes: ByteArray,
        val sha256: ByteArray,
        val compression: String,
        val transmittedSize: Long,
    )

    fun packFile(name: String, type: String?, bytes: ByteArray): Packed {
        require(bytes.isNotEmpty()) { "fileEmpty" }
        require(bytes.size.toLong() <= MAX_FILE_BYTES) { "fileOverLimit" }

        val nameBytes = safeFileName(name).toByteArray(Charsets.UTF_8)
        val typeBytes = (type?.takeIf { it.isNotEmpty() } ?: "application/octet-stream")
            .toByteArray(Charsets.UTF_8)
        require(nameBytes.size <= 0xFFFF && typeBytes.size <= 0xFFFF) { "fileNameTooLong" }

        val digest = sha256(bytes)

        // 太小不值得 gzip；已压缩格式直接跳过。
        val tryGzip = bytes.size >= 768 && !isPrecompressedType(type ?: "")
        var compressed: ByteArray? = null
        if (tryGzip) {
            val gz = gzip(bytes)
            if (gz.size + 64 < bytes.size) compressed = gz
        }
        val transmitted = compressed ?: bytes
        val compression = if (compressed != null) "gzip" else "none"

        val out = ByteArray(HEADER_LEN + nameBytes.size + typeBytes.size + transmitted.size)
        System.arraycopy(MAGIC, 0, out, 0, 4)
        out[4] = if (compressed != null) 1 else 0
        putU16(out, 5, nameBytes.size)
        putU16(out, 7, typeBytes.size)
        putU32(out, 9, bytes.size.toLong())
        putU32(out, 13, transmitted.size.toLong())
        System.arraycopy(digest, 0, out, 17, 32)
        var off = HEADER_LEN
        System.arraycopy(nameBytes, 0, out, off, nameBytes.size); off += nameBytes.size
        System.arraycopy(typeBytes, 0, out, off, typeBytes.size); off += typeBytes.size
        System.arraycopy(transmitted, 0, out, off, transmitted.size)

        return Packed(out, compression, bytes.size.toLong(), transmitted.size.toLong())
    }

    fun unpackFile(container: ByteArray): OpticalFile {
        require(container.size >= HEADER_LEN) { "containerTruncated" }
        for (i in MAGIC.indices) {
            require(container[i] == MAGIC[i]) { "containerBadMagic" }
        }
        val compressionByte = container[4].toInt() and 0xFF
        require(compressionByte <= 1) { "containerBadCompression" }
        val compression = if (compressionByte == 1) "gzip" else "none"

        val nameLength = getU16(container, 5)
        val typeLength = getU16(container, 7)
        val fileLength = getU32(container, 9)
        val transmittedLength = getU32(container, 13)
        val dataOffset = HEADER_LEN + nameLength + typeLength

        require(fileLength in 1..MAX_FILE_BYTES) { "containerLengthMismatch" }
        require(transmittedLength in 1..MAX_FILE_BYTES) { "containerLengthMismatch" }
        require(dataOffset + transmittedLength.toInt() == container.size) {
            "containerLengthMismatch"
        }

        val transmitted = container.copyOfRange(dataOffset, container.size)

        if (compression == "gzip") {
            require(transmitted.size >= 18) { "gzipIncomplete" }
            // gzip 尾部 ISIZE 是攻击者可控的提示，只做快速校验。
            val trailerSize = getU32(transmitted, transmitted.size - 4)
            require(trailerSize == fileLength) { "gzipLengthMismatch" }
        }

        val rawBytes = if (compression == "gzip") {
            gunzip(transmitted, fileLength)
        } else {
            transmitted
        }
        require(rawBytes.size.toLong() == fileLength) { "decompressedLengthMismatch" }

        val name = safeFileName(
            String(container, HEADER_LEN, nameLength, Charsets.UTF_8)
        )
        val mime = String(
            container, HEADER_LEN + nameLength, typeLength, Charsets.UTF_8
        ).ifEmpty { "application/octet-stream" }
        val digest = container.copyOfRange(17, 49)

        return OpticalFile(name, mime, rawBytes, digest, compression, transmittedLength)
    }

    /** 校验 SHA-256；接收端必须在返回 true 后才允许保存。 */
    fun verify(file: OpticalFile): Boolean =
        MessageDigest.isEqual(sha256(file.bytes), file.sha256)

    private fun gzip(bytes: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(bytes.size / 2)
        GZIPOutputStream(bos).use { it.write(bytes) }
        return bos.toByteArray()
    }

    /** 解压带硬上限：gzip 尾部长度不可信，超量即中止，防止 zip-bomb。 */
    private fun gunzip(bytes: ByteArray, maxBytes: Long): ByteArray {
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

    private fun putU16(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun putU32(buf: ByteArray, off: Int, v: Long) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun getU16(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or
            ((buf[off + 1].toInt() and 0xFF) shl 8)

    private fun getU32(buf: ByteArray, off: Int): Long =
        (buf[off].toLong() and 0xFF) or
            ((buf[off + 1].toLong() and 0xFF) shl 8) or
            ((buf[off + 2].toLong() and 0xFF) shl 16) or
            ((buf[off + 3].toLong() and 0xFF) shl 24)
}
