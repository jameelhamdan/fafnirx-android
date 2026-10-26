package app.fafnirx.android

import app.fafnirx.android.net.Failure
import app.fafnirx.android.net.SyncException
import app.fafnirx.android.sync.Pipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PipelineTest {
    private fun queueOf(n: Int): () -> Int? {
        val next = AtomicInteger()
        return { next.getAndIncrement().takeIf { it < n } }
    }

    @Test
    fun everyJobIsProcessedExactlyOnce() {
        val seen = ConcurrentHashMap<Int, Int>()
        Pipeline<Int>(3, { false }).run(queueOf(200)) { seen.merge(it, 1, Int::plus) }
        assertEquals(200, seen.size)
        assertTrue(seen.values.all { it == 1 })
    }

    @Test
    fun theNextFileIsPreparedWhileTheCurrentOneIsUploading() {
        // The first upload cannot finish until the second job has been prepared: with hashing and uploading in series this
        // would never happen.
        val secondPrepared = CountDownLatch(1)
        val prepared = AtomicInteger()
        val finishedFirstWhileSecondWasReady = AtomicBoolean(false)
        val next = AtomicInteger()
        Pipeline<Int>(1, { false }).run(
            prepare = {
                val i = next.getAndIncrement()
                if (i >= 2) null else i.also { if (prepared.incrementAndGet() == 2) secondPrepared.countDown() }
            },
            process = { job ->
                if (job == 0) finishedFirstWhileSecondWasReady.set(secondPrepared.await(5, TimeUnit.SECONDS))
            },
        )
        assertTrue(finishedFirstWhileSecondWasReady.get())
    }

    @Test
    fun severalUploadsRunAtTheSameTime() {
        val together = CountDownLatch(3)
        val allMet = AtomicBoolean(false)
        Pipeline<Int>(3, { false }).run(queueOf(3)) {
            together.countDown()
            if (together.await(5, TimeUnit.SECONDS)) allMet.set(true)
        }
        assertTrue("three workers should have been inside process at once", allMet.get())
    }

    @Test
    fun onlyAsManyUploadsRunAsAreAsked() {
        val now = AtomicInteger()
        val most = AtomicInteger()
        Pipeline<Int>(2, { false }).run(queueOf(30)) {
            most.accumulateAndGet(now.incrementAndGet(), ::maxOf)
            Thread.sleep(5)
            now.decrementAndGet()
        }
        assertEquals(2, most.get())
    }

    @Test
    fun aFailureStopsEveryoneAndIsRethrown() {
        val processed = CopyOnWriteArrayList<Int>()
        try {
            Pipeline<Int>(3, { false }).run(queueOf(1000)) {
                if (it == 5) throw SyncException(Failure.AUTH, "token")
                processed += it
                Thread.sleep(2)
            }
            throw AssertionError("expected the failure")
        } catch (e: SyncException) {
            assertEquals(Failure.AUTH, e.failure)
        }
        assertTrue("should not go on to the end", processed.size < 900)
    }

    @Test
    fun aFailureWhilePreparingStopsTheRun() {
        val next = AtomicInteger()
        try {
            Pipeline<Int>(2, { false }).run(
                prepare = { if (next.incrementAndGet() > 3) throw SyncException(Failure.OFFLINE, "down") else next.get() },
                process = { Thread.sleep(1) },
            )
            throw AssertionError("expected the failure")
        } catch (e: SyncException) {
            assertEquals(Failure.OFFLINE, e.failure)
        }
    }

    @Test
    fun stopEndsTheRunWithoutAnError() {
        val stop = AtomicBoolean(false)
        val done = AtomicInteger()
        Pipeline<Int>(2, { stop.get() }).run(queueOf(10_000)) {
            if (done.incrementAndGet() == 10) stop.set(true)
            Thread.sleep(1)
        }
        assertTrue(done.get() < 10_000)
    }

    @Test
    fun nothingToDoReturnsAtOnce() {
        Pipeline<Int>(3, { false }).run({ null }) { throw AssertionError("no job expected") }
    }
}
