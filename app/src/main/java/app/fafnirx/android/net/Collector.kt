package app.fafnirx.android.net

import fafnirx.v1.CollectorGrpc
import fafnirx.v1.Fafnirx
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ClientInterceptors
import io.grpc.ForwardingClientCall
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.ClientCallStreamObserver
import io.grpc.stub.ClientResponseObserver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What went wrong, as far as the app cares. */
enum class Failure {
    /** Nothing reached the master, or it could not answer: the server is offline for now. */
    OFFLINE,

    /** The token is unknown or revoked. */
    AUTH,

    /** Another install is using this device. */
    IN_USE,

    /** This file failed; the next one may work. */
    FILE,

    /** The user stopped the sync. */
    STOPPED,
}

class SyncException(val failure: Failure, message: String) : Exception(message)

/** The device side of `fafnirx.v1.Collector`: only the calls this app makes. There is no call that reads or changes the phone. */
class Collector(channel: Channel, token: String, instance: String) {
    private val authenticated = ClientInterceptors.intercept(channel, Auth(token, instance))
    private val blocking = CollectorGrpc.newBlockingStub(authenticated)
    private val async = CollectorGrpc.newStub(authenticated)

    fun identify(): String = call { blocking.withDeadlineAfter(UNARY_SECS, TimeUnit.SECONDS).identify(Fafnirx.IdentifyRequest.getDefaultInstance()).masterId }

    fun heartbeat(request: Fafnirx.HeartbeatRequest): Fafnirx.HeartbeatResponse =
        call { blocking.withDeadlineAfter(UNARY_SECS, TimeUnit.SECONDS).heartbeat(request) }

    fun negotiate(manifest: Fafnirx.Manifest): Fafnirx.NegotiateResponse =
        call { blocking.withDeadlineAfter(UNARY_SECS, TimeUnit.SECONDS).negotiate(manifest) }

    fun commit(request: Fafnirx.CommitRequest): Long =
        call { blocking.withDeadlineAfter(COMMIT_SECS, TimeUnit.SECONDS).commit(request).versionId }

    /**
     * One client-streaming call: every chunk in [indexes], produced one at a time by [chunkAt] and sent when the transport is
     * ready, so only one 4 MiB chunk is in memory. [stopped] is polled between chunks.
     */
    fun uploadChunks(indexes: List<Int>, stopped: () -> Boolean, chunkAt: (Int) -> Fafnirx.Chunk) {
        val done = CountDownLatch(1)
        var error: Throwable? = null
        var request: ClientCallStreamObserver<Fafnirx.Chunk>? = null
        val observer = object : ClientResponseObserver<Fafnirx.Chunk, Fafnirx.UploadSummary> {
            override fun beforeStart(requestStream: ClientCallStreamObserver<Fafnirx.Chunk>) {
                request = requestStream
            }

            override fun onNext(value: Fafnirx.UploadSummary) {}
            override fun onError(t: Throwable) {
                error = t
                done.countDown()
            }

            override fun onCompleted() = done.countDown()
        }
        // No fixed limit for a whole file: a few minutes per chunk (a stalled link is caught by the readiness wait below).
        val deadline = UPLOAD_BASE_SECS + UPLOAD_SECS_PER_CHUNK * indexes.size
        async.withDeadlineAfter(deadline, TimeUnit.SECONDS).uploadChunks(observer)
        val out = request!!
        try {
            for (index in indexes) {
                var waited = 0
                while (!out.isReady && done.count > 0) {
                    if (stopped()) throw SyncException(Failure.STOPPED, "stopped")
                    Thread.sleep(5)
                    if (++waited > 60_000 / 5) throw SyncException(Failure.OFFLINE, "upload stalled")
                }
                if (done.count == 0L) break
                if (stopped()) throw SyncException(Failure.STOPPED, "stopped")
                out.onNext(chunkAt(index))
            }
            if (done.count > 0) out.onCompleted()
            done.await()
        } catch (e: Throwable) {
            if (done.count > 0) out.cancel("aborted", e)
            throw e
        }
        error?.let { throw map(it) }
    }

    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: StatusRuntimeException) {
        throw map(e)
    }

    private class Auth(token: String, instance: String) : ClientInterceptor {
        private val authorization = "Bearer $token"
        private val instanceId = instance

        override fun <ReqT, RespT> interceptCall(
            method: MethodDescriptor<ReqT, RespT>,
            options: CallOptions,
            next: Channel,
        ): ClientCall<ReqT, RespT> = object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, options)) {
            override fun start(listener: Listener<RespT>, headers: Metadata) {
                headers.put(AUTHORIZATION, authorization)
                headers.put(INSTANCE, instanceId)
                super.start(listener, headers)
            }
        }
    }

    companion object {
        private val AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
        private val INSTANCE = Metadata.Key.of("x-fafnirx-instance", Metadata.ASCII_STRING_MARSHALLER)
        private const val UNARY_SECS = 30L
        private const val COMMIT_SECS = 300L
        private const val UPLOAD_BASE_SECS = 120L
        private const val UPLOAD_SECS_PER_CHUNK = 180L

        /** The same split the Windows client makes (`SyncError::from(Status)`), plus a separate answer for a bad token. */
        fun map(t: Throwable): SyncException {
            if (t is SyncException) return t
            val status = Status.fromThrowable(t)
            val text = "${status.code}: ${status.description ?: t.message ?: ""}"
            return when (status.code) {
                Status.Code.UNAUTHENTICATED -> SyncException(Failure.AUTH, text)
                Status.Code.PERMISSION_DENIED -> SyncException(Failure.IN_USE, text)
                Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED, Status.Code.CANCELLED, Status.Code.UNKNOWN,
                Status.Code.RESOURCE_EXHAUSTED, Status.Code.INTERNAL -> SyncException(Failure.OFFLINE, text)
                else -> SyncException(Failure.FILE, text)
            }
        }

        fun channel(endpoint: Endpoint): ManagedChannel {
            val builder = OkHttpChannelBuilder.forAddress(endpoint.host, endpoint.port)
            if (endpoint.tls) builder.useTransportSecurity() else builder.usePlaintext()
            return builder.build()
        }
    }
}
