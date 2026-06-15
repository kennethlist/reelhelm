package org.reelhelm.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import org.reelhelm.wear.sip.CallSnapshot
import org.reelhelm.wear.sip.CallState

/**
 * Full-screen call UI that auto-surfaces whenever there's a live call (driven by
 * [WearViewModel.calls]). RINGING_IN shows Answer/Decline; otherwise the in-call
 * controls (mute / speaker / hang up).
 */
@Composable
fun CallScreen(call: CallSnapshot, vm: WearViewModel) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(8.dp),
        ) {
            Text(call.peer, style = MaterialTheme.typography.title2)
            Text(stateLabel(call.state), style = MaterialTheme.typography.caption1, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))

            if (call.state == CallState.RINGING_IN) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    RoundButton(Icons.Filled.CallEnd, Color(0xFFD32F2F)) { vm.hangupCall(call.id) }
                    RoundButton(Icons.Filled.Call, Color(0xFF388E3C)) { vm.answerCall(call.id) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RoundButton(
                        if (call.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        if (call.muted) Color(0xFF616161) else Color(0xFF1565C0),
                    ) { vm.toggleMute(call.id, !call.muted) }
                    RoundButton(
                        Icons.Filled.VolumeUp,
                        if (call.speakerOn) Color(0xFF1565C0) else Color(0xFF616161),
                    ) { vm.toggleSpeaker(call.id, !call.speakerOn) }
                    RoundButton(Icons.Filled.CallEnd, Color(0xFFD32F2F)) { vm.hangupCall(call.id) }
                }
            }
        }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, bg: Color, onClick: () -> Unit) {
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(backgroundColor = bg)) {
        Icon(icon, contentDescription = null, tint = Color.White)
    }
}

private fun stateLabel(s: CallState) = when (s) {
    CallState.CONNECTING -> "Connecting…"
    CallState.RINGING_OUT -> "Ringing…"
    CallState.RINGING_IN -> "Incoming call"
    CallState.ACTIVE -> "Connected"
    CallState.ENDED -> "Ended"
}
