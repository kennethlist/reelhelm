package org.reelhelm.sip.telecom

import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.DisconnectCause
import org.reelhelm.sip.sip.CallSession
import org.reelhelm.sip.sip.CallState
import org.reelhelm.sip.sip.SipManager
import org.reelhelm.sip.sip.TelecomCallback

/**
 * A Telecom [Connection] backing one [CallSession]. Maps system call-control
 * actions onto the SIP call, and SIP-side state changes back onto Telecom.
 */
class SipConnection(
    private val manager: SipManager,
    private val session: CallSession,
) : Connection(), TelecomCallback {

    init {
        audioModeIsVoip = true
        connectionCapabilities = CAPABILITY_MUTE or CAPABILITY_SUPPORT_HOLD or CAPABILITY_HOLD
        setAddress(
            android.net.Uri.fromParts(android.telecom.PhoneAccount.SCHEME_SIP, session.peer, null),
            TelecomManagerPresentation.ALLOWED,
        )
        setCallerDisplayName(session.peer, TelecomManagerPresentation.ALLOWED)
        session.telecomCallback = this
    }

    // ---- SIP -> Telecom ---------------------------------------------------

    override fun onSipStateChanged(state: CallState) {
        when (state) {
            CallState.CONNECTING, CallState.RINGING_OUT -> setDialing()
            CallState.RINGING_IN -> setRinging()
            CallState.ACTIVE -> setActive()
            CallState.HOLD -> setOnHold()
            CallState.ENDED -> {
                setDisconnected(DisconnectCause(DisconnectCause.REMOTE))
                destroy()
            }
        }
    }

    // ---- Telecom -> SIP ---------------------------------------------------

    override fun onAnswer() {
        manager.answer(session)
    }

    override fun onReject() {
        manager.hangup(session)
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
    }

    override fun onDisconnect() {
        manager.hangup(session)
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
    }

    override fun onAbort() {
        manager.hangup(session)
        setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
        destroy()
    }

    override fun onHold() {
        manager.setHold(session, true)
        setOnHold()
    }

    override fun onUnhold() {
        manager.setHold(session, false)
        setActive()
    }

    override fun onPlayDtmfTone(c: Char) {
        manager.sendDtmf(session, c.toString())
    }

    override fun onCallAudioStateChanged(state: CallAudioState) {
        manager.setMuted(session, state.isMuted)
        // Reflect the real route back into the call snapshot so the UI's speaker
        // toggle stays in sync (incl. when the system/headset changes it).
        manager.updateSpeakerState(session, state.route == CallAudioState.ROUTE_SPEAKER)
    }

    // ---- UI -> Telecom audio route ----------------------------------------

    override fun onRequestSpeaker(on: Boolean) {
        setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE)
    }
}

/** Tiny alias so we don't repeat the long Connection presentation constant. */
private object TelecomManagerPresentation {
    const val ALLOWED = android.telecom.TelecomManager.PRESENTATION_ALLOWED
}
