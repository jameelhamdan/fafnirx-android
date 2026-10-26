package app.fafnirx.android.sync

import java.io.InputStream

const val CHUNK_SIZE = 4 * 1024 * 1024
const val HASH_LEN = 32

/** A file as the master sees it: ordered chunk hashes (concatenated, 32 bytes each), the size and the root hash. */
class FileHash(val size: Long, val chunks: ByteArray, val root: ByteArray) {
    val chunkCount get() = chunks.size / HASH_LEN
    fun chunkHash(i: Int): ByteArray = chunks.copyOfRange(i * HASH_LEN, (i + 1) * HASH_LEN)
}

object Hashing {
    /** root = blake3(chunk_hash_0 || ... || chunk_hash_n || size as u64 little endian), as in `fafnirx-common`. */
    fun root(blake: Blake3, chunks: ByteArray, size: Long): ByteArray {
        val buf = ByteArray(chunks.size + 8)
        System.arraycopy(chunks, 0, buf, 0, chunks.size)
        for (k in 0 until 8) buf[chunks.size + k] = (size ushr (8 * k)).toByte()
        return blake.hash(buf, buf.size)
    }

    /** Reads the stream to the end in 4 MiB chunks. [buf] must be [CHUNK_SIZE] bytes and is reused. */
    fun hashStream(input: InputStream, buf: ByteArray, onBytes: (Int) -> Unit = {}): FileHash {
        val blake = Blake3()
        val hashes = java.io.ByteArrayOutputStream()
        var size = 0L
        while (true) {
            val n = readFully(input, buf, buf.size)
            if (n == 0) break
            hashes.write(blake.hash(buf, n))
            size += n
            onBytes(n)
            if (n < buf.size) break
        }
        val chunks = hashes.toByteArray()
        return FileHash(size, chunks, root(blake, chunks, size))
    }

    fun readFully(input: InputStream, buf: ByteArray, len: Int): Int {
        var got = 0
        while (got < len) {
            val n = input.read(buf, got, len - got)
            if (n < 0) break
            got += n
        }
        return got
    }

    fun chunkLength(size: Long, index: Int): Int = minOf(CHUNK_SIZE.toLong(), size - index.toLong() * CHUNK_SIZE).toInt()
}
