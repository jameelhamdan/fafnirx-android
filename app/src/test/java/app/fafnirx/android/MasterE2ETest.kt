package app.fafnirx.android

import app.fafnirx.android.net.Collector
import app.fafnirx.android.net.Endpoint
import app.fafnirx.android.sync.CHUNK_SIZE
import app.fafnirx.android.sync.FileSource
import app.fafnirx.android.sync.Pipeline
import app.fafnirx.android.sync.RandomRead
import app.fafnirx.android.sync.Transfer
import fafnirx.v1.Fafnirx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Against a real master. Skipped unless FAFNIRX_E2E_SERVER (host:port) and FAFNIRX_E2E_TOKEN are set:
 * the files appear under E2E/ on the device, which the maker of the test then compares with the originals.
 */
class MasterE2ETest {
    private class Memory(val bytes: ByteArray, val mtime: Long) : FileSource {
        override fun stream(): InputStream = ByteArrayInputStream(bytes)
        override fun stat() = bytes.size.toLong() to mtime
        override fun random() = object : RandomRead {
            override fun read(offset: Long, buf: ByteArray, len: Int): Int {
                val n = minOf(len.toLong(), bytes.size - offset).toInt()
                System.arraycopy(bytes, offset.toInt(), buf, 0, n)
                return n
            }

            override fun close() {}
        }
    }

    @Test
    fun uploadsToARealMaster() {
        val server = System.getenv("FAFNIRX_E2E_SERVER")
        val token = System.getenv("FAFNIRX_E2E_TOKEN")
        assumeTrue("no master given", !server.isNullOrBlank() && !token.isNullOrBlank())
        val channel = Collector.channel(Endpoint.parse(server)!!)
        try {
            val api = Collector(channel, token, "0123456789abcdef0123456789abcdef")
            assertTrue(api.identify().isNotEmpty())
            val beat = api.heartbeat(
                Fafnirx.HeartbeatRequest.newBuilder().setHostname("e2e").setOs("android").setClientVersion("test").setPhase("idle").build(),
            )
            assertEquals("admin/pixel", beat.deviceName)

            val transfer = Transfer(api)
            val files = mapOf(
                "E2E/empty.bin" to Memory(ByteArray(0), 1_700_000_001_000L),
                "E2E/small.bin" to Memory(ByteArray(1000) { (it % 251).toByte() }, 1_700_000_002_000L),
                "E2E/big.bin" to Memory(ByteArray(2 * CHUNK_SIZE + 4321) { ((it * 7) % 251).toByte() }, 1_700_000_003_000L),
            )
            for ((path, src) in files) {
                val hash = transfer.hash(src)
                val sent = transfer.send(path, src, hash, src.mtime, { false }) {}
                assertEquals(path, src.bytes.size.toLong(), sent)
                // Sending the same version again is recognised: nothing goes over the wire.
                assertEquals(path, 0L, transfer.send(path, src, hash, src.mtime, { false }) {})
            }
        } finally {
            channel.shutdownNow()
        }
    }

    /** The pipeline used by the app: one thread hashes ahead, three upload at once, over one connection. */
    @Test
    fun uploadsManyFilesInParallelToARealMaster() {
        val server = System.getenv("FAFNIRX_E2E_SERVER")
        val token = System.getenv("FAFNIRX_E2E_TOKEN")
        assumeTrue("no master given", !server.isNullOrBlank() && !token.isNullOrBlank())
        val channel = Collector.channel(Endpoint.parse(server)!!)
        try {
            val api = Collector(channel, token, "0123456789abcdef0123456789abcdef")
            val files = (0 until 24).associate { i ->
                val size = if (i % 6 == 0) CHUNK_SIZE + 777 * i else 500 + 31 * i
                "E2EP/f$i.bin" to Memory(ByteArray(size) { ((it * (i + 3)) % 251).toByte() }, 1_700_000_100_000L + i * 1000L)
            }
            val todo = files.entries.toList()
            val next = java.util.concurrent.atomic.AtomicInteger()
            val sent = java.util.concurrent.atomic.AtomicLong()
            val transfers = ThreadLocal.withInitial { Transfer(api) }
            class Job(val path: String, val src: Memory, val hash: app.fafnirx.android.sync.FileHash)
            Pipeline<Job>(3, { false }).run(
                prepare = {
                    val i = next.getAndIncrement()
                    if (i >= todo.size) null else Job(todo[i].key, todo[i].value, transfers.get()!!.hash(todo[i].value))
                },
                process = { job -> sent.addAndGet(transfers.get()!!.send(job.path, job.src, job.hash, job.src.mtime, { false }) {}) },
            )
            assertEquals(files.values.sumOf { it.bytes.size.toLong() }, sent.get())
        } finally {
            channel.shutdownNow()
        }
    }
}
