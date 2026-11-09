@file:OptIn(ExperimentalMaterial3Api::class)

package dev.isaakrichards.tasktemplar.ui

import android.app.TimePickerDialog
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.collectAsState
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.isaakrichards.tasktemplar.data.MacroStore
import dev.isaakrichards.tasktemplar.data.Places
import dev.isaakrichards.tasktemplar.engine.Geo
import dev.isaakrichards.tasktemplar.engine.GeoMath
import dev.isaakrichards.tasktemplar.engine.LocalePlugins
import kotlinx.coroutines.launch
import dev.isaakrichards.tasktemplar.model.Macro
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.isaakrichards.tasktemplar.engine.Conditions
import dev.isaakrichards.tasktemplar.model.Catalog
import dev.isaakrichards.tasktemplar.model.ParamKind
import dev.isaakrichards.tasktemplar.model.ParamSpec
import dev.isaakrichards.tasktemplar.model.Step
import dev.isaakrichards.tasktemplar.model.StepKind
import dev.isaakrichards.tasktemplar.model.StepSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TypePickerDialog(kind: StepKind, onPick: (StepSpec) -> Unit, onDismiss: () -> Unit) {
    val groups = remember(kind) { Catalog.forKind(kind).groupBy { it.category } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add ${kind.title.lowercase()}") },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                groups.forEach { (category, specs) ->
                    item(key = "h_$category") {
                        Text(
                            category.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(specs, key = { it.type }) { spec ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(spec) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(spec.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                spec.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
fun StepEditDialog(spec: StepSpec, step: Step, onSave: (Step) -> Unit, onDismiss: () -> Unit) {
    val values = remember(step) {
        mutableStateMapOf<String, String>().apply {
            spec.params.forEach { put(it.key, step.params[it.key] ?: it.default) }
        }
    }
    var appPickerFor by remember { mutableStateOf<String?>(null) }
    var macroPickerFor by remember { mutableStateOf<String?>(null) }
    var conds by remember(step) { mutableStateOf(step.conditions) }
    var condMode by remember(step) { mutableStateOf(step.conditionMode) }
    var pickingCond by remember { mutableStateOf(false) }
    var editingCond by remember { mutableStateOf<Pair<Int, Step>?>(null) }
    val showConds = spec.conditions && (spec.type != "loop" || values["mode"] == "while")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(spec.label) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(spec.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                spec.params.forEach { ps ->
                    ParamField(
                        spec = ps,
                        value = values[ps.key] ?: "",
                        onChange = { values[ps.key] = it },
                        onPickApp = { appPickerFor = ps.key },
                        onPickMacro = { macroPickerFor = ps.key },
                    )
                }
                if (showConds) {
                    ConditionsEditor(
                        conds = conds,
                        mode = condMode,
                        onMode = { condMode = it },
                        onAdd = { pickingCond = true },
                        onEdit = { i -> editingCond = i to conds[i] },
                        onRemove = { i -> conds = conds.filterIndexed { j, _ -> j != i } },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(Step(spec.type, values.toMap(), if (spec.conditions) conds else emptyList(), condMode))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (pickingCond) {
        TypePickerDialog(
            kind = StepKind.CONSTRAINT,
            onDismiss = { pickingCond = false },
            onPick = { cs ->
                pickingCond = false
                val ns = Step(cs.type, cs.defaults())
                if (cs.params.isEmpty()) conds = conds + ns else editingCond = -1 to ns
            },
        )
    }

    editingCond?.let { (i, cs) ->
        val cspec = Catalog.spec(cs.type)
        if (cspec != null) {
            StepEditDialog(
                spec = cspec,
                step = cs,
                onDismiss = { editingCond = null },
                onSave = { ns ->
                    conds = if (i in conds.indices) conds.toMutableList().also { it[i] = ns } else conds + ns
                    editingCond = null
                },
            )
        }
    }

    macroPickerFor?.let { key ->
        MacroPickerDialog(
            allowNone = spec.params.firstOrNull { it.key == key }?.optional == true,
            onPick = { id ->
                values[key] = id
                macroPickerFor = null
            },
            onDismiss = { macroPickerFor = null },
        )
    }

    appPickerFor?.let { key ->
        AppPickerDialog(
            onPick = { pkg ->
                values[key] = pkg
                appPickerFor = null
            },
            onDismiss = { appPickerFor = null },
        )
    }
}

@Composable
private fun ParamField(
    spec: ParamSpec,
    value: String,
    onChange: (String) -> Unit,
    onPickApp: () -> Unit,
    onPickMacro: () -> Unit,
) {
    val ctx = LocalContext.current
    val label = spec.label + if (spec.optional) " (optional)" else ""
    when (spec.kind) {
        ParamKind.TEXT, ParamKind.NUMBER -> OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            supportingText = if (spec.hint.isNotEmpty()) ({ Text(spec.hint) }) else null,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (spec.kind == ParamKind.NUMBER) KeyboardType.Number else KeyboardType.Text,
            ),
            singleLine = spec.kind == ParamKind.NUMBER,
            modifier = Modifier.fillMaxWidth(),
        )

        ParamKind.TIME -> OutlinedButton(
            onClick = {
                val t = Conditions.parseTime(value.ifBlank { spec.default })
                TimePickerDialog(ctx, { _, h, m -> onChange("%02d:%02d".format(h, m)) }, t.hour, t.minute, true).show()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("$label: ${value.ifBlank { spec.default }}") }

        ParamKind.DAYS -> Column {
            Text(label, style = MaterialTheme.typography.labelLarge)
            val selected = Catalog.parseDays(value)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, letter ->
                    val day = i + 1
                    val on = day in selected
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                val next = if (on) selected - day else selected + day
                                onChange(next.sorted().joinToString(","))
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            letter,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        ParamKind.CHOICE -> Column {
            Text(label, style = MaterialTheme.typography.labelLarge)
            spec.choices.forEach { (key, text) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onChange(key) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = value == key, onClick = { onChange(key) })
                    Text(text)
                }
            }
        }

        ParamKind.MACRO -> OutlinedButton(onClick = onPickMacro, modifier = Modifier.fillMaxWidth()) {
            val name = if (value.isBlank()) (spec.hint.ifBlank { "Choose…" }) else Catalog.macroName(value)
            Text("$label: $name")
        }

        ParamKind.PLUGIN -> {
            var choosing by remember { mutableStateOf(false) }
            var chosen by remember { mutableStateOf<LocalePlugins.Plugin?>(null) }
            val editor = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
                val p = chosen ?: return@rememberLauncherForActivityResult
                val data = res.data ?: return@rememberLauncherForActivityResult
                val cfg = org.json.JSONObject()
                    .put("pkg", p.pkg).put("activity", p.activity).put("label", p.label)
                    .put("blurb", data.getStringExtra(LocalePlugins.EXTRA_BLURB) ?: "")
                    .put("bundle", LocalePlugins.bundleToJson(data.getBundleExtra(LocalePlugins.EXTRA_BUNDLE)))
                onChange(cfg.toString())
            }
            OutlinedButton(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (value.isBlank()) "Choose plugin…" else LocalePlugins.describe(value))
            }
            if (choosing) {
                val plugins = remember { LocalePlugins.installed(ctx) }
                AlertDialog(
                    onDismissRequest = { choosing = false },
                    title = { Text("Locale / Tasker plugins") },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } },
                    text = {
                        Column {
                            if (plugins.isEmpty()) Text("No compatible plugins installed.")
                            plugins.forEach { p ->
                                Text(
                                    p.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        choosing = false
                                        chosen = p
                                        val intent = android.content.Intent(LocalePlugins.ACTION_EDIT)
                                            .setClassName(p.pkg, p.activity)
                                        // Re-open with the current settings when editing the same plugin.
                                        runCatching { org.json.JSONObject(value) }.getOrNull()?.let { old ->
                                            if (old.optString("pkg") == p.pkg) {
                                                old.optJSONObject("bundle")?.let {
                                                    intent.putExtra(LocalePlugins.EXTRA_BUNDLE, LocalePlugins.jsonToBundle(it, emptyMap()))
                                                }
                                                intent.putExtra(LocalePlugins.EXTRA_BLURB, old.optString("blurb"))
                                            }
                                        }
                                        runCatching { editor.launch(intent) }
                                    }.padding(vertical = 10.dp),
                                )
                            }
                        }
                    },
                )
            }
        }

        ParamKind.SOUND -> {
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
                @Suppress("DEPRECATION")
                val uri = res.data?.getParcelableExtra<android.net.Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                if (uri != null) onChange(uri.toString())
            }
            val name = remember(value) {
                if (value.isBlank()) "Choose…" else runCatching {
                    android.media.RingtoneManager.getRingtone(ctx, android.net.Uri.parse(value))?.getTitle(ctx)
                }.getOrNull() ?: value
            }
            OutlinedButton(
                onClick = {
                    picker.launch(
                        android.content.Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_ALL)
                            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("$label: $name") }
        }

        ParamKind.LOCATION -> {
            val scope = rememberCoroutineScope()
            var locating by remember { mutableStateOf(false) }
            var placesOpen by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onChange,
                    label = { Text(label) },
                    placeholder = if (spec.hint.isNotEmpty()) ({ Text(spec.hint) }) else null,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = !locating,
                    onClick = {
                        locating = true
                        scope.launch {
                            val loc = runCatching { Geo.current(ctx, 20_000) }.getOrNull()
                            locating = false
                            if (loc != null) onChange(GeoMath.format(loc.latitude, loc.longitude))
                            else Toast.makeText(ctx, "No location: grant Location on the Permissions screen", Toast.LENGTH_LONG).show()
                        }
                    },
                ) { Text(if (locating) "…" else "Here") }
                TextButton(onClick = { placesOpen = true }) { Text("★") }
            }
            if (placesOpen) {
                PlacesDialog(
                    current = value,
                    onPick = { onChange(it); placesOpen = false },
                    onDismiss = { placesOpen = false },
                )
            }
        }

        ParamKind.APP -> Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                label = { Text(label) },
                placeholder = if (spec.hint.isNotEmpty()) ({ Text(spec.hint) }) else null,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onPickApp) { Text("Pick") }
        }
    }
}

