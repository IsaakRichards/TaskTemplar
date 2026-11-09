package dev.isaakrichards.tasktemplar.engine

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.telecom.TelecomManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import dev.isaakrichards.tasktemplar.model.Step

/** Calls, SMS, email and location actions. */
object PhoneActions {

    private fun need(ctx: Context, perm: String, what: String) {
        if (ContextCompat.checkSelfPermission(ctx, perm) != PackageManager.PERMISSION_GRANTED) {
            error("$what permission not granted (Permissions screen)")
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    suspend fun execute(ctx: Context, step: Step, vars: MutableMap<String, String>): String? {
        fun t(key: String, def: String = "") = MagicText.apply(step.p(key, def), vars)

        return when (step.type) {
            "make_call" -> {
                need(ctx, Manifest.permission.CALL_PHONE, "Phone")
                val number = t("number").trim()
                if (number.isEmpty()) error("No number")
                ctx.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                null
            }

            "answer_call" -> {
                need(ctx, Manifest.permission.ANSWER_PHONE_CALLS, "Answer calls")
                ctx.getSystemService(TelecomManager::class.java).acceptRingingCall()
                null
            }

            "end_call" -> {
                need(ctx, Manifest.permission.ANSWER_PHONE_CALLS, "Answer calls")
                if (Build.VERSION.SDK_INT < 28) error("Needs Android 9+")
                val ended = ctx.getSystemService(TelecomManager::class.java).endCall()
                if (ended) null else "no call to end"
            }

            "speakerphone" -> {
                val am = ctx.getSystemService(AudioManager::class.java)
                val current = am.isSpeakerphoneOn
                val on = when (step.p("state", "toggle")) {
                    "on" -> true
                    "off" -> false
                    else -> !current
                }
                am.isSpeakerphoneOn = on
                if (on) "on" else "off"
            }

            "send_sms" -> {
                need(ctx, Manifest.permission.SEND_SMS, "SMS")
                val number = t("number").trim()
                val text = t("text")
                if (number.isEmpty() || text.isEmpty()) error("Number and message are both needed")
                val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) {
                    ctx.getSystemService(SmsManager::class.java)
                } else {
                    SmsManager.getDefault()
                }
                val parts = sms.divideMessage(text)
                if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, null, null)
                else sms.sendTextMessage(number, null, text, null, null)
                "sent to $number"
            }

            "send_email" -> {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                    .putExtra(Intent.EXTRA_EMAIL, arrayOf(t("to")))
                    .putExtra(Intent.EXTRA_SUBJECT, t("subject"))
                    .putExtra(Intent.EXTRA_TEXT, t("body"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                "opened in your email app"
            }

            "open_call_log" -> {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, CallLog.Calls.CONTENT_URI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                null
            }

            "get_location" -> {
                val loc = Geo.current(ctx) ?: error("No location fix available")
                vars["lat"] = loc.latitude.toString()
                vars["lng"] = loc.longitude.toString()
                vars["accuracy"] = loc.accuracy.toInt().toString()
                vars["location_url"] = "https://www.openstreetmap.org/?mlat=${loc.latitude}&mlon=${loc.longitude}#map=17/${loc.latitude}/${loc.longitude}"
                "${GeoMath.format(loc.latitude, loc.longitude)} (±${loc.accuracy.toInt()} m)"
            }

            else -> error("Unknown phone action '${step.type}'")
        }
    }
}
