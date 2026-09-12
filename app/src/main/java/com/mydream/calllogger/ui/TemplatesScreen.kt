package com.mydream.calllogger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydream.calllogger.net.WaTemplate
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                title = { Text("Message templates", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { vm.closeTemplates() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CreateTemplateCard(vm)
            TestFlowCard(vm)
            YourTemplatesSection(vm)
        }
    }
}

@Composable
private fun CreateTemplateCard(vm: AppViewModel) {
    var name by remember { mutableStateOf("call_follow_up") }
    var body by remember { mutableStateOf("Hi {{1}}, thanks for calling {{2}}. How can we help?") }
    var footer by remember { mutableStateOf("Reply STOP to opt out.") }
    var submitting by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Create a template", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Restaurants need an approved WhatsApp template before the flow can message callers. " +
                    "Edit this starter and submit it for approval. {{1}} = caller name, {{2}} = your business.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Template name") },
                singleLine = true,
                supportingText = { Text("Lowercase letters, numbers and underscores.") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("Message body") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = footer,
                onValueChange = { footer = it },
                label = { Text("Footer (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Preview", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TemplateBubble(body = body, footer = footer, params = listOf("there", "your business"))
            Text(
                "Category: UTILITY · Language: en",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    val cleanName = normalizeTemplateName(name)
                    if (cleanName.isBlank() || body.isBlank()) return@Button
                    submitting = true
                    val components = JSONArray().apply {
                        put(JSONObject().apply { put("type", "BODY"); put("text", body.trim()) })
                        if (footer.isNotBlank()) {
                            put(JSONObject().apply { put("type", "FOOTER"); put("text", footer.trim()) })
                        }
                    }
                    vm.submitTemplate(cleanName, "en", "UTILITY", components) { _, _ -> submitting = false }
                },
                enabled = !submitting && normalizeTemplateName(name).isNotBlank() && body.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (submitting) "Submitting…" else "Submit for approval")
            }
        }
    }
}

@Composable
private fun TestFlowCard(vm: AppViewModel) {
    var number by remember { mutableStateOf("") }
    var simType by remember { mutableStateOf("missed") }
    var running by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Test the flow", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Runs your saved flow on a number now, simulating the chosen call type so the " +
                    "‘if missed / if answered’ steps are exercised. Include the country code.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = number,
                onValueChange = { number = it },
                label = { Text("Phone number") },
                placeholder = { Text("+9198…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = simType == "missed",
                    onClick = { simType = "missed" },
                    label = { Text("Missed call") },
                )
                FilterChip(
                    selected = simType == "incoming",
                    onClick = { simType = "incoming" },
                    label = { Text("Normal call") },
                )
            }
            Button(
                onClick = {
                    running = true
                    vm.testFlow(number.trim(), simType) { _, _ -> running = false }
                },
                enabled = !running && number.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (running) "Running…" else "Run test")
            }
        }
    }
}

@Composable
private fun YourTemplatesSection(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your templates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            when {
                state.templatesLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Loading…", style = MaterialTheme.typography.bodySmall)
                }

                state.templates.isEmpty() -> Text(
                    "No templates yet. Create one above and submit it for approval — it’ll show here " +
                        "and in your WhatsApp dashboard once Meta reviews it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> state.templates.forEachIndexed { i, t ->
                    if (i > 0) Divider()
                    TemplateRow(t)
                }
            }
        }
    }
}

@Composable
private fun TemplateRow(t: WaTemplate) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "${t.name} · ${t.language}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            TemplateStatusChip(t.status)
        }
        if (t.body.isNotBlank()) {
            Text(
                t.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

/** Meta template names allow only lowercase letters, digits and underscores. */
private fun normalizeTemplateName(raw: String): String =
    raw.trim().lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
