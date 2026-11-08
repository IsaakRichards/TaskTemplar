package dev.isaakrichards.tasktemplar.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One trigger, constraint or action: a type id from [Catalog] plus its settings. */
data class Step(val type: String, val params: Map<String, String> = emptyMap()) {

    /** Returns the param, or [default] when it is missing or blank. */
    fun p(key: String, default: String = ""): String =
        params[key]?.takeIf { it.isNotBlank() } ?: default

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("params", JSONObject().apply { params.forEach { (k, v) -> put(k, v) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Step {
            val p = o.optJSONObject("params") ?: JSONObject()
            val map = mutableMapOf<String, String>()
            p.keys().forEach { k -> map[k] = p.optString(k) }
            return Step(o.getString("type"), map)
        }
    }
}

data class Macro(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New macro",
    val enabled: Boolean = true,
    val triggers: List<Step> = emptyList(),
    val constraints: List<Step> = emptyList(),
    val constraintMode: String = MODE_ALL,
    val actions: List<Step> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        put("constraintMode", constraintMode)
        put("triggers", JSONArray().apply { triggers.forEach { put(it.toJson()) } })
        put("constraints", JSONArray().apply { constraints.forEach { put(it.toJson()) } })
        put("actions", JSONArray().apply { actions.forEach { put(it.toJson()) } })
    }

    companion object {
        const val MODE_ALL = "all"
        const val MODE_ANY = "any"

        private fun JSONArray?.steps(): List<Step> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { i ->
                optJSONObject(i)?.let { runCatching { Step.fromJson(it) }.getOrNull() }
            }
        }

        fun fromJson(o: JSONObject): Macro = Macro(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name", "Macro"),
            enabled = o.optBoolean("enabled", true),
            constraintMode = o.optString("constraintMode", MODE_ALL),
            triggers = o.optJSONArray("triggers").steps(),
            constraints = o.optJSONArray("constraints").steps(),
            actions = o.optJSONArray("actions").steps(),
        )
    }
}

/** Something that happened on the device, handed to the engine for matching. */
data class MacroEvent(val type: String, val data: Map<String, String> = emptyMap())

data class LogEntry(val time: Long, val macro: String, val message: String) {
    fun toJson(): JSONObject = JSONObject().put("t", time).put("m", macro).put("x", message)

    companion object {
        fun fromJson(o: JSONObject) = LogEntry(o.optLong("t"), o.optString("m"), o.optString("x"))
    }
}
