package org.reelhelm.sip.sip

import android.util.Log
import org.pjsip.pjsua2.AudDevManager
import org.pjsip.pjsua2.AudioMedia
import org.pjsip.pjsua2.Call
import org.pjsip.pjsua2.OnCallMediaStateParam
import org.pjsip.pjsua2.OnCallStateParam
import org.pjsip.pjsua2.pjmedia_type
import org.pjsip.pjsua2.pjsip_inv_state
import org.pjsip.pjsua2.pjsua_call_media_status

/**
 * pjsua2 [Call] subclass. Its callbacks arrive on PJSIP worker threads (already
 * registered), so they may touch pjsua2 directly, but must marshal UI/state
 * changes back through [SipManager].
 */
class MyCall : Call {
    private val manager: SipManager
    val session: CallSession

    /** Outgoing: fresh call on the account. */
    constructor(manager: SipManager, account: MyAccount, session: CallSession) : super(account) {
        this.manager = manager
        this.session = session
    }

    /** Incoming: adopt the call id handed to onIncomingCall. */
    constructor(manager: SipManager, account: MyAccount, session: CallSession, callId: Int) :
        super(account, callId) {
        this.manager = manager
        this.session = session
    }

    override fun onCallState(prm: OnCallStateParam) {
        val info = try { info } catch (e: Exception) { return }
        val newState = when (info.state) {
            pjsip_inv_state.PJSIP_INV_STATE_CALLING,
            pjsip_inv_state.PJSIP_INV_STATE_CONNECTING -> CallState.CONNECTING
            pjsip_inv_state.PJSIP_INV_STATE_EARLY -> {
                if (session.direction == CallDirection.OUTGOING) CallState.RINGING_OUT
                else CallState.RINGING_IN
            }
            pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED -> CallState.ACTIVE
            pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED -> CallState.ENDED
            else -> session.state
        }
        manager.onCallStateChanged(session, newState)
        if (info.state == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
            // Native object is freed shortly after; drop our reference.
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
                    val mgr = manager.audDevManager() ?: continue
                    // Bridge mic -> remote and remote -> speaker.
                    mgr.captureDevMedia.startTransmit(audioMedia)
                    audioMedia.startTransmit(mgr.playbackDevMedia)
                } catch (e: Exception) {
                    Log.e("MyCall", "audio bridge failed", e)
                }
            }
        }
    }

    /** Mute/unmute by toggling mic transmit into the call's audio media. */
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
                    if (muted) mgr.captureDevMedia.stopTransmit(am)
                    else mgr.captureDevMedia.startTransmit(am)
                }
            }
        }
    }
}
