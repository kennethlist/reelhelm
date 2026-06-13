package org.reelhelm.sip.sip

import java.util.UUID

/** Our own call state, decoupled from pjsip_inv_state and Telecom states. */
enum class CallState { CONNECTING, RINGING_OUT, RINGING_IN, ACTIVE, HOLD, ENDED }

enum class CallDirection { INCOMING, OUTGOING }

/**
 * A live call. Bridges three worlds:
 *  - [pjCall]      the pjsua2 Call (native), created on the SIP thread
 *  - the Telecom SipConnection (bound later, by sessionId)
 *  - the UI, which observes [SipManager.calls]
 */
class CallSession(
    val id: String = UUID.randomUUID().toString(),
    val accountId: Long,
    val direction: CallDirection,
    /** Remote party — mutable because for incoming calls we learn it only after
     *  the pjsua2 Call exists and we can read its info. */
    @Volatile var peer: String,
) {
    @Volatile var pjCall: MyCall? = null
    @Volatile var state: CallState =
        if (direction == CallDirection.INCOMING) CallState.RINGING_IN else CallState.CONNECTING
    @Volatile var muted: Boolean = false
    @Volatile var onHold: Boolean = false
    /** Whether call audio is routed to the speakerphone (reflects the Telecom route). */
    @Volatile var speakerOn: Boolean = false

    /** When the session was created (used as the call-log timestamp). */
    val startedAt: Long = System.currentTimeMillis()
    /** When the call became ACTIVE, or 0 if it never connected (missed/declined). */
    @Volatile var connectedAt: Long = 0L
    /** Guard so the call is written to the history at most once. */
    @Volatile var loggedToHistory: Boolean = false

    /** Set by the Telecom layer so call control can drive the system UI. */
    @Volatile var telecomCallback: TelecomCallback? = null

    /** Immutable snapshot for Compose state. */
    fun snapshot() = CallSnapshot(id, accountId, direction, peer, state, muted, onHold, speakerOn)
}

data class CallSnapshot(
    val id: String,
    val accountId: Long,
    val direction: CallDirection,
    val peer: String,
    val state: CallState,
    val muted: Boolean,
    val onHold: Boolean,
    val speakerOn: Boolean,
)

/** Implemented by the Telecom SipConnection to receive SIP-side state changes. */
interface TelecomCallback {
    fun onSipStateChanged(state: CallState)

    /** Request routing call audio to the speakerphone (true) or earpiece (false). */
    fun onRequestSpeaker(on: Boolean)
}
