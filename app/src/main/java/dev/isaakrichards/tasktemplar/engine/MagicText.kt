package dev.isaakrichards.tasktemplar.engine

import dev.isaakrichards.tasktemplar.data.GlobalVars

/**
 * Fills placeholders in user text:
 *  - {v=name}  → global variable
 *  - {lv=name} → local variable of this run
 *  - {key}     → any run value (time, battery, trigger data, local variables…)
 */
object MagicText {
    private val varRef = Regex("""\{(v|lv|sw)=([^}\[\]]+)\}""")
    private val itemRef = Regex("""\{(v|lv)=([^}\[\]]+)\[([^\]]*)\]\}""")

    /** Reads an item from an array / dictionary variable; replaced at app start. */
    var item: (String, String) -> String = { _, _ -> "" }

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
        // Second pass: list / dictionary items, now that any {lv=i} inside the brackets is filled in.
        val withItems = itemRef.replace(withVars) { m ->
            val name = m.groupValues[2].trim()
            val json = if (m.groupValues[1] == "v") global(name) else vars[name] ?: ""
            item(json, m.groupValues[3])
        }
        return vars.entries.fold(withItems) { acc, (k, v) -> acc.replace("{$k}", v) }
    }
}
