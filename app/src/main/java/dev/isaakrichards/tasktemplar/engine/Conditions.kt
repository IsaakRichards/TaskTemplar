package dev.isaakrichards.tasktemplar.engine

import android.content.Context
import dev.isaakrichards.tasktemplar.model.Catalog
import dev.isaakrichards.tasktemplar.model.Macro
import dev.isaakrichards.tasktemplar.model.MacroEvent
import dev.isaakrichards.tasktemplar.model.Step
import java.time.LocalDate
import java.time.LocalTime

object Conditions {

    /** Looks up whether a macro is enabled (null = no such macro); wired up at app start. */
    var macroEnabled: (String) -> Boolean? = { null }

    /** Package of the app in front; wired to the accessibility service at app start. */
    var foregroundApp: () -> String = { "" }

    /** (app, title, text) of showing notifications; wired to the notification listener at app start. */
    var activeNotifications: () -> List<Triple<String, String, String>> = { emptyList() }

    /** "idle", "ringing" or "in_call"; wired to TelephonyManager at app start. */
    var callState: () -> String = { "idle" }

    /** Last known (lat, lng); wired to LocationManager at app start. */
    var lastLocation: () -> Pair<Double, Double>? = { null }

    /** Today's date; replaceable in tests. */
    var today: () -> LocalDate = { LocalDate.now() }

    /** Does trigger number [index] of [macro] match this [event]? */
    fun triggerMatches(macro: Macro, index: Int, step: Step, event: MacroEvent): Boolean {
        val d = event.data
        return when (step.type) {
            "time", "interval", "sun" ->
                event.type == "alarm" && d["macro"] == macro.id && d["idx"] == index.toString()

            "boot" -> event.type == "boot"

            "screen" -> event.type == "screen" && d["state"] == step.p("state", "on")

            "battery_level" -> {
                if (event.type != "battery") return false
                val level = d["level"]?.toIntOrNull() ?: return false
                val prev = d["prev"]?.toIntOrNull() ?: return false
                val n = step.p("level", "20").toIntOrNull() ?: 20
                if (step.p("direction", "below") == "below") prev > n && level <= n
                else prev < n && level >= n
            }

            "power" -> event.type == "power" && d["state"] == step.p("state", "connected")

            "wifi" -> event.type == "wifi" &&
                d["state"] == step.p("state", "connected") &&
                step.p("ssid").let { it.isBlank() || it.equals(d["ssid"], ignoreCase = true) }

            "bluetooth" -> event.type == "bluetooth" &&
                d["state"] == step.p("state", "connected") &&
                step.p("device").let { it.isBlank() || (d["device"] ?: "").contains(it, ignoreCase = true) }

            "headphones" -> event.type == "headphones" && d["state"] == step.p("state", "plugged")

            "app_launched" -> event.type == "app" &&
                d["state"] == step.p("state", "opened") &&
                step.p("app").let { it.isBlank() || it == d["app"] }

            "app_installed" -> event.type == "package" &&
                d["state"] == step.p("state", "installed") &&
                step.p("app").let { it.isBlank() || it == d["app"] }

            "screen_text" ->
                event.type == "screen_text" && d["macro"] == macro.id && d["idx"] == index.toString()

            "call" -> event.type == "call" &&
                d["state"] == step.p("state", "incoming") &&
                numberMatches(step.p("number"), d["number"] ?: "")

            "sms_received" -> event.type == "sms" &&
                numberMatches(step.p("number"), d["number"] ?: "") &&
                step.p("contains").let { it.isBlank() || (d["text"] ?: "").contains(it, ignoreCase = true) }

            "geofence" -> event.type == "geofence" && d["macro"] == macro.id &&
                d["idx"] == index.toString() && d["state"] == step.p("state", "enter")

            "variable_changed" -> event.type == "variable" &&
                step.p("name").let { it.isBlank() || it.trim().equals(d["name"], ignoreCase = true) }

            "notification" -> {
                if (event.type != "notification") return false
                val app = step.p("app")
                val needle = step.p("contains")
                val hay = "${d["title"] ?: ""} ${d["text"] ?: ""}"
                (app.isBlank() || app == d["app"]) && (needle.isBlank() || hay.contains(needle, ignoreCase = true))
            }

            else -> false
        }
    }

    fun constraintsPass(ctx: Context, macro: Macro): Boolean =
        allPass(ctx, macro.constraints, macro.constraintMode)

