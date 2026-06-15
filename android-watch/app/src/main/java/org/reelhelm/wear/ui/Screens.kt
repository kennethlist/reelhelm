package org.reelhelm.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import org.reelhelm.wear.data.MessageDirection
import org.reelhelm.wear.data.MessageStatus
import org.reelhelm.wear.sip.RegState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
private fun ts(t: Long) = timeFmt.format(Date(t))

/** Home launcher menu. */
@Composable
fun HomeScreen(
    vm: WearViewModel,
    onMessages: () -> Unit,
    onMissed: () -> Unit,
    onAccount: () -> Unit,
    onStatus: () -> Unit,
) {
    val convos by vm.conversations.collectAsStateWithLifecycle()
    val missed by vm.missedCalls.collectAsStateWithLifecycle()
    val reg by vm.registration.collectAsStateWithLifecycle()
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = rememberScalingLazyListState(),
    ) {
        item { ListHeader { Text("reelhelm") } }
        item {
            MenuChip("Messages", "${convos.size} conversations", onMessages)
        }
        item {
            MenuChip("Missed calls", if (missed.isEmpty()) "none" else "${missed.size} missed", onMissed)
        }
        item { MenuChip("Account", regLabel(reg), onAccount) }
        item { MenuChip("Sync status", "tap to sync", onStatus) }
    }
}

@Composable
fun ConversationsScreen(vm: WearViewModel, onOpen: (String) -> Unit) {
    val convos by vm.conversations.collectAsStateWithLifecycle()
    ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
        item { ListHeader { Text("Messages") } }
        if (convos.isEmpty()) {
            item { Text("No conversations yet", textAlign = TextAlign.Center) }
        }
        items(convos, key = { it.peer }) { c ->
            Chip(
                modifier = Modifier.fillMaxWidth(),
                label = { Text(c.peer) },
                secondaryLabel = { Text(c.lastBody, maxLines = 1) },
                onClick = { onOpen(c.peer) },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }
}

@Composable
fun ConversationScreen(vm: WearViewModel, peer: String) {
    val threadFlow = remember(peer) { vm.thread(peer) }
    val thread by threadFlow.collectAsStateWithLifecycle()
    val launchInput = rememberTextInput { vm.send(peer, it) }
    val quickReplies = listOf("On my way", "OK 👍", "Call you later", "Yes", "No")

    ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
        item { ListHeader { Text(peer) } }
        items(thread, key = { it.id }) { m ->
            val mine = m.direction == MessageDirection.OUTGOING
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
                Text(
                    m.body,
                    textAlign = if (mine) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (mine) "${ts(m.timestamp)} · ${statusLabel(m.status)}" else ts(m.timestamp),
                    textAlign = if (mine) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                    style = androidx.wear.compose.material.MaterialTheme.typography.caption2,
                )
            }
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Reply…") },
                onClick = { launchInput("Reply to $peer") },
                colors = ChipDefaults.primaryChipColors(),
            )
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Call $peer") },
                onClick = { vm.placeCall(peer) },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        item { Text("Quick replies", style = androidx.wear.compose.material.MaterialTheme.typography.caption1) }
        items(quickReplies) { qr ->
            Chip(
                modifier = Modifier.fillMaxWidth(),
                label = { Text(qr) },
                onClick = { vm.send(peer, qr) },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }
}

@Composable
fun MissedCallsScreen(vm: WearViewModel, onMessage: (String) -> Unit) {
    val missed by vm.missedCalls.collectAsStateWithLifecycle()
    ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
        item { ListHeader { Text("Missed calls") } }
        if (missed.isEmpty()) {
            item { Text("No missed calls", textAlign = TextAlign.Center) }
        }
        items(missed, key = { it.id }) { c ->
            Chip(
                modifier = Modifier.fillMaxWidth(),
                label = { Text(c.peer) },
                secondaryLabel = { Text("Missed · ${ts(c.timestamp)}") },
                onClick = { onMessage(c.peer) },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }
}

@Composable
fun AccountScreen(vm: WearViewModel) {
    val account by vm.account.collectAsStateWithLifecycle()
    val a = account

    // Per-field editors via RemoteInput. Each edit preserves the OTHER fields from
    // the current account (empty fallbacks — no hardcoded credentials).
    val name = a?.displayName.orEmpty()
    val user = a?.username.orEmpty()
    val host = a?.host.orEmpty()
    val port = a?.port ?: 5060
    val pass = a?.password.orEmpty()

    val editName = rememberTextInput { v -> vm.saveAccount(v, user, host, port, pass) }
    val editUser = rememberTextInput { v -> vm.saveAccount(name, v, host, port, pass) }
    val editHost = rememberTextInput { v -> vm.saveAccount(name, user, v, port, pass) }
    val editPort = rememberTextInput { v -> vm.saveAccount(name, user, host, v.toIntOrNull() ?: 5060, pass) }
    val editPass = rememberTextInput { v -> vm.saveAccount(name, user, host, port, v) }

    ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
        item { ListHeader { Text("Account") } }
        item { FieldChip("Name", a?.displayName ?: "—") { editName("Display name") } }
        item { FieldChip("Extension", a?.username ?: "—") { editUser("Extension / username") } }
        item { FieldChip("Server", a?.host ?: "—") { editHost("Server host") } }
        item { FieldChip("Port", (a?.port ?: 5060).toString()) { editPort("Port") } }
        item { FieldChip("Password", if (a?.password.isNullOrEmpty()) "—" else "••••") { editPass("Password") } }
    }
}

@Composable
fun StatusScreen(vm: WearViewModel) {
    val reg by vm.registration.collectAsStateWithLifecycle()
    val sync by vm.lastSync.collectAsStateWithLifecycle()
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { ListHeader { Text("Sync status") } }
        item { InfoRow("Registration", regLabel(reg)) }
        item {
            InfoRow(
                "Last sync",
                sync?.let { "${it.newMessages} msg, ${it.newMissed} missed @ ${ts(it.at)}" } ?: "never",
            )
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Sync now") },
                onClick = { vm.syncNow() },
                colors = ChipDefaults.primaryChipColors(),
            )
        }
        item {
            Text(
                "Background sync runs every 15 min via WorkManager (Doze-friendly, no push).",
                style = androidx.wear.compose.material.MaterialTheme.typography.caption2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---- small reusable bits ----

@Composable
private fun MenuChip(title: String, subtitle: String, onClick: () -> Unit) {
    Chip(
        modifier = Modifier.fillMaxWidth(),
        label = { Text(title) },
        secondaryLabel = { Text(subtitle) },
        onClick = onClick,
        colors = ChipDefaults.secondaryChipColors(),
    )
}

@Composable
private fun FieldChip(label: String, value: String, onClick: () -> Unit) {
    Chip(
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        secondaryLabel = { Text(value) },
        onClick = onClick,
        colors = ChipDefaults.secondaryChipColors(),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
        Text(label, style = androidx.wear.compose.material.MaterialTheme.typography.caption1)
        Text(value)
    }
}

private fun regLabel(reg: RegState) = when (reg) {
    RegState.REGISTERED -> "Registered"
    RegState.REGISTERING -> "Registering…"
    RegState.UNREGISTERED -> "Not registered"
    RegState.FAILED -> "Registration failed"
}

private fun statusLabel(s: MessageStatus) = when (s) {
    MessageStatus.PENDING -> "sending…"
    MessageStatus.SENT -> "sent"
    MessageStatus.FAILED -> "failed"
    MessageStatus.RECEIVED -> "received"
}
