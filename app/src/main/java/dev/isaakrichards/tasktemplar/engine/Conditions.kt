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

    /** Does trigger number [index] of [macro] match this [event]? */
    fun triggerMatches(macro: Macro, index: Int, step: Step, event: MacroEvent): Boolean {
        val d = event.data
        return when (step.type) {
            "time", "interval" ->
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

        "time_window" -> {
            val now = LocalTime.now()
            val from = parseTime(step.p("from", "22:00"))
            val to = parseTime(step.p("to", "07:00"))
            if (!from.isAfter(to)) !now.isBefore(from) && now.isBefore(to)
            else !now.isBefore(from) || now.isBefore(to)
        }

        "days" -> LocalDate.now().dayOfWeek.value in Catalog.parseDays(step.p("days", Catalog.ALL_DAYS))

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

    fun parseTime(s: String): LocalTime = runCatching {
        val (h, m) = s.split(':').map { it.trim().toInt() }
        LocalTime.of(h.coerceIn(0, 23), m.coerceIn(0, 59))
    }.getOrDefault(LocalTime.of(8, 0))
}
