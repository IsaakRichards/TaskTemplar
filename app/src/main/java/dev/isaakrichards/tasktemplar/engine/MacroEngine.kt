package dev.isaakrichards.tasktemplar.engine

import android.content.Context
import android.os.PowerManager
import dev.isaakrichards.tasktemplar.data.GlobalVars
import dev.isaakrichards.tasktemplar.data.MacroStore
import dev.isaakrichards.tasktemplar.model.Catalog
import dev.isaakrichards.tasktemplar.model.Macro
import dev.isaakrichards.tasktemplar.model.MacroEvent
import dev.isaakrichards.tasktemplar.model.Step
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** Matches device events against macros and runs the ones whose constraints pass. */
object MacroEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val running = ConcurrentHashMap<String, MutableSet<Job>>()

    fun dispatch(ctx: Context, event: MacroEvent) {
        val app = ctx.applicationContext
        MacroStore.init(app)
        for (macro in MacroStore.macros.value) {
            if (!macro.enabled) continue
            val fired = macro.triggers.withIndex().any { (i, t) -> Conditions.triggerMatches(macro, i, t, event) }
            if (!fired) continue
            if (Conditions.constraintsPass(app, macro)) {
                start(app, macro, event)
            } else {
                MacroStore.log(macro.name, "Triggered by ${event.type}, but constraints not met")
            }
        }
    }

    /** Runs a macro immediately, skipping triggers and constraints (the ▶ button). */
    fun runNow(ctx: Context, macro: Macro) = start(ctx.applicationContext, macro, MacroEvent("manual"))

    fun isRunning(macroId: String): Boolean = running[macroId]?.any { it.isActive } == true

    /** Cancels running copies of [macroId], optionally sparing [except]. Returns how many were stopped. */
    fun cancel(macroId: String, except: Job? = null): Int {
        val jobs = running[macroId]?.toList().orEmpty().filter { it != except && it.isActive }
        jobs.forEach { it.cancel() }
        return jobs.size
    }

    fun start(ctx: Context, macro: Macro, event: MacroEvent): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) { runMacro(ctx, macro, event) }
        running.getOrPut(macro.id) { ConcurrentHashMap.newKeySet() }.add(job)
        job.invokeOnCompletion { running[macro.id]?.remove(job) }
        job.start()
        return job
    }

    private suspend fun runMacro(ctx: Context, macro: Macro, event: MacroEvent) {
        val wl = ctx.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TaskTemplar:run")
        wl?.acquire(10 * 60 * 1000L)
        var executed = 0
        var failures = 0
        try {
            val vars = baseVars(ctx, macro, event)
            val flow = Flow(macro.actions)
            val result = try {
                flow.run(
                cond = { s -> Conditions.allPass(ctx, s.conditions, s.conditionMode, vars) },
                loopCount = { s -> MagicText.apply(s.p("count", "3"), vars).trim().toDoubleOrNull()?.toInt() ?: 0 },
                onPass = { pass -> vars["loop_index"] = pass.toString() },
            ) { action ->
                val label = Catalog.spec(action.type)?.label ?: action.type
                try {
                    val note = execute(ctx, macro, action, vars)
                    executed++
                    if (note != null) MacroStore.log(macro.name, "$label: $note")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: StopMacroException) {
                    throw e
                } catch (e: Throwable) {
                    failures++
                    MacroStore.log(macro.name, "✗ $label failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            } catch (e: StopMacroException) {
                MacroStore.log(macro.name, e.message ?: "Stopped")
                Flow.Result.STOPPED
            }
            val ending = when (result) {
                Flow.Result.STOPPED -> " (stopped)"
                Flow.Result.STEP_LIMIT -> " — ✗ halted: too many steps, is a loop missing a Wait or Break?"
                Flow.Result.DONE -> ""
            }
            val summary = if (failures == 0) "✓ Ran $executed action(s)" else "Ran $executed action(s), $failures failed"
            MacroStore.log(macro.name, "$summary$ending · trigger: ${event.type}")
        } catch (e: CancellationException) {
            MacroStore.log(macro.name, "Cancelled after $executed action(s)")
            throw e
        } finally {
            wl?.let { if (it.isHeld) it.release() }
        }
    }

    /** Actions that need the engine itself (variables, macro control); everything else goes to [Actions]. */
    private suspend fun execute(ctx: Context, macro: Macro, step: Step, vars: MutableMap<String, String>): String? {
        fun t(key: String, def: String = "") = MagicText.apply(step.p(key, def), vars)

        return when (step.type) {
            "set_variable" -> setVariable(step, vars)

            "text_op" -> {
                val input = t("input")
                val a = MagicText.apply(step.params["a"] ?: "", vars)
                val b = MagicText.apply(step.params["b"] ?: "", vars)
                val result = when (step.p("op", "upper")) {
                    "lower" -> input.lowercase()
                    "trim" -> input.trim()
                    "length" -> input.length.toString()
                    "replace" -> if (a.isEmpty()) input else input.replace(a, b)
                    "substring" -> {
                        val start = (a.trim().toIntOrNull() ?: 0).coerceIn(0, input.length)
                        val len = b.trim().toIntOrNull() ?: (input.length - start)
                        input.substring(start, (start + len).coerceIn(start, input.length))
                    }
                    "regex" -> {
                        val group = b.trim().toIntOrNull() ?: 0
                        Regex(a).find(input)?.groupValues?.getOrNull(group) ?: ""
                    }
                    "url_encode" -> URLEncoder.encode(input, "UTF-8")
                    else -> input.uppercase()
                }
                assign(step, vars, VarTypes.STRING, result)
                null
            }

            "dialog_message" -> {
                Dialogs.ask(ctx, "message", t("title"), t("message"), timeoutSec = t("timeout", "120").toLongOrNull() ?: 120)
                null
            }

            "dialog_options" -> {
                val options = t("options").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (options.isEmpty()) error("No options given")
                val choice = Dialogs.ask(ctx, "options", t("title"), t("message"), options, t("timeout", "120").toLongOrNull() ?: 120)
                vars["option"] = choice ?: ""
                vars["option_index"] = (choice?.let { options.indexOf(it) + 1 } ?: 0).toString()
                if (choice == null && step.p("on_cancel", "continue") == "stop") throw StopMacroException("Stopped: no option chosen")
                choice?.let { "chose “$it”" } ?: "dismissed"
            }

            "confirm" -> {
                val answer = Dialogs.ask(ctx, "confirm", t("title", "Continue?"), t("message"), timeoutSec = t("timeout", "120").toLongOrNull() ?: 120)
                val yes = answer == "yes"
                vars["confirmed"] = yes.toString()
                if (!yes && step.p("on_no", "stop") == "stop") throw StopMacroException("Stopped: not confirmed")
                if (yes) "yes" else "no"
            }

            "input_dialog" -> {
                val text = Dialogs.ask(ctx, "input", t("title"), t("message"), timeoutSec = t("timeout", "120").toLongOrNull() ?: 120)
                if (text == null && step.p("on_cancel", "continue") == "stop") throw StopMacroException("Stopped: input cancelled")
                assign(step, vars, VarTypes.STRING, text ?: "")
                null
            }

            "send_intent" -> {
                val intent = android.content.Intent()
                t("action").takeIf { it.isNotBlank() }?.let { intent.action = it }
                t("data").takeIf { it.isNotBlank() }?.let { intent.data = android.net.Uri.parse(it) }
                t("package").takeIf { it.isNotBlank() }?.let { intent.setPackage(it) }
                t("extras").split(';').map { it.trim() }.filter { '=' in it }.forEach {
                    intent.putExtra(it.substringBefore('=').trim(), it.substringAfter('=').trim())
                }
                when (step.p("target", "activity")) {
                    "broadcast" -> ctx.sendBroadcast(intent)
                    "service" -> ctx.startService(intent)
                    else -> ctx.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                null
            }

            "log" -> {
                MacroStore.log(macro.name, t("text"))
                null
            }

            "run_macro" -> {
                val target = MacroStore.get(step.p("macro")) ?: error("Macro not found")
                if (step.p("check", "ignore") == "check" && !Conditions.constraintsPass(ctx, target)) {
                    "skipped “${target.name}” (constraints not met)"
                } else {
                    start(ctx, target, MacroEvent("macro", mapOf("from" to macro.name)))
                    null
                }
            }

            "macro_enable" -> {
                val target = MacroStore.get(step.p("macro")) ?: error("Macro not found")
                val on = when (step.p("state", "toggle")) {
                    "enable" -> true
                    "disable" -> false
                    else -> !target.enabled
                }
                MacroStore.setEnabled(target.id, on)
                "“${target.name}” ${if (on) "enabled" else "disabled"}"
            }

            "cancel_macro" -> {
                val id = step.p("macro").ifBlank { macro.id }
                val stopped = cancel(id, except = currentCoroutineContext()[Job])
                "stopped $stopped run(s)"
            }

            "ui_click", "ui_type", "ui_scroll", "ui_swipe", "global_action", "read_screen" ->
                UiActions.execute(step, vars)

            "make_call", "answer_call", "end_call", "speakerphone", "send_sms", "send_email", "open_call_log", "get_location" ->
                PhoneActions.execute(ctx, step, vars)

            "shell", "wifi_set", "bluetooth_set", "mobile_data_set", "airplane_set", "auto_rotate", "screen_timeout",
            "keep_awake", "screen_on", "media_control", "volume_step", "play_sound", "stop_sound", "set_alarm",
            "car_mode", "set_wallpaper" ->
                DeviceActions.execute(ctx, step, vars)

            else -> Actions.execute(ctx, step, vars)
        }
    }

    private fun setVariable(step: Step, vars: MutableMap<String, String>): String? {
        val name = MagicText.apply(step.p("name"), vars).trim()
        if (name.isEmpty()) error("No variable name")
        val type = step.p("type", VarTypes.INTEGER)
        val raw = MagicText.apply(step.params["value"] ?: "", vars)
        val current = if (step.p("scope", "global") == "local") vars[name] ?: "" else GlobalVars.valueOf(name)
        fun num(s: String) = VarTypes.eval(s) ?: 0.0
        val next = when (step.p("op", "set")) {
            "increment" -> VarTypes.format(num(current) + (VarTypes.eval(raw) ?: 1.0), type)
            "decrement" -> VarTypes.format(num(current) - (VarTypes.eval(raw) ?: 1.0), type)
            "toggle" -> (!VarTypes.truthy(current)).toString()
            "append" -> current + raw
            else -> when (type) {
                VarTypes.INTEGER, VarTypes.DECIMAL ->
                    VarTypes.eval(raw)?.let { VarTypes.format(it, type) } ?: error("“$raw” isn't a number")
                else -> raw
            }
        }
        assign(step, vars, type, next)
        return null
    }

    private fun assign(step: Step, vars: MutableMap<String, String>, type: String, value: String) {
        val name = MagicText.apply(step.p("name", "result"), vars).trim()
        if (step.p("scope", "global") == "local") vars[name] = VarTypes.normalize(type, value)
        else GlobalVars.set(name, type, value)
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
