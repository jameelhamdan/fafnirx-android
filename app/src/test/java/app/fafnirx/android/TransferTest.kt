package app.fafnirx.android

import app.fafnirx.android.net.Collector
import app.fafnirx.android.net.Failure
import app.fafnirx.android.net.SyncException
import app.fafnirx.android.sync.CHUNK_SIZE
import app.fafnirx.android.sync.FileSource
import app.fafnirx.android.sync.Hashing
import app.fafnirx.android.sync.RandomRead
import app.fafnirx.android.sync.Transfer
import fafnirx.v1.CollectorGrpc
import fafnirx.v1.Fafnirx
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/** The upload against a fake master (in process): the same calls, in the same order, as the Windows client makes. */
class TransferTest {
    private class Memory(val bytes: ByteArray, var mtime: Long = 1_700_000_000_000L) : FileSource {
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

    private val calls = mutableListOf<String>()
    private val chunks = mutableListOf<Fafnirx.Chunk>()
    private val commits = mutableListOf<Fafnirx.CommitRequest>()
    private var headers: Metadata? = null
    private var missing = listOf(1)
    private var alreadyCurrent = false
    private var negotiateError: Status? = null
    private lateinit var server: Server
    private lateinit var api: Collector
    private lateinit var channel: io.grpc.ManagedChannel

    @Before
    fun start() {
        val service = object : CollectorGrpc.CollectorImplBase() {
            override fun negotiate(request: Fafnirx.Manifest, responseObserver: StreamObserver<Fafnirx.NegotiateResponse>) {
                calls += "negotiate"
                negotiateError?.let {
                    responseObserver.onError(it.asRuntimeException())
                    return
                }
                responseObserver.onNext(Fafnirx.NegotiateResponse.newBuilder().addAllMissing(missing).setAlreadyCurrent(alreadyCurrent).build())
                responseObserver.onCompleted()
            }

            override fun uploadChunks(responseObserver: StreamObserver<Fafnirx.UploadSummary>): StreamObserver<Fafnirx.Chunk> {
                calls += "upload"
                return object : StreamObserver<Fafnirx.Chunk> {
                    override fun onNext(value: Fafnirx.Chunk) {
                        chunks += value
                    }

                    override fun onError(t: Throwable) {}
                    override fun onCompleted() {
                        responseObserver.onNext(Fafnirx.UploadSummary.newBuilder().setStored(chunks.size).build())
                        responseObserver.onCompleted()
                    }
                }
            }

            override fun commit(request: Fafnirx.CommitRequest, responseObserver: StreamObserver<Fafnirx.CommitResponse>) {
                calls += "commit"
                commits += request
                responseObserver.onNext(Fafnirx.CommitResponse.newBuilder().setVersionId(7).build())
                responseObserver.onCompleted()
            }
        }
        val capture = object : ServerInterceptor {
            override fun <ReqT, RespT> interceptCall(
                call: ServerCall<ReqT, RespT>,
                h: Metadata,
                next: ServerCallHandler<ReqT, RespT>,
            ): ServerCall.Listener<ReqT> {
                headers = h
                return next.startCall(call, h)
            }
        }
        val name = InProcessServerBuilder.generateName()
        server = InProcessServerBuilder.forName(name).directExecutor().addService(ServerInterceptors.intercept(service, capture)).build().start()
        channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        api = Collector(channel, "secret-token", "0123456789abcdef0123456789abcdef")
    }

    @After
    fun stop() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    private fun file(size: Int) = Memory(ByteArray(size) { (it % 251).toByte() })