@Composable
fun AppPickerDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val found = pm.queryIntentActivities(launcher, 0)
            found
                .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase() }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose app") },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val list = apps
                if (list == null) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val shown = list.filter {
                        query.isBlank() || it.first.contains(query, true) || it.second.contains(query, true)
                    }
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(shown, key = { it.second }) { (name, pkg) ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(pkg) }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(name, style = MaterialTheme.typography.titleSmall)
                                Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun PlacesDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var places by remember { mutableStateOf(Places.all(ctx)) }
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Saved places") },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (places.isEmpty()) Text("No saved places yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                places.forEach { (name, latLng) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(latLng) }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(name, style = MaterialTheme.typography.titleSmall)
                            Text(latLng, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { Places.delete(ctx, name); places = Places.all(ctx) }) { Icon(Icons.Default.Close, "Delete $name") }
                    }
                }
                if (GeoMath.parse(current) != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(newName, { newName = it }, label = { Text("Save $current as…") }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(
                            enabled = newName.isNotBlank(),
                            onClick = { Places.save(ctx, newName, current); newName = ""; places = Places.all(ctx) },
                        ) { Text("Save") }
                    }
                }
            }
        },
    )
}

@Composable
private fun ConditionsEditor(
    conds: List<Step>,
    mode: String,
    onMode: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Conditions", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (conds.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == Macro.MODE_ALL, onClick = { onMode(Macro.MODE_ALL) }, label = { Text("All") })
                FilterChip(selected = mode == Macro.MODE_ANY, onClick = { onMode(Macro.MODE_ANY) }, label = { Text("Any") })
            }
        }
        if (conds.isEmpty()) {
            Text("None yet: always true", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        conds.forEachIndexed { i, c ->
            Row(Modifier.fillMaxWidth().clickable { onEdit(i) }, verticalAlignment = Alignment.CenterVertically) {
                Text(Catalog.summarize(c), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { onRemove(i) }) { Icon(Icons.Default.Close, "Remove condition") }
            }
        }
        TextButton(onClick = onAdd) {
            Icon(Icons.Default.Add, null)
            Text(" Add condition")
        }
    }
}

@Composable
fun MacroPickerDialog(allowNone: Boolean, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val macros by MacroStore.macros.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose macro") },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (allowNone) {
                    item {
                        Text(
                            "This macro",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.fillMaxWidth().clickable { onPick("") }.padding(vertical = 10.dp),
                        )
                    }
                }
                if (macros.isEmpty()) {
                    item { Text("No saved macros yet. Save this one first, then come back.") }
                }
                items(macros, key = { it.id }) { m ->
                    Text(
                        m.name,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(m.id) }.padding(vertical = 10.dp),
                    )
                }
            }
        },
    )
}
