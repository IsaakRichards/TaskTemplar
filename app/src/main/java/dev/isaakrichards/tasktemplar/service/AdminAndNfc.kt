package dev.isaakrichards.tasktemplar.service

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import androidx.activity.ComponentActivity
import dev.isaakrichards.tasktemplar.data.MacroStore
import dev.isaakrichards.tasktemplar.engine.MacroEngine
import dev.isaakrichards.tasktemplar.model.MacroEvent

/** Device admin: only used to notice wrong unlock attempts (and to lock the screen on request). */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val count = runCatching { dpm.currentFailedPasswordAttempts }.getOrDefault(1)
        MacroStore.init(context)
        MacroEngine.dispatch(context, MacroEvent("login_failed", mapOf("count" to count.toString())))
    }

    companion object {
        fun component(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
        fun isActive(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java).isAdminActive(component(ctx))
    }
}

/** Receives scanned NFC tags (when no other app claims them) and fires "NFC tag" triggers. */
class NfcActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tag: Tag? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        val id = tag?.id?.joinToString("") { "%02X".format(it) } ?: ""
        if (id.isNotEmpty()) {
            MacroStore.init(this)
            MacroStore.log("NFC", "Scanned tag $id")
            MacroEngine.dispatch(this, MacroEvent("nfc", mapOf("tag" to id)))
        }
        finish()
    }
}
