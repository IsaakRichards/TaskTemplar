package dev.isaakrichards.tasktemplar.engine

import android.content.Context
import android.os.PowerManager
import dev.isaakrichards.tasktemplar.data.MacroStore
import dev.isaakrichards.tasktemplar.model.Catalog
import dev.isaakrichards.tasktemplar.model.Macro
import dev.isaakrichards.tasktemplar.model.MacroEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Matches device events against macros and runs the ones whose constraints pass. */
object MacroEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    fun dispatch(ctx: Context, event: MacroEvent) {
        val app = ctx.applicationContext
        MacroStore.init(app)
        for (macro in MacroStore.macros.value) {
            if (!macro.enabled) continue
            val fired = macro.triggers.withIndex().any { (i, t) -> Conditions.triggerMatches(macro, i, t, event) }
            if (!fired) continue
            if (Conditions.constraintsPass(app, macro)) {
                run(app, macro, event)
            } else {
                MacroStore.log(macro.name, "Triggered by ${event.type}, but constraints not met")
            }
        }
    }

    /** Runs a macro immediately, skipping triggers and constraints (the ▶ button). */
    fun runNow(ctx: Context, macro: Macro) = run(ctx.applicationContext, macro, MacroEvent("manual"))

    private fun run(ctx: Context, macro: Macro, event: MacroEvent) {
        scope.launch {
            val wl = ctx.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TaskTemplar:run")
            wl?.acquire(10 * 60 * 1000L)
            try {
                val vars = baseVars(ctx, macro, event)
                var failures = 0
                for (action in macro.actions) {
                    val label = Catalog.spec(action.type)?.label ?: action.type
                    try {
                        val note = Actions.execute(ctx, action, vars)
                        if (note != null) MacroStore.log(macro.name, "$label: $note")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        failures++
                        MacroStore.log(macro.name, "✗ $label failed: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
                val summary = if (failures == 0) "✓ Ran ${macro.actions.size} action(s)" else "Ran with $failures failure(s)"
                MacroStore.log(macro.name, "$summary · trigger: ${event.type}")
            } finally {
                wl?.let { if (it.isHeld) it.release() }
            }
        }
    }

    private fun baseVars(ctx: Context, macro: Macro, event: MacroEvent): MutableMap<String, String> {
        val vars = mutableMapOf(
            "macro" to macro.name,
            "trigger" to event.type,
            "time" to LocalTime.now().format(timeFmt),
            "date" to LocalDate.now().toString(),
            "battery" to DeviceState.batteryLevel(ctx).toString(),
            "ssid" to DeviceState.currentSsid(ctx),
        )
        vars.putAll(event.data)
        return vars
    }
}
