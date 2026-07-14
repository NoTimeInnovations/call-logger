package com.mydream.calllogger.flow

import org.json.JSONArray
import org.json.JSONObject

/** Node kinds in a call-follow-up flow. Only one trigger ("call received") is allowed. */
enum class NodeType(val key: String) {
    TRIGGER("trigger"),
    SEND("send"),
    WAIT("wait"),
    CONDITION("condition");

    companion object {
        fun from(k: String): NodeType = entries.firstOrNull { it.key == k } ?: SEND
    }
}

/** A node with its canvas position and type-specific fields. */
data class FlowNode(
    val id: String,
    val type: NodeType,
    var x: Float = 0f,
    var y: Float = 0f,
    // send
    var template: String = "",
    var language: String = "en",
    var params: List<String> = emptyList(),
    // wait
    var waitSeconds: Long = 86_400,
    // condition
    var check: String = "not_replied",
)

/** A directed connection. `branch` is "true"/"false" for condition outputs. */
data class FlowEdge(val from: String, val to: String?, val branch: String? = null)

data class FlowGraph(
    val nodes: MutableList<FlowNode> = mutableListOf(),
    val edges: MutableList<FlowEdge> = mutableListOf(),
    var enabled: Boolean = false,
    var name: String = "Call follow-up",
)

/** Serializes/parses the graph to the exact JSON contract the Worker expects. */
object FlowJson {

    /** Body for PUT /flow: { name, enabled, graph: { nodes, edges } }. */
    fun toRequestBody(g: FlowGraph): String {
        val nodes = JSONArray()
        for (n in g.nodes) {
            val data = JSONObject()
            when (n.type) {
                NodeType.TRIGGER -> data.put("event", "call_received")
                NodeType.SEND -> {
                    data.put("template", n.template)
                    data.put("language", n.language)
                    data.put("params", JSONArray(n.params))
                }
                NodeType.WAIT -> data.put("seconds", n.waitSeconds)
                NodeType.CONDITION -> data.put("check", n.check)
            }
            nodes.put(
                JSONObject()
                    .put("id", n.id)
                    .put("type", n.type.key)
                    .put("position", JSONObject().put("x", n.x.toDouble()).put("y", n.y.toDouble()))
                    .put("data", data)
            )
        }
        val edges = JSONArray()
        for (e in g.edges) {
            val edge = JSONObject().put("from", e.from).put("to", e.to ?: JSONObject.NULL)
            if (e.branch != null) edge.put("branch", e.branch)
            edges.put(edge)
        }
        val graph = JSONObject().put("nodes", nodes).put("edges", edges)
        return JSONObject()
            .put("name", g.name)
            .put("enabled", g.enabled)
            .put("graph", graph)
            .toString()
    }

    /** Parses a GET /flow response ({ name, enabled, graph: {...} } or empty). */
    fun parse(json: String): FlowGraph {
        val root = JSONObject(json)
        val g = FlowGraph(
            enabled = root.optBoolean("enabled", false),
            name = root.optString("name", "Call follow-up"),
        )
        val graph = root.optJSONObject("graph") ?: JSONObject()
        val nodes = graph.optJSONArray("nodes") ?: JSONArray()
        for (i in 0 until nodes.length()) {
            val o = nodes.getJSONObject(i)
            val type = NodeType.from(o.optString("type"))
            val data = o.optJSONObject("data") ?: JSONObject()
            val pos = o.optJSONObject("position")
            val node = FlowNode(
                id = o.optString("id"),
                type = type,
                x = pos?.optDouble("x", 0.0)?.toFloat() ?: 0f,
                y = pos?.optDouble("y", 0.0)?.toFloat() ?: 0f,
                template = data.optString("template", ""),
                language = data.optString("language", "en"),
                params = data.optJSONArray("params")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }
                } ?: emptyList(),
                waitSeconds = data.optLong("seconds", 86_400),
                check = data.optString("check", "not_replied"),
            )
            g.nodes.add(node)
        }
        val edges = graph.optJSONArray("edges") ?: JSONArray()
        for (i in 0 until edges.length()) {
            val o = edges.getJSONObject(i)
            val to = if (o.isNull("to")) null else o.optString("to")
            val branch = if (o.has("branch")) o.optString("branch") else null
            g.edges.add(FlowEdge(o.optString("from"), to, branch))
        }
        return g
    }

    /** A fresh graph containing just the fixed trigger node. */
    fun starter(): FlowGraph =
        FlowGraph(nodes = mutableListOf(FlowNode(id = "trigger", type = NodeType.TRIGGER, x = 40f, y = 40f)))
}
