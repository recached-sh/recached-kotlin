package dev.recached.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.recached.ConnectionState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as DemoApp
        // `adb shell am start ... --es server ws://...`: used when the cache
        // opens, so in a fresh process (after `am force-stop`).
        intent.getStringExtra("server")?.let(app::useServer)
        setContent {
            MaterialTheme { ChecklistScreen(app.checklist, app.serverUrl) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun ChecklistScreen(checklist: Checklist, serverUrl: String) {
    val items by checklist.items.collectAsStateWithLifecycle(emptyList())
    val state by checklist.connectionState.collectAsStateWithLifecycle()
    val pending by checklist.pendingWrites.collectAsStateWithLifecycle()

    Scaffold(
        // Test tags become resource ids, so scripts/acceptance.py can find
        // views with uiautomator.
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        topBar = { TopAppBar(title = { Text("Checklist") }) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(state.label(), Modifier.testTag("connection")) })
                AssistChip(
                    onClick = {},
                    label = { Text(if (pending == 0L) "Synced" else "$pending unsynced", Modifier.testTag("pending")) },
                )
            }
            AddRow(onAdd = checklist::add)
            LazyColumn(Modifier.weight(1f)) {
                items(items, key = { it.key }) { item ->
                    ItemRow(item, onToggle = { checklist.toggle(item) }, onDelete = { checklist.delete(item) })
                }
            }
            Text(
                "Server: $serverUrl",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

private fun ConnectionState.label() = when (this) {
    ConnectionState.CONNECTED -> "Connected"
    ConnectionState.CONNECTING -> "Connecting"
    ConnectionState.WAITING -> "Offline, retrying"
    ConnectionState.OFFLINE -> "Local only"
}

@Composable
private fun AddRow(onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val submit = {
        onAdd(text)
        text = ""
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("New item") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.weight(1f).testTag("new-item"),
        )
        Button(onClick = submit, modifier = Modifier.padding(start = 8.dp).testTag("add")) { Text("Add") }
    }
}

@Composable
private fun ItemRow(item: Item, onToggle: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = item.done, onCheckedChange = { onToggle() })
        Text(
            item.title,
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (item.done) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${item.title}") }
    }
}
