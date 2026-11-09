package dev.isaakrichards.tasktemplar.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.isaakrichards.tasktemplar.engine.Dialogs
import dev.isaakrichards.tasktemplar.service.LauncherBridge

/** Transparent activity that shows a macro's pop-up dialog and reports the answer. */
class DialogActivity : ComponentActivity() {

    private var answered = false
    private lateinit var requestId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        val kind = intent.getStringExtra(EXTRA_KIND) ?: "message"
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: ""
        val options = intent.getStringArrayExtra(EXTRA_OPTIONS)?.toList() ?: emptyList()

        setContent {
            TemplarTheme {
                var text by remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { answer(null) },
                    title = { if (title.isNotBlank()) Text(title) },
                    text = {
                        Column {
                            if (message.isNotBlank()) Text(message)
                            when (kind) {
                                "options" -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                                    itemsIndexed(options) { _, opt ->
                                        Text(
                                            opt,
                                            style = MaterialTheme.typography.titleMedium,
                                            modifier = Modifier.fillMaxWidth().clickable { answer(opt) }.padding(vertical = 12.dp),
                                        )
                                    }
                                }
                                "input" -> OutlinedTextField(
                                    value = text,
                                    onValueChange = { text = it },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                )
                            }
                        }
                    },
                    confirmButton = {
                        when (kind) {
                            "confirm" -> TextButton(onClick = { answer("yes") }) { Text("Yes") }
                            "input" -> TextButton(onClick = { answer(text) }) { Text("OK") }
                            "options" -> {}
                            else -> TextButton(onClick = { answer("ok") }) { Text("OK") }
                        }
                    },
                    dismissButton = {
                        when (kind) {
                            "confirm" -> TextButton(onClick = { answer("no") }) { Text("No") }
                            "input", "options" -> TextButton(onClick = { answer(null) }) { Text("Cancel") }
                            else -> {}
                        }
                    },
                )
            }
        }
    }

    private fun answer(result: String?) {
        if (answered) return
        answered = true
        Dialogs.complete(requestId, result)
        finish()
    }

    override fun onDestroy() {
        if (!answered && ::requestId.isInitialized && isFinishing) Dialogs.complete(requestId, null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_KIND = "kind"
        const val EXTRA_TITLE = "title"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_OPTIONS = "options"
    }
}

/** Invisible activity behind home-screen shortcuts: runs the macro and closes. */
class ShortcutRunActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("macro")?.let { LauncherBridge.runFromLauncher(this, it, "shortcut") }
        finish()
    }
}
