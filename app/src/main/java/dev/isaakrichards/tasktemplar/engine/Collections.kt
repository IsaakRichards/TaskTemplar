package dev.isaakrichards.tasktemplar.engine

import dev.isaakrichards.tasktemplar.data.GlobalVars
import dev.isaakrichards.tasktemplar.model.Step
import org.json.JSONArray
import org.json.JSONObject

/**
 * Array and dictionary variables, stored as JSON text. Read items with magic text:
 * {v=list[0]}, {lv=prices[apple]}, {v=list[#]} for the count.
 */
object Collections {

    fun read(json: String, index: String): String {
        val i = index.trim()
        val t = json.trim()
        return runCatching {
            when {
                t.startsWith("[") -> {
                    val a = JSONArray(t)
                    when {
                        i == "#" -> a.length().toString()
                        i == "last" -> if (a.length() == 0) "" else a.opt(a.length() - 1)?.toString() ?: ""
                        else -> i.toIntOrNull()?.let { if (it in 0 until a.length()) a.opt(it)?.toString() else null } ?: ""
                    }
                }
                t.startsWith("{") -> {
                    val o = JSONObject(t)
                    when (i) {
                        "#" -> o.length().toString()
                        "keys" -> o.keys().asSequence().joinToString(",")
                        else -> o.opt(i)?.toString() ?: ""
                    }
                }
                else -> ""
            }
        }.getOrDefault("")
    }

    private fun current(step: Step, vars: Map<String, String>, name: String): String =
        if (step.p("scope", "global") == "local") vars[name] ?: "" else GlobalVars.valueOf(name)

    private fun store(step: Step, vars: MutableMap<String, String>, name: String, type: String, json: String) {
        if (step.p("scope", "global") == "local") vars[name] = json else GlobalVars.set(name, type, json)
    }

    fun listOp(step: Step, vars: MutableMap<String, String>): String? {
        val name = MagicText.apply(step.p("name"), vars).trim().ifEmpty { error("No variable name") }
        val value = MagicText.apply(step.params["value"] ?: "", vars)
        val index = MagicText.apply(step.params["index"] ?: "", vars).trim().toIntOrNull()
        val arr = runCatching { JSONArray(current(step, vars, name).ifBlank { "[]" }) }.getOrDefault(JSONArray())
        val items = (0 until arr.length()).map { arr.opt(it)?.toString() ?: "" }.toMutableList()
        var note: String? = null
        when (step.p("op", "add")) {
            "add" -> items.add(value)
            "insert" -> items.add((index ?: 0).coerceIn(0, items.size), value)
            "set" -> { val i = index ?: error("Index needed"); if (i in items.indices) items[i] = value else error("Index $i out of range") }
            "remove_at" -> { val i = index ?: error("Index needed"); if (i in items.indices) items.removeAt(i) }
            "remove_value" -> items.remove(value)
            "clear" -> items.clear()
            "sort" -> items.sortWith(compareBy<String> { it.toDoubleOrNull() ?: Double.MAX_VALUE }.thenBy { it.lowercase() })
            "reverse" -> items.reverse()
            "split" -> { items.clear(); items.addAll(value.split(step.p("separator", ",")).map { it.trim() }) }
            "join" -> {
                val joined = items.joinToString(step.p("separator", ", "))
                vars["joined"] = joined
                note = "joined ${items.size}"
            }
        }
        store(step, vars, name, "array", JSONArray(items).toString())
        return note
    }

    fun dictOp(step: Step, vars: MutableMap<String, String>): String? {
        val name = MagicText.apply(step.p("name"), vars).trim().ifEmpty { error("No variable name") }
        val key = MagicText.apply(step.params["key"] ?: "", vars)
        val value = MagicText.apply(step.params["value"] ?: "", vars)
        val obj = runCatching { JSONObject(current(step, vars, name).ifBlank { "{}" }) }.getOrDefault(JSONObject())
        when (step.p("op", "put")) {
            "put" -> obj.put(key.ifEmpty { error("Key needed") }, value)
            "remove" -> obj.remove(key)
            "clear" -> obj.keys().asSequence().toList().forEach { obj.remove(it) }
            "from_json" -> {
                val parsed = runCatching { JSONObject(value) }.getOrElse { error("That isn't a JSON object") }
                parsed.keys().forEach { k -> obj.put(k, parsed.opt(k)) }
            }
        }
        store(step, vars, name, "dict", obj.toString())
        return null
    }
}
