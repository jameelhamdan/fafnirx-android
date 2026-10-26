package app.fafnirx.android.sync

import app.fafnirx.android.net.Collector
import app.fafnirx.android.net.Failure
import app.fafnirx.android.net.SyncException
import com.google.protobuf.ByteString
import fafnirx.v1.Fafnirx
import java.io.Closeable
import java.io.InputStream

/** A file the app may only read. */
interface FileSource {
    fun stream(): InputStream
    fun random(): RandomRead

    /** (size, modified in ms) as the file is now, or null when it cannot be read. */
    fun stat(): Pair<Long, Long>?
}

interface RandomRead : Closeable {
    /** Fills [buf] with [len] bytes from [offset]; returns how many were read (fewer only at the end of the file). */
    fun read(offset: Long, buf: ByteArray, len: Int): Int
}

/** Hashing and sending one file, the way `fafnirx` does it: Negotiate, then only the missing chunks, then Commit. */
class Transfer(private val api: Collector, private val buf: ByteArray = ByteArray(CHUNK_SIZE)) {
    private val blake = Blake3()

    fun hash(src: FileSource, onBytes: (Int) -> Unit = {}): FileHash = src.stream().use { Hashing.hashStream(it, buf, onBytes) }

    /**
     * Returns the bytes sent. Throws [SyncException] (FILE when the file changed or cannot be read).
     * [size] and [mtimeMs] are what the file had when it was hashed.
     */
    fun send(
        remotePath: String,
        src: FileSource,
        hash: FileHash,
        mtimeMs: Long,
        stopped: () -> Boolean,
        onBytes: (Int) -> Unit,
    ): Long {
        val manifest = Fafnirx.Manifest.newBuilder()
            .setPath(remotePath)
            .setSize(hash.size)
            .setRootHash(ByteString.copyFrom(hash.root))
            .also { b -> for (i in 0 until hash.chunkCount) b.addChunkHashes(ByteString.copyFrom(hash.chunkHash(i))) }
            .build()
        val neg = api.negotiate(manifest)
        // The master holds this version already: nothing to send, nothing to commit.
        if (neg.alreadyCurrent) return 0
        var sent = 0L
        if (neg.missingCount > 0) {
            src.random().use { file ->
                api.uploadChunks(neg.missingList, stopped) { index ->
                    val len = Hashing.chunkLength(hash.size, index)
                    val got = try {
                        file.read(index.toLong() * CHUNK_SIZE, buf, len)
                    } catch (e: java.io.IOException) {
                        throw SyncException(Failure.FILE, "cannot read the file: ${e.message}")
                    }
                    if (got != len) throw SyncException(Failure.FILE, "file changed during upload")
                    val digest = blake.hash(buf, len)
                    if (!digest.contentEquals(hash.chunkHash(index))) throw SyncException(Failure.FILE, "file changed during upload")
                    sent += len
                    onBytes(len)
                    Fafnirx.Chunk.newBuilder()
                        .setHash(ByteString.copyFrom(digest))
                        .setData(ByteString.copyFrom(buf, 0, len))
                        .setRootHash(ByteString.copyFrom(hash.root))
                        .setIndex(index)
                        .build()
                }
            }
        }
        // It must be unchanged since it was hashed.
        val now = src.stat()
        if (now == null || now.first != hash.size || now.second != mtimeMs) throw SyncException(Failure.FILE, "file changed during upload")
        api.commit(
            Fafnirx.CommitRequest.newBuilder()
                .setManifest(manifest)
                .setMtime(mtimeMs * 1_000_000L)
                .setMode(0)
                .build(),
        )
        return sent
    }
}
