package dev.isaakrichards.tasktemplar.service

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.isaakrichards.tasktemplar.TemplarApp
import dev.isaakrichards.tasktemplar.R
import dev.isaakrichards.tasktemplar.data.MacroStore
import dev.isaakrichards.tasktemplar.engine.AlarmScheduler
import dev.isaakrichards.tasktemplar.engine.DeviceState
import dev.isaakrichards.tasktemplar.engine.MacroEngine
import dev.isaakrichards.tasktemplar.model.MacroEvent
import dev.isaakrichards.tasktemplar.ui.MainActivity

/**
 * Always-on foreground service that listens for device events. Android won't deliver most of
 * these broadcasts to manifest receivers any more, so a running service is required —
 * this is the same approach MacroDroid uses.
 */
class MacroService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastBattery = -1
    private var wifiUp: Boolean? = null
    private var netRegisteredAt = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    if (level < 0) return
                    val pct = level * 100 / scale
                    if (lastBattery >= 0 && pct != lastBattery) {
                        emit(MacroEvent("battery", mapOf("level" to pct.toString(), "prev" to lastBattery.toString())))
                    }
                    lastBattery = pct
                }
                Intent.ACTION_POWER_CONNECTED -> emit(MacroEvent("power", mapOf("state" to "connected")))
                Intent.ACTION_POWER_DISCONNECTED -> emit(MacroEvent("power", mapOf("state" to "disconnected")))
                Intent.ACTION_SCREEN_ON -> emit(MacroEvent("screen", mapOf("state" to "on")))
                Intent.ACTION_SCREEN_OFF -> emit(MacroEvent("screen", mapOf("state" to "off")))
                Intent.ACTION_USER_PRESENT -> emit(MacroEvent("screen", mapOf("state" to "unlocked")))
                AudioManager.ACTION_HEADSET_PLUG -> {
                    if (isInitialStickyBroadcast) return
                    val plugged = intent.getIntExtra("state", 0) == 1
                    emit(MacroEvent("headphones", mapOf("state" to if (plugged) "plugged" else "unplugged")))
                }
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val state = if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) "connected" else "disconnected"
                    emit(MacroEvent("bluetooth", mapOf("state" to state, "device" to deviceName(intent))))
                }
            }
        }
    }

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = wifiChanged(true)
        override fun onLost(network: Network) = wifiChanged(false)
    }

    override fun onCreate() {
        super.onCreate()
        MacroStore.init(this)
        startInForeground()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)

        runCatching {
            val cm = getSystemService(ConnectivityManager::class.java)
            val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            netRegisteredAt = SystemClock.elapsedRealtime()
            cm.registerNetworkCallback(req, netCallback)
        }

        AlarmScheduler.rescheduleAll(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback) }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun emit(event: MacroEvent) = MacroEngine.dispatch(this, event)

    private fun wifiChanged(up: Boolean) {
        handler.post {
            val initial = wifiUp == null && SystemClock.elapsedRealtime() - netRegisteredAt < 2000
            val changed = wifiUp != up
            wifiUp = up
            if (initial || !changed) return@post
            // Give Android a moment to report the network name before matching on it.
            handler.postDelayed({
                val ssid = if (up) DeviceState.currentSsid(this) else ""
                emit(MacroEvent("wifi", mapOf("state" to if (up) "connected" else "disconnected", "ssid" to ssid)))
            }, if (up) 1500L else 0L)
        }
    }

    private fun deviceName(intent: Intent): String {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return ""
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        return runCatching { device?.name ?: "" }.getOrDefault("")
    }

    private fun startInForeground() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, TemplarApp.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("TaskTemplar is watching")
            .setContentText("Your macros are active")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val NOTIFICATION_ID = 1

        fun start(ctx: Context) {
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, MacroService::class.java))
            }.onFailure {
                MacroStore.init(ctx)
                MacroStore.log("System", "Couldn't start engine: ${it.message}")
            }
        }
    }
}
