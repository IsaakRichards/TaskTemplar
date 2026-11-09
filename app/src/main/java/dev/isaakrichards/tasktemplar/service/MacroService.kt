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
import android.telephony.TelephonyManager
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
    private var lastTemp = Double.NaN
    private var lastRinger: String? = null
    private var lastGps: Boolean? = null
    private var musicOn: Boolean? = null
    private lateinit var sensors: SensorWatch
    private lateinit var floating: FloatingButtons
    private val screenToggles = ArrayDeque<Long>()
    private var lastConnection: String? = null
    private var lastVpn: Boolean? = null
    private var lastSmsId = -1L
    private var lastSync: Boolean? = null
    private var syncHandle: Any? = null
    private val ssidPresent = HashMap<String, Boolean>()
    private var lastSignal: Boolean? = null
    private var lastCell: String? = null
    private val cellInside = HashMap<String, Boolean>()

    @Suppress("DEPRECATION")
    private val phoneListener = object : android.telephony.PhoneStateListener() {
        @Deprecated("Deprecated in Java")
        override fun onServiceStateChanged(serviceState: android.telephony.ServiceState?) {
            val on = serviceState?.state == android.telephony.ServiceState.STATE_IN_SERVICE
            if (lastSignal != null && on != lastSignal) emit(MacroEvent("signal", mapOf("state" to if (on) "on" else "off")))
            lastSignal = on
            checkCell()
        }

        @Deprecated("Deprecated in Java")
        override fun onCellInfoChanged(cellInfo: MutableList<android.telephony.CellInfo>?) = checkCell()
    }

    /** Fires "Cell tower" triggers when the registered cell changes. */
    private fun checkCell() {
        val users = LauncherBridge.usersOf("cell_tower")
        if (users.isEmpty()) return
        val id = DeviceState.cellId(this) ?: return
        if (id != lastCell) {
            lastCell = id
            MacroStore.log("Cell tower", "Now on cell $id")
        }
        for ((macro, idx, step) in users) {
            val ids = step.p("ids").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val key = "${macro.id}#$idx"
            val inside = id in ids
            val was = cellInside[key]
            cellInside[key] = inside
            if (was != null && was != inside && inside == (step.p("state", "enter") == "enter")) {
                emit(MacroEvent("cell_tower", mapOf("macro" to macro.id, "idx" to idx.toString(), "cell" to id)))
            }
        }
    }

    private val defaultNetCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = checkConnection()
        override fun onLost(network: Network) = checkConnection()
    }

    private fun checkConnection() {
        handler.post {
            val type = DeviceState.connectionType(this)
            val vpn = DeviceState.flag(this, "vpn")
            if (lastConnection != null && type != lastConnection) emit(MacroEvent("connection", mapOf("state" to type)))
            if (lastVpn != null && vpn != lastVpn) emit(MacroEvent("vpn", mapOf("state" to if (vpn) "on" else "off")))
            lastConnection = type
            lastVpn = vpn
        }
    }

    private val smsObserver = object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            runCatching {
                contentResolver.query(
                    android.provider.Telephony.Sms.Sent.CONTENT_URI,
                    arrayOf(android.provider.Telephony.Sms._ID, android.provider.Telephony.Sms.ADDRESS, android.provider.Telephony.Sms.BODY),
                    null, null, "${android.provider.Telephony.Sms._ID} DESC",
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val id = c.getLong(0)
                        if (lastSmsId >= 0 && id > lastSmsId) {
                            emit(MacroEvent("sms_sent", mapOf("number" to (c.getString(1) ?: ""), "text" to (c.getString(2) ?: ""))))
                        }
                        lastSmsId = id
                    }
                }
            }
        }
    }

    private val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        val clip = runCatching {
            getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull() ?: ""
        emit(MacroEvent("clipboard", mapOf("clip" to clip)))
    }

    /** Fires "Wi-Fi network in range" triggers from scan results. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun onScanResults() {
        val users = LauncherBridge.usersOf("wifi_in_range")
        if (users.isEmpty()) return
        val seen = runCatching {
            getSystemService(android.net.wifi.WifiManager::class.java).scanResults.map {
                @Suppress("DEPRECATION")
                val name = it.SSID
                name.trim('"').lowercase()
            }.toSet()
        }.getOrNull() ?: return
        for ((macro, idx, step) in users) {
            val key = "${macro.id}#$idx"
            val present = step.p("ssid").trim().lowercase() in seen
            val was = ssidPresent[key]
            ssidPresent[key] = present
            if (was != null && was != present && present == (step.p("state", "appears") == "appears")) {
                emit(MacroEvent("wifi_in_range", mapOf("macro" to macro.id, "idx" to idx.toString())))
            }
        }
    }
    private lateinit var webhooks: WebhookServer
    private var intentActions: Set<String> = emptySet()

    private val intentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val data = mutableMapOf("action" to (intent.action ?: ""))
            intent.extras?.keySet()?.forEach { k ->
                @Suppress("DEPRECATION")
                val v = intent.extras?.get(k)
                if (v != null) data[k] = v.toString()
            }
            emit(MacroEvent("intent", data))
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) {
            val now = getSystemService(AudioManager::class.java).isMusicActive
            val prev = musicOn
            musicOn = now
            if (prev != null && prev != now) emit(MacroEvent("music", mapOf("state" to if (now) "playing" else "stopped")))
        }
    }
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
                    val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                    if (temp != Int.MIN_VALUE) {
                        val c = temp / 10.0
                        if (!lastTemp.isNaN() && c != lastTemp) {
                            emit(MacroEvent("battery_temp", mapOf("temp" to c.toString(), "prev" to lastTemp.toString())))
                        }
                        lastTemp = c
                    }
                }
                Intent.ACTION_POWER_CONNECTED -> emit(MacroEvent("power", mapOf("state" to "connected")))
                Intent.ACTION_POWER_DISCONNECTED -> emit(MacroEvent("power", mapOf("state" to "disconnected")))
                Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF -> {
                    val on = intent.action == Intent.ACTION_SCREEN_ON
                    emit(MacroEvent("screen", mapOf("state" to if (on) "on" else "off")))
                    countPowerPresses()
                }
                Intent.ACTION_DREAMING_STARTED -> emit(MacroEvent("daydream", mapOf("state" to "started")))
                Intent.ACTION_DREAMING_STOPPED -> emit(MacroEvent("daydream", mapOf("state" to "stopped")))
                "android.net.wifi.WIFI_AP_STATE_CHANGED" -> {
                    when (intent.getIntExtra("wifi_state", -1)) {
                        13 -> emit(MacroEvent("hotspot", mapOf("state" to "on")))
                        11 -> emit(MacroEvent("hotspot", mapOf("state" to "off")))
                    }
                }
                android.net.wifi.WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> onScanResults()
                Intent.ACTION_USER_PRESENT -> emit(MacroEvent("screen", mapOf("state" to "unlocked")))
                AudioManager.ACTION_HEADSET_PLUG -> {
                    if (isInitialStickyBroadcast) return
                    val plugged = intent.getIntExtra("state", 0) == 1
                    emit(MacroEvent("headphones", mapOf("state" to if (plugged) "plugged" else "unplugged")))
                }
                AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                    val mode = DeviceState.ringerMode(context)
                    if (lastRinger != null && mode != lastRinger) emit(MacroEvent("silent_mode", mapOf("state" to mode)))
                    lastRinger = mode
                }
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    val on = intent.getBooleanExtra("state", DeviceState.flag(context, "airplane"))
                    emit(MacroEvent("airplane_changed", mapOf("state" to if (on) "on" else "off")))
                }
                android.location.LocationManager.PROVIDERS_CHANGED_ACTION -> {
                    val on = DeviceState.flag(context, "gps")
                    if (lastGps != null && on != lastGps) emit(MacroEvent("gps_changed", mapOf("state" to if (on) "on" else "off")))
                    lastGps = on
                }
                Intent.ACTION_DOCK_EVENT -> {
                    if (isInitialStickyBroadcast) return
                    val docked = intent.getIntExtra(Intent.EXTRA_DOCK_STATE, Intent.EXTRA_DOCK_STATE_UNDOCKED) != Intent.EXTRA_DOCK_STATE_UNDOCKED
                    emit(MacroEvent("dock", mapOf("state" to if (docked) "docked" else "undocked")))
                }
                android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED -> emit(MacroEvent("usb_device", mapOf("state" to "attached")))
                android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED -> emit(MacroEvent("usb_device", mapOf("state" to "detached")))
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val state = if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) "connected" else "disconnected"
                    emit(MacroEvent("bluetooth", mapOf("state" to state, "device" to deviceName(intent))))
                }
            }
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            val state = when (intent.action) {
                Intent.ACTION_PACKAGE_ADDED -> if (replacing) return else "installed"
                Intent.ACTION_PACKAGE_REPLACED -> "updated"
                Intent.ACTION_PACKAGE_FULLY_REMOVED -> "removed"
                else -> return
            }
            emit(MacroEvent("package", mapOf("state" to state, "app" to pkg)))
        }
    }

    private var callState = TelephonyManager.EXTRA_STATE_IDLE
    private var callAnswered = false
    private var callNumber = ""

    private val phoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            @Suppress("DEPRECATION")
            val incoming = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            if (!incoming.isNullOrBlank()) callNumber = incoming
            if (state == callState) return // Android often sends each change twice
            val prev = callState
            callState = state
            fun call(kind: String) = emit(MacroEvent("call", mapOf("state" to kind, "number" to callNumber)))
            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    callAnswered = false
                    call("incoming")
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    callAnswered = true
                    call(if (prev == TelephonyManager.EXTRA_STATE_RINGING) "answered" else "outgoing")
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    if (prev == TelephonyManager.EXTRA_STATE_RINGING && !callAnswered) call("missed") else call("ended")
                    callAnswered = false
                    callNumber = ""
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
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(android.location.LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(Intent.ACTION_DOCK_EVENT)
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(Intent.ACTION_DREAMING_STARTED)
            addAction(Intent.ACTION_DREAMING_STOPPED)
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            addAction(android.net.wifi.WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        }
        lastRinger = DeviceState.ringerMode(this)
        lastGps = DeviceState.flag(this, "gps")
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        val pkgFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, pkgFilter, ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(
            this, phoneReceiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )

        runCatching {
            val cm = getSystemService(ConnectivityManager::class.java)
            val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            netRegisteredAt = SystemClock.elapsedRealtime()
            cm.registerNetworkCallback(req, netCallback)
        }

        runCatching {
            val am = getSystemService(AudioManager::class.java)
            musicOn = am.isMusicActive
            am.registerAudioPlaybackCallback(playbackCallback, handler)
        }
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(defaultNetCallback) }
        lastConnection = DeviceState.connectionType(this)
        lastVpn = DeviceState.flag(this, "vpn")
        runCatching {
            smsObserver.onChange(false) // remember the newest sent SMS
            contentResolver.registerContentObserver(android.provider.Telephony.Sms.CONTENT_URI, true, smsObserver)
        }
        runCatching { getSystemService(android.content.ClipboardManager::class.java).addPrimaryClipChangedListener(clipListener) }
        lastSync = DeviceState.flag(this, "autosync")
        syncHandle = runCatching {
            android.content.ContentResolver.addStatusChangeListener(android.content.ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS) {
                handler.post {
                    val now = DeviceState.flag(this, "autosync")
                    if (lastSync != null && now != lastSync) emit(MacroEvent("autosync", mapOf("state" to if (now) "on" else "off")))
                    lastSync = now
                }
            }
        }.getOrNull()
        runCatching {
            @Suppress("DEPRECATION")
            getSystemService(TelephonyManager::class.java).listen(
                phoneListener,
                android.telephony.PhoneStateListener.LISTEN_SERVICE_STATE or android.telephony.PhoneStateListener.LISTEN_CELL_INFO,
            )
        }
        sensors = SensorWatch(this) { emit(it) }
        floating = FloatingButtons(this)
        webhooks = WebhookServer(this)
        refreshSensors()

        AlarmScheduler.rescheduleAll(this)
    }

    /** Turns sensors on/off depending on which triggers enabled macros use. */
    private fun refreshSensors() {
        val needs = MacroStore.macros.value.filter { it.enabled }.flatMap { m ->
            m.triggers.map { it.type } + m.constraints.flatMap { c -> listOf(c.type) + c.conditions.map { it.type } } +
                m.actions.flatMap { a -> a.conditions.map { it.type } }
        }.toSet()
        sensors.update(needs)
        floating.update()
        floating.updateEdges()
        webhooks.update()
        startInForeground() // re-posts the notification with current buttons

        val actions = LauncherBridge.usersOf("intent_received").map { it.third.p("action").trim() }.filter { it.isNotEmpty() }.toSet()
        if (actions != intentActions) {
            if (intentActions.isNotEmpty()) runCatching { unregisterReceiver(intentReceiver) }
            intentActions = actions
            if (actions.isNotEmpty()) {
                val f = IntentFilter().apply { actions.forEach { addAction(it) } }
                ContextCompat.registerReceiver(this, intentReceiver, f, ContextCompat.RECEIVER_EXPORTED)
            }
        }
        MacroWidget.refreshAll(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH && ::sensors.isInitialized) refreshSensors()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        runCatching { unregisterReceiver(packageReceiver) }
        runCatching { unregisterReceiver(phoneReceiver) }
        runCatching { getSystemService(AudioManager::class.java).unregisterAudioPlaybackCallback(playbackCallback) }
        if (::sensors.isInitialized) sensors.stop()
        if (::floating.isInitialized) floating.removeAll()
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(defaultNetCallback) }
        runCatching { contentResolver.unregisterContentObserver(smsObserver) }
        runCatching {
            @Suppress("DEPRECATION")
            getSystemService(TelephonyManager::class.java).listen(phoneListener, android.telephony.PhoneStateListener.LISTEN_NONE)
        }
        runCatching { getSystemService(android.content.ClipboardManager::class.java).removePrimaryClipChangedListener(clipListener) }
        syncHandle?.let { runCatching { android.content.ContentResolver.removeStatusChangeListener(it) } }
        if (::webhooks.isInitialized) webhooks.stop()
        if (intentActions.isNotEmpty()) runCatching { unregisterReceiver(intentReceiver) }
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback) }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun emit(event: MacroEvent) = MacroEngine.dispatch(this, event)

    /** Counts quick screen on/off toggles for the "Power button presses" trigger. */
    private fun countPowerPresses() {
        val users = LauncherBridge.usersOf("power_button")
        if (users.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val window = users.maxOf { (it.third.p("window", "3").toLongOrNull() ?: 3L) } * 1000
        screenToggles.addLast(now)
        while (screenToggles.isNotEmpty() && now - screenToggles.first() > window) screenToggles.removeFirst()
        val count = screenToggles.size
        if (users.any { count == (it.third.p("presses", "3").toIntOrNull() ?: 3) }) {
            emit(MacroEvent("power_button", mapOf("count" to count.toString())))
            if (count >= users.maxOf { it.third.p("presses", "3").toIntOrNull() ?: 3 }) screenToggles.clear()
        }
    }

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
        val builder = NotificationCompat.Builder(this, TemplarApp.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("TaskTemplar is watching")
            .setContentText("Your macros are active")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
        LauncherBridge.usersOf("notif_button").take(3).forEach { (macro, idx, step) ->
            builder.addAction(
                R.drawable.ic_stat,
                step.p("label").ifBlank { macro.name },
                ButtonReceiver.pending(this, "notif_button", macro.id, idx),
            )
        }
        val notification = builder.build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        const val ACTION_REFRESH = "dev.isaakrichards.tasktemplar.REFRESH"

        /** Tells a running service that macros changed (sensor needs may differ). */
        fun refresh(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, MacroService::class.java).setAction(ACTION_REFRESH)) }
        }

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
