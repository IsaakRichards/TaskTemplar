package dev.isaakrichards.tasktemplar.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.OrientationEventListener
import dev.isaakrichards.tasktemplar.model.MacroEvent
import java.util.Locale
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Shake, flip, proximity and orientation detection. Each sensor is only switched on while an
 * enabled macro actually uses it, to save battery.
 */
class SensorWatch(private val ctx: Context, private val emit: (MacroEvent) -> Unit) : SensorEventListener {

    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val accelSensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val proxSensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val lightSensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_LIGHT)
    private var accelOn = false
    private var proxOn = false
    private var lightOn = false
    private var lastLux = Double.NaN
    private var orientation: OrientationEventListener? = null

    private var lastShake = 0L
    private var faceDown: Boolean? = null
    private var flipCandidate: Boolean? = null
    private var flipSince = 0L
    private var lastProx: String? = null
    private var lastOrient: String? = null

    /** [needs] = trigger types used by enabled macros. */
    fun update(needs: Set<String>) {
        val wantAccel = "shake" in needs || "flip" in needs
        if (wantAccel != accelOn && accelSensor != null && sm != null) {
            if (wantAccel) sm.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
            else sm.unregisterListener(this, accelSensor)
            accelOn = wantAccel
            if (!wantAccel) faceDown = null
        }
        val wantProx = "proximity" in needs || "proximity_state" in needs
        if (wantProx != proxOn && proxSensor != null && sm != null) {
            if (wantProx) sm.registerListener(this, proxSensor, SensorManager.SENSOR_DELAY_NORMAL)
            else sm.unregisterListener(this, proxSensor)
            proxOn = wantProx
            if (!wantProx) {
                lastProx = null
                latest.remove("near")
            }
        }
        val wantLight = "light" in needs || "light_level" in needs
        if (wantLight != lightOn && lightSensor != null && sm != null) {
            if (wantLight) sm.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
            else sm.unregisterListener(this, lightSensor)
            lightOn = wantLight
            if (!wantLight) {
                lastLux = Double.NaN
                latest.remove("lux")
            }
        }
        val wantOrient = "orientation" in needs
        if (wantOrient && orientation == null) {
            orientation = object : OrientationEventListener(ctx) {
                override fun onOrientationChanged(deg: Int) {
                    if (deg == ORIENTATION_UNKNOWN) return
                    val o = when {
                        deg in 65..115 || deg in 245..295 -> "landscape"
                        deg <= 25 || deg >= 335 || deg in 155..205 -> "portrait"
                        else -> return
                    }
                    if (o != lastOrient) {
                        val first = lastOrient == null
                        lastOrient = o
                        if (!first) emit(MacroEvent("orientation", mapOf("state" to o)))
                    }
                }
            }.also { if (it.canDetectOrientation()) it.enable() }
        } else if (!wantOrient && orientation != null) {
            orientation?.disable()
            orientation = null
            lastOrient = null
        }
    }

    fun stop() = update(emptySet())

    companion object {
        /** Latest readings for constraints: "lux", "near" (1/0). */
        val latest = java.util.concurrent.ConcurrentHashMap<String, Double>()
    }

    override fun onSensorChanged(e: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = e.values[0]
                val y = e.values[1]
                val z = e.values[2]
                val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                if (g > 2.0f && now - lastShake > 1200) {
                    lastShake = now
                    emit(MacroEvent("shake", mapOf("force" to String.format(Locale.US, "%.2f", g))))
                }
                val down = when {
                    z < -8.0f -> true
                    z > 8.0f -> false
                    else -> null
                }
                if (down != null) {
                    if (down != flipCandidate) {
                        flipCandidate = down
                        flipSince = now
                    } else if (now - flipSince > 600 && down != faceDown) {
                        val first = faceDown == null
                        faceDown = down
                        if (!first) emit(MacroEvent("flip", mapOf("state" to if (down) "face_down" else "face_up")))
                    }
                }
            }
            Sensor.TYPE_LIGHT -> onLight(e.values[0].toDouble(), now)
            Sensor.TYPE_PROXIMITY -> {
                val near = e.values[0] < min(e.sensor.maximumRange, 5f)
                val st = if (near) "near" else "far"
                latest["near"] = if (near) 1.0 else 0.0
                if (st != lastProx) {
                    val first = lastProx == null
                    lastProx = st
                    if (!first) emit(MacroEvent("proximity", mapOf("state" to st)))
                }
            }
        }
    }

    private var lastLightEmit = 0L

    private fun onLight(lux: Double, now: Long) {
        latest["lux"] = lux
        if (lastLux.isNaN()) {
            lastLux = lux
            return
        }
        // Light jitters constantly; only report meaningful changes, at most every 2 s.
        if (now - lastLightEmit < 2000 || kotlin.math.abs(lux - lastLux) < maxOf(1.0, lastLux * 0.15)) return
        emit(MacroEvent("light", mapOf("lux" to lux.toString(), "prev" to lastLux.toString())))
        lastLux = lux
        lastLightEmit = now
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
