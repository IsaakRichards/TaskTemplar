package dev.isaakrichards.tasktemplar.engine

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.isaakrichards.tasktemplar.TemplarApp
import dev.isaakrichards.tasktemplar.R
import dev.isaakrichards.tasktemplar.model.Step
import dev.isaakrichards.tasktemplar.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

object Actions {

    private val notifId = AtomicInteger(2000)

    @Volatile
    var torchOn = false
        private set

    fun watchTorch(ctx: Context) {
        runCatching {
            ctx.getSystemService(CameraManager::class.java)?.registerTorchCallback(
                object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        torchOn = enabled
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        }
    }

    /** Replaces {name} placeholders with values from [vars]. */
    fun magic(text: String, vars: Map<String, String>): String = MagicText.apply(text, vars)

    /** Runs one action. Returns a short note for the log, or throws with a readable message. */
    suspend fun execute(ctx: Context, step: Step, vars: MutableMap<String, String>): String? {
        fun t(key: String, def: String = "") = magic(step.p(key, def), vars)

        return when (step.type) {
            "notify" -> {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) error("Notification permission not granted")
                val open = PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val n = NotificationCompat.Builder(ctx, TemplarApp.CHANNEL_ALERTS)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(t("title", "TaskTemplar"))
                    .setContentText(t("text"))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(t("text")))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
                ctx.getSystemService(NotificationManager::class.java).notify(notifId.incrementAndGet(), n)
                null
            }

            "toast" -> {
                val msg = t("text")
                withContext(Dispatchers.Main) { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
                null
            }

            "speak" -> {
                val text = t("text")
                if (withTimeoutOrNull(120_000L) { speak(ctx, text) } == null) "timed out" else null
            }

            "vibrate" -> {
                val ms = (step.p("ms", "500").toLongOrNull() ?: 500L).coerceIn(1L, 10_000L)
                @Suppress("DEPRECATION")
                val v = ctx.getSystemService(Vibrator::class.java) ?: error("No vibrator")
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                null
            }

            "ringer" -> {
                val am = ctx.getSystemService(AudioManager::class.java)
                val mode = step.p("mode", "vibrate")
                if (mode == "silent" && !policyAccess(ctx)) error("Silent mode needs Do Not Disturb access")
                am.ringerMode = when (mode) {
                    "normal" -> AudioManager.RINGER_MODE_NORMAL
                    "silent" -> AudioManager.RINGER_MODE_SILENT
                    else -> AudioManager.RINGER_MODE_VIBRATE
                }
                null
            }

            "dnd" -> {
                if (!policyAccess(ctx)) error("Do Not Disturb access not granted")
                val nm = ctx.getSystemService(NotificationManager::class.java)
                nm.setInterruptionFilter(
                    when (step.p("mode", "priority")) {
                        "off" -> NotificationManager.INTERRUPTION_FILTER_ALL
                        "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
                        "none" -> NotificationManager.INTERRUPTION_FILTER_NONE
                        else -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    },
                )
                null
            }

            "volume" -> {
                val am = ctx.getSystemService(AudioManager::class.java)
                val stream = when (step.p("stream", "media")) {
                    "ring" -> AudioManager.STREAM_RING
                    "notification" -> AudioManager.STREAM_NOTIFICATION
                    "alarm" -> AudioManager.STREAM_ALARM
                    else -> AudioManager.STREAM_MUSIC
                }
                val pct = (step.p("percent", "50").toIntOrNull() ?: 50).coerceIn(0, 100)
                val max = am.getStreamMaxVolume(stream)
                am.setStreamVolume(stream, (max * pct / 100f).roundToInt(), 0)
                null
            }

            "brightness" -> {
                if (!Settings.System.canWrite(ctx)) error("Needs 'Modify system settings' permission")
                val pct = (step.p("percent", "40").toIntOrNull() ?: 40).coerceIn(0, 100)
                val cr = ctx.contentResolver
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (pct * 255 / 100).coerceIn(1, 255))
                null
            }

            "flashlight" -> {
                val cm = ctx.getSystemService(CameraManager::class.java)
                val id = cm.cameraIdList.firstOrNull {
                    cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: error("No flashlight on this device")
                val on = when (step.p("state", "toggle")) {
                    "on" -> true
                    "off" -> false
                    else -> !torchOn
                }
                cm.setTorchMode(id, on)
                torchOn = on
                if (on) "on" else "off"
            }

            "clipboard" -> {
                val text = t("text")
                withContext(Dispatchers.Main) {
                    ctx.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("TaskTemplar", text))
                }
                null
            }

            "launch_app" -> {
                val pkg = step.p("app")
                if (pkg.isBlank()) error("No app chosen")
                val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: error("$pkg is not installed")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                if (!Settings.canDrawOverlays(ctx)) "may be blocked in background without 'Display over other apps'" else null
            }

            "launch_home" -> {
                ctx.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                null
            }

            "kill_app" -> {
                val pkg = step.p("app")
                if (pkg.isBlank()) error("No app chosen")
                ctx.getSystemService(android.app.ActivityManager::class.java).killBackgroundProcesses(pkg)
                "asked Android to close $pkg's background processes"
            }

            "clear_notifications" -> {
                val n = dev.isaakrichards.tasktemplar.service.NotifListener.clear(step.p("app"))
                "cleared $n"
            }

            "open_url" -> {
                val url = t("url")
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                null
            }

            "http" -> http(step.p("method", "GET"), t("url"), t("body"), vars)

            "wait" -> {
                val s = (step.p("seconds", "5").toDoubleOrNull() ?: 5.0).coerceIn(0.0, 3600.0)
                delay((s * 1000).toLong())
                null
            }

            else -> error("Unknown action '${step.type}'")
        }
    }

    private fun policyAccess(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    private suspend fun http(method: String, url: String, body: String, vars: MutableMap<String, String>): String =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", "TaskTemplar")
                if (method == "POST") {
                    conn.doOutput = true
                    val trimmed = body.trimStart()
                    val type = if (trimmed.startsWith("{") || trimmed.startsWith("[")) "application/json" else "text/plain; charset=utf-8"
                    conn.setRequestProperty("Content-Type", type)
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = conn.responseCode
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val text = stream?.bufferedReader()?.use { it.readText() }?.take(4000) ?: ""
                vars["http_code"] = code.toString()
                vars["http_body"] = text
                "HTTP $code"
            } finally {
                conn.disconnect()
            }
        }

    private suspend fun speak(ctx: Context, text: String) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { cont ->
            var tts: TextToSpeech? = null
            tts = TextToSpeech(ctx.applicationContext) init@{ status ->
                val engine = tts
                if (status != TextToSpeech.SUCCESS || engine == null) {
                    engine?.shutdown()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Text-to-speech unavailable"))
                    return@init
                }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        engine.shutdown()
                        if (cont.isActive) cont.resume(Unit)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        engine.shutdown()
                        if (cont.isActive) cont.resume(Unit)
                    }
                })
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "templar")
            }
            cont.invokeOnCancellation { tts?.shutdown() }
        }
    }
}
