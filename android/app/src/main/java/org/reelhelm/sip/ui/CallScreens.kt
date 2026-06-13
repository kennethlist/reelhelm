package org.reelhelm.sip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.reelhelm.sip.sip.CallDirection
import org.reelhelm.sip.sip.CallSnapshot
import org.reelhelm.sip.sip.CallState
import org.reelhelm.sip.sip.RegState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialerScreen(vm: SipViewModel) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val regStates by vm.regStates.collectAsStateWithLifecycle()
    var number by remember { mutableStateOf("") }
    var selectedAccount by remember { mutableStateOf<Long?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val accountId = selectedAccount ?: accounts.firstOrNull { it.enabled }?.id
    val selected = accounts.firstOrNull { it.id == accountId }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Account selector + registration status.
        if (accounts.isNotEmpty()) {
            Box {
                TextButton(onClick = { menuOpen = true }) {
                    val reg = regStates[accountId]?.state ?: RegState.UNREGISTERED
                    Text("${selected?.displayName ?: "Account"} • ${reg.name}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    accounts.filter { it.enabled }.forEach { acc ->
                        DropdownMenuItem(
                            text = { Text(acc.displayName) },
                            onClick = { selectedAccount = acc.id; menuOpen = false },
                        )
                    }
                }
            }
        } else {
            Text("No accounts — add one in Settings", color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(16.dp))
        Text(
            number,
            fontSize = 32.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        DialPad(
            onDigit = { number += it },
            onBackspace = { number = number.dropLast(1) },
        )

        Spacer(Modifier.height(24.dp))
        FilledIconButton(
            onClick = { accountId?.let { vm.call(it, number) } },
            enabled = accountId != null && number.isNotBlank(),
            modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF2E7D32)),
        ) {
            Icon(Icons.Filled.Call, contentDescription = "Call", tint = Color.White)
        }
    }
}

@Composable
private fun DialPad(onDigit: (String) -> Unit, onBackspace: () -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("*", "0", "#"),
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                row.forEach { d ->
                    TextButton(onClick = { onDigit(d) }, modifier = Modifier.size(72.dp)) {
                        Text(d, fontSize = 28.sp)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = onBackspace) {
                Icon(Icons.Filled.Backspace, contentDescription = "Delete")
            }
        }
    }
}

@Composable
fun InCallScreen(vm: SipViewModel, call: CallSnapshot) {
    var showKeypad by remember(call.id) { mutableStateOf(false) }
    val active = call.state == CallState.ACTIVE || call.state == CallState.HOLD

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            Modifier.padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(call.peer, fontSize = 28.sp)
            Spacer(Modifier.height(8.dp))
            Text(stateLabel(call.state), color = MaterialTheme.colorScheme.primary)
        }

        // Middle area: the in-call controls, or the DTMF keypad when toggled on.
        // Only meaningful once the call is connected.
        when {
            active && showKeypad -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    DtmfPad(onDigit = { vm.dtmf(call.id, it) })
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { showKeypad = false }) { Text("Hide keypad") }
                }
            }
            active -> {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    CallControl(
                        icon = if (call.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        label = "Mute",
                        selected = call.muted,
                    ) { vm.setMuted(call.id, !call.muted) }
                    CallControl(
                        icon = Icons.Filled.VolumeUp,
                        label = "Speaker",
                        selected = call.speakerOn,
                    ) { vm.setSpeaker(call.id, !call.speakerOn) }
                    CallControl(
                        icon = Icons.Filled.Dialpad,
                        label = "Keypad",
                        selected = false,
                    ) { showKeypad = true }
                    CallControl(
                        icon = Icons.Filled.Pause,
                        label = "Hold",
                        selected = call.onHold,
                    ) { vm.setHold(call.id, !call.onHold) }
                }
            }
            else -> Spacer(Modifier.height(1.dp))
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (call.direction == CallDirection.INCOMING && call.state == CallState.RINGING_IN) {
                FilledIconButton(
                    onClick = { vm.answer(call.id) },
                    modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF2E7D32)),
                ) { Icon(Icons.Filled.Call, contentDescription = "Answer", tint = Color.White) }
            }
            FilledIconButton(
                onClick = { vm.hangup(call.id) },
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFC62828)),
            ) { Icon(Icons.Filled.CallEnd, contentDescription = "Hang up", tint = Color.White) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** One round in-call control button with a label; highlighted when [selected]. */
@Composable
private fun CallControl(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(56.dp),
            colors = if (selected) {
                IconButtonDefaults.filledIconButtonColors()
            } else {
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        ) { Icon(icon, contentDescription = label) }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** In-call DTMF keypad: each press sends the tone down the active call. */
@Composable
private fun DtmfPad(onDigit: (String) -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("*", "0", "#"),
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { d ->
                    TextButton(
                        onClick = { onDigit(d) },
                        modifier = Modifier.size(64.dp),
                    ) { Text(d, fontSize = 24.sp) }
                }
            }
        }
    }
}

private fun stateLabel(state: CallState): String = when (state) {
    CallState.CONNECTING -> "Connecting…"
    CallState.RINGING_OUT -> "Ringing…"
    CallState.RINGING_IN -> "Incoming call"
    CallState.ACTIVE -> "In call"
    CallState.HOLD -> "On hold"
    CallState.ENDED -> "Call ended"
}
