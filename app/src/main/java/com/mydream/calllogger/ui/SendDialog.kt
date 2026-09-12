package com.mydream.calllogger.ui

import android.provider.CallLog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Who a day-send goes to, and the call types that maps to. */
private enum class RecipientChoice(val label: String) {
    ALL("All contacted (incoming + missed)"),
    INCOMING("Incoming"),
    MISSED("Missed"),
    OUTGOING("Outgoing");

    fun types(): Set<Int> = when (this) {
        ALL -> setOf(CallLog.Calls.INCOMING_TYPE, CallLog.Calls.MISSED_TYPE)
        INCOMING -> setOf(CallLog.Calls.INCOMING_TYPE)
        MISSED -> setOf(CallLog.Calls.MISSED_TYPE)
        OUTGOING -> setOf(CallLog.Calls.OUTGOING_TYPE)
    }
}

/** Fill preview tokens with sample values so the bubble reads naturally. */
private fun sampleParam(p: String): String = p
    .replace("{{contact_name}}", "there")
    .replace("{{business_name}}", "your business")
    .replace("{{number}}", "")

@Composable
fun SendTemplateDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Only APPROVED templates can be sent; day-send goes through the scheduler, which
    // doesn't carry header media, so hide media-header templates (they'd fail at Meta).
    val approved = remember(state.templates) {
        state.templates.filter { it.isApproved && it.headerMediaFormat == null }
    }

    var choice by remember { mutableStateOf(RecipientChoice.ALL) }
    var selected by remember(approved) { mutableStateOf(approved.firstOrNull()) }
    var params by remember { mutableStateOf(emptyList<String>()) }
    var sending by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(selected) {
        val n = selected?.variableCount ?: 0
        params = List(n) { i ->
            when (i) {
                0 -> "{{contact_name}}"
                1 -> "{{business_name}}"
                else -> ""
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Send to ${state.selectedRange.label.lowercase()} callers") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.templatesLoading && approved.isEmpty()) {
                    Text("Loading templates…", style = MaterialTheme.typography.bodySmall)
                } else if (approved.isEmpty()) {
                    Text(
                        "No approved templates yet. Open “Message templates”, create one and wait " +
                            "for WhatsApp to approve it — then you can send it here.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text("Recipients", style = MaterialTheme.typography.labelLarge)
                    RecipientChoice.values().forEach { rc ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { choice = rc }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = choice == rc, onClick = { choice = rc })
                            Text(rc.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    Text("Template", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                    Box {
                        OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(selected?.let { "${it.name} · ${it.language}" } ?: "Select a template")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            approved.forEach { t ->
                                DropdownMenuItem(
                                    text = { Text("${t.name} · ${t.language}") },
                                    onClick = { selected = t; menuOpen = false },
                                )
                            }
                        }
                    }

                    selected?.let { t ->
                        TemplateBubble(
                            body = t.body,
                            footer = t.footer,
                            params = params.map(::sampleParam),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (params.isNotEmpty()) {
                            Text(
                                "Variables — tokens {{contact_name}}, {{business_name}}, {{number}} " +
                                    "are filled per recipient; or type fixed text.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            params.forEachIndexed { i, v ->
                                OutlinedTextField(
                                    value = v,
                                    onValueChange = { nv -> params = params.toMutableList().also { it[i] = nv } },
                                    label = { Text("{{${i + 1}}}") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val t = selected ?: return@TextButton
                    sending = true
                    vm.sendToRange(choice.types(), t.name, t.language, params) { ok, _ ->
                        sending = false
                        if (ok) onDismiss()
                    }
                },
                enabled = selected != null && !sending,
            ) {
                Text(if (sending) "Sending…" else "Send", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !sending) { Text("Cancel") }
        },
    )
}
