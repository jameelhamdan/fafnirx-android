package app.fafnirx.android.sync

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** What a running sync is doing right now, for the Status screen and the notification. */
object Live {
    @Volatile var syncing = false
    @Volatile var phase = ""
    @Volatile var current = ""
    @Volatile var filesDone = 0
    @Volatile var filesTotal = 0
    @Volatile var bytesDone = 0L
    @Volatile var bytesTotal = 0L
    @Volatile var speed = 0L
    @Volatile var fileBytes = 0L
    @Volatile var fileSize = 0L

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    @Synchronized fun addBytes(n: Long) {
        bytesDone += n
    }

    @Synchronized fun fileDone() {
        filesDone++
    }

    fun listen(l: () -> Unit) = listeners.add(l)
    fun unlisten(l: () -> Unit) = listeners.remove(l)

    private val scheduled = AtomicBoolean(false)

    /** Tells the screens to refresh; many calls in a burst become one refresh a quarter of a second later. */
    fun changed() {
        if (!scheduled.compareAndSet(false, true)) return
        main.postDelayed({
            scheduled.set(false)
            listeners.forEach { it() }
        }, 250)
    }

    fun reset() {
        phase = ""; current = ""; filesDone = 0; filesTotal = 0; bytesDone = 0; bytesTotal = 0; speed = 0; fileBytes = 0; fileSize = 0
    }
}

/** One sync at a time, whoever starts it (the periodic job, or Sync now). */
object Runner {
    private val running = AtomicBoolean(false)
    @Volatile var stop = false

    /** Runs [work] unless a sync is already going. Returns false when it did not run. */
    fun runOnce(work: () -> Unit): Boolean {
        if (!running.compareAndSet(false, true)) return false
        stop = false
        try {
            work()
        } finally {
            running.set(false)
        }
        return true
    }

    val isRunning get() = running.get()
}
