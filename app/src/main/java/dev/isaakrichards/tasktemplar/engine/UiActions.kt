package dev.isaakrichards.tasktemplar.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import dev.isaakrichards.tasktemplar.model.Step
import dev.isaakrichards.tasktemplar.service.TemplarAccessibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Actions that drive other apps through the accessibility service. */
object UiActions {

    private fun service(): TemplarAccessibility = TemplarAccessibility.instance
        ?: error("Turn on TaskTemplar under Settings › Accessibility (see the Permissions screen)")

    suspend fun execute(step: Step, vars: MutableMap<String, String>): String? {
        fun t(key: String, def: String = "") = MagicText.apply(step.p(key, def), vars)
        val svc = service()

        return when (step.type) {
            "ui_click" -> {
                val long = step.p("press", "click") == "long"
                when (step.p("target", "text")) {
                    "coords" -> {
                        val x = t("x").trim().toFloatOrNull() ?: error("X is not a number")
                        val y = t("y").trim().toFloatOrNull() ?: error("Y is not a number")
                        if (!tap(svc, x, y, long)) error("Tap was cancelled")
                        null
                    }
                    else -> {
                        val node = findNode(svc, step, vars) ?: error("Nothing on screen matched")
                        clickNode(svc, node, long)
                        null
                    }
                }
            }

            "ui_type" -> {
                val root = svc.rootInActiveWindow ?: error("No active window")
                val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: error("No text field is focused")
                val text = t("text")
                val full = if (step.p("mode", "replace") == "append") (field.text?.toString() ?: "") + text else text
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, full)
                }
                if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) error("Field refused the text")
                null
            }

            "ui_scroll" -> {
                val root = svc.rootInActiveWindow ?: error("No active window")
                val scrollable = firstScrollable(root) ?: error("Nothing scrollable on screen")
                val action = if (step.p("direction", "forward") == "backward") {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                if (!scrollable.performAction(action)) "reached the end" else null
            }

            "ui_swipe" -> {
                fun f(k: String) = t(k).trim().toFloatOrNull() ?: error("$k is not a number")
                val path = Path().apply {
                    moveTo(f("x1"), f("y1"))
                    lineTo(f("x2"), f("y2"))
                }
                val ms = (t("ms", "300").trim().toLongOrNull() ?: 300L).coerceIn(1L, 10_000L)
                if (!gesture(svc, path, ms)) error("Swipe was cancelled")
                null
            }

            "global_action" -> {
                val action = when (step.p("button", "back")) {
                    "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                    "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                    "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                    "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                    "power_dialog" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
                    "split_screen" -> AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
                    "lock" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else error("Needs Android 9+")
                    "screenshot" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else error("Needs Android 9+")
                    else -> AccessibilityService.GLOBAL_ACTION_BACK
                }
                val ok = withContext(Dispatchers.Main) { svc.performGlobalAction(action) }
                if (!ok) error("The system refused that button")
                null
            }

            "read_screen" -> {
                val text = TemplarAccessibility.screenText(svc.rootInActiveWindow).trim()
                val name = MagicText.apply(step.p("name", "screen"), vars).trim()
                if (step.p("scope", "local") == "local") vars[name] = text
                else dev.isaakrichards.tasktemplar.data.GlobalVars.set(name, VarTypes.STRING, text)
                "${text.lines().size} line(s)"
            }

            else -> error("Unknown screen action '${step.type}'")
        }
    }

    private fun findNode(svc: TemplarAccessibility, step: Step, vars: Map<String, String>): AccessibilityNodeInfo? {
        val root = svc.rootInActiveWindow ?: return null
        return if (step.p("target", "text") == "id") {
            val id = MagicText.apply(step.p("id"), vars).trim()
            if (id.isEmpty()) return null
            val full = if (":id/" in id) id else "${root.packageName}:id/$id"
            root.findAccessibilityNodeInfosByViewId(full).firstOrNull()
        } else {
            val text = MagicText.apply(step.p("text"), vars).trim()
            if (text.isEmpty()) return null
            val exact = step.p("match", "contains") == "exact"
            root.findAccessibilityNodeInfosByText(text).firstOrNull { n ->
                if (!exact) true else {
                    val label = (n.text ?: n.contentDescription)?.toString()?.trim() ?: ""
                    label.equals(text, ignoreCase = true)
                }
            }
        }
    }

    private suspend fun clickNode(svc: TemplarAccessibility, node: AccessibilityNodeInfo, long: Boolean) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !(if (long) target.isLongClickable else target.isClickable)) target = target.parent
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        if (target != null && target.performAction(action)) return
        // Not clickable through the accessibility tree: tap the middle of it instead.
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty || !tap(svc, r.exactCenterX(), r.exactCenterY(), long)) error("Couldn't press that element")
    }

    private fun firstScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 4000) {
            val n = queue.removeFirst()
            seen++
            if (n.isScrollable) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private suspend fun tap(svc: TemplarAccessibility, x: Float, y: Float, long: Boolean): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return gesture(svc, path, if (long) 800L else 60L)
    }

    private suspend fun gesture(svc: TemplarAccessibility, path: Path, ms: Long): Boolean =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val g = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
                    .build()
                val sent = svc.dispatchGesture(
                    g,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null,
                )
                if (!sent && cont.isActive) cont.resume(false)
            }
        }
}
