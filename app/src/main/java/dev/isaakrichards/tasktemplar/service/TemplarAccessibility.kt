package dev.isaakrichards.tasktemplar.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import dev.isaakrichards.tasktemplar.data.MacroStore
import dev.isaakrichards.tasktemplar.engine.MacroEngine
import dev.isaakrichards.tasktemplar.model.MacroEvent

/**
 * Accessibility service: sees which app is in front and what text is on screen, and lets
 * actions tap, type, scroll and press system buttons. Only active when the user turns it on.
 * Nothing it sees is stored or sent anywhere; it's only matched against your own macros.
 */
class TemplarAccessibility : AccessibilityService() {

    private var lastPkg = ""
    private var lastScan = 0L
    private val textState = HashMap<String, Boolean>()
    private var keyboards: Set<String> = emptySet()

    override fun onServiceConnected() {
        super.onServiceConnected()
        MacroStore.init(this)
        instance = this
        keyboards = runCatching {
            getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }.toSet()
        }.getOrDefault(emptySet())
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val state = when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_UP -> "up"
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> "down"
                else -> null
            }
            if (state != null) MacroEngine.dispatch(this, MacroEvent("volume_button", mapOf("state" to state)))
        }
        return false // never swallow the key
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg != lastPkg && pkg !in ignored && pkg !in keyboards) {
                    val prev = lastPkg
                    lastPkg = pkg
                    foregroundPkg = pkg
                    if (prev.isNotEmpty()) {
                        MacroEngine.dispatch(this, MacroEvent("app", mapOf("state" to "closed", "app" to prev)))
                    }
                    MacroEngine.dispatch(this, MacroEvent("app", mapOf("state" to "opened", "app" to pkg)))
                }
                scanScreen(pkg, force = true)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> scanScreen(pkg, force = false)
        }
    }

    /** Fires "Screen content" triggers when their text newly appears. Throttled; skipped if unused. */
    private fun scanScreen(pkg: String, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastScan < 700) return
        val watchers = MacroStore.macros.value.filter { it.enabled }.flatMap { m ->
            m.triggers.withIndex().filter { it.value.type == "screen_text" }.map { Triple(m, it.index, it.value) }
        }
        if (watchers.isEmpty()) return
        lastScan = now
        val text = screenText(rootInActiveWindow)
        for ((macro, idx, step) in watchers) {
            val needle = step.p("text")
            val app = step.p("app")
            val present = needle.isNotBlank() && (app.isBlank() || app == pkg) && text.contains(needle, ignoreCase = true)
            val key = "${macro.id}#$idx"
            val was = textState[key] == true
            textState[key] = present
            if (present && !was) {
                MacroEngine.dispatch(
                    this,
                    MacroEvent("screen_text", mapOf("macro" to macro.id, "idx" to idx.toString(), "app" to pkg, "matched" to needle)),
                )
            }
        }
    }

    companion object {
        @Volatile
        var instance: TemplarAccessibility? = null
            private set

        /** Package of the app currently in front ("" if unknown or the service is off). */
        @Volatile
        var foregroundPkg: String = ""
            private set

        private val ignored = setOf("com.android.systemui", "dev.isaakrichards.tasktemplar", "android")

        /** All visible text (and content descriptions) under [root], one item per line. */
        fun screenText(root: AccessibilityNodeInfo?): String {
            val sb = StringBuilder()
            var count = 0
            fun walk(n: AccessibilityNodeInfo?) {
                if (n == null || count > 4000) return
                count++
                n.text?.let { if (it.isNotBlank()) sb.append(it).append('\n') }
                n.contentDescription?.let { if (it.isNotBlank()) sb.append(it).append('\n') }
                for (i in 0 until n.childCount) walk(n.getChild(i))
            }
            walk(root)
            return sb.toString()
        }
    }
}
