package dev.isaakrichards.tasktemplar.data

import android.content.Context
import org.json.JSONObject

/** Saved places (name → "lat,lng") reused by geofence, location, sun and weather settings. */
object Places {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("places", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Pair<String, String>> = runCatching {
        val o = JSONObject(prefs(ctx).getString("all", "{}") ?: "{}")
        o.keys().asSequence().map { it to o.getString(it) }.sortedBy { it.first.lowercase() }.toList()
    }.getOrDefault(emptyList())

    fun save(ctx: Context, name: String, latLng: String) {
        val o = runCatching { JSONObject(prefs(ctx).getString("all", "{}") ?: "{}") }.getOrDefault(JSONObject())
        o.put(name.trim(), latLng.trim())
        prefs(ctx).edit().putString("all", o.toString()).apply()
    }

    fun delete(ctx: Context, name: String) {
        val o = runCatching { JSONObject(prefs(ctx).getString("all", "{}") ?: "{}") }.getOrDefault(JSONObject())
        o.remove(name)
        prefs(ctx).edit().putString("all", o.toString()).apply()
    }
}
