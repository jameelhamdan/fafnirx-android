package app.fafnirx.android.sync

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Hashing and uploading side by side: one thread prepares jobs (reads and hashes the next files) and up to [workers] threads
 * process them (upload), with at most [depth] prepared jobs waiting. The first failure that escapes [run]'s callbacks stops
 * everything and is rethrown; [abort] (the user pressed Stop) ends it quietly.
 */
class Pipeline<J : Any>(private val workers: Int, private val abort: () -> Boolean, private val depth: Int = 2) {
    private val failure = AtomicReference<Throwable?>()

    /** True once the run should wind down: callbacks poll this between chunks and blocks. */
    fun stopping(): Boolean = abort() || failure.get() != null

    /**
     * [prepare] runs on one thread and returns the next job, or null when there is nothing more. [process] runs on the workers.
     * Both handle the failures of a single job themselves; anything thrown out of them ends the run.
     */
    fun run(prepare: () -> J?, process: (J) -> Unit) {
        val queue = ArrayBlockingQueue<J>(depth)
        val finished = AtomicBoolean(false)

        val producer = Thread {
            try {
                while (!stopping()) {
                    val job = prepare() ?: break
                    while (!stopping() && !queue.offer(job, POLL_MS, TimeUnit.MILLISECONDS)) {
                        // wait for a worker to take one
                    }
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                finished.set(true)
            }
        }
        val pool = List(workers) {
            Thread {
                try {
                    while (!stopping()) {
                        val job = queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
                        if (job == null) {
                            if (finished.get() && queue.isEmpty()) break
                            continue
                        }
                        process(job)
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
        }
        producer.start()
        pool.forEach { it.start() }
        producer.join()
        pool.forEach { it.join() }
        failure.get()?.let { throw it }
    }

    private companion object {
        const val POLL_MS = 200L
    }
}
