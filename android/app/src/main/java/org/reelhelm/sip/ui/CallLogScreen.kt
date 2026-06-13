package org.reelhelm.sip.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.reelhelm.sip.data.CallLogEntity

@Composable
fun CallLogScreen(vm: SipViewModel) {
    val logs by vm.callLog.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Recents", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (logs.isNotEmpty()) {
                TextButton(onClick = { vm.clearCallLog() }) { Text("Clear") }
            }
        }
        if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No recent calls", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(logs, key = { it.id }) { e -> CallLogRow(vm, e) }
            }
        }
    }
}

@Composable
private fun CallLogRow(vm: SipViewModel, e: CallLogEntity) {
    val name = rememberContactName(vm, e.peer)
    val missed = e.incoming && !e.answered
    val icon = when {
        !e.incoming -> Icons.Filled.CallMade
        e.answered -> Icons.Filled.CallReceived
        else -> Icons.Filled.CallMissed
    }
    val tint = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { vm.call(e.accountId, e.peer) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name ?: e.peer,
                style = MaterialTheme.typography.titleMedium,
                color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (name != null) {
                Text(
                    e.peer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                callSubtitle(e),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { vm.call(e.accountId, e.peer) }) {
            Icon(Icons.Filled.Call, contentDescription = "Call back", tint = MaterialTheme.colorScheme.primary)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
private fun callSubtitle(e: CallLogEntity): String {
    val time = formatMessageTime(e.timestamp)
    val detail = when {
        e.incoming && !e.answered -> "Missed"
        !e.incoming && !e.answered -> "No answer"
        else -> formatDuration(e.durationSec)
    }
    return "$time · $detail"
}

private fun formatDuration(sec: Int): String =
    if (sec >= 60) "${sec / 60}m ${sec % 60}s" else "${sec}s"
