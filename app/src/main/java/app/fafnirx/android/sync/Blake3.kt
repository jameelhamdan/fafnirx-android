package app.fafnirx.android.sync

/**
 * BLAKE3 (the default 32-byte hash) of a byte range held in memory. Follows the reference implementation; checked against
 * the official algorithm by the vectors in the unit tests. An instance owns scratch arrays: use one per thread.
 */
class Blake3 {
    private val v = IntArray(16)
    private val m = IntArray(16)
    private val cv = IntArray(8)

    fun hash(data: ByteArray, len: Int): ByteArray {
        val out = IntArray(8)
        if (len <= CHUNK_LEN) {
            chunkCv(data, 0, len, 0, true, out)
            return toBytes(out)
        }
        val chunks = (len + CHUNK_LEN - 1) / CHUNK_LEN
        val stack = ArrayList<IntArray>()
        for (i in 0 until chunks - 1) {
            var c = IntArray(8)
            chunkCv(data, i * CHUNK_LEN, CHUNK_LEN, i.toLong(), false, c)
            var total = i + 1
            while (total and 1 == 0) {
                c = parent(stack.removeAt(stack.size - 1), c, false)
                total = total shr 1
            }
            stack.add(c)
        }
        val last = (chunks - 1) * CHUNK_LEN
        var cur = IntArray(8)
        chunkCv(data, last, len - last, (chunks - 1).toLong(), false, cur)
        while (stack.isNotEmpty()) {
            val left = stack.removeAt(stack.size - 1)
            cur = parent(left, cur, stack.isEmpty())
        }
        return toBytes(cur)
    }

    private fun parent(left: IntArray, right: IntArray, root: Boolean): IntArray {
        System.arraycopy(left, 0, m, 0, 8)
        System.arraycopy(right, 0, m, 8, 8)
        compress(IV, 0, 64, PARENT or (if (root) ROOT else 0))
        return v.copyOf(8)
    }

    private fun chunkCv(data: ByteArray, off: Int, len: Int, counter: Long, root: Boolean, out: IntArray) {
        System.arraycopy(IV, 0, cv, 0, 8)
        val blocks = if (len == 0) 1 else (len + 63) / 64
        for (b in 0 until blocks) {
            val start = off + b * 64
            val blockLen = minOf(64, len - b * 64)
            for (i in 0 until 16) {
                val p = i * 4
                m[i] = if (p + 3 < blockLen) {
                    (data[start + p].toInt() and 0xff) or ((data[start + p + 1].toInt() and 0xff) shl 8) or
                        ((data[start + p + 2].toInt() and 0xff) shl 16) or ((data[start + p + 3].toInt() and 0xff) shl 24)
                } else {
                    var w = 0
                    for (k in 0 until 4) if (p + k < blockLen) w = w or ((data[start + p + k].toInt() and 0xff) shl (8 * k))
                    w
                }
            }
            var flags = 0
            if (b == 0) flags = flags or CHUNK_START
            if (b == blocks - 1) flags = flags or CHUNK_END or (if (root) ROOT else 0)
            compress(cv, counter, blockLen, flags)
            System.arraycopy(v, 0, cv, 0, 8)
        }
        System.arraycopy(cv, 0, out, 0, 8)
    }

    private fun compress(cvIn: IntArray, counter: Long, blockLen: Int, flags: Int) {
        for (i in 0 until 8) v[i] = cvIn[i]
        v[8] = IV[0]; v[9] = IV[1]; v[10] = IV[2]; v[11] = IV[3]
        v[12] = counter.toInt(); v[13] = (counter ushr 32).toInt(); v[14] = blockLen; v[15] = flags
        for (r in 0 until 7) {
            val s = SCHEDULE[r]
            g(0, 4, 8, 12, m[s[0]], m[s[1]]); g(1, 5, 9, 13, m[s[2]], m[s[3]])
            g(2, 6, 10, 14, m[s[4]], m[s[5]]); g(3, 7, 11, 15, m[s[6]], m[s[7]])
            g(0, 5, 10, 15, m[s[8]], m[s[9]]); g(1, 6, 11, 12, m[s[10]], m[s[11]])
            g(2, 7, 8, 13, m[s[12]], m[s[13]]); g(3, 4, 9, 14, m[s[14]], m[s[15]])
        }
        for (i in 0 until 8) {
            v[i] = v[i] xor v[i + 8]
            v[i + 8] = v[i + 8] xor cvIn[i]
        }
    }

    private fun g(a: Int, b: Int, c: Int, d: Int, mx: Int, my: Int) {
        v[a] = v[a] + v[b] + mx
        v[d] = Integer.rotateRight(v[d] xor v[a], 16)
        v[c] = v[c] + v[d]
        v[b] = Integer.rotateRight(v[b] xor v[c], 12)
        v[a] = v[a] + v[b] + my
        v[d] = Integer.rotateRight(v[d] xor v[a], 8)
        v[c] = v[c] + v[d]
        v[b] = Integer.rotateRight(v[b] xor v[c], 7)
    }

    private fun toBytes(words: IntArray): ByteArray {
        val out = ByteArray(32)
        for (i in 0 until 8) for (k in 0 until 4) out[i * 4 + k] = (words[i] ushr (8 * k)).toByte()
        return out
    }

    private companion object {
        const val CHUNK_LEN = 1024
        const val CHUNK_START = 1
        const val CHUNK_END = 2
        const val PARENT = 4
        const val ROOT = 8

        val IV = intArrayOf(
            0x6A09E667, 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt(),
            0x510E527F, 0x9B05688C.toInt(), 0x1F83D9AB, 0x5BE0CD19,
        )
        private val PERMUTATION = intArrayOf(2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8)
        val SCHEDULE: Array<IntArray> = Array(7) { IntArray(16) }.also { s ->
            for (i in 0 until 16) s[0][i] = i
            for (r in 1 until 7) for (i in 0 until 16) s[r][i] = s[r - 1][PERMUTATION[i]]
        }
    }
}
