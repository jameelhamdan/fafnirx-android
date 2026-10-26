package app.fafnirx.android.net

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager

/** What the phone is connected to and whether it is charging. */
object NetworkState {
    private fun caps(context: Context): NetworkCapabilities? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork ?: return null)
    }

    /**
     * The phone is on a network. Whether the internet works is not asked: a master on the home network (Wi-Fi without internet,
     * a VPN) is reachable all the same, and if the master cannot be reached that is "server offline".
     */
    fun online(context: Context): Boolean = caps(context)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    /** Wi-Fi or another network that is not metered. */
    fun unmetered(context: Context): Boolean = caps(context)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

    fun charging(context: Context): Boolean {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }
}
