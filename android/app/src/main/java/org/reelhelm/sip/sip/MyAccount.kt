package org.reelhelm.sip.sip

import android.util.Log
import org.pjsip.pjsua2.Account
import org.pjsip.pjsua2.AccountConfig
import org.pjsip.pjsua2.Buddy
import org.pjsip.pjsua2.BuddyConfig
import org.pjsip.pjsua2.OnIncomingCallParam
import org.pjsip.pjsua2.OnInstantMessageParam
import org.pjsip.pjsua2.OnInstantMessageStatusParam
import org.pjsip.pjsua2.OnRegStateParam
import org.pjsip.pjsua2.SendInstantMessageParam
import org.pjsip.pjsua2.pjmedia_srtp_use

/** Registration state surfaced to the UI. */
enum class RegState { UNREGISTERED, REGISTERING, REGISTERED, FAILED }

/**
 * pjsua2 [Account] subclass: one per [accountId]. Handles registration status,
 * incoming calls (handed to Telecom via [SipManager]) and SMS-over-SIP
 * (page-mode MESSAGE) send/receive.
 */
class MyAccount(
    private val manager: SipManager,
    val accountId: Long,
    val host: String,
) : Account() {

    // Page-mode IM in pjsua2 is sent through a Buddy. We keep a non-subscribing
    // buddy per peer so we don't recreate it on every message.
    private val buddies = HashMap<String, Buddy>()

    override fun onRegState(prm: OnRegStateParam) {
        val active = try { info.regIsActive } catch (e: Exception) { false }
        val code = prm.code // already an int in this binding
        val state = when {
            code / 100 == 2 && active -> RegState.REGISTERED
            code / 100 == 2 && !active -> RegState.UNREGISTERED
            code >= 400 -> RegState.FAILED
            else -> RegState.REGISTERING
        }
        manager.onRegStateChanged(accountId, state, code, prm.reason)
    }

    override fun onIncomingCall(prm: OnIncomingCallParam) {
        manager.onIncomingCall(this, prm.callId)
    }

    override fun onInstantMessage(prm: OnInstantMessageParam) {
        val peer = SipUri.userPart(prm.fromUri)
        manager.onIncomingMessage(accountId, peer, prm.msgBody)
    }

    override fun onInstantMessageStatus(prm: OnInstantMessageStatusParam) {
        // Page-mode MESSAGE has no userData token in the Java binding, so we
        // resolve the message optimistically by peer (from the recipient URI).
        val peer = SipUri.userPart(prm.toUri)
        manager.onMessageStatus(accountId, peer, prm.code / 100 == 2, prm.reason)
    }

    /** Send an SMS-over-SIP (RFC 3428) page-mode MESSAGE to [peer]. */
    fun sendMessage(peer: String, body: String) {
        val buddy = buddyFor(peer)
        val prm = SendInstantMessageParam().apply {
            content = body
            contentType = "text/plain"
        }
        buddy.sendInstantMessage(prm)
    }

    private fun buddyFor(peer: String): Buddy = buddies.getOrPut(peer) {
        val cfg = BuddyConfig().apply {
            uri = "sip:$peer@$host"
            subscribe = false // we want IM only, not presence
        }
        Buddy().apply { create(this@MyAccount, cfg) }
    }

    fun shutdownBuddies() {
        buddies.values.forEach { runCatching { it.delete() } }
        buddies.clear()
    }

    companion object {
        fun config(
            username: String,
            password: String,
            host: String,
            port: Int,
            transportParam: String,
            keepAliveSec: Long,
            srtp: Boolean = false,
        ): AccountConfig = AccountConfig().apply {
            val reg = "sip:$host:$port$transportParam"
            idUri = "sip:$username@$host"
            regConfig.registrarUri = reg
            regConfig.registerOnAdd = true
            sipConfig.authCreds.add(
                org.pjsip.pjsua2.AuthCredInfo("digest", "*", username, 0, password),
            )
            // Keep-alive: short UDP/TCP keep-alive so NAT mappings + registration
            // survive mobile networks and Doze (best-effort, no push).
            natConfig.udpKaIntervalSec = keepAliveSec
            if (srtp) {
                // Mandatory SRTP: encrypt media to match the TLS signalling. If the
                // peer won't negotiate SRTP the call fails rather than silently
                // going cleartext. srtpSecureSignaling=1 only sends/accepts the
                // SDES keys over a secure (TLS) transport, so the keys never travel
                // in plaintext SDP.
                mediaConfig.srtpUse = pjmedia_srtp_use.PJMEDIA_SRTP_MANDATORY
                mediaConfig.srtpSecureSignaling = 1
            }
            try {
                // Prefer the proxy = registrar so all requests follow it.
                sipConfig.proxies.add(reg)
            } catch (e: Exception) {
                Log.w("MyAccount", "proxy set failed", e)
            }
        }
    }
}

/** Tiny SIP URI helper — extracts the user part for display/conversation keys. */
object SipUri {
    fun userPart(uri: String): String {
        // Handles: "Name" <sip:1234@host>, <sip:1234@host>, sip:1234@host
        val start = uri.indexOf("sip:")
        if (start < 0) return uri.trim().trim('<', '>', '"')
        val at = uri.indexOf('@', start)
        val user = if (at > start) uri.substring(start + 4, at) else uri.substring(start + 4)
        return user.trim()
    }
}
