package dev.isaakrichards.tasktemplar.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.isaakrichards.tasktemplar.R
import dev.isaakrichards.tasktemplar.data.MacroStore
import dev.isaakrichards.tasktemplar.engine.Conditions
import dev.isaakrichards.tasktemplar.engine.MacroEngine
import dev.isaakrichards.tasktemplar.model.Macro
import dev.isaakrichards.tasktemplar.model.MacroEvent
import dev.isaakrichards.tasktemplar.ui.ShortcutRunActivity
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread
import kotlin.math.abs

/** Ways to start macros from outside the app: shortcuts, widgets, tiles, buttons, intents, webhooks. */
object LauncherBridge {

    /** Runs a macro directly (its constraints still apply). */
    fun runFromLauncher(ctx: Context, macroId: String, source: String) {
        MacroStore.init(ctx)
        val macro = MacroStore.get(macroId) ?: return
        if (Conditions.constraintsPass(ctx.applicationContext, macro)) {
            MacroEngine.start(ctx.applicationContext, macro, MacroEvent("launcher", mapOf("source" to source)))
        } else {
            MacroStore.log(macro.name, "Started from $source, but constraints not met")
        }
    }

    /** Asks the launcher to pin a home-screen shortcut that runs [macro]. */
    fun pinShortcut(ctx: Context, macro: Macro): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) return false
        val info = ShortcutInfoCompat.Builder(ctx, "macro_${macro.id}")
            .setShortLabel(macro.name.take(24).ifBlank { "Macro" })
            .setLongLabel(macro.name.ifBlank { "Macro" })
            .setIcon(IconCompat.createWithResource(ctx, R.mipmap.ic_launcher))
            .setIntent(
                Intent(ctx, ShortcutRunActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra("macro", macro.id),
            )
            .build()
        return ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
    }

    /** Macros (with trigger index) that use a trigger of [type]. */
    fun usersOf(type: String): List<Triple<Macro, Int, dev.isaakrichards.tasktemplar.model.Step>> =
        MacroStore.macros.value.filter { it.enabled }.flatMap { m ->
            m.triggers.withIndex().filter { it.value.type == type }.map { Triple(m, it.index, it.value) }
        }
}

/** Receives taps on notification buttons and floating buttons. */
class ButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val macro = intent.getStringExtra("macro") ?: return
        val idx = intent.getIntExtra("idx", -1)
        MacroStore.init(context)
        MacroEngine.dispatch(context, MacroEvent(intent.getStringExtra("kind") ?: "notif_button", mapOf("macro" to macro, "idx" to idx.toString())))
    }

    companion object {
        fun pending(ctx: Context, kind: String, macroId: String, idx: Int): PendingIntent {
            val code = (kind + macroId + idx).hashCode()
            val intent = Intent(ctx, ButtonReceiver::class.java)
                .setAction("dev.isaakrichards.tasktemplar.BUTTON.$code")
                .putExtra("kind", kind).putExtra("macro", macroId).putExtra("idx", idx)
            return PendingIntent.getBroadcast(ctx, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}

// ---------------- Home-screen widget ----------------

class MacroWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val prefs = prefs(context).edit()
        ids.forEach { prefs.remove("w$it") }
        prefs.apply()
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_RUN) {
            intent.getStringExtra("macro")?.let { LauncherBridge.runFromLauncher(context, it, "widget") }
        }
    }

    companion object {
        const val ACTION_RUN = "dev.isaakrichards.tasktemplar.WIDGET_RUN"

        fun prefs(ctx: Context) = ctx.getSharedPreferences("widgets", Context.MODE_PRIVATE)

        fun update(ctx: Context, manager: AppWidgetManager, widgetId: Int) {
            MacroStore.init(ctx)
            val macroId = prefs(ctx).getString("w$widgetId", null)
            val macro = macroId?.let { MacroStore.get(it) }
            val views = RemoteViews(ctx.packageName, R.layout.widget_macro)
            views.setTextViewText(R.id.widget_label, macro?.name ?: "Tap to set up")
            if (macro != null) {
                val run = Intent(ctx, MacroWidget::class.java).setAction(ACTION_RUN).putExtra("macro", macro.id)
                views.setOnClickPendingIntent(
                    R.id.widget_root,
                    PendingIntent.getBroadcast(ctx, widgetId, run, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
            }
            manager.updateAppWidget(widgetId, views)
        }

        fun refreshAll(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(android.content.ComponentName(ctx, MacroWidget::class.java))
            ids.forEach { update(ctx, manager, it) }
        }
    }
}

// ---------------- Quick-settings tiles (three slots) ----------------

abstract class MacroTile(private val slot: Int) : TileService() {

    private fun owner(): Macro? = LauncherBridge.usersOf("qs_tile")
        .firstOrNull { it.third.p("slot", "1") == slot.toString() }?.first

    override fun onStartListening() {
        super.onStartListening()
        MacroStore.init(this)
        val tile = qsTile ?: return
        val m = owner()
        tile.label = m?.name ?: "TaskTemplar $slot"
        tile.state = if (m != null) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        MacroStore.init(this)
        MacroEngine.dispatch(this, MacroEvent("tile", mapOf("slot" to slot.toString())))
    }
}

class TileSlot1 : MacroTile(1)
class TileSlot2 : MacroTile(2)
class TileSlot3 : MacroTile(3)

// ---------------- Floating buttons ----------------

/** Draggable overlay bubbles for "Floating button" triggers. Needs "Display over other apps". */
class FloatingButtons(private val ctx: Context) {

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val shown = HashMap<String, View>()
    private val prefs = ctx.getSharedPreferences("floating", Context.MODE_PRIVATE)

    @SuppressLint("ClickableViewAccessibility")
    fun update() {
        val wanted = LauncherBridge.usersOf("floating_button").associateBy { "${it.first.id}#${it.second}" }
        if (!Settings.canDrawOverlays(ctx)) {
            removeAll()
            return
        }
        (shown.keys - wanted.keys).forEach { key -> shown.remove(key)?.let { runCatching { wm.removeView(it) } } }
        for ((key, entry) in wanted) {
            val (macro, idx, step) = entry
            shown[key]?.let { runCatching { wm.removeView(it) } }
            val density = ctx.resources.displayMetrics.density
            val size = ((step.p("size", "52").toIntOrNull() ?: 52).coerceIn(32, 120) * density).toInt()
            val color = runCatching { android.graphics.Color.parseColor(step.p("color", "#B69CFF")) }.getOrDefault(0xFFB69CFF.toInt())
            val view = TextView(ctx).apply {
                text = step.p("label").ifBlank { macro.name.take(2) }
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.BLACK)
                textSize = 13f
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    alpha = ((step.p("opacity", "90").toIntOrNull() ?: 90).coerceIn(20, 100) * 255 / 100)
                }
            }
            val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            val lp = WindowManager.LayoutParams(
                size, size, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = prefs.getInt("$key.x", 40)
                y = prefs.getInt("$key.y", 400)
            }
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            view.setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = startX + (e.rawX - downX).toInt()
                        lp.y = startY + (e.rawY - downY).toInt()
                        runCatching { wm.updateViewLayout(v, lp) }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (abs(e.rawX - downX) < 12 && abs(e.rawY - downY) < 12) {
                            MacroEngine.dispatch(ctx, MacroEvent("floating", mapOf("macro" to macro.id, "idx" to idx.toString())))
                        } else {
                            prefs.edit().putInt("$key.x", lp.x).putInt("$key.y", lp.y).apply()
                        }
                        true
                    }
                    else -> false
                }
            }
            runCatching { wm.addView(view, lp) }.onSuccess { shown[key] = view }
        }
    }

    fun removeAll() {
        shown.values.forEach { runCatching { wm.removeView(it) } }
        shown.clear()
    }
}

