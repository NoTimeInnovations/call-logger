package com.mydream.calllogger.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * A WhatsApp message template as returned by the Worker's GET /templates (mirrored
 * from cravings-v2's `whatsapp_message_templates`). Only the fields we render/pick are
 * modelled. Preview helpers mirror cravings-v2's TemplatePicker.tsx so the in-app
 * preview matches the web one.
 */
data class WaTemplateComponent(
    val type: String,            // HEADER | BODY | FOOTER | BUTTONS
    val format: String? = null,  // TEXT | IMAGE | VIDEO | DOCUMENT (HEADER only)
    val text: String? = null,
    val buttons: List<String> = emptyList(),
)

data class WaTemplate(
    val name: String,
    val language: String,
    val category: String?,
    val status: String?,
    val components: List<WaTemplateComponent>,
) {
    val isApproved: Boolean get() = (status ?: "").uppercase() == "APPROVED"
    val header: WaTemplateComponent? get() = components.firstOrNull { it.type == "HEADER" }
    val body: String get() = components.firstOrNull { it.type == "BODY" }?.text ?: ""
    val footer: String? get() = components.firstOrNull { it.type == "FOOTER" }?.text

    /** HEADER format that needs media supplied at send time (IMAGE/VIDEO/DOCUMENT), or null. */
    val headerMediaFormat: String? get() = header?.format?.takeIf { it != "TEXT" }

    /** How many distinct {{n}} placeholders the body needs. */
    val variableCount: Int
        get() = PLACEHOLDER.findAll(body).map { it.groupValues[1] }.toSet().size

    companion object {
        private val PLACEHOLDER = Regex("""\{\{(\d+)}}""")

        /** Replace {{1}}, {{2}} … with [params] (keeps the token when a param is missing). */
        fun fillPlaceholders(body: String, params: List<String>): String =
            PLACEHOLDER.replace(body) { m ->
                val i = (m.groupValues[1].toIntOrNull() ?: return@replace m.value) - 1
                params.getOrNull(i)?.takeIf { it.isNotBlank() } ?: m.value
            }

        /** Parse the Worker's `{ "items": [...] }` response. Empty list on any error. */
        fun parseList(json: String?): List<WaTemplate> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val items = JSONObject(json).optJSONArray("items") ?: return emptyList()
                (0 until items.length()).mapNotNull { i -> items.optJSONObject(i)?.let(::parseOne) }
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun parseOne(o: JSONObject): WaTemplate = WaTemplate(
            name = o.optString("name"),
            language = o.optString("language", "en"),
            category = o.optString("category").ifBlank { null },
            status = o.optString("status").ifBlank { null },
            components = parseComponents(o.opt("components")),
        )

        /** `components` may arrive as a JSON array or as a JSON string (jsonb round-trip). */
        private fun parseComponents(raw: Any?): List<WaTemplateComponent> {
            val arr: JSONArray = when (raw) {
                is JSONArray -> raw
                is String -> try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
                else -> JSONArray()
            }
            return (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                val buttons = c.optJSONArray("buttons")?.let { b ->
                    (0 until b.length()).mapNotNull { j ->
                        b.optJSONObject(j)?.optString("text")?.ifBlank { null }
                    }
                } ?: emptyList()
                WaTemplateComponent(
                    type = c.optString("type").uppercase(),
                    format = c.optString("format").ifBlank { null }?.uppercase(),
                    text = c.optString("text").ifBlank { null },
                    buttons = buttons,
                )
            }
        }
    }
}
