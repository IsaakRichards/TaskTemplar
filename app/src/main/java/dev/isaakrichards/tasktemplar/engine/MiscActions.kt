package dev.isaakrichards.tasktemplar.engine

import android.Manifest
import android.annotation.SuppressLint
import android.app.RemoteInput
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.isaakrichards.tasktemplar.data.MacroStore
import dev.isaakrichards.tasktemplar.model.Step
import dev.isaakrichards.tasktemplar.service.NotifListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Files, network, sounds, notifications and other odds and ends. */
object MiscActions {

    private var dimView: View? = null

    private fun need(ctx: Context, perm: String, what: String) {
        if (ContextCompat.checkSelfPermission(ctx, perm) != PackageManager.PERMISSION_GRANTED) error("$what permission not granted")
    }

    /** Resolves a user path; relative paths go under Download/TaskTemplar. */
    @Suppress("DEPRECATION")
    fun file(path: String): File {
        val p = path.trim()
        if (p.isEmpty()) error("No file path given")
        return if (p.startsWith("/")) File(p)
        else File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TaskTemplar/$p")
    }

    private fun storageHint(e: Throwable): Nothing =
        error("${e.message ?: "File error"}. Grant “All files access” on the Permissions screen to use paths outside Download.")

    /** Writes text to Download/TaskTemplar/<name> without any permission (MediaStore), or to a path. */
    fun writeText(ctx: Context, path: String, text: String, append: Boolean): String {
        val f = file(path)
        val ok = runCatching {
            f.parentFile?.mkdirs()
            if (append) f.appendText(text) else f.writeText(text)
        }.isSuccess
        if (ok) return f.absolutePath
        if (Build.VERSION.SDK_INT >= 29 && !path.trim().startsWith("/") && !append) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, path.trim().substringAfterLast('/'))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TaskTemplar")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Couldn't create file")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            return "Download/TaskTemplar/${path.trim().substringAfterLast('/')}"
        }
        storageHint(IllegalStateException("Couldn't write ${f.absolutePath}"))
    }

    @SuppressLint("MissingPermission")
    suspend fun execute(ctx: Context, step: Step, vars: MutableMap<String, String>): String? {
        fun t(key: String, def: String = "") = MagicText.apply(step.p(key, def), vars)

        return when (step.type) {
            "clear_log" -> {
                MacroStore.clearLog()
                null
            }

            "say_time" -> {
                val now = LocalTime.now()
                val text = if (step.p("format", "12") == "24") "It's ${now.format(DateTimeFormatter.ofPattern("H:mm"))}"
                else "It's ${now.format(DateTimeFormatter.ofPattern("h:mm a"))}"
                Actions.execute(ctx, Step("speak", mapOf("text" to text)), vars)
                null
            }

            "notification_interact" -> {
                val listener = NotifListener.instance ?: error("Notification access is not granted")
                val app = step.p("app")
                val needle = t("contains")
                val sbn = listener.activeNotifications.orEmpty().filter { s ->
                    val ex = s.notification?.extras
                    val txt = "${ex?.getCharSequence(android.app.Notification.EXTRA_TITLE) ?: ""} ${ex?.getCharSequence(android.app.Notification.EXTRA_TEXT) ?: ""}"
                    (app.isBlank() || s.packageName == app) && (needle.isBlank() || txt.contains(needle, ignoreCase = true))
                }.maxByOrNull { it.postTime } ?: error("No matching notification")
                val n = sbn.notification
                when (step.p("op", "tap")) {
                    "dismiss" -> listener.cancelNotification(sbn.key)
                    "button" -> {
                        val label = t("button")
                        val action = n.actions?.firstOrNull { it.title?.toString()?.contains(label, ignoreCase = true) == true }
                            ?: error("No button called “$label”")
                        action.actionIntent.send()
                    }
                    "reply" -> {
                        val action = n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() } ?: error("That notification has no reply box")
                        val inputs = action.remoteInputs
                        val bundle = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, t("text")) } }
                        val fill = Intent()
                        RemoteInput.addResultsToIntent(inputs, fill, bundle)
                        action.actionIntent.send(ctx, 0, fill)
                    }
                    else -> n.contentIntent?.send() ?: error("That notification can't be opened")
                }
                null
            }

            "set_ringtone" -> {
                if (!Settings.System.canWrite(ctx)) error("Needs 'Modify system settings' permission")
                val type = when (step.p("which", "ringtone")) {
                    "notification" -> RingtoneManager.TYPE_NOTIFICATION
                    "alarm" -> RingtoneManager.TYPE_ALARM
                    else -> RingtoneManager.TYPE_RINGTONE
                }
                val uri = step.p("sound").ifBlank { error("No sound chosen") }
                RingtoneManager.setActualDefaultRingtoneUri(ctx, type, Uri.parse(uri))
                null
            }

            "vibrate_ringing" -> {
                if (!Settings.System.canWrite(ctx)) error("Needs 'Modify system settings' permission")
                val on = step.p("state", "off") == "on"
                Settings.System.putInt(ctx.contentResolver, "vibrate_when_ringing", if (on) 1 else 0)
                null
            }

            "dim_screen" -> {
                withContext(Dispatchers.Main) {
                    val wm = ctx.getSystemService(WindowManager::class.java)
                    dimView?.let { runCatching { wm.removeView(it) } }
                    dimView = null
                    if (step.p("state", "on") == "on") {
                        if (!Settings.canDrawOverlays(ctx)) error("Needs “Display over other apps”")
                        val level = (step.p("level", "50").toIntOrNull() ?: 50).coerceIn(5, 95)
                        val v = View(ctx).apply { setBackgroundColor(android.graphics.Color.argb(level * 255 / 100, 0, 0, 0)) }
                        val lp = WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            PixelFormat.TRANSLUCENT,
                        )
                        wm.addView(v, lp)
                        dimView = v
                    }
                }
                null
            }

            "keyboard_picker" -> {
                withContext(Dispatchers.Main) { ctx.getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
                null
            }

            "voice_search" -> {
                ctx.startActivity(Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                null
            }

            "screensaver" -> {
                val intent = Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.android.systemui", "com.android.systemui.Somnambulator"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(intent) }.getOrElse { error("This phone doesn't expose the screensaver launcher") }
                null
            }

            "hotspot_settings" -> {
                val intent = Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.TetherSettings"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(intent) }.getOrElse {
                    ctx.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                "Android doesn't let apps switch the hotspot directly; opened its settings"
            }

            "launch_activity" -> {
                val pkg = t("package").trim()
                val cls = t("class").trim()
                if (pkg.isEmpty() || cls.isEmpty()) error("Package and activity class are both needed")
                val full = if (cls.startsWith(".")) pkg + cls else cls
                ctx.startActivity(Intent().setComponent(ComponentName(pkg, full)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                null
            }

            "open_file" -> {
                val f = file(t("path"))
                if (!f.exists()) error("${f.absolutePath} doesn't exist")
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
                )
                null
            }

            "clear_call_log" -> {
                need(ctx, Manifest.permission.WRITE_CALL_LOG, "Call log")
                val number = t("number").trim()
                val n = if (number.isEmpty()) ctx.contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null)
                else ctx.contentResolver.delete(CallLog.Calls.CONTENT_URI, "${CallLog.Calls.NUMBER} LIKE ?", arrayOf("%${number.takeLast(7)}%"))
                "deleted $n"
            }

            "udp_send" -> {
                val host = t("host").trim()
                val port = t("port", "9").trim().toIntOrNull() ?: error("Bad port")
                val bytes = t("message").toByteArray()
                withContext(Dispatchers.IO) {
                    DatagramSocket().use { it.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(host), port)) }
                }
                "${bytes.size} bytes to $host:$port"
            }

            "write_file" -> {
                val where = withContext(Dispatchers.IO) {
                    writeText(ctx, t("path", "notes.txt"), t("text") + if (step.p("newline", "yes") == "yes") "\n" else "", step.p("mode", "append") == "append")
                }
                "wrote $where"
            }

            "read_file" -> {
                val text = withContext(Dispatchers.IO) { runCatching { file(t("path")).readText() }.getOrElse { storageHint(it) } }
                val name = MagicText.apply(step.p("name", "file"), vars).trim()
                if (step.p("scope", "local") == "local") vars[name] = text
                else dev.isaakrichards.tasktemplar.data.GlobalVars.set(name, VarTypes.STRING, text)
                "${text.length} chars"
            }

            "file_op" -> {
                val src = file(t("path"))
                withContext(Dispatchers.IO) {
                    runCatching {
                        when (step.p("op", "copy")) {
                            "delete" -> if (src.isDirectory) src.deleteRecursively() else src.delete()
                            "mkdir" -> src.mkdirs()
                            "move" -> {
                                val dst = file(t("dest"))
                                dst.parentFile?.mkdirs()
                                if (!src.renameTo(dst)) {
                                    src.copyRecursively(dst, overwrite = true)
                                    src.deleteRecursively()
                                }
                            }
                            else -> {
                                val dst = file(t("dest"))
                                dst.parentFile?.mkdirs()
                                src.copyRecursively(dst, overwrite = true)
                            }
                        }
                    }.getOrElse { storageHint(it) }
                }
                null
            }

            "upload_file" -> {
                val f = file(t("path"))
                if (!f.exists()) error("${f.absolutePath} doesn't exist")
                val code = withContext(Dispatchers.IO) {
                    val boundary = "----TaskTemplar" + UUID.randomUUID().toString().replace("-", "")
                    val conn = URL(t("url")).openConnection() as HttpURLConnection
                    try {
                        conn.requestMethod = "POST"
                        conn.doOutput = true
                        conn.connectTimeout = 20_000
                        conn.readTimeout = 60_000
                        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                        DataOutputStream(conn.outputStream).use { out ->
                            out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"${step.p("field", "file")}\"; filename=\"${f.name}\"\r\n")
                            out.writeBytes("Content-Type: application/octet-stream\r\n\r\n")
                            f.inputStream().use { it.copyTo(out) }
                            out.writeBytes("\r\n--$boundary--\r\n")
                        }
                        conn.responseCode
                    } finally {
                        conn.disconnect()
                    }
                }
                vars["http_code"] = code.toString()
                "HTTP $code"
            }

            "calendar_add" -> {
                need(ctx, Manifest.permission.WRITE_CALENDAR, "Calendar")
                val calId = ctx.contentResolver.query(
                    CalendarContract.Calendars.CONTENT_URI,
                    arrayOf(CalendarContract.Calendars._ID),
                    "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
                    null, "${CalendarContract.Calendars.IS_PRIMARY} DESC",
                )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: error("No writable calendar on this phone")
                val start = LocalDateTime.now().plusMinutes(t("in_minutes", "60").toLongOrNull() ?: 60L)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val mins = t("duration", "60").toLongOrNull() ?: 60L
                val values = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calId)
                    put(CalendarContract.Events.TITLE, t("title", "TaskTemplar"))
                    put(CalendarContract.Events.DESCRIPTION, t("notes"))
                    put(CalendarContract.Events.DTSTART, start)
                    put(CalendarContract.Events.DTEND, start + mins * 60_000)
                    put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
                }
                ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: error("Calendar refused the event")
                null
            }

            "export_macros" -> {
                val name = t("path", "backup-{date}.json").ifBlank { "backup.json" }
                val where = withContext(Dispatchers.IO) { writeText(ctx, name, MacroStore.exportJson(), append = false) }
                "saved $where"
            }

            else -> error("Unknown action '${step.type}'")
        }
    }
}
