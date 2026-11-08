package dev.isaakrichards.tasktemplar.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager

/** Small helpers for reading the phone's current state. */
object DeviceState {

    fun batteryLevel(ctx: Context): Int =
        ctx.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

    fun isCharging(ctx: Context): Boolean =
        ctx.getSystemService(BatteryManager::class.java)?.isCharging ?: false

    fun isScreenOn(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isInteractive ?: true

    fun isOnWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * The current Wi-Fi network name, or "" if unknown. Android only reveals it when the app
     * has location permission (and "Allow all the time" for background use) and location is on.
     */
    @Suppress("DEPRECATION")
    fun currentSsid(ctx: Context): String {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return ""
        val raw = runCatching { wm.connectionInfo?.ssid }.getOrNull() ?: return ""
        val s = raw.trim().removeSurrounding("\"")
        return if (s == "<unknown ssid>" || s == "0x") "" else s
    }
}
