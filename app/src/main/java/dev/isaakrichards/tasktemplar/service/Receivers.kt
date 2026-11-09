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

/** Feeds incoming texts to "SMS received" triggers. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != android.provider.Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = android.provider.Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val from = parts[0].originatingAddress ?: ""
        val body = parts.joinToString("") { it.messageBody ?: "" }
        MacroStore.init(context)
        MacroEngine.dispatch(context, MacroEvent("sms", mapOf("number" to from, "text" to body)))
    }
}

/** Feeds posted notifications to "Notification received" triggers. */
class NotifListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

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

    companion object {
        @Volatile
        var instance: NotifListener? = null
            private set

        /** (app, title, text) of every notification currently showing; empty without notification access. */
        fun active(): List<Triple<String, String, String>> {
            val l = instance ?: return emptyList()
            return runCatching {
                l.activeNotifications.orEmpty().map { sbn ->
                    val ex = sbn.notification?.extras
                    Triple(
                        sbn.packageName,
                        ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "",
                        ex?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "",
                    )
                }
            }.getOrDefault(emptyList())
        }

        /** Dismisses clearable notifications, optionally only from [pkg]. Returns how many. */
        fun clear(pkg: String): Int {
            val l = instance ?: error("Notification access is not granted")
            val targets = l.activeNotifications.orEmpty().filter { it.isClearable && (pkg.isBlank() || it.packageName == pkg) }
            if (pkg.isBlank()) l.cancelAllNotifications() else targets.forEach { l.cancelNotification(it.key) }
            return targets.size
        }
    }
}