// ---------------- Local webhook server ----------------

/**
 * Tiny HTTP server for "Webhook (local network)" triggers: any device on your Wi-Fi (or your home
 * server) can call http://PHONE-IP:PORT/hook/NAME?key=value. Only runs while such a macro exists.
 */
class WebhookServer(private val ctx: Context) {

    @Volatile private var socket: ServerSocket? = null
    private var port = -1

    fun update() {
        val users = LauncherBridge.usersOf("webhook")
        val wantPort = users.firstOrNull()?.third?.p("port", "8765")?.toIntOrNull() ?: -1
        if (users.isEmpty()) {
            stop()
            return
        }
        if (wantPort == port && socket != null) return
        stop()
        port = wantPort
        val server = runCatching { ServerSocket(port, 16, InetAddress.getByName("0.0.0.0")) }.getOrElse {
            MacroStore.log("System", "✗ Webhook server couldn't open port $port: ${it.message}")
            return
        }
        socket = server
        thread(isDaemon = true, name = "templar-webhook") {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { handle(client) }
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        port = -1
    }

    private fun handle(client: Socket) = client.use { s ->
        s.soTimeout = 10_000
        val input = BufferedInputStream(s.getInputStream())
        fun readLine(): String {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c == -1 || c == '\n'.code) break
                if (c != '\r'.code) sb.append(c.toChar())
                if (sb.length > 8192) break
            }
            return sb.toString()
        }
        val requestLine = readLine()
        val parts = requestLine.split(' ')
        if (parts.size < 2) return@use
        var length = 0
        while (true) {
            val h = readLine()
            if (h.isEmpty()) break
            if (h.startsWith("Content-Length:", ignoreCase = true)) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val bodyBytes = ByteArray(length.coerceIn(0, 64_000))
        var read = 0
        while (read < bodyBytes.size) {
            val n = input.read(bodyBytes, read, bodyBytes.size - read)
            if (n <= 0) break
            read += n
        }
        val body = String(bodyBytes, 0, read)
        val target = parts[1]
        val path = target.substringBefore('?').trim('/')
        val name = path.removePrefix("hook/").removePrefix("hook").trim('/')
        val query = target.substringAfter('?', "").split('&').filter { '=' in it }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        val matched = LauncherBridge.usersOf("webhook").any { (_, _, step) ->
            step.p("name").equals(name, ignoreCase = true) && step.p("token").let { it.isBlank() || it == query["token"] }
        }
        val (status, reply) = if (matched) {
            MacroEngine.dispatch(ctx, MacroEvent("webhook", query - "token" + mapOf("name" to name, "body" to body, "method" to parts[0])))
            "200 OK" to "ok"
        } else {
            "404 Not Found" to "no macro listens on /hook/$name"
        }
        val out = s.getOutputStream()
        out.write("HTTP/1.1 $status\r\nContent-Type: text/plain\r\nContent-Length: ${reply.toByteArray().size}\r\nConnection: close\r\n\r\n$reply".toByteArray())
        out.flush()
    }
}
