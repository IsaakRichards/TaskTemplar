package dev.isaakrichards.tasktemplar.service

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.isaakrichards.tasktemplar.data.MacroStore
import dev.isaakrichards.tasktemplar.engine.AlarmScheduler
import dev.isaakrichards.tasktemplar.engine.MacroEngine
import dev.isaakrichards.tasktemplar.model.MacroEvent

/** Restarts the engine after a reboot or app update and fires "Device boot" triggers. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MacroStore.init(context)
        MacroService.start(context)
        AlarmScheduler.rescheduleAll(context)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            MacroEngine.dispatch(context, MacroEvent("boot"))
        }
    }
}

/** Feeds posted notifications to "Notification received" triggers. */
class NotifListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        MacroEngine.dispatch(
            this,
            MacroEvent("notification", mapOf("app" to sbn.packageName, "title" to title, "text" to text)),
        )
    }
}
