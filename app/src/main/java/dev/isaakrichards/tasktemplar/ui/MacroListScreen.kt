@file:OptIn(ExperimentalMaterial3Api::class)

package dev.isaakrichards.tasktemplar.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.isaakrichards.tasktemplar.data.MacroStore
import dev.isaakrichards.tasktemplar.engine.MacroEngine
import dev.isaakrichards.tasktemplar.model.Catalog
import dev.isaakrichards.tasktemplar.model.Macro

@Composable
fun MacroListScreen(
    macros: List<Macro>,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onLog: () -> Unit,
    onPerms: () -> Unit,
    onVars: () -> Unit,
) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var templates by remember { mutableStateOf(false) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(MacroStore.exportJson().toByteArray()) }
        }.onSuccess { Toast.makeText(ctx, "Exported ${macros.size} macros", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(ctx, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            val imported = MacroStore.parseImport(text)
            MacroStore.replaceAll(MacroStore.macros.value + imported)
            imported.size
        }.onSuccess { Toast.makeText(ctx, "Imported $it macros", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(ctx, "Import failed: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TaskTemplar", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    IconButton(onClick = onLog) { Icon(Icons.AutoMirrored.Filled.List, "Run log") }
                    IconButton(onClick = onPerms) { Icon(Icons.Default.Settings, "Permissions") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("New from template") }, onClick = {
                                menu = false
                                templates = true
                            })
                            DropdownMenuItem(text = { Text("Global variables") }, onClick = {
                                menu = false
                                onVars()
                            })
                            DropdownMenuItem(text = { Text("Export macros") }, onClick = {
                                menu = false
                                exporter.launch("tasktemplar-backup.json")
                            })
                            DropdownMenuItem(text = { Text("Import macros") }, onClick = {
                                menu = false
                                importer.launch(arrayOf("application/json", "text/plain", "*/*"))
                            })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New macro") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { pad ->
        if (templates) {
            TemplateDialog(
                onDismiss = { templates = false },
                onPick = { t ->
                    templates = false
                    val copy = t.copy(id = java.util.UUID.randomUUID().toString())
                    MacroStore.upsert(copy)
                    onOpen(copy.id)
                },
            )
        }
        if (macros.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(pad).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No macros yet", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "A macro is: when something happens (trigger), if conditions are right (constraints), do things (actions).\n\nTap “New macro” to build your first one, and check the ⚙ screen for permissions.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = pad.calculateTopPadding() + 8.dp,
                    bottom = pad.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val groups = macros.groupBy { it.category.trim() }.toSortedMap(compareBy<String> { it.isEmpty() }.thenBy { it.lowercase() })
                groups.forEach { (category, list) ->
                    if (groups.size > 1 || category.isNotEmpty()) {
                        item(key = "cat_$category") {
                            Text(
                                category.ifEmpty { "Uncategorised" }.uppercase(),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                            )
                        }
                    }
                items(list, key = { it.id }) { m ->
                    MacroCard(
                        macro = m,
                        onClick = { onOpen(m.id) },
                        onToggle = { MacroStore.setEnabled(m.id, it) },
                        onRun = {
                            MacroEngine.runNow(ctx, m)
                            Toast.makeText(ctx, "Running “${m.name}”", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
                }
            }
        }
    }
}

@Composable
private fun TemplateDialog(onPick: (Macro) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New from template") },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            LazyColumn(Modifier.androidxHeight()) {
                items(dev.isaakrichards.tasktemplar.model.Templates.all) { (desc, macro) ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .androidxClick { onPick(macro) }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(macro.name, style = MaterialTheme.typography.titleSmall)
                        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
    )
}

private fun Modifier.androidxHeight() = this.then(Modifier.heightIn(max = 460.dp))
private fun Modifier.androidxClick(onClick: () -> Unit) = this.then(Modifier.clickable(onClick = onClick))

@Composable
private fun MacroCard(macro: Macro, onClick: () -> Unit, onToggle: (Boolean) -> Unit, onRun: () -> Unit) {
    val dim = !macro.enabled
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    macro.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                val whenText = if (macro.triggers.isEmpty()) "Manual only" else macro.triggers.joinToString(" or ") { Catalog.summarize(it) }
                Text(
                    whenText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${macro.actions.size} action(s) · ${macro.constraints.size} constraint(s)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRun) { Icon(Icons.Default.PlayArrow, "Run now", tint = MaterialTheme.colorScheme.primary) }
            Switch(checked = macro.enabled, onCheckedChange = onToggle)
        }
    }
}
