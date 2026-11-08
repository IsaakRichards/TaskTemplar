@file:OptIn(ExperimentalMaterial3Api::class)

package dev.isaakrichards.tasktemplar.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.isaakrichards.tasktemplar.data.MacroStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogScreen(onBack: () -> Unit) {
    val log by MacroStore.log.collectAsState()
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()) }
    val entries = remember(log) { log.asReversed() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Run log") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { MacroStore.clearLog() }) { Icon(Icons.Default.Delete, "Clear log") } },
            )
        },
    ) { pad ->
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("Nothing has run yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = pad.calculateTopPadding(),
                    bottom = pad.calculateBottomPadding() + 16.dp,
                ),
            ) {
                items(entries) { e ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(
                            "${fmt.format(Date(e.time))} · ${e.macro}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            e.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (e.message.startsWith("✗")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}
