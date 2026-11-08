package dev.isaakrichards.tasktemplar.data

import android.content.Context
import dev.isaakrichards.tasktemplar.engine.VarTypes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Variable(val name: String, val type: String, val value: String)

/** Global variables shared by every macro, stored on the phone in variables.json. */
object GlobalVars {

    private lateinit var file: File
    private val _vars = MutableStateFlow<Map<String, Variable>>(emptyMap())
    val vars: StateFlow<Map<String, Variable>> = _vars.asStateFlow()

    /** Called after a variable's value changes (wired to the "Variable changed" trigger). */
    @Volatile
    var onChanged: ((Variable) -> Unit)? = null

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.applicationContext.filesDir, "variables.json")
        _vars.value = runCatching {
            if (!file.exists()) emptyMap() else {
                val arr = JSONArray(file.readText())
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                    .map { Variable(it.optString("name"), it.optString("type", VarTypes.STRING), it.optString("value")) }
                    .filter { it.name.isNotBlank() }
                    .associateBy { it.name }
            }
        }.getOrDefault(emptyMap())
    }

    fun get(name: String): Variable? = _vars.value[name]

    fun valueOf(name: String): String = get(name)?.value ?: ""

    /** Sets a variable (creating it if needed). Fires [onChanged] when the value actually changed. */
    fun set(name: String, type: String, rawValue: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val v = Variable(clean, type, VarTypes.normalize(type, rawValue))
        val changed: Boolean
        synchronized(this) {
            val old = _vars.value[clean]
            changed = old == null || old.value != v.value || old.type != v.type
            if (!changed) return
            _vars.value = _vars.value + (clean to v)
            save()
        }
        onChanged?.invoke(v)
    }

    @Synchronized
    fun delete(name: String) {
        _vars.value = _vars.value - name
        save()
    }

    private fun save() {
        val arr = JSONArray()
        _vars.value.values.sortedBy { it.name.lowercase() }.forEach {
            arr.put(JSONObject().put("name", it.name).put("type", it.type).put("value", it.value))
        }
        file.writeText(arr.toString())
    }
}
