package app.fafnirx.android.sync

import android.content.Context
import android.net.Uri
import android.os.Build
import app.fafnirx.android.data.Db
import app.fafnirx.android.data.LastResult
import app.fafnirx.android.data.Pending
import app.fafnirx.android.data.Settings
import app.fafnirx.android.net.Collector
import app.fafnirx.android.net.Endpoint
import app.fafnirx.android.net.Failure
import app.fafnirx.android.net.NetworkState
import app.fafnirx.android.net.SyncException
import fafnirx.v1.Fafnirx
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One pass: ask the master who it is, look at the folders, then send what is new or changed. One thread reads and hashes the
 * next files while up to [Settings.parallel] others upload the ones already hashed, all over one connection.
 */
class SyncEngine(private val context: Context) {
    private val settings = Settings(context)
    private val db = Db.get(context)
    private val errors: MutableList<String> = Collections.synchronizedList(ArrayList())
    private var lastBeat = 0L
    private var started = 0L
    private val version = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    /** A file that is hashed and ready to send. */
    private class Job(val file: Pending, val tree: String, val remote: String, val hash: FileHash)

    /** [force] ignores the Wi-Fi-only and charging options (the user pressed Sync now and agreed). */
    fun run(force: Boolean) {
        val endpoint = Endpoint.parse(settings.server)
        if (!settings.syncEnabled || endpoint == null || settings.token.isEmpty() || db.folders().isEmpty()) return
        if (!NetworkState.online(context)) return
        if (!force && !allowed()) return

        Live.reset()
        Live.syncing = true
        Live.phase = "scanning"
        Live.changed()
        val channel = Collector.channel(endpoint)
        try {
            cycle(Collector(channel, settings.token, settings.instanceId))
        } catch (e: SyncException) {
            when (e.failure) {
                Failure.AUTH -> settings.lastResult = LastResult.AUTH
                Failure.IN_USE -> settings.lastResult = LastResult.IN_USE
                Failure.STOPPED -> {}
                else -> settings.lastResult = LastResult.SERVER_DOWN
            }
        } catch (e: Exception) {
            // Something unexpected: end this pass; the next one starts from the database.
            errors.add("internal error: ${e.javaClass.simpleName}")
        } finally {
            channel.shutdown()
            try {
                channel.awaitTermination(3, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                channel.shutdownNow()
            }
            Live.syncing = false
            Live.reset()
            settings.lastSyncEnd = System.currentTimeMillis()
            Live.changed()
        }
    }

    /** The user pressed Stop, or turned sync off in the settings while it was running. */
    private fun stopNow() = Runner.stop || !settings.syncEnabled

    private fun allowed() = (!settings.wifiOnly || NetworkState.unmetered(context)) && (!settings.chargingOnly || NetworkState.charging(context))

    private fun cycle(api: Collector) {
        started = System.currentTimeMillis()
        val masterId = api.identify()
        if (masterId != settings.masterId) {
            // Another master (a new database, another server at this address): what we sent before no longer counts.
            if (settings.masterId.isNotEmpty()) db.forgetUploaded()
            settings.masterId = masterId
        }
        settings.lastResult = LastResult.OK
        settings.lastContact = System.currentTimeMillis()
        beat(api)

        val generation = System.currentTimeMillis()
        for (folder in db.folders()) {
            if (stopNow()) throw SyncException(Failure.STOPPED, "stopped")
            if (!Saf.scan(context, db, folder, generation) { stopNow() }) {
                errors.add("${folder.name}: cannot read the folder (was access removed?)")
            }
        }

        Live.filesTotal = db.counts().waiting
        val workers = settings.parallel
        val pipeline = Pipeline<Job>(workers, { stopNow() })
        val claimed = ConcurrentHashMap.newKeySet<String>()
        val uploading = AtomicInteger()
        // Each thread has its own 4 MiB buffer and hash state.
        val transfers = ThreadLocal.withInitial { Transfer(api) }

        Live.phase = "hashing"
        pipeline.run(
            prepare = { prepare(transfers.get()!!, api, claimed, pipeline, uploading) },
            process = { job -> upload(transfers.get()!!, api, job, pipeline, uploading, workers) },
        )
        if (stopNow()) throw SyncException(Failure.STOPPED, "stopped")
        Live.phase = ""
        Live.current = ""
        beat(api)
    }

    /** Picks the next due file and hashes it (or takes the hash kept from an earlier pass). Null when none is left. */
    private fun prepare(transfer: Transfer, api: Collector, claimed: MutableSet<String>, pipeline: Pipeline<Job>, uploading: AtomicInteger): Job? {
        while (!pipeline.stopping()) {
            val now = System.currentTimeMillis()
            val file = db.pending(now, SETTLE_MS, 32).firstOrNull { claimed.add(it.key) } ?: return null
            val folder = db.folders().firstOrNull { it.id == file.folderId }
            if (folder == null) {
                db.failed(file, "folder removed", now, permanent = true)
                continue
            }
            val remote = Paths.clean(folder.prefix + "/" + file.relPath)
            if (remote == null) {
                db.failed(file, "name not accepted by the master", now, permanent = true)
                errors.add("${file.relPath}: name not accepted by the master")
                continue
            }
            try {
                if (uploading.get() == 0) Live.phase = "hashing"
                return Job(file, folder.treeUri, remote, hashOf(transfer, api, file, Saf.Source(context, Saf.document(Uri.parse(folder.treeUri), file.docId)), pipeline))
            } catch (e: SyncException) {
                if (e.failure != Failure.FILE) throw e
                fail(file, remote, e.message ?: "failed")
            } catch (e: java.io.IOException) {
                fail(file, remote, e.message ?: "cannot read")
            } catch (e: SecurityException) {
                fail(file, remote, "no access")
            }
        }
        return null
    }

    private fun hashOf(transfer: Transfer, api: Collector, file: Pending, source: FileSource, pipeline: Pipeline<Job>): FileHash {
        if (file.root != null && file.chunks != null) return FileHash(file.size, file.chunks, file.root)
        val h = transfer.hash(source) {
            if (pipeline.stopping()) throw SyncException(Failure.STOPPED, "stopped")
            maybeBeat(api)
        }
        // Same size and time as the scan saw, or it is still being written: try again later.
        val now = source.stat()
        if (h.size != file.size || now == null || now.second != file.mtime) {
            throw SyncException(Failure.FILE, "file changed while it was read")
        }
        db.hashed(file, h.root, h.chunks, h.size)
        return h
    }

    private fun upload(transfer: Transfer, api: Collector, job: Job, pipeline: Pipeline<Job>, uploading: AtomicInteger, workers: Int) {
        val file = job.file
        uploading.incrementAndGet()
        Live.phase = "uploading"
        Live.current = job.remote
        if (workers == 1) {
            Live.fileSize = file.size
            Live.fileBytes = 0
        }
        try {
            val source = Saf.Source(context, Saf.document(Uri.parse(job.tree), file.docId))
            transfer.send(job.remote, source, job.hash, file.mtime, { pipeline.stopping() }) { n ->
                Live.addBytes(n.toLong())
                if (workers == 1) Live.fileBytes += n
                val secs = maxOf(1L, (System.currentTimeMillis() - started) / 1000)
                Live.speed = Live.bytesDone / secs
                maybeBeat(api)
                Live.changed()
            }
            db.uploaded(file)
            Live.fileDone()
            settings.lastContact = System.currentTimeMillis()
        } catch (e: SyncException) {
            if (e.failure != Failure.FILE) throw e
            fail(file, job.remote, e.message ?: "failed")
        } catch (e: java.io.IOException) {
            fail(file, job.remote, e.message ?: "cannot read")
        } catch (e: SecurityException) {
            fail(file, job.remote, "no access")
        } finally {
            if (uploading.decrementAndGet() == 0) Live.phase = "hashing"
            Live.changed()
        }
    }

    private fun fail(file: Pending, remote: String, message: String) {
        db.failed(file, message, System.currentTimeMillis())
        errors.add("$remote: $message")
    }

    private fun maybeBeat(api: Collector) {
        if (System.currentTimeMillis() - lastBeat >= BEAT_MS) beat(api)
    }

    @Synchronized
    private fun beat(api: Collector) {
        lastBeat = System.currentTimeMillis()
        val drained = synchronized(errors) { errors.takeLast(20).toList() }
        val request = Fafnirx.HeartbeatRequest.newBuilder()
            .setHostname(Build.MODEL ?: "android")
            .setOs("android")
            .setClientVersion(version)
            .setPhase(Live.phase.ifEmpty { "idle" })
            .setCurrentFile(Live.current)
            .setFilesDone(Live.filesDone)
            .setFilesTotal(Live.filesTotal)
            .setBytesDone(Live.bytesDone)
            .setBytesTotal(Live.bytesTotal)
            .setSpeedBps(Live.speed)
            .addAllErrors(drained)
            .build()
        api.heartbeat(request)
        errors.clear()
        settings.lastContact = System.currentTimeMillis()
    }

    companion object {
        /** A file modified more recently than this is still being written. */
        const val SETTLE_MS = 30_000L
        const val BEAT_MS = 60_000L
    }
}
