package com.airscan.qr.core

/**
 * wasm 平台实现：纯 Kotlin SHA-256 / DEFLATE。
 *
 * gzip 解压必须可用——Android/桌面发送端会发出 gzip 容器；
 * 压缩侧暂不实现（返回 null），web 端发出的容器走不压缩路径。
 */
internal object Platform {

    fun sha256(bytes: ByteArray): ByteArray = PureSha256.digest(bytes)

    fun gzipCompress(bytes: ByteArray): ByteArray? = null

    fun gzipDecompress(bytes: ByteArray, maxBytes: Long): ByteArray =
        PureInflate.gunzip(bytes, maxBytes)

    /** 常数时间比较，避免计时侧信道。 */
    fun ctEqual(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].toInt() xor b[i].toInt())
        return r == 0
    }
}
