@file:OptIn(ExperimentalMaterial3Api::class)

package dev.isaakrichards.tasktemplar.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.isaakrichards.tasktemplar.service.MacroService

private data class Perm(val title: String, val why: String, val granted: Boolean, val grant: () -> Unit)

@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }

    // Re-check everything whenever we come back from a system settings page.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
        MacroService.start(ctx)
    }

    val perms = remember(tick) { buildPerms(ctx) { runtime.launch(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Permissions") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = pad.calculateTopPadding() + 4.dp,
                bottom = pad.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Grant only what your macros use. Everything stays on this phone — no ads, no analytics, no accounts.\n\n" +
                        "Android 13+: if Accessibility or Notification access is greyed out (“Restricted setting”), open " +
                        "Settings › Apps › TaskTemplar, tap ⋮ in the top corner, choose “Allow restricted settings”, then try again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(perms) { p ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 10.dp)) {
                            Text(p.title, style = MaterialTheme.typography.titleSmall)
                            Text(p.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (p.granted) {
                            Text("✓ Granted", color = Okay, style = MaterialTheme.typography.labelLarge)
                        } else {
                            FilledTonalButton(onClick = p.grant) { Text("Grant") }
                        }
                    }
                }
            }
        }
    }
}

private fun accessibilityOn(ctx: Context): Boolean {
    val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return enabled.split(':').any { it.startsWith(ctx.packageName + "/") }
}

private fun has(ctx: Context, perm: String) =
    ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

private fun openSettings(ctx: Context, action: String, withPackage: Boolean) {
    val pkgUri = Uri.parse("package:${ctx.packageName}")
    val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (withPackage) intent.data = pkgUri
    runCatching { ctx.startActivity(intent) }.onFailure {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun buildPerms(ctx: Context, request: (Array<String>) -> Unit): List<Perm> {
    val list = mutableListOf<Perm>()
    val sdk = Build.VERSION.SDK_INT

    if (sdk >= 33) {
        list += Perm(
            "Notifications", "Engine status + the “Show notification” action.",
            has(ctx, Manifest.permission.POST_NOTIFICATIONS),
        ) { request(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
    }

    val pm = ctx.getSystemService(PowerManager::class.java)
    list += Perm(
        "Ignore battery optimization", "Stops Android from killing the engine. Strongly recommended.",
        pm.isIgnoringBatteryOptimizations(ctx.packageName),
    ) { openSettings(ctx, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, true) }

    if (sdk >= 31) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        list += Perm(
            "Alarms & reminders", "Lets time-of-day and interval triggers fire on the exact minute.",
            am.canScheduleExactAlarms(),
        ) { openSettings(ctx, Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, true) }
    }

    list += Perm(
        "Accessibility", "Needed for app opened/closed, screen content, and tap / type / scroll / system-button actions.",
        accessibilityOn(ctx),
    ) { openSettings(ctx, Settings.ACTION_ACCESSIBILITY_SETTINGS, false) }

    list += Perm(
        "Notification access", "Needed for the “Notification received” trigger.",
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName),
    ) { openSettings(ctx, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, false) }

    val nm = ctx.getSystemService(NotificationManager::class.java)
    list += Perm(
        "Do Not Disturb access", "Needed for the Do Not Disturb action and silent ringer mode.",
        nm.isNotificationPolicyAccessGranted,
    ) { openSettings(ctx, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, false) }

    list += Perm(
        "Modify system settings", "Needed for the screen brightness action.",
        Settings.System.canWrite(ctx),
    ) { openSettings(ctx, Settings.ACTION_MANAGE_WRITE_SETTINGS, true) }

    list += Perm(
        "Display over other apps", "Lets “Launch app” and “Open website” work while you're in another app.",
        Settings.canDrawOverlays(ctx),
    ) { openSettings(ctx, Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true) }

    list += Perm(
        "Location", "Android hides Wi-Fi network names without it. Only needed to match a specific network.",
        has(ctx, Manifest.permission.ACCESS_FINE_LOCATION),
    ) { request(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }

    if (sdk >= 29 && has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) {
        list += Perm(
            "Location — all the time", "Lets Wi-Fi name matching work while the app is in the background.",
            has(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        ) {
            if (sdk >= 30) openSettings(ctx, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
            else request(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        }
    }

    if (sdk >= 31) {
        list += Perm(
            "Nearby devices (Bluetooth)", "Lets Bluetooth triggers see device names.",
            has(ctx, Manifest.permission.BLUETOOTH_CONNECT),
        ) { request(arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) }
    }

    return list
}
