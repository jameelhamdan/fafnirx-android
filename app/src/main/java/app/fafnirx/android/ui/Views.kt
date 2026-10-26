package app.fafnirx.android.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings as SystemSettings
import android.text.InputType
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.ScrollView
import android.widget.TextView
import app.fafnirx.android.data.Db
import app.fafnirx.android.data.LastResult
import app.fafnirx.android.data.Settings
import app.fafnirx.android.net.Collector
import app.fafnirx.android.net.Endpoint
import app.fafnirx.android.net.NetworkState
import app.fafnirx.android.sync.Live
import app.fafnirx.android.sync.Runner
import app.fafnirx.android.sync.State
import app.fafnirx.android.sync.StatusModel
import app.fafnirx.android.sync.SyncJobService
import fafnirx.v1.Fafnirx
import java.text.NumberFormat

private fun Activity.column(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(16), dp(16), dp(16))
}

private fun Activity.scroll(inner: View): ScrollView = ScrollView(this).apply { addView(inner) }

private fun Activity.text(size: Float = 16f, bold: Boolean = false): TextView = TextView(this).apply {
    textSize = size
    if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
}

private fun number(n: Int): String = NumberFormat.getIntegerInstance().format(n)

/** Status: one state line, the counts, and the sync controls. */
class StatusView(
    private val activity: Activity,
    private val settings: Settings,
    private val db: Db,
    private val onSync: () -> Unit,
    private val onStop: () -> Unit,
) : ScrollView(activity) {
    private val dot = activity.text(28f)
    private val state = activity.text(22f, true)
    private val hint = activity.text(14f)
    private val last = activity.text(14f)
    private val synced = activity.text(18f)
    private val waiting = activity.text(18f)
    private val failed = activity.text(18f)
    private val progress = activity.text(14f)
    private val sync = Button(activity)
    private val stop = Button(activity)

    init {
        val col = activity.column()
        val head = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(dot)
        head.addView(state, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = activity.dp(8) })
        col.addView(head)
        col.addView(hint)
        col.addView(last)
        col.addView(space(24))
        col.addView(synced); col.addView(waiting); col.addView(failed)
        col.addView(space(16))
        col.addView(progress)
        col.addView(space(16))
        sync.text = "Sync now"
        sync.setOnClickListener { onSync() }
        stop.text = "Stop"
        stop.setOnClickListener { onStop() }
        col.addView(sync); col.addView(stop)
        failed.setOnClickListener {
            val items = db.failures()
            AlertDialog.Builder(activity)
                .setTitle("Files that failed")
                .setMessage(if (items.isEmpty()) "None" else items.joinToString("\n\n"))
                .setPositiveButton("Close", null)
                .show()
        }
        addView(col)
    }

    private fun space(dp: Int) = View(activity).apply { layoutParams = LinearLayout.LayoutParams(1, activity.dp(dp)) }

    fun refresh() {
        val counts = db.counts()
        val s = StatusModel.evaluate(
            StatusModel.Inputs(
                hasServer = settings.server.isNotBlank(),
                hasToken = settings.token.isNotBlank(),
                hasFolders = db.folders().isNotEmpty(),
                phoneOnline = NetworkState.online(activity),
                wifiOk = !settings.wifiOnly || NetworkState.unmetered(activity),
                chargerOk = !settings.chargingOnly || NetworkState.charging(activity),
                syncing = Live.syncing,
                lastResult = settings.lastResult,
                waiting = counts.waiting,
                failed = counts.failed,
                enabled = settings.syncEnabled,
            ),
        )
        dot.text = "●"
        dot.setTextColor(s.dot)
        state.text = when {
            s == State.SYNCING && Live.filesTotal > 0 -> "Syncing ${number(Live.filesDone)} of ${number(Live.filesTotal)}"
            s == State.SERVER_OFFLINE && settings.lastContact > 0 -> "Server is not reachable"
            else -> s.label
        }
        hint.text = when (s) {
            State.NOT_CONFIGURED -> StatusModel.setupHint(settings.server.isNotBlank(), settings.token.isNotBlank(), db.folders().isNotEmpty())
            State.SYNC_OFF -> "Turn it on in the Server settings"
            State.AUTH -> "Enter a new token on the Server screen"
            State.IN_USE -> "Press Release on the device page in the web app, or wait 90 seconds"
            State.SERVER_OFFLINE -> if (settings.lastContact > 0) "Last seen ${ago(settings.lastContact)}" else ""
            else -> ""
        }
        last.text = if (settings.lastSyncEnd > 0) "Last sync: ${ago(settings.lastSyncEnd)}" else "Not synced yet"
        synced.text = "Synced  ${number(counts.synced)}"
        waiting.text = "Waiting  ${number(counts.waiting)}"
        failed.text = "Failed  ${number(counts.failed)}" + if (counts.failed > 0) "  (tap for details)" else ""
        progress.text = if (Live.syncing && Live.current.isNotEmpty()) {
            val pct = if (Live.fileSize > 0) " ${(Live.fileBytes * 100 / Live.fileSize).coerceIn(0, 100)}%" else ""
            val speed = if (Live.speed > 0) "  %.1f MB/s".format(Live.speed / 1048576.0) else ""
            "${Live.phase.replaceFirstChar { it.uppercase() }} ${Live.current.substringAfterLast('/')}$pct$speed"
        } else {
            ""
        }
        sync.isEnabled = !Live.syncing && settings.syncEnabled
        stop.visibility = if (Live.syncing) View.VISIBLE else View.GONE
    }

    /** Opening the screen asks the master once, so "server offline" is current and not the result of the last background run. */
    fun probe() {
        val endpoint = Endpoint.parse(settings.server)
        if (!settings.syncEnabled || endpoint == null || settings.token.isEmpty() || Live.syncing || !NetworkState.online(activity)) return
        Thread {
            val channel = Collector.channel(endpoint)
            try {
                Collector(channel, settings.token, settings.instanceId).identify()
                settings.lastResult = LastResult.OK
                settings.lastContact = System.currentTimeMillis()
            } catch (e: app.fafnirx.android.net.SyncException) {
                settings.lastResult = when (e.failure) {
                    app.fafnirx.android.net.Failure.AUTH -> LastResult.AUTH
                    app.fafnirx.android.net.Failure.IN_USE -> LastResult.IN_USE
                    else -> LastResult.SERVER_DOWN
                }
            } finally {
                channel.shutdownNow()
            }
            activity.runOnUiThread { refresh() }
        }.start()
    }

    private fun ago(ms: Long) = DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}

