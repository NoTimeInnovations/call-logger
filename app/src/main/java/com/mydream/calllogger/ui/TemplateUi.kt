package com.mydream.calllogger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mydream.calllogger.net.WaTemplate

/** Colour of a template status chip. */
@Composable
fun statusColor(status: String?): Color = when ((status ?: "").uppercase()) {
    "APPROVED" -> Color(0xFF16A34A)
    "PENDING" -> Color(0xFFEA580C)
    "REJECTED" -> Color(0xFFDC2626)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * WhatsApp-style preview of a template's header/body/footer, with {{n}} placeholders
 * filled from [params]. Mirrors cravings-v2's TemplatePreview so in-app matches the web.
 */
@Composable
fun TemplateBubble(
    body: String,
    footer: String?,
    params: List<String> = emptyList(),
    headerMediaFormat: String? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color(0xFFE5DDD5),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.widthIn(max = 260.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    if (headerMediaFormat != null) {
                        Surface(
                            color = Color(0xFFF1F1F1),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                headerMediaFormat,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF9AA0A6),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 18.dp),
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    val text = WaTemplate.fillPlaceholders(body, params).ifBlank { "No body text" }
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF1F2937),
                    )
                    if (!footer.isNullOrBlank()) {
                        Text(
                            footer,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF9AA0A6),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TemplateStatusChip(status: String?) {
    Surface(
        color = statusColor(status).copy(alpha = 0.14f),
        shape = RoundedCornerShape(6.dp),
        contentColor = statusColor(status),
    ) {
        Text(
            (status ?: "?").uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
