package app.fafnirx.android

import app.fafnirx.android.sync.Blake3
import app.fafnirx.android.sync.CHUNK_SIZE
import app.fafnirx.android.sync.Hashing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** The vectors in `vectors.txt` come from the reference BLAKE3 library and the root-hash formula of `fafnirx-common`. */
class HashingTest {
    private fun data(n: Int) = ByteArray(n) { (it % 251).toByte() }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private val lines = javaClass.getResourceAsStream("/vectors.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }

    @Test
    fun blake3MatchesTheReference() {
        val rows = lines.filter { it.startsWith("b3 ") }
        assertTrue(rows.size > 10)
        for (row in rows) {
            val (_, len, expected) = row.split(" ")
            assertEquals("length $len", expected, hex(Blake3().hash(data(len.toInt()), len.toInt())))
        }
    }

    @Test
    fun fileHashesMatchTheServersRootHash() {
        val rows = lines.filter { it.startsWith("root ") }
        assertTrue(rows.any { it.split(" ")[1].toInt() > CHUNK_SIZE })
        for (row in rows) {
            val (_, len, chunks, expected) = row.split(" ")
            val hash = Hashing.hashStream(ByteArrayInputStream(data(len.toInt())), ByteArray(CHUNK_SIZE))
            assertEquals("chunks of $len", chunks.toInt(), hash.chunkCount)
            assertEquals("root of $len", expected, hex(hash.root))
            assertEquals(len.toLong(), hash.size)
        }
    }

    @Test
    fun chunkLengths() {
        assertEquals(CHUNK_SIZE, Hashing.chunkLength(2L * CHUNK_SIZE + 5, 0))
        assertEquals(5, Hashing.chunkLength(2L * CHUNK_SIZE + 5, 2))
        assertEquals(CHUNK_SIZE, Hashing.chunkLength(CHUNK_SIZE.toLong(), 0))
    }
}
