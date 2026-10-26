package app.fafnirx.android

import app.fafnirx.android.data.LastResult
import app.fafnirx.android.sync.State
import app.fafnirx.android.sync.StatusModel
import app.fafnirx.android.sync.StatusModel.Inputs
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusModelTest {
    private fun inputs(
        server: Boolean = true, token: Boolean = true, folders: Boolean = true, online: Boolean = true, wifi: Boolean = true,
        charger: Boolean = true, syncing: Boolean = false, last: Int = LastResult.OK, waiting: Int = 0, failed: Int = 0,
    ) = Inputs(server, token, folders, online, wifi, charger, syncing, last, waiting, failed)

    @Test
    fun theFirstMatchingRowWins() {
        assertEquals(State.NOT_CONFIGURED, StatusModel.evaluate(inputs(server = false)))
        assertEquals(State.NOT_CONFIGURED, StatusModel.evaluate(inputs(folders = false)))
        assertEquals(State.PHONE_OFFLINE, StatusModel.evaluate(inputs(online = false, last = LastResult.SERVER_DOWN)))
        assertEquals(State.SERVER_OFFLINE, StatusModel.evaluate(inputs(last = LastResult.SERVER_DOWN)))
        assertEquals(State.AUTH, StatusModel.evaluate(inputs(last = LastResult.AUTH)))
        assertEquals(State.IN_USE, StatusModel.evaluate(inputs(last = LastResult.IN_USE)))
        assertEquals(State.WAITING_WIFI, StatusModel.evaluate(inputs(wifi = false, waiting = 3)))
        assertEquals(State.WAITING_CHARGER, StatusModel.evaluate(inputs(charger = false, waiting = 3)))
        assertEquals(State.SYNCING, StatusModel.evaluate(inputs(syncing = true, waiting = 3)))
        assertEquals(State.SYNCED_WITH_ERRORS, StatusModel.evaluate(inputs(failed = 2)))
        assertEquals(State.SYNCED, StatusModel.evaluate(inputs()))
    }

    @Test
    fun turningSyncOffWinsOverEverythingExceptSetup() {
        assertEquals(State.SYNC_OFF, StatusModel.evaluate(Inputs(true, true, true, true, true, true, true, LastResult.SERVER_DOWN, 5, 2, enabled = false)))
        assertEquals(State.SYNC_OFF, StatusModel.evaluate(Inputs(true, true, true, false, true, true, false, LastResult.OK, 0, 0, enabled = false)))
        assertEquals(State.NOT_CONFIGURED, StatusModel.evaluate(Inputs(false, true, true, true, true, true, false, LastResult.OK, 0, 0, enabled = false)))
    }

    @Test
    fun nothingWaitingMeansNoWaitingState() {
        assertEquals(State.SYNCED, StatusModel.evaluate(inputs(wifi = false, charger = false, waiting = 0)))
    }
}
