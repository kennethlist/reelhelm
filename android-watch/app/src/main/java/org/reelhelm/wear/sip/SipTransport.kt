package org.reelhelm.wear.sip

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The seam that decouples the Wear app from the actual SIP stack.
 *
 * Text-first design (calls are NOT required to ring instantly), so the contract
 * is built around **store-and-forward + poll**, which is Doze-friendly:
 *  - [sendMessage] pushes a SIP MESSAGE out (best-effort; returns server-accept).
 *  - [pollInbound] / [pollMissedCalls] pull anything the server queued while the
 *    watch was asleep — called from WorkManager sync (maintenance windows) and on
 *    app resume. This is what lets texts arrive "eventually" without FCM.
 *
 * MockTransport implements this today so the whole app runs on the watch.
 * A future PjsipTransport (real pjsua2 + server-side queue) drops into the same
 * interface with no UI/repo changes.
 */
interface SipTransport {
    val registration: StateFlow<RegState>

    /**
     * Live inbound, pushed the moment a SIP MESSAGE / INVITE arrives while the
     * app is running and registered (the real-time path). The repository collects
     * these for the app's lifetime. [pollInbound]/[pollMissedCalls] are the
     * complementary store-and-forward catch-up path for things that arrived while
     * we were asleep.
     */
    val incoming: SharedFlow<InboundMessage>
    val missed: SharedFlow<MissedCall>

    /** Live voice calls (outbound + inbound-when-awake). Drives the call UI. */
    val calls: StateFlow<List<CallSnapshot>>
    /** Emitted when a call ends, for the call-history DB. */
    val callLog: SharedFlow<CallLogRecord>

    suspend fun initialize(account: TransportAccount?)
    suspend fun shutdown()

    /** Send a SIP MESSAGE. Returns true if the server accepted it (2xx). */
    suspend fun sendMessage(peer: String, body: String): Boolean

    /** Place an outbound voice call; returns the session id (or null if no account). */
    fun placeCall(peer: String): String?
    fun answerCall(sessionId: String)
    fun hangupCall(sessionId: String)
    fun setMuted(sessionId: String, muted: Boolean)
    fun setSpeaker(sessionId: String, on: Boolean)

    /** Pull queued inbound messages (store-and-forward). */
    suspend fun pollInbound(): List<InboundMessage>

    /** Pull queued missed-call records (we can show these even if we can't answer live). */
    suspend fun pollMissedCalls(): List<MissedCall>
}

enum class RegState { UNREGISTERED, REGISTERING, REGISTERED, FAILED }

data class TransportAccount(
    val username: String,
    val password: String,
    val host: String,
    val port: Int,
    val transport: String, // "udp" | "tcp" | "tls"
)

data class InboundMessage(
    val peer: String,
    val body: String,
    val timestamp: Long,
)

data class MissedCall(
    val peer: String,
    val timestamp: Long,
)
