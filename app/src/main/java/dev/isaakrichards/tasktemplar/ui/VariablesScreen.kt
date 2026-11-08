@file:OptIn(ExperimentalMaterial3Api::class)

package dev.isaakrichards.tasktemplar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.isaakrichards.tasktemplar.data.GlobalVars
import dev.isaakrichards.tasktemplar.data.Variable
import dev.isaakrichards.tasktemplar.engine.VarTypes

private val typeLabels = listOf(
    VarTypes.INTEGER to "Integer",
    VarTypes.DECIMAL to "Decimal",
    VarTypes.BOOLEAN to "True/false",
    VarTypes.STRING to "Text",
)

@Composable
fun VariablesScreen(onBack: () -> Unit) {
    val vars by GlobalVars.vars.collectAsState()
    val list = remember(vars) { vars.values.sortedBy { it.name.lowercase() } }
    var editing by remember { mutableStateOf<Variable?>(null) }
    var creating by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Global variables") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { creating = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Icon(Icons.Default.Add, "New variable") }
        },
    ) { pad ->
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No global variables yet. Create one here, or let a “Set variable” action create it. Use it in text as {v=name}.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = pad.calculateTopPadding() + 4.dp,
                    bottom = pad.calculateBottomPadding() + 88.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.name }) { v ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { editing = v },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(v.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${typeLabels.firstOrNull { it.first == v.type }?.second ?: v.type} · ${v.value.ifEmpty { "(empty)" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    maxLines = 2,
                                )
                            }
                            IconButton(onClick = { GlobalVars.delete(v.name) }) { Icon(Icons.Default.Delete, "Delete ${v.name}") }
                        }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        VariableDialog(
            initial = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { name, type, value ->
                editing?.let { old -> if (old.name != name) GlobalVars.delete(old.name) }
                GlobalVars.set(name, type, value)
                creating = false
                editing = null
            },
        )
    }
}

@Composable
private fun VariableDialog(initial: Variable?, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: VarTypes.INTEGER) }
    var value by remember { mutableStateOf(initial?.value ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New variable" else "Edit variable") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                typeLabels.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { (key, label) ->
                            FilterChip(selected = type == key, onClick = { type = key }, label = { Text(label) })
                        }
                    }
                }
                OutlinedTextField(value, { value = it }, label = { Text("Value") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim(), type, value) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
