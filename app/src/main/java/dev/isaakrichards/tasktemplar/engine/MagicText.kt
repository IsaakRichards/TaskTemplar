package dev.isaakrichards.tasktemplar.engine

import dev.isaakrichards.tasktemplar.data.GlobalVars

/**
 * Fills placeholders in user text:
 *  - {v=name}  → global variable
 *  - {lv=name} → local variable of this run
 *  - {key}     → any run value (time, battery, trigger data, local variables…)
 */
object MagicText {
    private val varRef = Regex("""\{(v|lv|sw)=([^}]+)\}""")

    /** Seconds on a named stopwatch, for {sw=name}. */
    var stopwatch: (String) -> String = { "0" }

    /** Lookup for global variables; swapped out in unit tests. */
    var global: (String) -> String = { GlobalVars.valueOf(it) }

    fun apply(text: String, vars: Map<String, String>): String {
        if (!text.contains('{')) return text
        val withVars = varRef.replace(text) { m ->
            val name = m.groupValues[2].trim()
            when (m.groupValues[1]) {
                "v" -> global(name)
                "sw" -> stopwatch(name)
                else -> vars[name] ?: ""
            }
        }
        return vars.entries.fold(withVars) { acc, (k, v) -> acc.replace("{$k}", v) }
    }
}
