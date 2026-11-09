package dev.isaakrichards.tasktemplar.engine

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dev.isaakrichards.tasktemplar.data.MacroStore
import dev.isaakrichards.tasktemplar.model.MacroEvent
import dev.isaakrichards.tasktemplar.model.Step
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Named stopwatches that survive restarts. Value in seconds via {sw=name}. */
object Stopwatches {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("stopwatches", Context.MODE_PRIVATE)

    /** (startedAtMillis or 0 if paused, accumulatedMillis) */
    private fun state(ctx: Context, name: String): Pair<Long, Long> {
        val p = prefs(ctx)
        return p.getLong("$name.start", 0L) to p.getLong("$name.acc", 0L)
    }

    fun elapsedMs(ctx: Context, name: String): Long {
        val (start, acc) = state(ctx, name)
        return acc + if (start > 0) System.currentTimeMillis() - start else 0
    }

    fun isRunning(ctx: Context, name: String) = state(ctx, name).first > 0

    fun op(ctx: Context, name: String, op: String) {
        val (start, acc) = state(ctx, name)
        val now = System.currentTimeMillis()
        val e = prefs(ctx).edit()
        when (op) {
            "start" -> if (start == 0L) e.putLong("$name.start", now)
            "pause" -> if (start > 0) e.putLong("$name.acc", acc + now - start).putLong("$name.start", 0L)
            "reset" -> e.putLong("$name.acc", 0L).putLong("$name.start", if (start > 0) now else 0L)
            "restart" -> e.putLong("$name.acc", 0L).putLong("$name.start", now)
            "stop" -> e.putLong("$name.acc", 0L).putLong("$name.start", 0L)
        }
        e.apply()
    }

    /** When a running stopwatch reaches [seconds], or null if paused / already past. */
    fun fireAt(ctx: Context, name: String, seconds: Long): Long? {
        val (start, acc) = state(ctx, name)
        if (start == 0L) return null
        val at = start + seconds * 1000 - acc
        return at.takeIf { it > System.currentTimeMillis() }
    }
}

/** Calendar events from the phone's own calendar provider (READ_CALENDAR). */
object Calendars {
    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Next event start/end (+ offset) matching the step, as (fireAtMillis, title). */
    fun next(ctx: Context, step: Step): Pair<Long, String>? {
        if (!hasPermission(ctx)) return null
        val now = System.currentTimeMillis()
        val offset = (step.p("offset", "0").toLongOrNull() ?: 0L) * 60_000
        val useEnd = step.p("when", "start") == "end"
        val needle = step.p("title")
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - 86_400_000L)
            ContentUris.appendId(it, now + 14 * 86_400_000L)
        }.build()
        val proj = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY)
        var best: Pair<Long, String>? = null
        runCatching {
            ctx.contentResolver.query(uri, proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(0) ?: ""
                    if (step.p("all_day", "include") == "exclude" && c.getInt(3) == 1) continue
                    if (needle.isNotBlank() && !title.contains(needle, ignoreCase = true)) continue
                    val at = (if (useEnd) c.getLong(2) else c.getLong(1)) + offset
                    if (at > now && (best == null || at < best!!.first)) best = at to title
                }
            }
        }
        return best
    }
}

/** Weather from Open-Meteo (free, no account, no key). */
object Weather {
    private val codes = mapOf(
        0 to "clear", 1 to "mainly clear", 2 to "partly cloudy", 3 to "overcast", 45 to "fog", 48 to "fog",
        51 to "light drizzle", 53 to "drizzle", 55 to "heavy drizzle", 61 to "light rain", 63 to "rain", 65 to "heavy rain",
        66 to "freezing rain", 67 to "freezing rain", 71 to "light snow", 73 to "snow", 75 to "heavy snow", 77 to "snow grains",
        80 to "rain showers", 81 to "rain showers", 82 to "violent rain showers", 85 to "snow showers", 86 to "snow showers",
        95 to "thunderstorm", 96 to "thunderstorm with hail", 99 to "thunderstorm with hail",
    )

    data class Now(val temp: Double, val humidity: Int, val wind: Double, val precip: Double, val code: Int) {
        val description get() = codes[code] ?: "unknown"
        val raining get() = code in 51..67 || code in 80..82 || code >= 95
        val snowing get() = code in 71..77 || code in 85..86
    }

    suspend fun fetch(lat: Double, lng: Double, fahrenheit: Boolean): Now = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lng" +
            "&current=temperature_2m,relative_humidity_2m,precipitation,weather_code,wind_speed_10m" +
            (if (fahrenheit) "&temperature_unit=fahrenheit&wind_speed_unit=mph" else "")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            val cur = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONObject("current")
            Now(
                cur.optDouble("temperature_2m"), cur.optInt("relative_humidity_2m"),
                cur.optDouble("wind_speed_10m"), cur.optDouble("precipitation"), cur.optInt("weather_code"),
            )
        } finally {
            conn.disconnect()
        }
    }

    fun putVars(w: Now, vars: MutableMap<String, String>) {
        vars["temp"] = VarTypes.format(w.temp, VarTypes.DECIMAL)
        vars["humidity"] = w.humidity.toString()
        vars["wind"] = VarTypes.format(w.wind, VarTypes.DECIMAL)
        vars["precip"] = VarTypes.format(w.precip, VarTypes.DECIMAL)
        vars["weather"] = w.description
    }

    fun condition(step: Step, w: Now): Boolean {
        val n = step.p("value", "0").toDoubleOrNull() ?: 0.0
        return when (step.p("condition", "temp_above")) {
            "temp_below" -> w.temp < n
            "rain" -> w.raining
            "snow" -> w.snowing
            "clear" -> w.code <= 1
            "wind_above" -> w.wind > n
            "humidity_above" -> w.humidity > n
            else -> w.temp > n
        }
    }

    /** Called by the alarm for a "Weather" trigger: fires when the condition becomes true. */
    suspend fun check(ctx: Context, macroId: String, idx: Int) {
        val macro = MacroStore.get(macroId) ?: return
        val step = macro.triggers.getOrNull(idx) ?: return
        val (lat, lng) = GeoMath.parse(step.p("location")) ?: Geo.lastKnown(ctx)?.let { it.latitude to it.longitude } ?: return
        val w = runCatching { fetch(lat, lng, step.p("unit", "fahrenheit") == "fahrenheit") }.getOrNull() ?: return
        val prefs = ctx.getSharedPreferences("weather_state", Context.MODE_PRIVATE)
        val key = "$macroId#$idx"
        val now = condition(step, w)
        val was = prefs.getBoolean(key, false)
        prefs.edit().putBoolean(key, now).apply()
        if (now && !was) {
            val data = mutableMapOf("macro" to macroId, "idx" to idx.toString())
            putVars(w, data)
            MacroEngine.dispatch(ctx, MacroEvent("weather", data))
        }
    }
}
