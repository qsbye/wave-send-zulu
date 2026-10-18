package com.airscan.qr.core.ggwave

/**
 * Reed-Solomon 编解码器（GF(2^8)，本原多项式 0x11d）。
 *
 * 语义移植自 ggwave src/reed-solomon/rs.hpp（mersinvald 实现），
 * 编解码结果与 C 版逐位一致。改用显式 ByteArray 多项式替代 C 版的内存视图。
 */
internal class ReedSolomon(
    private val msgLength: Int,
    private val eccLength: Int,
) {
    init {
        require(msgLength + eccLength < 256) { "msg_length + ecc_length must be < 256" }
    }

    /** 生成多项式（缓存）。 */
    private val generator: IntArray by lazy {
        var gen = intArrayOf(1)
        for (i in 0 until eccLength) {
            val mulp = intArrayOf(1, Gf.pow(2, i))
            gen = polyMul(gen, mulp)
        }
        gen
    }

    /** 编码：dst = src(msgLength) + ecc(eccLength)。 */
    fun encode(src: ByteArray, srcOffset: Int = 0): ByteArray {
        val gen = generator
        // msg_out = src followed by eccLength zeros
        val out = IntArray(msgLength + eccLength)
        for (i in 0 until msgLength) out[i] = src[srcOffset + i].toInt() and 0xFF
        for (i in 0 until msgLength) {
            val coef = out[i]
            if (coef != 0) {
                for (j in 1 until gen.size) {
                    out[i + j] = out[i + j] xor Gf.mul(gen[j], coef)
                }
            }
        }
        val result = ByteArray(msgLength + eccLength)
        for (i in 0 until msgLength) result[i] = src[srcOffset + i]
        for (i in 0 until eccLength) result[msgLength + i] = out[msgLength + i].toByte()
        return result
    }

    /**
     * 解码。src 为 msgLength + eccLength 字节（消息 + ECC）。
     * 成功返回 msgLength 字节消息；失败返回 null。
     */
    fun decode(src: ByteArray, srcOffset: Int = 0): ByteArray? {
        val msgIn = IntArray(msgLength + eccLength)
        for (i in 0 until msgLength + eccLength) msgIn[i] = src[srcOffset + i].toInt() and 0xFF

        val synd = calcSyndromes(msgIn)
        var hasErrors = false
        for (i in synd.indices) if (synd[i] != 0) { hasErrors = true; break }

        val corrected: IntArray
        if (!hasErrors) {
            corrected = msgIn
        } else {
            val fsynd = calcForneySyndromes(synd, intArrayOf(0), 0, msgIn.size)
            val errLoc = findErrorLocator(fsynd, null, 0) ?: return null
            // reverse errLoc
            val reloc = errLoc.reversedArray()
            val errPos = findErrors(reloc, msgIn.size) ?: return null
            if (errPos.isEmpty()) return null
            corrected = correctErrata(synd, errPos, msgIn)
        }

        val out = ByteArray(msgLength)
        for (i in 0 until msgLength) out[i] = corrected[i].toByte()
        return out
    }

    // ---- 内部多项式运算（系数按 C 版同序，index 0 为最高次） ----

    private fun polyMul(p: IntArray, q: IntArray): IntArray {
        val r = IntArray(p.size + q.size - 1)
        for (j in q.indices) {
            for (i in p.indices) {
                r[i + j] = r[i + j] xor Gf.mul(p[i], q[j])
            }
        }
        return r
    }

    private fun polyAdd(p: IntArray, q: IntArray): IntArray {
        val len = maxOf(p.size, q.size)
        val r = IntArray(len)
        for (i in p.indices) r[i + len - p.size] = p[i]
        for (i in q.indices) r[i + len - q.size] = r[i + len - q.size] xor q[i]
        return r
    }

    private fun polyScale(p: IntArray, x: Int): IntArray {
        val r = IntArray(p.size)
        for (i in p.indices) r[i] = Gf.mul(p[i], x)
        return r
    }

    private fun polyEval(p: IntArray, x: Int): Int {
        var y = p[0]
        for (i in 1 until p.size) {
            y = Gf.mul(y, x) xor p[i]
        }
        return y
    }

    /** 除法取余（返回余数）。C 版 poly_div 将结果左移保留余数。 */
    private fun polyMod(p: IntArray, q: IntArray): IntArray {
        val r = p.copyOf()
        for (i in 0 until (p.size - (q.size - 1))) {
            val coef = r[i]
            if (coef != 0) {
                for (j in 1 until q.size) {
                    if (q[j] != 0) r[i + j] = r[i + j] xor Gf.mul(q[j], coef)
                }
            }
        }
        val sep = p.size - (q.size - 1)
        return r.copyOfRange(sep, r.size)
    }

    private fun calcSyndromes(msg: IntArray): IntArray {
        // synd[0]=0, synd[i] = poly_eval(msg, 2^(i-1)), i in 1..eccLength
        val synd = IntArray(eccLength + 1)
        synd[0] = 0
        for (i in 1..eccLength) {
            synd[i] = polyEval(msg, Gf.pow(2, i - 1))
        }
        return synd
    }

    private fun calcForneySyndromes(synd: IntArray, erasePos: IntArray, eraseCount: Int, msgInSize: Int): IntArray {
        val erasePosReversed = IntArray(eraseCount) { msgInSize - 1 - erasePos[it] }
        var forneySynd = synd.copyOfRange(1, synd.size)
        for (i in 0 until eraseCount) {
            val x = Gf.pow(2, erasePosReversed[i])
            for (j in 0 until forneySynd.size - 1) {
                forneySynd[j] = Gf.mul(forneySynd[j], x) xor forneySynd[j + 1]
            }
        }
        return forneySynd
    }

    private fun findErrorLocator(synd: IntArray, eraseLoc: IntArray?, eraseCount: Int): IntArray? {
        var errLoc: IntArray
        var oldLoc: IntArray
        if (eraseLoc != null) {
            errLoc = eraseLoc.copyOf()
            oldLoc = eraseLoc.copyOf()
        } else {
            errLoc = intArrayOf(1)
            oldLoc = intArrayOf(1)
        }

        val syndShift = if (synd.size > eccLength) synd.size - eccLength else 0

        for (i in 0 until eccLength - eraseCount) {
            val k = (if (eraseLoc != null) eraseCount + i else i) + syndShift
            var delta = synd[k]
            for (j in 1 until errLoc.size) {
                val index = errLoc.size - j - 1
                delta = delta xor Gf.mul(errLoc[index], synd[k - j])
            }

            oldLoc = oldLoc + 0 // append 0

            if (delta != 0) {
                if (oldLoc.size > errLoc.size) {
                    val temp = polyScale(oldLoc, delta)
                    oldLoc = polyScale(errLoc, Gf.inverse(delta))
                    errLoc = temp
                }
                val temp = polyScale(oldLoc, delta)
                errLoc = polyAdd(errLoc, temp)
            }
        }

        var shift = 0
        while (shift < errLoc.size && errLoc[shift] == 0) shift++
        val errs = errLoc.size - shift - 1
        if ((errs - eraseCount) * 2 + eraseCount > eccLength) return null
        return errLoc.copyOfRange(shift, errLoc.size)
    }

    private fun findErrors(errorLoc: IntArray, msgInSize: Int): IntArray? {
        val errs = errorLoc.size - 1
        val errPos = mutableListOf<Int>()
        for (i in 0 until msgInSize) {
            if (polyEval(errorLoc, Gf.pow(2, i)) == 0) {
                errPos.add(msgInSize - 1 - i)
            }
        }
        if (errPos.size != errs) return null
        return errPos.toIntArray()
    }

    private fun correctErrata(synd: IntArray, errPos: IntArray, msgIn: IntArray): IntArray {
        // c_pos = msgIn.size-1 - e for e in errPos
        val cPos = IntArray(errPos.size) { msgIn.size - 1 - errPos[it] }

        // errata locator
        val errataLoc = findErrataLocator(cPos)

        // reverse syndromes
        val rsynd = synd.reversedArray()

        // error evaluator = (rsynd * errataLoc) mod (x^(errataLoc.size-1 + 1))
        val reEval = findErrorEvaluator(rsynd, errataLoc, errataLoc.size - 1)

        // C 版（rs.hpp L432）直接对【反转后的】evaluator 求值，不反转回原序
        val X = IntArray(cPos.size) {
            val l = 255 - cPos[it]
            Gf.pow(2, -l)
        }

        val E = IntArray(msgIn.size)
        for (i in X.indices) {
            val xiInv = Gf.inverse(X[i])
            // err_loc_prime = product over j != i of (1 - xiInv*X[j])
            var errLocPrime = 1
            for (j in X.indices) {
                if (j != i) {
                    errLocPrime = Gf.mul(errLocPrime, Gf.sub(1, Gf.mul(xiInv, X[j])))
                }
            }
            var y = polyEval(reEval, xiInv)
            y = Gf.mul(Gf.pow(X[i], 1), y)
            E[errPos[i]] = Gf.div(y, errLocPrime)
        }

        return polyAdd(msgIn, E)
    }

    private fun findErrataLocator(cPos: IntArray): IntArray {
        var errataLoc = intArrayOf(1)
        for (i in cPos.indices) {
            val addp = intArrayOf(Gf.pow(2, cPos[i]), 0)
            val mulp = intArrayOf(1)
            val apol = polyAdd(mulp, addp)
            errataLoc = polyMul(errataLoc, apol)
        }
        return errataLoc
    }

    private fun findErrorEvaluator(synd: IntArray, errataLoc: IntArray, eccLen: Int): IntArray {
        val mulp = polyMul(synd, errataLoc)
        val divisor = IntArray(eccLen + 2)
        divisor[0] = 1
        return polyMod(mulp, divisor)
    }
}
