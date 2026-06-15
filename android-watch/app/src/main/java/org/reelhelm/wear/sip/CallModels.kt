package org.reelhelm.wear.sip

import android.util.Log
import org.pjsip.pjsua2.AudDevManager
import org.pjsip.pjsua2.AudioMedia
import org.pjsip.pjsua2.Call
import org.pjsip.pjsua2.OnCallMediaStateParam
import org.pjsip.pjsua2.OnCallStateParam
import org.pjsip.pjsua2.pjmedia_type
import org.pjsip.pjsua2.pjsip_inv_state
import org.pjsip.pjsua2.pjsua_call_media_status
import java.util.UUID

/** Our call state, decoupled from pjsip_inv_state. */
enum class CallState { CONNECTING, RINGING_OUT, RINGING_IN, ACTIVE, ENDED }

enum class CallDirection { INCOMING, OUTGOING }

/** A live call session (no Telecom on the watch — managed directly). */
class CallSession(
    val id: String = UUID.randomUUID().toString(),
    val accountId: Long,
    val direction: CallDirection,
    @Volatile var peer: String,
) {
    @Volatile var pjCall: MyCall? = null
    @Volatile var state: CallState =
        if (direction == CallDirection.INCOMING) CallState.RINGING_IN else CallState.CONNECTING
    @Volatile var muted: Boolean = false
    @Volatile var speakerOn: Boolean = false

    val startedAt: Long = System.currentTimeMillis()
    @Volatile var connectedAt: Long = 0L
    @Volatile var loggedToHistory: Boolean = false

    fun snapshot() = CallSnapshot(id, accountId, direction, peer, state, muted, speakerOn)
}

data class CallSnapshot(
    val id: String,
    val accountId: Long,
    val direction: CallDirection,
    val peer: String,
    val state: CallState,
    val muted: Boolean,
    val speakerOn: Boolean,
)

/** Emitted when a call ends, for the call-history DB. */
data class CallLogRecord(
    val peer: String,
    val incoming: Boolean,
    val answered: Boolean,
    val durationSec: Int,
    val timestamp: Long,
)

/**
 * pjsua2 [Call] subclass. Callbacks arrive on PJSIP worker threads (already
 * registered), so they may touch pjsua2 directly but marshal state back through
 * [PjsipTransport]. Ported from the phone app's MyCall (sans Telecom).
 */
class MyCall : Call {
    private val transport: PjsipTransport
    val session: CallSession

    constructor(transport: PjsipTransport, account: MsgAccount, session: CallSession) : super(account) {
        this.transport = transport
        this.session = session
    }

    constructor(transport: PjsipTransport, account: MsgAccount, session: CallSession, callId: Int) :
        super(account, callId) {
        this.transport = transport
        this.session = session
    }

    override fun onCallState(prm: OnCallStateParam) {
        val info = try { info } catch (e: Exception) { return }
        val newState = when (info.state) {
            pjsip_inv_state.PJSIP_INV_STATE_CALLING,
            pjsip_inv_state.PJSIP_INV_STATE_CONNECTING -> CallState.CONNECTING
            pjsip_inv_state.PJSIP_INV_STATE_EARLY ->
                if (session.direction == CallDirection.OUTGOING) CallState.RINGING_OUT else CallState.RINGING_IN
            pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED -> CallState.ACTIVE
            pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED -> CallState.ENDED
            else -> session.state
        }
        transport.onCallStateChanged(session, newState)
        if (info.state == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
            session.pjCall = null
            delete()
        }
    }

    override fun onCallMediaState(prm: OnCallMediaStateParam) {
        val info = try { info } catch (e: Exception) { return }
        for (i in 0 until info.media.size) {
            val mi = info.media[i]
            if (mi.type == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                mi.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE
            ) {
                try {
                    val audioMedia = AudioMedia.typecastFromMedia(getMedia(i.toLong()))
                    val mgr = transport.audDevManager() ?: continue
                    mgr.captureDevMedia.startTransmit(audioMedia)   // mic -> remote
                    audioMedia.startTransmit(mgr.playbackDevMedia)  // remote -> speaker
                } catch (e: Exception) {
                    Log.e("WearSip", "audio bridge failed", e)
                }
            }
        }
    }

    fun setMicMuted(muted: Boolean, mgr: AudDevManager?) {
        mgr ?: return
        val info = try { info } catch (e: Exception) { return }
        for (i in 0 until info.media.size) {
            val mi = info.media[i]
            if (mi.type == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                mi.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE
            ) {
                runCatching {
                    val am = AudioMedia.typecastFromMedia(getMedia(i.toLong()))
                    if (muted) mgr.captureDevMedia.stopTransmit(am) else mgr.captureDevMedia.startTransmit(am)
                }
            }
        }
    }
}
