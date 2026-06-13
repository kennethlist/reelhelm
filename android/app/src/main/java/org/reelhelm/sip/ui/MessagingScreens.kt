package org.reelhelm.sip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import android.text.format.DateUtils
import org.reelhelm.sip.data.MessageDirection
import org.reelhelm.sip.data.MessageStatus

@Composable
fun MessagesScreen(vm: SipViewModel, nav: NavController) {
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    if (conversations.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No messages yet")
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(conversations, key = { it.peer }) { c ->
            val name = rememberContactName(vm, c.peer)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { nav.navigate("conversation/${c.peer}") }
                    .padding(16.dp),
            ) {
                Text(name ?: c.peer, style = MaterialTheme.typography.titleMedium)
                if (name != null) {
                    Text(
                        c.peer,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    c.lastBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun ConversationScreen(vm: SipViewModel, peer: String) {
    val threadFlow = remember(peer) { vm.thread(peer) }
    val thread by threadFlow.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val name = rememberContactName(vm, peer)

    // Keep the newest message in view: scroll to the bottom on a new message and
    // whenever the viewport shrinks. The activity uses adjustResize (with
    // edge-to-edge enforcement opted out), so opening the keyboard resizes the
    // window — the list shrinks and stays scrollable above the input.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val viewportH = listState.layoutInfo.viewportSize.height
    LaunchedEffect(thread.size, imeVisible, viewportH) {
        if (thread.isNotEmpty()) listState.animateScrollToItem(thread.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(name ?: peer, style = MaterialTheme.typography.titleLarge)
            if (name != null) {
                Text(
                    peer,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(thread, key = { it.id }) { m ->
                val outgoing = m.direction == MessageDirection.OUTGOING
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
                ) {
                    val bg = if (outgoing) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                    Text(
                        m.body,
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(bg)
                            .padding(10.dp),
                    )
                    // Time, plus delivery state for our outgoing messages. SIP
                    // MESSAGE is page-mode: a 200 OK means the server accepted it
                    // (Sent), not a true carrier delivery receipt — so there's no
                    // "Delivered". PENDING shows until that 200 OK arrives.
                    val status = if (outgoing) when (m.status) {
                        MessageStatus.PENDING -> "Sending…"
                        MessageStatus.SENT -> "Sent"
                        MessageStatus.FAILED -> "Failed"
                        MessageStatus.RECEIVED -> null
                    } else null
                    val time = formatMessageTime(m.timestamp)
                    Text(
                        if (status != null) "$time · $status" else time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Text message") },
                supportingText = {
                    // Surface the server's SIP-MESSAGE limit instead of hiding it.
                    if (draft.length > 160) Text("${draft.length} chars — will be split (160/segment, no MMS)")
                },
            )
            IconButton(
                onClick = { vm.sendMessage(peer, draft); draft = "" },
                enabled = draft.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

/**
 * Resolve a contact display name for [number] from the address book (off the
 * main thread). Returns null if there's no match (or no READ_CONTACTS), and
 * never returns the number itself so callers can show name + number distinctly.
 */
/** Locale-aware time for a message; includes the date when it's not today. */
@Composable
internal fun formatMessageTime(ts: Long): String {
    val context = LocalContext.current
    val flags = DateUtils.FORMAT_SHOW_TIME or
        if (DateUtils.isToday(ts)) 0
        else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    return DateUtils.formatDateTime(context, ts, flags)
}

@Composable
internal fun rememberContactName(vm: SipViewModel, number: String): String? {
    val name by produceState<String?>(initialValue = null, number) {
        value = runCatching { vm.resolveContact(number)?.name }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != number }
    }
    return name
}
