package dev.isaakrichards.tasktemplar.data

import android.content.Context
import dev.isaakrichards.tasktemplar.engine.AlarmScheduler
import dev.isaakrichards.tasktemplar.model.LogEntry
import dev.isaakrichards.tasktemplar.model.Macro
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File

/** Macros and the run log, kept as small JSON files in app storage. Nothing leaves the phone. */
object MacroStore {
    private const val MAX_LOG = 300

    private lateinit var appContext: Context
    private lateinit var macroFile: File
    private lateinit var logFile: File

    private val _macros = MutableStateFlow<List<Macro>>(emptyList())
    val macros: StateFlow<List<Macro>> = _macros.asStateFlow()

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        macroFile = File(appContext.filesDir, "macros.json")
        logFile = File(appContext.filesDir, "log.json")
        _macros.value = readArray(macroFile).mapNotNull { runCatching { Macro.fromJson(it) }.getOrNull() }
        _log.value = readArray(logFile).map { LogEntry.fromJson(it) }
    }

    fun get(id: String): Macro? = _macros.value.firstOrNull { it.id == id }

    @Synchronized
    fun upsert(macro: Macro) {
        val list = _macros.value.toMutableList()
        val i = list.indexOfFirst { it.id == macro.id }
        if (i >= 0) list[i] = macro else list.add(macro)
        commit(list)
    }

    @Synchronized
    fun delete(id: String) = commit(_macros.value.filterNot { it.id == id })

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) =
        commit(_macros.value.map { if (it.id == id) it.copy(enabled = enabled) else it })

    /** Replaces everything (used by import). */
    @Synchronized
    fun replaceAll(list: List<Macro>) = commit(list)

    @Synchronized
    fun log(macro: String, message: String) {
        val next = (_log.value + LogEntry(System.currentTimeMillis(), macro, message)).takeLast(MAX_LOG)
        _log.value = next
        writeArray(logFile, JSONArray().apply { next.forEach { put(it.toJson()) } })
    }

    @Synchronized
    fun clearLog() {
        _log.value = emptyList()
        writeArray(logFile, JSONArray())
    }

    fun exportJson(): String =
        JSONArray().apply { _macros.value.forEach { put(it.toJson()) } }.toString(2)

    /** Parses exported JSON; returns the macros with fresh ids so imports never clobber existing ones. */
    fun parseImport(json: String): List<Macro> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            Macro.fromJson(arr.getJSONObject(i)).copy(id = java.util.UUID.randomUUID().toString())
        }
    }

    private fun commit(list: List<Macro>) {
        _macros.value = list
        writeArray(macroFile, JSONArray().apply { list.forEach { put(it.toJson()) } })
        AlarmScheduler.rescheduleAll(appContext)
    }

    private fun readArray(file: File): List<org.json.JSONObject> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }.getOrDefault(emptyList())
    }

    private fun writeArray(file: File, arr: JSONArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString())
            tmp.delete()
        }
    }
}
