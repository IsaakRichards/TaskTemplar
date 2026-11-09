package dev.isaakrichards.tasktemplar

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.isaakrichards.tasktemplar.data.GlobalVars
import dev.isaakrichards.tasktemplar.data.MacroStore
import dev.isaakrichards.tasktemplar.engine.Conditions
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
        Actions.watchTorch(this)
    }

    companion object {
        const val CHANNEL_SERVICE = "engine"
        const val CHANNEL_ALERTS = "alerts"
    }
}
