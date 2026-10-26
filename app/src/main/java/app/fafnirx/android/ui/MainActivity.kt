package app.fafnirx.android.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.fafnirx.android.data.Db
import app.fafnirx.android.data.Settings
import app.fafnirx.android.sync.Live
import app.fafnirx.android.sync.Runner
import app.fafnirx.android.sync.Saf
import app.fafnirx.android.sync.SyncJobService
import app.fafnirx.android.sync.SyncService

/** One activity, three screens: Status, Folders, Server. */
class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var db: Db
    private lateinit var content: FrameLayout
    private lateinit var status: StatusView
    private lateinit var folders: FoldersView
    private lateinit var server: ServerView
    private var shown: View? = null
    private val refresh: () -> Unit = { refreshAll() }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = runOnUiThread { refreshAll() }
        override fun onLost(network: Network) = runOnUiThread { refreshAll() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        db = Db.get(this)
        title = "fafnirx"

        status = StatusView(this, settings, db, ::syncNow, ::stopSync)
        folders = FoldersView(this, db, ::pickFolder, ::refreshAll)
        server = ServerView(this, settings, ::refreshAll)

        content = FrameLayout(this)
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun tab(label: String, view: View) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            setOnClickListener { show(view) }
            tabs.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        tab("Status", status)
        tab("Folders", folders)
        tab("Server", server)

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                fitsSystemWindows = true
            },
        )
        if (!handleLink(intent)) show(if (settings.configured) status else server)
        SyncJobService.schedule(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    override fun onStart() {
        super.onStart()
        Live.listen(refresh)
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        refreshAll()
        status.probe()
    }

    override fun onStop() {
        Live.unlisten(refresh)
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        super.onStop()
    }

    private fun show(view: View) {
        if (shown === view) return
        content.removeAllViews()
        content.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        shown = view
        refreshAll()
    }

    private fun refreshAll() {
        status.refresh()
        folders.refresh()
        server.refreshHints()
    }

    /** fafnirx://enroll?server=...&token=... fills the Server screen. */
    private fun handleLink(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme != "fafnirx" || uri.host != "enroll") return false
        server.fill(uri.getQueryParameter("server") ?: "", uri.getQueryParameter("token") ?: "")
        show(server)
        return true
    }

    private fun pickFolder() {
        // Read access only: the picker is asked for no write grant, and the app never holds one.
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        startActivityForResult(intent, REQUEST_FOLDER)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_FOLDER || resultCode != RESULT_OK || uri == null) return
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        db.addFolder(uri.toString(), Saf.folderName(this, uri))
        refreshAll()
        status.probe()
    }

    private fun syncNow() {
        if (!settings.configured) {
            show(server)
            return
        }
        if (!settings.syncEnabled) {
            AlertDialog.Builder(this)
                .setMessage("Sync is turned off. Turn it on in the Server settings first.")
                .setPositiveButton("Open settings") { _, _ -> show(server) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        val blocked = (settings.wifiOnly && !app.fafnirx.android.net.NetworkState.unmetered(this)) ||
            (settings.chargingOnly && !app.fafnirx.android.net.NetworkState.charging(this))
        if (blocked) {
            AlertDialog.Builder(this)
                .setMessage("Your options say to wait for Wi-Fi or the charger. Sync now anyway (this may use mobile data)?")
                .setPositiveButton("Sync now") { _, _ -> startSync(true) }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            startSync(false)
        }
    }

    private fun startSync(force: Boolean) {
        startForegroundService(Intent(this, SyncService::class.java).putExtra(SyncService.EXTRA_FORCE, force))
    }

    private fun stopSync() {
        Runner.stop = true
    }

    private companion object {
        const val REQUEST_FOLDER = 1
        const val REQUEST_NOTIFICATIONS = 2
    }
}

internal fun Activity.dp(n: Int) = (n * resources.displayMetrics.density).toInt()
