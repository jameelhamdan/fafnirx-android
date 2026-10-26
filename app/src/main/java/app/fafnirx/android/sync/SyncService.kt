package app.fafnirx.android.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.fafnirx.android.R
import app.fafnirx.android.data.Settings
import app.fafnirx.android.ui.MainActivity

/** "Sync now" and big first backups: runs in the foreground with a notification, started from the visible app. */
class SyncService : Service() {
    private val listener: () -> Unit = { update() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Runner.stop = true
            return START_NOT_STICKY
        }
        // Already syncing in the foreground: a second tap changes nothing (and must not remove the first one's notification).
        if (!active.compareAndSet(false, true)) return START_NOT_STICKY
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Sync", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION, notification("Starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        Live.listen(listener)
        val force = intent?.getBooleanExtra(EXTRA_FORCE, false) == true
        Thread {
            try {
                Runner.runOnce { SyncEngine(applicationContext).run(force) }
            } finally {
                Live.unlisten(listener)
                active.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }.start()
        return START_NOT_STICKY
    }

    /** Android 15 ends data-sync services after a few hours; the periodic job carries on from where this stopped. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Runner.stop = true
    }

    private fun update() {
        val text = if (Live.filesTotal > 0) "${Live.filesDone} of ${Live.filesTotal}" else Live.phase.ifEmpty { "Working" }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SyncService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("fafnirx backup")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        const val ACTION_STOP = "app.fafnirx.android.STOP"
        const val EXTRA_FORCE = "force"
        private val active = java.util.concurrent.atomic.AtomicBoolean(false)
        private const val CHANNEL = "sync"
        private const val NOTIFICATION = 1
    }
}

/** The regular run: every 15 minutes (the least Android allows) when the network and battery rules are met. */
class SyncJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            Runner.runOnce { SyncEngine(applicationContext).run(false) }
            jobFinished(params, false)
        }.start()
        return true
    }

    // Android stops a job after about ten minutes; uploads resume at the missing chunks on the next run.
    override fun onStopJob(params: JobParameters): Boolean {
        Runner.stop = true
        return true
    }

    companion object {
        private const val JOB_ID = 1

        fun schedule(context: Context) {
            val settings = Settings(context)
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (!settings.syncEnabled) {
                scheduler.cancel(JOB_ID)
                return
            }
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, SyncJobService::class.java))
                .setPeriodic(15 * 60 * 1000L)
                .setRequiredNetworkType(if (settings.wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
                .setRequiresCharging(settings.chargingOnly)
                .setRequiresBatteryNotLow(true)
                .setPersisted(true)
                .build()
            scheduler.schedule(job)
        }
    }
}
