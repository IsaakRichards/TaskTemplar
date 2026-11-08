@file:OptIn(ExperimentalMaterial3Api::class)

package dev.isaakrichards.tasktemplar.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.isaakrichards.tasktemplar.engine.MacroEngine
import dev.isaakrichards.tasktemplar.model.Catalog
import dev.isaakrichards.tasktemplar.model.Macro
import dev.isaakrichards.tasktemplar.model.Step
import dev.isaakrichards.tasktemplar.model.StepKind

private data class Editing(val kind: StepKind, val index: Int, val step: Step)

@Composable
fun MacroEditorScreen(
    initial: Macro,
    isNew: Boolean,
    onSave: (Macro) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var macro by remember { mutableStateOf(initial) }
    var picking by remember { mutableStateOf<StepKind?>(null) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun stepsFor(kind: StepKind): List<Step> = when (kind) {
        StepKind.TRIGGER -> macro.triggers
        StepKind.CONSTRAINT -> macro.constraints
        StepKind.ACTION -> macro.actions
    }

    fun setSteps(kind: StepKind, list: List<Step>) {
        macro = when (kind) {
            StepKind.TRIGGER -> macro.copy(triggers = list)
            StepKind.CONSTRAINT -> macro.copy(constraints = list)
            StepKind.ACTION -> macro.copy(actions = list)
        }
    }

    fun putStep(kind: StepKind, index: Int, step: Step) {
        val list = stepsFor(kind).toMutableList()
        if (index in list.indices) list[index] = step else list.add(step)
        setSteps(kind, list)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New macro" else "Edit macro") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = {
                        MacroEngine.runNow(ctx, macro)
                        Toast.makeText(ctx, "Test run started — see the log", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Default.PlayArrow, "Test run") }
                    if (!isNew) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                    IconButton(onClick = {
                        if (macro.name.isBlank()) {
                            Toast.makeText(ctx, "Give the macro a name", Toast.LENGTH_SHORT).show()
                        } else if (macro.actions.isEmpty()) {
                            Toast.makeText(ctx, "Add at least one action", Toast.LENGTH_SHORT).show()
                        } else {
                            onSave(macro.copy(name = macro.name.trim()))
                        }
                    }) { Icon(Icons.Default.Check, "Save", tint = MaterialTheme.colorScheme.primary) }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = macro.name,
                onValueChange = { macro = macro.copy(name = it) },
                label = { Text("Macro name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            StepSection(
                title = "Triggers",
                subtitle = "Any one of these starts the macro. Leave empty for a manual-only macro.",
                steps = macro.triggers,
                onAdd = { picking = StepKind.TRIGGER },
                onEdit = { editing = Editing(StepKind.TRIGGER, it, macro.triggers[it]) },
                onRemove = { i -> setSteps(StepKind.TRIGGER, macro.triggers.filterIndexed { j, _ -> j != i }) },
            )

            StepSection(
                title = "Constraints",
                subtitle = "Optional conditions checked when a trigger fires.",
                steps = macro.constraints,
                onAdd = { picking = StepKind.CONSTRAINT },
                onEdit = { editing = Editing(StepKind.CONSTRAINT, it, macro.constraints[it]) },
                onRemove = { i -> setSteps(StepKind.CONSTRAINT, macro.constraints.filterIndexed { j, _ -> j != i }) },
                header = {
                    if (macro.constraints.size > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            FilterChip(
                                selected = macro.constraintMode == Macro.MODE_ALL,
                                onClick = { macro = macro.copy(constraintMode = Macro.MODE_ALL) },
                                label = { Text("All must pass") },
                            )
                            FilterChip(
                                selected = macro.constraintMode == Macro.MODE_ANY,
                                onClick = { macro = macro.copy(constraintMode = Macro.MODE_ANY) },
                                label = { Text("Any can pass") },
                            )
                        }
                    }
                },
            )

            StepSection(
                title = "Actions",
                subtitle = "Run top to bottom.",
                steps = macro.actions,
                onAdd = { picking = StepKind.ACTION },
                onEdit = { editing = Editing(StepKind.ACTION, it, macro.actions[it]) },
                onRemove = { i -> setSteps(StepKind.ACTION, macro.actions.filterIndexed { j, _ -> j != i }) },
                onMove = { from, to ->
                    val list = macro.actions.toMutableList()
                    if (from in list.indices && to in list.indices) {
                        val s = list.removeAt(from)
                        list.add(to, s)
                        setSteps(StepKind.ACTION, list)
                    }
                },
            )

            Text(
                "Tip: text fields accept magic text such as {time}, {date}, {battery}, {ssid}, and for notification triggers {app}, {title}, {text}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))
        }
    }

    picking?.let { kind ->
        TypePickerDialog(
            kind = kind,
            onDismiss = { picking = null },
            onPick = { spec ->
                picking = null
                val step = Step(spec.type, spec.defaults())
                if (spec.params.isEmpty()) putStep(kind, -1, step) else editing = Editing(kind, -1, step)
            },
        )
    }

    editing?.let { e ->
        val spec = Catalog.spec(e.step.type)
        if (spec == null) {
            editing = null
        } else {
            StepEditDialog(
                spec = spec,
                step = e.step,
                onDismiss = { editing = null },
                onSave = { s ->
                    putStep(e.kind, e.index, s)
                    editing = null
                },
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${macro.name}”?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StepSection(
    title: String,
    subtitle: String,
    steps: List<Step>,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: ((Int, Int) -> Unit)? = null,
    header: @Composable () -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalIconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Add") }
            }
            header()
            if (steps.isEmpty()) {
                Text(
                    "None",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            steps.forEachIndexed { i, step ->
                val spec = Catalog.spec(step.type)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onEdit(i) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(spec?.label ?: step.type, style = MaterialTheme.typography.titleSmall)
                        Text(
                            Catalog.summarize(step),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    if (onMove != null) {
                        IconButton(onClick = { onMove(i, i - 1) }, enabled = i > 0) {
                            Icon(Icons.Default.KeyboardArrowUp, "Move up")
                        }
                        IconButton(onClick = { onMove(i, i + 1) }, enabled = i < steps.lastIndex) {
                            Icon(Icons.Default.KeyboardArrowDown, "Move down")
                        }
                    }
                    IconButton(onClick = { onRemove(i) }) { Icon(Icons.Default.Close, "Remove") }
                }
            }
        }
    }
}
