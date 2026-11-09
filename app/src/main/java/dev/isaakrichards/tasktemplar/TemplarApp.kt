package dev.isaakrichards.tasktemplar

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.isaakrichards.tasktemplar.data.GlobalVars
import dev.isaakrichards.tasktemplar.data.MacroStore
import android.telephony.TelephonyManager
import dev.isaakrichards.tasktemplar.engine.Conditions
import dev.isaakrichards.tasktemplar.engine.Geo
import dev.isaakrichards.tasktemplar.engine.LocalePlugins
import dev.isaakrichards.tasktemplar.engine.MagicText
import dev.isaakrichards.tasktemplar.engine.Stopwatches
import dev.isaakrichards.tasktemplar.service.SensorWatch
import dev.isaakrichards.tasktemplar.engine.MacroEngine
import dev.isaakrichards.tasktemplar.model.Catalog
import dev.isaakrichards.tasktemplar.model.MacroEvent
import dev.isaakrichards.tasktemplar.service.NotifListener
import dev.isaakrichards.tasktemplar.service.TemplarAccessibility
import dev.isaakrichards.tasktemplar.engine.Actions

class TemplarApp : Application() {

    override fun onCreate() {
        super.onCreate()
        MacroStore.init(this)
        Catalog.macroName = { id -> MacroStore.get(id)?.name ?: if (id.isBlank()) "(none)" else "(deleted macro)" }
        Conditions.macroEnabled = { id -> MacroStore.get(id)?.enabled }
        Conditions.foregroundApp = { TemplarAccessibility.foregroundPkg }
        Conditions.activeNotifications = { NotifListener.active() }
        Conditions.sensorValue = { key -> SensorWatch.latest[key] }
        Conditions.stopwatchSeconds = { name -> Stopwatches.elapsedMs(this, name) / 1000.0 }
        MagicText.item = { json, index -> dev.isaakrichards.tasktemplar.engine.Collections.read(json, index) }
        MagicText.stopwatch = { name -> (Stopwatches.elapsedMs(this, name) / 1000).toString() }
        Conditions.lastLocation = { Geo.lastKnown(this)?.let { it.latitude to it.longitude } }
        Conditions.callState = {
            runCatching {
                @Suppress("DEPRECATION")
                val state = getSystemService(TelephonyManager::class.java).callState
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> "ringing"
                    TelephonyManager.CALL_STATE_OFFHOOK -> "in_call"
                    else -> "idle"
                }
            }.getOrDefault("idle")
        }
        GlobalVars.onChanged = { v ->
            MacroEngine.dispatch(this, MacroEvent("variable", mapOf("name" to v.name, "value" to v.value)))
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Engine running", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps macros active in the background. You can hide this channel."
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Macro notifications", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notifications posted by your macros"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LED, "Macro notifications (LED)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notifications that blink the notification light, on phones that have one"
                enableLights(true)
                lightColor = 0xFFB69CFF.toInt()
            },
        )
        Catalog.pluginSummary = { LocalePlugins.describe(it) }
        Actions.watchTorch(this)
    }

    companion object {
        const val CHANNEL_SERVICE = "engine"
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_LED = "alerts_led"
    }
}
