package dev.isaakrichards.tasktemplar.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.isaakrichards.tasktemplar.data.MacroStore
import dev.isaakrichards.tasktemplar.model.Catalog
import dev.isaakrichards.tasktemplar.model.Macro
import dev.isaakrichards.tasktemplar.model.MacroEvent
import dev.isaakrichards.tasktemplar.model.Step
import java.time.ZonedDateTime

/** Schedules the next firing of every "Time of day" and "Regular interval" trigger. */
object AlarmScheduler {

    private const val PREFS = "alarms"
    private const val KEY_CODES = "codes"
    private const val ACTION_PREFIX = "dev.isaakrichards.tasktemplar.ALARM."

    @Synchronized
    fun rescheduleAll(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        prefs.getStringSet(KEY_CODES, emptySet())?.forEach { code ->
            am.cancel(pending(app, code.toInt(), "", -1))
        }

        val codes = mutableSetOf<String>()
        for (macro in MacroStore.macros.value) {
            if (!macro.enabled) continue
            macro.triggers.forEachIndexed { i, t ->
                if (t.type == "time" || t.type == "interval") {
                    if (schedule(app, am, macro, i, t)) codes += code(macro, i).toString()
                }
            }
        }
        prefs.edit().putStringSet(KEY_CODES, codes).apply()
    }

    fun scheduleNext(ctx: Context, macroId: String, index: Int) {
        val app = ctx.applicationContext
        val macro = MacroStore.get(macroId) ?: return
        if (!macro.enabled) return
        val step = macro.triggers.getOrNull(index) ?: return
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        schedule(app, am, macro, index, step)
    }

    private fun schedule(ctx: Context, am: AlarmManager, macro: Macro, index: Int, step: Step): Boolean {
        val at = nextTime(step) ?: return false
        val pi = pending(ctx, code(macro, index), macro.id, index)
        val exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exactOk) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        return true
    }

    fun nextTime(step: Step, now: ZonedDateTime = ZonedDateTime.now()): Long? = when (step.type) {
        "interval" -> {
            val min = (step.p("minutes", "15").toLongOrNull() ?: 15L).coerceAtLeast(1L)
            now.plusMinutes(min).toInstant().toEpochMilli()
        }
        "time" -> {
            val t = Conditions.parseTime(step.p("time", "08:00"))
            val days = Catalog.parseDays(step.p("days", Catalog.ALL_DAYS))
            if (days.isEmpty()) {
                null
            } else {
                (0..7).asSequence()
                    .map { now.toLocalDate().plusDays(it.toLong()).atTime(t).atZone(now.zone) }
                    .firstOrNull { it.isAfter(now) && it.dayOfWeek.value in days }
                    ?.toInstant()?.toEpochMilli()
            }
        }
        else -> null
    }

    private fun code(macro: Macro, index: Int) = (macro.id + "#" + index).hashCode()

    private fun pending(ctx: Context, code: Int, macroId: String, index: Int): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java)
            .setAction(ACTION_PREFIX + code)
            .putExtra("macro", macroId)
            .putExtra("idx", index)
        return PendingIntent.getBroadcast(
            ctx, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val macroId = intent.getStringExtra("macro") ?: return
        val idx = intent.getIntExtra("idx", -1)
        if (idx < 0) return
        MacroStore.init(context)
        AlarmScheduler.scheduleNext(context, macroId, idx)
        MacroEngine.dispatch(context, MacroEvent("alarm", mapOf("macro" to macroId, "idx" to idx.toString())))
    }
}
