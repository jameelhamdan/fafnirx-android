package app.fafnirx.android

import app.fafnirx.android.net.Collector
import app.fafnirx.android.net.Endpoint
import app.fafnirx.android.net.Failure
import app.fafnirx.android.net.SyncException
import app.fafnirx.android.sync.CHUNK_SIZE
import app.fafnirx.android.sync.FileSource
import app.fafnirx.android.sync.RandomRead
import app.fafnirx.android.sync.Transfer
import fafnirx.v1.CollectorGrpc
import fafnirx.v1.Fafnirx
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.StreamObserver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The real transport: the app's own channel (OkHttp, plain HTTP/2 without TLS, as used for a master on a LAN) talking over a TCP
 * socket to a gRPC server, which is what tonic is on the master. The upload is large enough to need flow control.
 */
class NetworkTest {
    private val received = CopyOnWriteArrayList<Int>()
    private val committed = CopyOnWriteArrayList<String>()
    private lateinit var server: Server

    private class Memory(val bytes: ByteArray) : FileSource {
        override fun stream(): InputStream = ByteArrayInputStream(bytes)
        override fun stat() = bytes.size.toLong() to 1_700_000_000_000L
        override fun random() = object : RandomRead {
            override fun read(offset: Long, buf: ByteArray, len: Int): Int {
                val n = minOf(len.toLong(), bytes.size - offset).toInt()
                System.arraycopy(bytes, offset.toInt(), buf, 0, n)
                return n
            }

            override fun close() {}
        }
    }

    @Before
    fun start() {
        val service = object : CollectorGrpc.CollectorImplBase() {
            override fun identify(request: Fafnirx.IdentifyRequest, responseObserver: StreamObserver<Fafnirx.IdentifyResponse>) {
                responseObserver.onNext(Fafnirx.IdentifyResponse.newBuilder().setMasterId("master-1").build())
                responseObserver.onCompleted()
            }

            override fun heartbeat(request: Fafnirx.HeartbeatRequest, responseObserver: StreamObserver<Fafnirx.HeartbeatResponse>) {
                responseObserver.onNext(Fafnirx.HeartbeatResponse.newBuilder().setDeviceName("phone").build())
                responseObserver.onCompleted()
            }

            override fun negotiate(request: Fafnirx.Manifest, responseObserver: StreamObserver<Fafnirx.NegotiateResponse>) {
                responseObserver.onNext(Fafnirx.NegotiateResponse.newBuilder().addAllMissing(0 until request.chunkHashesCount).build())
                responseObserver.onCompleted()
            }

            override fun uploadChunks(responseObserver: StreamObserver<Fafnirx.UploadSummary>): StreamObserver<Fafnirx.Chunk> =
                object : StreamObserver<Fafnirx.Chunk> {
                    override fun onNext(value: Fafnirx.Chunk) {
                        received += value.index
                        assertEquals(CHUNK_SIZE.coerceAtMost(value.data.size()), value.data.size())
                    }

                    override fun onError(t: Throwable) {}
                    override fun onCompleted() {
                        responseObserver.onNext(Fafnirx.UploadSummary.newBuilder().setStored(received.size).build())
                        responseObserver.onCompleted()
                    }
                }

            override fun commit(request: Fafnirx.CommitRequest, responseObserver: StreamObserver<Fafnirx.CommitResponse>) {
                committed += request.manifest.path
                responseObserver.onNext(Fafnirx.CommitResponse.newBuilder().setVersionId(1).build())
                responseObserver.onCompleted()
            }
        }
        server = NettyServerBuilder.forPort(0).addService(service).maxInboundMessageSize(8 * 1024 * 1024).build().start()
    }

    @After
    fun stop() {
        server.shutdownNow()
    }

    private fun endpoint() = Endpoint.parse("127.0.0.1:${server.port}")!!

    @Test
    fun identifyAndHeartbeatOverPlainHttp2() {
        val channel = Collector.channel(endpoint())
        try {
            val api = Collector(channel, "t", "i")
            assertEquals("master-1", api.identify())
            assertEquals("phone", api.heartbeat(Fafnirx.HeartbeatRequest.newBuilder().setOs("android").build()).deviceName)
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun uploadsSeveralChunksUnderFlowControl() {
        val channel = Collector.channel(endpoint())
        try {
            val src = Memory(ByteArray(3 * CHUNK_SIZE + 12345) { (it % 251).toByte() })
            val transfer = Transfer(Collector(channel, "t", "i"))
            val hash = transfer.hash(src)
            val sent = transfer.send("Camera/big.mp4", src, hash, 1_700_000_000_000L, { false }) {}
            assertEquals(src.bytes.size.toLong(), sent)
            assertEquals(listOf(0, 1, 2, 3), received.toList())
            assertEquals(listOf("Camera/big.mp4"), committed.toList())
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun anUnreachableServerIsReportedAsOffline() {
        val port = server.port
        server.shutdownNow().awaitTermination()
        val channel = Collector.channel(Endpoint("127.0.0.1", port, false))
        try {
            Collector(channel, "t", "i").identify()
            throw AssertionError("expected a failure")
        } catch (e: SyncException) {
            assertEquals(Failure.OFFLINE, e.failure)
            assertTrue(e.message!!.isNotEmpty())
        } finally {
            channel.shutdownNow()
        }
    }
}
