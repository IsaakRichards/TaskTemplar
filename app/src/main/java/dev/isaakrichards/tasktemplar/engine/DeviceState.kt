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

    /** On/off state of a named device feature, used by the "Device state" constraints. */
    @Suppress("DEPRECATION")
    fun flag(ctx: Context, key: String): Boolean = runCatching {
        when (key) {
            "headphones" -> ctx.getSystemService(android.media.AudioManager::class.java)
                .getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
                    it.type in setOf(
                        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
                    )
                }
            "bluetooth" -> ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled == true
            "wifi" -> ctx.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
            "airplane" -> android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            "auto_rotate" -> android.provider.Settings.System.getInt(ctx.contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 0) == 1
            "locked" -> ctx.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
            "landscape" -> ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            "music" -> ctx.getSystemService(android.media.AudioManager::class.java).isMusicActive
            "gps" -> {
                val lm = ctx.getSystemService(android.location.LocationManager::class.java)
                if (android.os.Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
                else lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
            }
            "mobile_data" -> ctx.getSystemService(android.telephony.TelephonyManager::class.java).isDataEnabled
            "roaming" -> ctx.getSystemService(android.telephony.TelephonyManager::class.java).isNetworkRoaming
            "dnd" -> ctx.getSystemService(android.app.NotificationManager::class.java).currentInterruptionFilter !=
                android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            "power_save" -> ctx.getSystemService(PowerManager::class.java).isPowerSaveMode
            "docked" -> ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_DOCK_EVENT))
                ?.getIntExtra(android.content.Intent.EXTRA_DOCK_STATE, 0).let { it != null && it != android.content.Intent.EXTRA_DOCK_STATE_UNDOCKED }
            else -> false
        }
    }.getOrDefault(false)

    fun ringerMode(ctx: Context): String = when (ctx.getSystemService(android.media.AudioManager::class.java)?.ringerMode) {
        android.media.AudioManager.RINGER_MODE_SILENT -> "silent"
        android.media.AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        else -> "normal"
    }

    /** Battery temperature in °C (from the sticky battery broadcast). */
    fun batteryTemp(ctx: Context): Double? =
        ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 }

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
