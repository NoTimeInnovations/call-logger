package com.mydream.calllogger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mydream.calllogger.flow.FlowNode
import com.mydream.calllogger.flow.NodeType

private val NODE_W = 156.dp
private val NODE_H = 88.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowBuilderScreen(onBack: () -> Unit, vm: FlowBuilderViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val density = LocalDensity.current.density

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Call flow") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
                },
                actions = {
                    Text("On", style = MaterialTheme.typography.labelMedium)
                    Switch(checked = state.graph.enabled, onCheckedChange = { vm.setEnabled(it) })
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { vm.save() }, enabled = !state.saving) {
                        Text(if (state.saving) "Saving…" else "Save")
                    }
                    Spacer(Modifier.width(8.dp))
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.notRegistered -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                    Text(
                        "Couldn't reach your account. Make sure this email is a registered partner and " +
                            "you're online, then reopen this screen.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                else -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        FlowCanvas(state, vm, density)
                    }
                    when {
                        state.connectFromId != null -> ConnectBanner(onCancel = { vm.cancelConnect() })
                        else -> {
                            val selected = state.graph.nodes.firstOrNull { it.id == state.selectedNodeId }
                            if (selected != null) NodeEditor(selected, vm) else AddBar(vm)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FlowCanvas(state: FlowUiState, vm: FlowBuilderViewModel, density: Float) {
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val nodes = state.graph.nodes
    val canvasW = ((nodes.maxOfOrNull { it.x } ?: 0f) + 400f)
    val canvasH = ((nodes.maxOfOrNull { it.y } ?: 0f) + 400f)
    val primary = MaterialTheme.colorScheme.primary

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .horizontalScroll(hScroll)
            .verticalScroll(vScroll)
    ) {
        Box(Modifier.size(canvasW.dp, canvasH.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                for (e in state.graph.edges) {
                    val from = nodes.firstOrNull { it.id == e.from } ?: continue
                    val to = e.to?.let { id -> nodes.firstOrNull { it.id == id } } ?: continue
                    val start = Offset((from.x + NODE_W.value / 2) * density, (from.y + NODE_H.value) * density)
                    val end = Offset((to.x + NODE_W.value / 2) * density, to.y * density)
                    val color = when (e.branch) {
                        "true" -> Color(0xFF2E7D32)
                        "false" -> Color(0xFFC62828)
                        else -> primary
                    }
                    drawLine(color = color, start = start, end = end, strokeWidth = 4f)
                    drawCircle(color = color, radius = 7f, center = end)
                }
            }
            for (node in nodes) {
                NodeCard(node, node.id == state.selectedNodeId, state.connectFromId != null, density, vm)
            }
        }
    }
}

@Composable
private fun NodeCard(
    node: FlowNode,
    selected: Boolean,
    connecting: Boolean,
    density: Float,
    vm: FlowBuilderViewModel,
) {
    val accent = typeColor(node.type)
    Box(
        Modifier
            .offset(node.x.dp, node.y.dp)
            .size(NODE_W, NODE_H)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(
                width = if (selected) 2.5.dp else 1.dp,
                color = if (selected) accent else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp)
            )
            .pointerInput(node.id) {
                detectDragGestures { change, drag ->
                    change.consume()
                    vm.moveNode(node.id, drag.x / density, drag.y / density)
                }
            }
            .pointerInput(node.id, connecting) {
                detectTapGestures {
                    if (connecting) vm.completeConnect(node.id) else vm.select(node.id)
                }
            }
            .padding(10.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(typeIcon(node.type), null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(typeLabel(node.type), style = MaterialTheme.typography.labelLarge, color = accent)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                nodeSummary(node),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun ConnectBanner(onCancel: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Tap a node to connect to it", Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun AddBar(vm: FlowBuilderViewModel) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("Add step", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.addNode(NodeType.SEND) }) {
                    Icon(Icons.Filled.Send, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Send")
                }
                OutlinedButton(onClick = { vm.addNode(NodeType.WAIT) }) {
                    Icon(Icons.Filled.Schedule, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Wait")
                }
                OutlinedButton(onClick = { vm.addNode(NodeType.CONDITION) }) {
                    Icon(Icons.Filled.CallSplit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("If")
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Tap a node to edit it. Drag nodes to arrange.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NodeEditor(node: FlowNode, vm: FlowBuilderViewModel) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(typeIcon(node.type), null, tint = typeColor(node.type))
                Spacer(Modifier.width(8.dp))
                Text(typeLabel(node.type), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (node.type != NodeType.TRIGGER) {
                    IconButton(onClick = { vm.deleteNode(node.id) }) {
                        Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
                IconButton(onClick = { vm.select(null) }) { Icon(Icons.Filled.Close, "Close") }
            }
            Spacer(Modifier.height(8.dp))

            when (node.type) {
                NodeType.TRIGGER -> Text(
                    "This flow runs when a call is received. Connect it to the first step.",
                    style = MaterialTheme.typography.bodyMedium
                )
                NodeType.SEND -> SendEditor(node, vm)
                NodeType.WAIT -> WaitEditor(node, vm)
                NodeType.CONDITION -> ConditionEditor(node, vm)
            }

            Spacer(Modifier.height(12.dp))
            if (node.type == NodeType.CONDITION) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.startConnect(node.id, "true") }) { Text("If yes →") }
                    OutlinedButton(onClick = { vm.startConnect(node.id, "false") }) { Text("If no →") }
                }
            } else {
                OutlinedButton(onClick = { vm.startConnect(node.id, null) }) { Text("Connect to next →") }
            }
        }
    }
}

@Composable
private fun SendEditor(node: FlowNode, vm: FlowBuilderViewModel) {
    var template by remember(node.id) { mutableStateOf(node.template) }
    var language by remember(node.id) { mutableStateOf(node.language) }
    var paramsText by remember(node.id) { mutableStateOf(node.params.joinToString("\n")) }

    fun push() = vm.updateSend(
        node.id,
        template.trim(),
        language.trim().ifBlank { "en" },
        paramsText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    )

    OutlinedTextField(
        value = template,
        onValueChange = { template = it; push() },
        label = { Text("WhatsApp template name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = language,
        onValueChange = { language = it; push() },
        label = { Text("Language code") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = paramsText,
        onValueChange = { paramsText = it; push() },
        label = { Text("Body params (one per line)") },
        supportingText = { Text("Use {{contact_name}}, {{business_name}}, {{number}}") },
        modifier = Modifier.fillMaxWidth()
    )
}

private enum class WaitUnit(val label: String, val seconds: Long) {
    MINUTES("Minutes", 60), HOURS("Hours", 3_600), DAYS("Days", 86_400)
}

@Composable
private fun WaitEditor(node: FlowNode, vm: FlowBuilderViewModel) {
    val initialUnit = when {
        node.waitSeconds % 86_400L == 0L -> WaitUnit.DAYS
        node.waitSeconds % 3_600L == 0L -> WaitUnit.HOURS
        else -> WaitUnit.MINUTES
    }
    var unit by remember(node.id) { mutableStateOf(initialUnit) }
    var value by remember(node.id) { mutableStateOf((node.waitSeconds / initialUnit.seconds).toString()) }

    fun push() = vm.updateWait(node.id, (value.toLongOrNull() ?: 0L) * unit.seconds)

    OutlinedTextField(
        value = value,
        onValueChange = { value = it.filter { c -> c.isDigit() }; push() },
        label = { Text("Wait") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WaitUnit.entries.forEach { u ->
            FilterChip(selected = unit == u, onClick = { unit = u; push() }, label = { Text(u.label) })
        }
    }
}

@Composable
private fun ConditionEditor(node: FlowNode, vm: FlowBuilderViewModel) {
    Text("Check whether the caller replied on WhatsApp.", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = node.check == "not_replied",
            onClick = { vm.updateCondition(node.id, "not_replied") },
            label = { Text("Not replied") }
        )
        FilterChip(
            selected = node.check == "replied",
            onClick = { vm.updateCondition(node.id, "replied") },
            label = { Text("Replied") }
        )
    }
}

private fun typeLabel(t: NodeType) = when (t) {
    NodeType.TRIGGER -> "Call received"
    NodeType.SEND -> "Send WhatsApp"
    NodeType.WAIT -> "Wait"
    NodeType.CONDITION -> "Condition"
}

private fun typeIcon(t: NodeType) = when (t) {
    NodeType.TRIGGER -> Icons.Filled.CallReceived
    NodeType.SEND -> Icons.Filled.Send
    NodeType.WAIT -> Icons.Filled.Schedule
    NodeType.CONDITION -> Icons.Filled.CallSplit
}

private fun typeColor(t: NodeType) = when (t) {
    NodeType.TRIGGER -> Color(0xFF1565C0)
    NodeType.SEND -> Color(0xFF2E7D32)
    NodeType.WAIT -> Color(0xFFEF6C00)
    NodeType.CONDITION -> Color(0xFF6A1B9A)
}

private fun nodeSummary(node: FlowNode): String = when (node.type) {
    NodeType.TRIGGER -> "When a call comes in"
    NodeType.SEND -> node.template.ifBlank { "(pick a template)" }
    NodeType.WAIT -> humanizeWait(node.waitSeconds)
    NodeType.CONDITION -> "If ${node.check.replace('_', ' ')}"
}

private fun humanizeWait(seconds: Long): String = when {
    seconds % 86_400L == 0L && seconds >= 86_400L -> "${seconds / 86_400L} day(s)"
    seconds % 3_600L == 0L && seconds >= 3_600L -> "${seconds / 3_600L} hour(s)"
    else -> "${seconds / 60L} min"
}
