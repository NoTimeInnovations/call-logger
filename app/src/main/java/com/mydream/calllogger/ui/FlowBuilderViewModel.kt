package com.mydream.calllogger.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mydream.calllogger.flow.FlowEdge
import com.mydream.calllogger.flow.FlowGraph
import com.mydream.calllogger.flow.FlowJson
import com.mydream.calllogger.flow.FlowNode
import com.mydream.calllogger.flow.NodeType
import com.mydream.calllogger.net.AccountManager
import com.mydream.calllogger.net.FlowApi
import com.mydream.calllogger.net.IngestClient
import com.mydream.calllogger.prefs.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FlowUiState(
    val graph: FlowGraph = FlowJson.starter(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val selectedNodeId: String? = null,
    val connectFromId: String? = null,
    val connectBranch: String? = null,
    val notRegistered: Boolean = false,
    val message: String? = null,
)

class FlowBuilderViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsManager(app)
    private val account = AccountManager(app)

    private val _state = MutableStateFlow(FlowUiState())
    val state: StateFlow<FlowUiState> = _state.asStateFlow()

    private var idCounter = 1

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, notRegistered = false) }
            val token = ensureToken()
            if (token == null) {
                _state.update { it.copy(loading = false, notRegistered = true) }
                return@launch
            }
            val raw = withContext(Dispatchers.IO) { FlowApi.getFlow(token) }
            val graph = raw?.let { runCatching { FlowJson.parse(it) }.getOrNull() }
                ?.takeIf { it.nodes.isNotEmpty() } ?: FlowJson.starter()
            if (graph.nodes.none { it.type == NodeType.TRIGGER }) {
                graph.nodes.add(0, FlowNode(id = "trigger", type = NodeType.TRIGGER, x = 40f, y = 40f))
            }
            autoLayoutIfNeeded(graph)
            idCounter = 1 + (graph.nodes.mapNotNull { it.id.removePrefix("n").toIntOrNull() }.maxOrNull() ?: 0)
            _state.update { it.copy(graph = graph, loading = false) }
        }
    }

    fun save() {
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val token = ensureToken()
            if (token == null) {
                _state.update { it.copy(saving = false, notRegistered = true, message = "Not registered yet") }
                return@launch
            }
            val body = FlowJson.toRequestBody(_state.value.graph)
            val code = withContext(Dispatchers.IO) { FlowApi.putFlow(token, body) }
            _state.update {
                it.copy(
                    saving = false,
                    message = if (code in 200..299) "Flow saved" else "Save failed ($code)",
                )
            }
        }
    }

    private suspend fun ensureToken(): String? {
        account.token?.let { return it }
        val email = settings.email ?: return null
        val token = withContext(Dispatchers.IO) { IngestClient.register(email, settings.deviceId) }
        if (token != null) account.token = token
        return token
    }

    // --- graph mutations (each produces a fresh FlowGraph so Compose recomposes) ---

    private fun mutate(block: (nodes: MutableList<FlowNode>, edges: MutableList<FlowEdge>) -> Unit) {
        _state.update { s ->
            val nodes = s.graph.nodes.toMutableList()
            val edges = s.graph.edges.toMutableList()
            block(nodes, edges)
            s.copy(graph = FlowGraph(nodes, edges, s.graph.enabled, s.graph.name))
        }
    }

    fun setEnabled(enabled: Boolean) =
        _state.update { it.copy(graph = FlowGraph(it.graph.nodes, it.graph.edges, enabled, it.graph.name)) }

    fun setName(name: String) =
        _state.update { it.copy(graph = FlowGraph(it.graph.nodes, it.graph.edges, it.graph.enabled, name)) }

    fun addNode(type: NodeType) {
        val id = "n${idCounter++}"
        val node = FlowNode(
            id = id,
            type = type,
            x = 60f + (idCounter % 3) * 30f,
            y = 200f + (idCounter % 6) * 40f,
            template = if (type == NodeType.SEND) "call_followup" else "",
        )
        mutate { nodes, _ -> nodes.add(node) }
        select(id)
    }

    fun moveNode(id: String, dx: Float, dy: Float) = mutate { nodes, _ ->
        val i = nodes.indexOfFirst { it.id == id }
        if (i >= 0) nodes[i] = nodes[i].copy(x = (nodes[i].x + dx).coerceAtLeast(0f), y = (nodes[i].y + dy).coerceAtLeast(0f))
    }

    fun updateSend(id: String, template: String, language: String, params: List<String>) = mutate { nodes, _ ->
        val i = nodes.indexOfFirst { it.id == id }
        if (i >= 0) nodes[i] = nodes[i].copy(template = template, language = language, params = params)
    }

    fun updateWait(id: String, seconds: Long) = mutate { nodes, _ ->
        val i = nodes.indexOfFirst { it.id == id }
        if (i >= 0) nodes[i] = nodes[i].copy(waitSeconds = seconds.coerceAtLeast(0))
    }

    fun updateCondition(id: String, check: String) = mutate { nodes, _ ->
        val i = nodes.indexOfFirst { it.id == id }
        if (i >= 0) nodes[i] = nodes[i].copy(check = check)
    }

    fun deleteNode(id: String) {
        if (id == "trigger") return
        mutate { nodes, edges ->
            nodes.removeAll { it.id == id }
            edges.removeAll { it.from == id || it.to == id }
        }
        _state.update { if (it.selectedNodeId == id) it.copy(selectedNodeId = null) else it }
    }

    fun select(id: String?) = _state.update { it.copy(selectedNodeId = id) }

    /** Begin connecting from a node (optionally a condition branch). */
    fun startConnect(fromId: String, branch: String?) =
        _state.update { it.copy(connectFromId = fromId, connectBranch = branch) }

    fun cancelConnect() = _state.update { it.copy(connectFromId = null, connectBranch = null) }

    /** Complete a connection to [targetId]. Replaces any existing edge from the same handle. */
    fun completeConnect(targetId: String) {
        val from = _state.value.connectFromId ?: return
        val branch = _state.value.connectBranch
        if (from == targetId) { cancelConnect(); return }
        mutate { _, edges ->
            edges.removeAll { it.from == from && it.branch == branch }
            edges.add(FlowEdge(from = from, to = targetId, branch = branch))
        }
        cancelConnect()
    }

    fun removeEdgesFrom(id: String, branch: String?) =
        mutate { _, edges -> edges.removeAll { it.from == id && it.branch == branch } }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun autoLayoutIfNeeded(graph: FlowGraph) {
        val allZero = graph.nodes.all { it.x == 0f && it.y == 0f }
        if (!allZero) return
        graph.nodes.forEachIndexed { i, n ->
            n.x = 60f
            n.y = 40f + i * 130f
        }
    }
}