    @Test
    fun sendsOnlyTheMissingChunksThenCommits() {
        val src = file(CHUNK_SIZE + 1000)
        val transfer = Transfer(api)
        val hash = transfer.hash(src)
        assertEquals(2, hash.chunkCount)
        var counted = 0L
        val sent = transfer.send("Camera/a.jpg", src, hash, src.mtime, { false }) { counted += it }

        assertEquals(listOf("negotiate", "upload", "commit"), calls)
        assertEquals(1, chunks.size)
        assertEquals(1, chunks[0].index)
        assertEquals(1000, chunks[0].data.size())
        assertArrayEquals(src.bytes.copyOfRange(CHUNK_SIZE, CHUNK_SIZE + 1000), chunks[0].data.toByteArray())
        assertArrayEquals(hash.chunkHash(1), chunks[0].hash.toByteArray())
        assertArrayEquals(hash.root, chunks[0].rootHash.toByteArray())
        assertEquals(1000L, sent)
        assertEquals(1000L, counted)
        assertEquals("Camera/a.jpg", commits[0].manifest.path)
        assertEquals(src.mtime * 1_000_000L, commits[0].mtime)
        assertEquals(0, commits[0].mode)
        assertEquals(src.bytes.size.toLong(), commits[0].manifest.size)
    }

    @Test
    fun everyCallCarriesTheTokenAndTheInstance() {
        val src = file(10)
        val transfer = Transfer(api)
        missing = emptyList()
        transfer.send("a", src, transfer.hash(src), src.mtime, { false }) {}
        val h = headers!!
        assertEquals("Bearer secret-token", h.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)))
        assertEquals("0123456789abcdef0123456789abcdef", h.get(Metadata.Key.of("x-fafnirx-instance", Metadata.ASCII_STRING_MARSHALLER)))
    }

    @Test
    fun aVersionTheMasterHasAlreadyNeedsNothing() {
        alreadyCurrent = true
        missing = emptyList()
        val src = file(100)
        val transfer = Transfer(api)
        val sent = transfer.send("a", src, transfer.hash(src), src.mtime, { false }) {}
        assertEquals(0L, sent)
        assertEquals(listOf("negotiate"), calls)
    }

    @Test
    fun knownContentIsCommittedWithoutUpload() {
        missing = emptyList()
        val src = file(100)
        val transfer = Transfer(api)
        transfer.send("a", src, transfer.hash(src), src.mtime, { false }) {}
        assertEquals(listOf("negotiate", "commit"), calls)
    }

    @Test
    fun aFileChangedAfterHashingIsNotCommitted() {
        missing = emptyList()
        val src = file(100)
        val transfer = Transfer(api)
        val hash = transfer.hash(src)
        val hashedAt = src.mtime
        src.mtime += 5000
        try {
            transfer.send("a", src, hash, hashedAt, { false }) {}
            throw AssertionError("expected a failure")
        } catch (e: SyncException) {
            assertEquals(Failure.FILE, e.failure)
        }
        assertTrue("commit" !in calls)
    }

    @Test
    fun statusCodesAreMappedLikeTheWindowsClient() {
        fun failure(code: Status.Code) = Collector.map(Status.fromCode(code).asRuntimeException()).failure
        assertEquals(Failure.AUTH, failure(Status.Code.UNAUTHENTICATED))
        assertEquals(Failure.IN_USE, failure(Status.Code.PERMISSION_DENIED))
        val offline = listOf(
            Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED, Status.Code.CANCELLED, Status.Code.UNKNOWN,
            Status.Code.RESOURCE_EXHAUSTED, Status.Code.INTERNAL,
        )
        for (c in offline) assertEquals(c.name, Failure.OFFLINE, failure(c))
        assertEquals(Failure.FILE, failure(Status.Code.INVALID_ARGUMENT))
    }

    @Test
    fun theMasterRefusingTheTokenSurfacesAsAuth() {
        negotiateError = Status.UNAUTHENTICATED.withDescription("unknown or revoked device token")
        val src = file(10)
        val transfer = Transfer(api)
        try {
            transfer.send("a", src, transfer.hash(src), src.mtime, { false }) {}
            throw AssertionError("expected a failure")
        } catch (e: SyncException) {
            assertEquals(Failure.AUTH, e.failure)
        }
    }

    @Test
    fun emptyFilesHaveNoChunks() {
        val h = Hashing.hashStream(ByteArrayInputStream(ByteArray(0)), ByteArray(CHUNK_SIZE))
        assertEquals(0, h.chunkCount)
        assertEquals(0L, h.size)
    }
}