/** Folders: the folders being backed up, with how many of their files are synced. */
class FoldersView(
    private val activity: Activity,
    private val db: Db,
    private val onAdd: () -> Unit,
    private val onChanged: () -> Unit,
) : ScrollView(activity) {
    private val list = activity.column()

    init {
        addView(list)
    }

    fun refresh() {
        list.removeAllViews()
        val add = Button(activity).apply {
            text = "+ Add folder"
            setOnClickListener { onAdd() }
        }
        list.addView(add)
        for (folder in db.folders()) {
            val c = db.counts(folder.id)
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, activity.dp(8), 0, activity.dp(8))
            }
            val label = activity.text(16f).apply { text = "${folder.name}\n${number(c.synced)} of ${number(c.total)} synced" }
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(
                Button(activity).apply {
                    text = "Remove"
                    setOnClickListener {
                        AlertDialog.Builder(activity)
                            .setMessage("Stop backing up \"${folder.name}\"? Files already on the server stay there.")
                            .setPositiveButton("Remove") { _, _ ->
                                // The read grant goes with the folder.
                                try {
                                    activity.contentResolver.releasePersistableUriPermission(Uri.parse(folder.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                } catch (e: SecurityException) {
                                }
                                db.removeFolder(folder.id)
                                onChanged()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                },
            )
            list.addView(row)
        }
        if (db.folders().isEmpty()) {
            list.addView(activity.text(14f).apply { text = "\nNo folders yet. Pick the folders to back up, for example DCIM/Camera." })
        }
        list.addView(
            activity.text(13f).apply {
                text = "\nRead only: fafnirx can only read these folders. It cannot change, create or delete anything on your phone.\n\n" +
                    "Android does not let apps pick the storage root or the Download folder; pick a folder inside them."
            },
        )
    }
}

/** Server: address and token, and the options. */
class ServerView(
    private val activity: Activity,
    private val settings: Settings,
    private val onSaved: () -> Unit,
) : ScrollView(activity) {
    private val address = EditText(activity).apply {
        hint = "Server address, e.g. 192.168.1.10:7443"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setSingleLine()
    }
    private val token = EditText(activity).apply {
        hint = "Device token"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setSingleLine()
    }
    private val warning = activity.text(13f)
    private val result = activity.text(14f)
    private val wifi = CheckBox(activity).apply { text = "Wi-Fi only (no mobile data)" }
    private val charging = CheckBox(activity).apply { text = "Only while charging" }
    private val enabled = Switch(activity).apply { text = "Sync files" }
    private val parallel = RadioGroup(activity).apply { orientation = LinearLayout.HORIZONTAL }

    init {
        address.setText(settings.server)
        token.setText(settings.token)
        wifi.isChecked = settings.wifiOnly
        charging.isChecked = settings.chargingOnly
        for (n in 1..3) {
            parallel.addView(RadioButton(activity).apply { id = 100 + n; text = n.toString(); isChecked = n == settings.parallel })
        }
        enabled.isChecked = settings.syncEnabled
        // Takes effect at once, without Save: off stops a running sync and the background runs; on brings them back.
        enabled.setOnCheckedChangeListener { _, on ->
            settings.syncEnabled = on
            if (!on) Runner.stop = true
            SyncJobService.schedule(activity)
            Live.changed()
            onSaved()
        }
        val col = activity.column()
        col.addView(activity.text(18f, true).apply { text = "Sync" })
        col.addView(enabled)
        col.addView(activity.text(14f).apply { text = "When off, nothing is uploaded, in the background or with Sync now." })
        col.addView(activity.text(18f, true).apply { text = "\nServer" })
        col.addView(address)
        col.addView(warning)
        col.addView(token)
        val paste = Button(activity).apply {
            text = "Paste token"
            setOnClickListener {
                val clip = activity.getSystemService(ClipboardManager::class.java).primaryClip
                clip?.getItemAt(0)?.text?.let { token.setText(it.toString().trim()) }
            }
        }
        val show = CheckBox(activity).apply {
            text = "Show token"
            setOnCheckedChangeListener { _, on ->
                token.inputType = InputType.TYPE_CLASS_TEXT or
                    if (on) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }
        col.addView(paste)
        col.addView(show)
        col.addView(wifi)
        col.addView(charging)
        col.addView(activity.text(14f).apply { text = "Files uploaded at the same time" })
        col.addView(parallel)
        col.addView(Button(activity).apply { text = "Test connection"; setOnClickListener { test() } })
        col.addView(Button(activity).apply { text = "Save"; setOnClickListener { save() } })
        col.addView(result)
        col.addView(
            Button(activity).apply {
                text = "Battery settings"
                setOnClickListener { activity.startActivity(Intent(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            },
        )
        addView(col)
        refreshHints()
    }

    fun fill(server: String, tokenText: String) {
        address.setText(server)
        token.setText(tokenText)
        result.text = "Check the details, then press Save."
    }

    fun refreshHints() {
        val endpoint = Endpoint.parse(address.text.toString())
        warning.text = if (endpoint?.looksPublicOverHttp == true) {
            "This looks like a public address over plain http: the token and your files can be read on the way. Use https://."
        } else {
            ""
        }
    }

    private fun save() {
        settings.server = address.text.toString()
        settings.token = token.text.toString()
        settings.wifiOnly = wifi.isChecked
        settings.chargingOnly = charging.isChecked
        settings.parallel = (parallel.checkedRadioButtonId - 100).coerceIn(1, 3)
        settings.lastResult = LastResult.NONE
        SyncJobService.schedule(activity)
        result.text = if (Endpoint.parse(settings.server) == null) "Saved, but the address is not valid." else "Saved."
        refreshHints()
        onSaved()
    }

    private fun test() {
        val endpoint = Endpoint.parse(address.text.toString())
        val tokenText = token.text.toString().trim()
        if (endpoint == null || tokenText.isEmpty()) {
            result.text = "Enter the server address and the token first."
            return
        }
        result.text = "Testing…"
        val instance = settings.instanceId
        Thread {
            val channel = Collector.channel(endpoint)
            val message = try {
                val api = Collector(channel, tokenText, instance)
                api.identify()
                val beat = api.heartbeat(
                    Fafnirx.HeartbeatRequest.newBuilder().setHostname(android.os.Build.MODEL ?: "android").setOs("android").setPhase("idle").build(),
                )
                "Connected as ${beat.deviceName}"
            } catch (e: app.fafnirx.android.net.SyncException) {
                when (e.failure) {
                    app.fafnirx.android.net.Failure.AUTH -> "The server did not accept this token."
                    app.fafnirx.android.net.Failure.IN_USE -> "Another install is using this device. Press Release in the web app."
                    else -> "Cannot reach the server (${e.message})."
                }
            } finally {
                channel.shutdownNow()
            }
            activity.runOnUiThread { result.text = message }
        }.start()
    }
}
