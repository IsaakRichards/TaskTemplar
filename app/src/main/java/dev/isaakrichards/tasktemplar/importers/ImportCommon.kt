package dev.isaakrichards.tasktemplar.importers

import dev.isaakrichards.tasktemplar.model.Macro
import dev.isaakrichards.tasktemplar.model.Step

/** What an importer produced, plus human-readable notes about anything it couldn't convert exactly. */
data class ImportResult(
    val source: String,
    val macros: List<Macro>,
    val notes: List<String>,
)

/** Public entry point used by the UI. */
object ImportCommon {
    fun resolve(imported: List<Macro>, existing: List<Macro>, notes: MutableList<String>): List<Macro> =
        Imp.resolveRefs(imported, existing, notes)
}

/** Shared helpers for the MacroDroid and Tasker converters. Pure Kotlin so it can be unit-tested. */
internal object Imp {

    fun s(type: String, vararg p: Pair<String, String>) = Step(type, p.toMap())

    fun cond(type: String, conditions: List<Step>, any: Boolean = false, vararg p: Pair<String, String>) =
        Step(type, p.toMap(), conditions, if (any) Macro.MODE_ANY else Macro.MODE_ALL)

    /** Placeholder kept in the action list where something couldn't be converted, so its position isn't lost. */
    fun unsupported(app: String, what: String) =
        s("log", "text" to "⚠ Not converted from $app: $what. Recreate this step by hand.")

    /** Marks a macro reference to be resolved by name after import. */
    fun macroRef(name: String) = "name:$name"

    /** ISO day numbers (1 = Monday) from a Monday-first boolean list. */
    fun daysFromMondayFirst(flags: List<Boolean>): String =
        flags.mapIndexedNotNull { i, on -> if (on && i < 7) i + 1 else null }.joinToString(",")

    fun hhmm(h: Int, m: Int) = "%02d:%02d".format(h.coerceIn(0, 23), m.coerceIn(0, 59))

    fun onOffToggle(state: Int) = when (state) {
        0 -> "on"
        1 -> "off"
        else -> "toggle"
    }

    /** Wraps a list of actions in If … End If. */
    fun guarded(conditions: List<Step>, any: Boolean, body: List<Step>): List<Step> =
        if (conditions.isEmpty()) body else listOf(cond("if", conditions, any)) + body + s("end_if")

    /**
     * After importing, turn "name:Macro name" references (Run macro, Enable macro, Macro enabled)
     * into real macro ids. [existing] are macros already in the app.
     */
    fun resolveRefs(imported: List<Macro>, existing: List<Macro>, notes: MutableList<String>): List<Macro> {
        val byName = (existing + imported).associateBy { it.name.trim().lowercase() }
        fun fix(step: Step): Step {
            val ref = step.params["macro"]
            val newParams = if (ref != null && ref.startsWith("name:")) {
                val name = ref.removePrefix("name:")
                val target = byName[name.trim().lowercase()]
                if (target == null) notes += "Couldn't find a macro called “$name” for a ${step.type.replace('_', ' ')} step"
                step.params + ("macro" to (target?.id ?: ""))
            } else {
                step.params
            }
            return step.copy(params = newParams, conditions = step.conditions.map { fix(it) })
        }
        return imported.map { m ->
            m.copy(
                triggers = m.triggers.map { fix(it) },
                constraints = m.constraints.map { fix(it) },
                actions = m.actions.map { fix(it) },
            )
        }
    }
}
