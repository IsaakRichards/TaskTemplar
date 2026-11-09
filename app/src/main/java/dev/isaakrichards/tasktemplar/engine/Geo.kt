package dev.isaakrichards.tasktemplar.engine

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.isaakrichards.tasktemplar.data.MacroStore
import dev.isaakrichards.tasktemplar.model.MacroEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Location without Google Play Services: uses Android's own LocationManager, so geofences
 * and "where am I" work on de-Googled phones too.
 */
object Geo {

    private const val PREFS = "geofences"
    private const val KEY = "codes"
    private const val ACTION_PREFIX = "dev.isaakrichards.tasktemplar.GEOFENCE."

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Most recent fix any provider has, without waiting. */
    @SuppressLint("MissingPermission")
    fun lastKnown(ctx: Context): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /** A fresh fix (waits up to [timeoutMs]), falling back to the last known one. */
    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context, timeoutMs: Long = 30_000): Location? {
        if (!hasPermission(ctx)) error("Location permission not granted")
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> error("Location is turned off")
        }
        val fresh = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Location?> { cont ->
                    if (Build.VERSION.SDK_INT >= 30) {
                        lm.getCurrentLocation(provider, null, ctx.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
                    } else {
                        val listener = object : LocationListener {
                            override fun onLocationChanged(location: Location) {
                                if (cont.isActive) cont.resume(location)
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                            override fun onProviderEnabled(provider: String) {}
                            override fun onProviderDisabled(provider: String) {}
                        }
                        @Suppress("DEPRECATION")
                        lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                        cont.invokeOnCancellation { lm.removeUpdates(listener) }
                    }
                }
            }
        }
        return fresh ?: lastKnown(ctx)
    }

    /** Re-registers a proximity alert for every enabled "Location (geofence)" trigger. */
    @SuppressLint("MissingPermission")
    fun refreshFences(ctx: Context) {
        val app = ctx.applicationContext
        val lm = app.getSystemService(LocationManager::class.java) ?: return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet(KEY, emptySet())?.forEach { code ->
            runCatching { lm.removeProximityAlert(pending(app, code.toInt(), "", -1)) }
        }
        val codes = mutableSetOf<String>()
        if (hasPermission(app)) {
            for (macro in MacroStore.macros.value) {
                if (!macro.enabled) continue
                macro.triggers.forEachIndexed { i, t ->
                    if (t.type != "geofence") return@forEachIndexed
                    val (lat, lng) = GeoMath.parse(t.p("location")) ?: return@forEachIndexed
                    val radius = (t.p("radius", "150").toFloatOrNull() ?: 150f).coerceAtLeast(30f)
                    val code = (macro.id + "#geo" + i).hashCode()
                    runCatching {
                        lm.addProximityAlert(lat, lng, radius, -1, pending(app, code, macro.id, i))
                        codes += code.toString()
                    }.onFailure { MacroStore.log(macro.name, "✗ Couldn't register geofence: ${it.message}") }
                }
            }
        }
        prefs.edit().putStringSet(KEY, codes).apply()
    }

    private fun pending(ctx: Context, code: Int, macroId: String, index: Int): PendingIntent {
        val intent = Intent(ctx, GeofenceReceiver::class.java)
            .setAction(ACTION_PREFIX + code)
            .putExtra("macro", macroId)
            .putExtra("idx", index)
        // Must be mutable: the system adds the entering/exiting flag to it.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, code, intent, flags)
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val macroId = intent.getStringExtra("macro") ?: return
        val idx = intent.getIntExtra("idx", -1)
        if (idx < 0 || !intent.hasExtra(LocationManager.KEY_PROXIMITY_ENTERING)) return
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
        MacroStore.init(context)
        MacroEngine.dispatch(
            context,
            MacroEvent(
                "geofence",
                mapOf("macro" to macroId, "idx" to idx.toString(), "state" to if (entering) "enter" else "exit"),
            ),
        )
    }
}
