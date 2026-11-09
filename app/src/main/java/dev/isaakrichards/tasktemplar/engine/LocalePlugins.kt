package dev.isaakrichards.tasktemplar.engine

import android.content.Context
import android.content.Intent
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/**
 * Host for Locale / Tasker "action" plugins (the open com.twofortyfouram.locale plugin API used by
 * AutoTools, AutoInput, Join and many more). A plugin's settings are
 * captured from its own edit screen and fired back to it as a broadcast.
 */
object LocalePlugins {
    const val ACTION_EDIT = "com.twofortyfouram.locale.intent.action.EDIT_SETTING"
    const val ACTION_FIRE = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
    const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
    const val EXTRA_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"

    data class Plugin(val label: String, val pkg: String, val activity: String)

    fun installed(ctx: Context): List<Plugin> {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val found = pm.queryIntentActivities(Intent(ACTION_EDIT), 0)
        return found.map { Plugin(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
            .sortedBy { it.label.lowercase() }
    }

    /** Bundle → JSON with type tags, so it survives saving to macros.json. */
    fun bundleToJson(b: Bundle?): JSONObject {
        val o = JSONObject()
        b ?: return o
        for (k in b.keySet()) {
            @Suppress("DEPRECATION")
            val v = b.get(k)
            val tagged = when (v) {
                is String -> JSONObject().put("t", "s").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Double -> JSONObject().put("t", "d").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is Array<*> -> JSONObject().put("t", "sa").put("v", JSONArray(v.map { it?.toString() }))
                is Bundle -> JSONObject().put("t", "bundle").put("v", bundleToJson(v))
                else -> null
            }
            if (tagged != null) o.put(k, tagged)
        }
        return o
    }

    fun jsonToBundle(o: JSONObject, vars: Map<String, String>): Bundle {
        val b = Bundle()
        for (k in o.keys()) {
            val e = o.optJSONObject(k) ?: continue
            when (e.optString("t")) {
                // Strings get magic text, so plugins can receive {lv=...} values.
                "s" -> b.putString(k, MagicText.apply(e.optString("v"), vars))
                "i" -> b.putInt(k, e.optInt("v"))
                "l" -> b.putLong(k, e.optLong("v"))
                "b" -> b.putBoolean(k, e.optBoolean("v"))
                "d" -> b.putDouble(k, e.optDouble("v"))
                "f" -> b.putFloat(k, e.optDouble("v").toFloat())
                "sa" -> e.optJSONArray("v")?.let { a -> b.putStringArray(k, Array(a.length()) { a.optString(it) }) }
                "bundle" -> e.optJSONObject("v")?.let { b.putBundle(k, jsonToBundle(it, vars)) }
            }
        }
        return b
    }

    fun fire(ctx: Context, config: String, vars: Map<String, String>): String? {
        val c = runCatching { JSONObject(config) }.getOrElse { error("Plugin isn't set up yet: edit the action and choose a plugin") }
        val pkg = c.optString("pkg").ifBlank { error("No plugin chosen") }
        val bundle = jsonToBundle(c.optJSONObject("bundle") ?: JSONObject(), vars)
        ctx.sendBroadcast(Intent(ACTION_FIRE).setPackage(pkg).putExtra(EXTRA_BUNDLE, bundle))
        return c.optString("blurb").takeIf { it.isNotBlank() }
    }

    fun describe(config: String): String = runCatching {
        val c = JSONObject(config)
        c.optString("label") + c.optString("blurb").let { if (it.isBlank()) "" else ": $it" }
    }.getOrDefault("(not set up)")
}
