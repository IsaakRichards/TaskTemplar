package dev.isaakrichards.tasktemplar.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.isaakrichards.tasktemplar.data.MacroStore
import dev.isaakrichards.tasktemplar.model.Macro
import dev.isaakrichards.tasktemplar.service.MacroService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MacroService.start(this)
        setContent { TemplarTheme { AppRoot() } }
    }
}

private const val HOME = "home"
private const val LOG = "log"
private const val PERMS = "perms"
private const val VARS = "vars"
private const val NEW = "edit:"

@Composable
private fun AppRoot() {
    val ctx = LocalContext.current
    // "home" | "log" | "perms" | "edit:" (new) | "edit:<id>"
    var screen by rememberSaveable { mutableStateOf(HOME) }
    val macros by MacroStore.macros.collectAsState()

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    BackHandler(enabled = screen != HOME) { screen = HOME }

    when {
        screen == LOG -> LogScreen(onBack = { screen = HOME })
        screen == PERMS -> PermissionsScreen(onBack = { screen = HOME })
        screen == VARS -> VariablesScreen(onBack = { screen = HOME })
        screen.startsWith(NEW) -> {
            val id = screen.removePrefix(NEW).ifBlank { null }
            val initial = remember(screen) { id?.let { MacroStore.get(it) } ?: Macro() }
            MacroEditorScreen(
                initial = initial,
                isNew = id == null,
                onSave = { MacroStore.upsert(it); screen = HOME },
                onDelete = { MacroStore.delete(initial.id); screen = HOME },
                onBack = { screen = HOME },
            )
        }
        else -> MacroListScreen(
            macros = macros,
            onOpen = { screen = NEW + it },
            onNew = { screen = NEW },
            onLog = { screen = LOG },
            onPerms = { screen = PERMS },
            onVars = { screen = VARS },
        )
    }
}
