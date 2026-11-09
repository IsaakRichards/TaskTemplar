package dev.isaakrichards.tasktemplar.engine

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.app.WallpaperManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import dev.isaakrichards.tasktemplar.data.GlobalVars
import dev.isaakrichards.tasktemplar.model.Step
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Device toggles, media, sounds, screen, wallpaper, alarms and shell commands. */
object DeviceActions {

    private var player: MediaPlayer? = null
    private var keepAwake: PowerManager.WakeLock? = null
    @Volatile
    private var rootChecked: Boolean? = null

    /** Runs a shell command (optionally as root). Returns exit code and combined output. */
    suspend fun shell(cmd: String, root: Boolean, timeoutMs: Long = 30_000): Pair<Int, String> = withContext(Dispatchers.IO) {
        val pb = if (root) ProcessBuilder("su", "-c", cmd) else ProcessBuilder("sh", "-c", cmd)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val out = StringBuilder()
        val reader = thread(isDaemon = true) {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(out) { if (out.length < 16_000) out.append(line).append('\n') }
                }
            }
        }
        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) proc.destroy()
        reader.join(1000)
        (if (finished) proc.exitValue() else -1) to synchronized(out) { out.toString().trim() }
    }

    suspend fun hasRoot(): Boolean {
        rootChecked?.let { return it }
        val ok = runCatching { shell("id", root = true, timeoutMs = 8000).let { it.first == 0 && "uid=0" in it.second } }
            .getOrDefault(false)
        rootChecked = ok
        return ok
    }

    @SuppressLint("MissingPermission", "WakelockTimeout")
    @Suppress("DEPRECATION")
    suspend fun execute(ctx: Context, step: Step, vars: MutableMap<String, String>): String? {
        fun t(key: String, def: String = "") = MagicText.apply(step.p(key, def), vars)
        fun want(current: Boolean): Boolean = when (step.p("state", "toggle")) {
            "on" -> true
            "off" -> false
            else -> !current
        }
        fun onOff(b: Boolean) = if (b) "on" else "off"

        return when (step.type) {
            "shell" -> {
                val (code, out) = shell(t("command"), step.p("root", "no") == "yes", (t("timeout", "30").toLongOrNull() ?: 30L) * 1000)
                vars["shell_output"] = out
                vars["shell_exit"] = code.toString()
                step.p("name").takeIf { it.isNotBlank() }?.let { GlobalVars.set(MagicText.apply(it, vars), VarTypes.STRING, out) }
                if (code == 0) "exit 0" else "exit $code: ${out.take(120)}"
            }

            "wifi_set" -> {
                val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
                val on = want(wm.isWifiEnabled)
                when {
                    Build.VERSION.SDK_INT < 29 -> { wm.isWifiEnabled = on; "Wi-Fi ${onOff(on)}" }
                    hasRoot() -> { shell("svc wifi ${if (on) "enable" else "disable"}", true); "Wi-Fi ${onOff(on)} (root)" }
                    else -> { openPanel(ctx, Settings.Panel.ACTION_WIFI); "Android 10+ only lets apps open the Wi-Fi panel without root" }
                }
            }

            "bluetooth_set" -> {
                val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: error("No Bluetooth on this device")
                val on = want(adapter.isEnabled)
                when {
                    Build.VERSION.SDK_INT < 33 -> {
                        if (on) adapter.enable() else adapter.disable()
                        "Bluetooth ${onOff(on)}"
                    }
                    hasRoot() -> { shell("svc bluetooth ${if (on) "enable" else "disable"}", true); "Bluetooth ${onOff(on)} (root)" }
                    else -> { openSettings(ctx, Settings.ACTION_BLUETOOTH_SETTINGS); "Android 13+ only lets apps open Bluetooth settings without root" }
                }
            }

            "mobile_data_set" -> {
                val on = want(DeviceState.flag(ctx, "mobile_data"))
                if (!hasRoot()) {
                    openSettings(ctx, Settings.ACTION_DATA_ROAMING_SETTINGS)
                    "Mobile data needs root; opened settings instead"
                } else {
                    shell("svc data ${if (on) "enable" else "disable"}", true)
                    "Mobile data ${onOff(on)} (root)"
                }
            }

            "airplane_set" -> {
                val on = want(DeviceState.flag(ctx, "airplane"))
                if (!hasRoot()) {
                    openSettings(ctx, Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                    "Airplane mode needs root; opened settings instead"
                } else {
                    val cmd = if (Build.VERSION.SDK_INT >= 30) "cmd connectivity airplane-mode ${if (on) "enable" else "disable"}"
                    else "settings put global airplane_mode_on ${if (on) 1 else 0}; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $on"
                    shell(cmd, true)
                    "Airplane mode ${onOff(on)} (root)"
                }
            }

            "auto_rotate" -> {
                if (!Settings.System.canWrite(ctx)) error("Needs 'Modify system settings' permission")
                val on = want(DeviceState.flag(ctx, "auto_rotate"))
                Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
                "auto-rotate ${onOff(on)}"
            }

            "screen_timeout" -> {
                if (!Settings.System.canWrite(ctx)) error("Needs 'Modify system settings' permission")
                val secs = (t("seconds", "60").toIntOrNull() ?: 60).coerceIn(5, 86_400)
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, secs * 1000)
                "${secs}s"
            }

            "keep_awake" -> {
                val pm = ctx.getSystemService(PowerManager::class.java)
                val on = want(keepAwake?.isHeld == true)
                keepAwake?.let { if (it.isHeld) it.release() }
                keepAwake = null
                if (on) {
                    keepAwake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "TaskTemplar:awake").also {
                        val mins = t("minutes", "0").toLongOrNull() ?: 0L
                        if (mins > 0) it.acquire(mins * 60_000) else it.acquire()
                    }
                }
                "keep awake ${onOff(on)}"
            }

            "screen_on" -> {
                val pm = ctx.getSystemService(PowerManager::class.java)
                pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "TaskTemplar:wake",
                ).acquire(3000)
                null
            }

            "media_control" -> {
                val am = ctx.getSystemService(AudioManager::class.java)
                val code = when (step.p("button", "play_pause")) {
                    "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                    "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                    "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                    "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
                    else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                }
                val time = SystemClock.uptimeMillis()
                am.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0))
                am.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0))
                null
            }

            "volume_step" -> {
                val am = ctx.getSystemService(AudioManager::class.java)
                val stream = when (step.p("stream", "media")) {
                    "ring" -> AudioManager.STREAM_RING
                    "notification" -> AudioManager.STREAM_NOTIFICATION
                    "alarm" -> AudioManager.STREAM_ALARM
                    else -> AudioManager.STREAM_MUSIC
                }
                val dir = if (step.p("direction", "up") == "down") AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE
                val flags = if (step.p("show", "yes") == "yes") AudioManager.FLAG_SHOW_UI else 0
                am.adjustStreamVolume(stream, dir, flags)
                null
            }

            "play_sound" -> {
                val uri: Uri = when (step.p("source", "notification")) {
                    "ringtone" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    "alarm" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    "notification" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    else -> {
                        val path = t("path").trim()
                        if (path.isEmpty()) error("No sound file or URL given")
                        if (path.startsWith("/")) Uri.fromFile(File(path)) else Uri.parse(path)
                    }
                }
                val usage = if (step.p("stream", "notification") == "alarm") AudioAttributes.USAGE_ALARM
                else if (step.p("stream", "notification") == "media") AudioAttributes.USAGE_MEDIA
                else AudioAttributes.USAGE_NOTIFICATION
                withContext(Dispatchers.Main) {
                    player?.release()
                    player = MediaPlayer().apply {
                        setAudioAttributes(AudioAttributes.Builder().setUsage(usage).build())
                        setDataSource(ctx, uri)
                        isLooping = step.p("loop", "no") == "yes"
                        setOnCompletionListener { mp -> if (!mp.isLooping) { mp.release(); if (player === mp) player = null } }
                        prepare()
                        start()
                    }
                }
                null
            }

            "stop_sound" -> {
                withContext(Dispatchers.Main) {
                    player?.runCatching { stop() }
                    player?.release()
                    player = null
                }
                null
            }

            "set_alarm" -> {
                val time = Conditions.parseTime(t("time", "07:00"))
                ctx.startActivity(
                    Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, time.hour)
                        .putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, t("label", "TaskTemplar"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                "alarm %02d:%02d".format(time.hour, time.minute)
            }

            "car_mode" -> {
                val ui = ctx.getSystemService(UiModeManager::class.java)
                val on = want(ui.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_CAR)
                if (on) ui.enableCarMode(0) else ui.disableCarMode(0)
                "car mode ${onOff(on)}"
            }

            "set_wallpaper" -> {
                val src = t("source")
                if (src.isBlank()) error("No image path or URL")
                val bmp = withContext(Dispatchers.IO) {
                    if (src.startsWith("http://") || src.startsWith("https://")) URL(src).openStream().use { BitmapFactory.decodeStream(it) }
                    else BitmapFactory.decodeFile(src.removePrefix("file://"))
                } ?: error("Couldn't read that image")
                val which = when (step.p("target", "both")) {
                    "home" -> WallpaperManager.FLAG_SYSTEM
                    "lock" -> WallpaperManager.FLAG_LOCK
                    else -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                }
                withContext(Dispatchers.IO) { WallpaperManager.getInstance(ctx).setBitmap(bmp, null, true, which) }
                null
            }

            else -> error("Unknown device action '${step.type}'")
        }
    }

    private fun openPanel(ctx: Context, action: String) =
        ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun openSettings(ctx: Context, action: String) =
        runCatching { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
