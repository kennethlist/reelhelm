package org.reelhelm.wear.sip

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * In-memory stand-in for a real SIP stack + server-side store-and-forward queue.
 *
 * It models the text-first architecture end-to-end without any network:
 *  - [sendMessage] "delivers" to the fake server, which auto-queues a canned reply
 *    from the peer (so you can see a round-trip on the watch).
 *  - The fake server also seeds a couple of queued items, so the FIRST sync after
 *    launch surfaces an inbound message + a missed call — exactly what real
 *    poll-on-wake would pull after the watch was asleep.
 *  - [pollInbound]/[pollMissedCalls] drain those queues, just like a real sync
 *    pulling whatever the server buffered during Doze.
 *
 * Swap this for PjsipTransport later; nothing else changes.
 */
class MockTransport : SipTransport {

    private val _registration = MutableStateFlow(RegState.UNREGISTERED)
    override val registration: StateFlow<RegState> = _registration

    // The mock delivers everything via the poll() path, so these stay empty.
    override val incoming: SharedFlow<InboundMessage> = MutableSharedFlow()
    override val missed: SharedFlow<MissedCall> = MutableSharedFlow()
    override val calls: StateFlow<List<CallSnapshot>> = MutableStateFlow(emptyList())
    override val callLog: SharedFlow<CallLogRecord> = MutableSharedFlow()

    // Mock has no voice path.
    override fun placeCall(peer: String): String? = null
    override fun answerCall(sessionId: String) {}
    override fun hangupCall(sessionId: String) {}
    override fun setMuted(sessionId: String, muted: Boolean) {}
    override fun setSpeaker(sessionId: String, on: Boolean) {}

    private val inboundQueue = ConcurrentLinkedQueue<InboundMessage>()
    private val missedQueue = ConcurrentLinkedQueue<MissedCall>()
    private var seeded = false

    override suspend fun initialize(account: TransportAccount?) {
        _registration.value = RegState.REGISTERING
        delay(600)
        _registration.value = if (account == null) RegState.UNREGISTERED else RegState.REGISTERED
        Log.i(TAG, "initialize -> ${_registration.value} (account=${account?.username})")
        if (!seeded && account != null) {
            seeded = true
            // Pretend these arrived while the watch was asleep; they surface on first sync.
            inboundQueue += InboundMessage("1002", "Hey, you up? (queued while watch slept)", now())
            missedQueue += MissedCall("1002", now() - 5 * 60_000)
        }
    }

    override suspend fun shutdown() {
        _registration.value = RegState.UNREGISTERED
    }

    override suspend fun sendMessage(peer: String, body: String): Boolean {
        if (_registration.value != RegState.REGISTERED) {
            Log.w(TAG, "sendMessage while not REGISTERED -> queued server-side")
        }
        delay(400) // fake round-trip to the server
        Log.i(TAG, "sendMessage -> $peer: $body")
        // Fake peer auto-reply, queued for the next sync (store-and-forward).
        inboundQueue += InboundMessage(peer, "Got it 👍 (auto-reply from $peer)", now() + 1)
        return true
    }

    override suspend fun pollInbound(): List<InboundMessage> =
        drain(inboundQueue).also { if (it.isNotEmpty()) Log.i(TAG, "pollInbound -> ${it.size}") }

    override suspend fun pollMissedCalls(): List<MissedCall> =
        drain(missedQueue).also { if (it.isNotEmpty()) Log.i(TAG, "pollMissedCalls -> ${it.size}") }

    private fun <T> drain(q: ConcurrentLinkedQueue<T>): List<T> {
        val out = ArrayList<T>()
        while (true) { out.add(q.poll() ?: break) }
        return out
    }

    // Wall-clock isn't available deterministically in tests; SystemClock is fine here.
    private fun now() = System.currentTimeMillis().let { if (it > 0) it else SystemClock.elapsedRealtime() }

    companion object { private const val TAG = "WearSip" }
}
