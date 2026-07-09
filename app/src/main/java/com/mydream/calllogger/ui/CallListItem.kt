package com.mydream.calllogger.ui

import android.provider.CallLog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mydream.calllogger.data.CallEntity
import com.mydream.calllogger.data.CallTypes
import com.mydream.calllogger.export.Formatters

private data class TypeStyle(val icon: ImageVector, val color: Color)

@Composable
private fun styleFor(type: Int): TypeStyle = when (type) {
    CallLog.Calls.INCOMING_TYPE -> TypeStyle(Icons.AutoMirrored.Filled.CallReceived, Color(0xFF16A34A))
    CallLog.Calls.OUTGOING_TYPE -> TypeStyle(Icons.AutoMirrored.Filled.CallMade, Color(0xFF2563EB))
    CallLog.Calls.MISSED_TYPE -> TypeStyle(Icons.AutoMirrored.Filled.CallMissed, Color(0xFFDC2626))
    CallLog.Calls.REJECTED_TYPE -> TypeStyle(Icons.Filled.Block, Color(0xFFDC2626))
    CallLog.Calls.BLOCKED_TYPE -> TypeStyle(Icons.Filled.Block, Color(0xFF64748B))
    else -> TypeStyle(Icons.Filled.Call, Color(0xFF64748B))
}

@Composable
fun CallListItem(call: CallEntity) {
    val style = styleFor(call.type)
    val title = call.name?.takeIf { it.isNotBlank() } ?: call.number

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(style.color.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(style.icon, contentDescription = null, tint = style.color, modifier = Modifier.size(20.dp))
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = "${CallTypes.label(call.type)} · ${call.number}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(10.dp))

        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
            Text(
                text = Formatters.dateTime(call.date),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (call.type == CallLog.Calls.INCOMING_TYPE || call.type == CallLog.Calls.OUTGOING_TYPE) {
                Spacer(Modifier.size(2.dp))
                Text(
                    text = Formatters.duration(call.duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }
    }
}
