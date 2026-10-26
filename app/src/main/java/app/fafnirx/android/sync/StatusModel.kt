package app.fafnirx.android.sync

import app.fafnirx.android.data.LastResult

enum class State(val dot: Int, val label: String) {
    NOT_CONFIGURED(0xFF9E9E9E.toInt(), "Not set up"),
    SYNC_OFF(0xFF9E9E9E.toInt(), "Sync is off"),
    PHONE_OFFLINE(0xFFD32F2F.toInt(), "Phone is offline"),
    WAITING_WIFI(0xFFF9A825.toInt(), "Waiting for Wi-Fi"),
    WAITING_CHARGER(0xFFF9A825.toInt(), "Waiting for the charger"),
    SERVER_OFFLINE(0xFFD32F2F.toInt(), "Server is not reachable"),
    AUTH(0xFFD32F2F.toInt(), "Token rejected"),
    IN_USE(0xFFF9A825.toInt(), "Device used by another install"),
    SYNCING(0xFF1976D2.toInt(), "Syncing"),
    SYNCED(0xFF388E3C.toInt(), "Synced"),
    SYNCED_WITH_ERRORS(0xFFF9A825.toInt(), "Synced, some files failed"),
}

/** What the Status screen says, decided from facts only (so it can be tested). The first matching row wins. */
object StatusModel {
    class Inputs(
        val hasServer: Boolean,
        val hasToken: Boolean,
        val hasFolders: Boolean,
        val phoneOnline: Boolean,
        val wifiOk: Boolean,
        val chargerOk: Boolean,
        val syncing: Boolean,
        val lastResult: Int,
        val waiting: Int,
        val failed: Int,
        val enabled: Boolean = true,
    )

    fun evaluate(i: Inputs): State = when {
        !i.hasServer || !i.hasToken || !i.hasFolders -> State.NOT_CONFIGURED
        !i.enabled -> State.SYNC_OFF
        i.syncing -> State.SYNCING
        !i.phoneOnline -> State.PHONE_OFFLINE
        i.lastResult == LastResult.AUTH -> State.AUTH
        i.lastResult == LastResult.SERVER_DOWN -> State.SERVER_OFFLINE
        i.lastResult == LastResult.IN_USE -> State.IN_USE
        i.waiting > 0 && !i.wifiOk -> State.WAITING_WIFI
        i.waiting > 0 && !i.chargerOk -> State.WAITING_CHARGER
        i.failed > 0 -> State.SYNCED_WITH_ERRORS
        else -> State.SYNCED
    }

    fun setupHint(hasServer: Boolean, hasToken: Boolean, hasFolders: Boolean): String = when {
        !hasServer || !hasToken -> "Enter the server and token"
        !hasFolders -> "Add a folder to back up"
        else -> ""
    }
}