    /** Combines [conds] with "all" / "any" logic. An empty list passes. */
    fun allPass(ctx: Context, conds: List<Step>, mode: String, vars: Map<String, String> = emptyMap()): Boolean {
        if (conds.isEmpty()) return true
        return if (mode == Macro.MODE_ANY) conds.any { constraintPasses(ctx, it, vars) }
        else conds.all { constraintPasses(ctx, it, vars) }
    }

    fun constraintPasses(ctx: Context, step: Step, vars: Map<String, String> = emptyMap()): Boolean = when (step.type) {
        "variable" -> {
            val name = MagicText.apply(step.p("name"), vars).trim()
            val left = if (step.p("scope", "global") == "local") vars[name] ?: "" else MagicText.global(name)
            VarTypes.compare(left, step.p("op", "eq"), MagicText.apply(step.params["value"] ?: "", vars))
        }

        "macro_enabled" -> macroEnabled(step.p("macro")) == (step.p("state", "enabled") == "enabled")

        "call_state" -> callState() == step.p("state", "idle")

        "location" -> {
            val here = lastLocation()
            val center = GeoMath.parse(step.p("location"))
            if (here == null || center == null) {
                false
            } else {
                val radius = step.p("radius", "150").toDoubleOrNull() ?: 150.0
                (GeoMath.distance(here, center) <= radius) == (step.p("state", "inside") == "inside")
            }
        }

        "day_of_month" -> today().dayOfMonth in numberList(step.p("days"))

        "month" -> today().monthValue in numberList(step.p("months"))

        "app_foreground" -> {
            val inFront = step.p("app").isNotBlank() && foregroundApp() == step.p("app")
            inFront == (step.p("state", "foreground") == "foreground")
        }

        "notification_present" -> {
            val app = step.p("app")
            val needle = step.p("contains")
            val found = activeNotifications().any { (pkg, title, text) ->
                (app.isBlank() || app == pkg) && (needle.isBlank() || "$title $text".contains(needle, ignoreCase = true))
            }
            found == (step.p("state", "present") == "present")
        }

        "time_window" -> {
            val now = LocalTime.now()
            val from = parseTime(step.p("from", "22:00"))
            val to = parseTime(step.p("to", "07:00"))
            if (!from.isAfter(to)) !now.isBefore(from) && now.isBefore(to)
            else !now.isBefore(from) || now.isBefore(to)
        }

        "days" -> today().dayOfWeek.value in Catalog.parseDays(step.p("days", Catalog.ALL_DAYS))

        "battery" -> {
            val level = DeviceState.batteryLevel(ctx)
            val n = step.p("level", "30").toIntOrNull() ?: 30
            if (step.p("direction", "below") == "below") level < n else level > n
        }

        "charging" -> DeviceState.isCharging(ctx) == (step.p("state", "charging") == "charging")

        "wifi_state" -> {
            val on = DeviceState.isOnWifi(ctx)
            val ssid = step.p("ssid")
            if (step.p("state", "connected") == "connected") {
                on && (ssid.isBlank() || ssid.equals(DeviceState.currentSsid(ctx), ignoreCase = true))
            } else {
                !on || (ssid.isNotBlank() && !ssid.equals(DeviceState.currentSsid(ctx), ignoreCase = true))
            }
        }

        "screen_state" -> DeviceState.isScreenOn(ctx) == (step.p("state", "on") == "on")

        else -> true
    }

    /** Matches phone numbers loosely: digits only, and the shorter must end the longer. */
    fun numberMatches(want: String, got: String): Boolean {
        if (want.isBlank()) return true
        val a = want.filter { it.isDigit() }
        val b = got.filter { it.isDigit() }
        if (a.isEmpty() || b.isEmpty()) return got.contains(want, ignoreCase = true)
        return if (a.length <= b.length) b.endsWith(a) else a.endsWith(b)
    }

    /** Parses "1, 15, 20-25" into a set of numbers. */
    fun numberList(s: String): Set<Int> = s.split(',').flatMap { part ->
        val p = part.trim()
        val range = p.split('-').map { it.trim().toIntOrNull() }
        when {
            range.size == 2 && range[0] != null && range[1] != null -> (range[0]!!..range[1]!!).toList()
            else -> listOfNotNull(p.toIntOrNull())
        }
    }.toSet()

    fun parseTime(s: String): LocalTime = runCatching {
        val (h, m) = s.split(':').map { it.trim().toInt() }
        LocalTime.of(h.coerceIn(0, 23), m.coerceIn(0, 59))
    }.getOrDefault(LocalTime.of(8, 0))
}
