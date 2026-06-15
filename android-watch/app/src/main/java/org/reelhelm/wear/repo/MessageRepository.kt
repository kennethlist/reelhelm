package org.reelhelm.wear.repo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.reelhelm.wear.data.AppDatabase
import org.reelhelm.wear.data.CallLogEntity
import org.reelhelm.wear.data.ConversationSummary
import org.reelhelm.wear.data.MessageDirection
import org.reelhelm.wear.data.MessageEntity
import org.reelhelm.wear.data.MessageStatus
import org.reelhelm.wear.data.SipAccountEntity
import org.reelhelm.wear.sip.RegState
import org.reelhelm.wear.sip.SipTransport
import org.reelhelm.wear.sip.TransportAccount
import org.reelhelm.wear.ui.Notifications

/** Result of one sync pass, surfaced to the status screen. */
data class SyncResult(val newMessages: Int, val newMissed: Int, val at: Long)

/**
 * Single hub the UI talks to. Writes go to Room (instant UI), the network side
 * goes through [SipTransport]. [sync] is the Doze-friendly pull that the worker
 * and on-resume both call.
 */
class MessageRepository(
    private val appContext: Context,
    private val db: AppDatabase,
    private val transport: SipTransport,
) {
    val conversations: Flow<List<ConversationSummary>> = db.messageDao().observeConversations()
    val accounts: Flow<List<SipAccountEntity>> = db.accountDao().observeAll()
    val account: Flow<SipAccountEntity?> = db.accountDao().observeFirst()
    val missedCalls: Flow<List<CallLogEntity>> = db.callLogDao().observeMissed()
    val registration get() = transport.registration
    val calls get() = transport.calls

    fun thread(peer: String): Flow<List<MessageEntity>> = db.messageDao().observeThread(peer)

    // Voice call control (passthrough to the transport).
    fun placeCall(peer: String) = transport.placeCall(peer)
    fun answerCall(id: String) = transport.answerCall(id)
    fun hangupCall(id: String) = transport.hangupCall(id)
    fun setMuted(id: String, muted: Boolean) = transport.setMuted(id, muted)
    fun setSpeaker(id: String, on: Boolean) = transport.setSpeaker(id, on)

    /**
     * Collect the transport's LIVE inbound streams for the app's lifetime — a real
     * SIP MESSAGE / missed call arriving while we're registered lands in the DB +
     * a notification immediately. (The poll path in [sync] is the asleep catch-up.)
     */
    fun startLiveCollection(scope: CoroutineScope) {
        scope.launch {
            transport.incoming.collect { m -> persistInbound(m.peer, m.body, m.timestamp) }
        }
        scope.launch {
            transport.missed.collect { c -> persistMissed(c.peer, c.timestamp) }
        }
        scope.launch {
            transport.callLog.collect { r -> persistCallLog(r) }
        }
    }

    private suspend fun persistCallLog(r: org.reelhelm.wear.sip.CallLogRecord) {
        val acc = db.accountDao().firstEnabled() ?: return
        db.callLogDao().insert(
            CallLogEntity(
                accountId = acc.id, peer = r.peer, incoming = r.incoming,
                answered = r.answered, durationSec = r.durationSec, timestamp = r.timestamp,
            ),
        )
        // An unanswered inbound call is a missed call → notify (the row already
        // makes it show in the missed-calls list).
        if (r.incoming && !r.answered) Notifications.showMissedCall(appContext, r.peer)
    }

    private suspend fun persistInbound(peer: String, body: String, ts: Long) {
        val acc = db.accountDao().firstEnabled() ?: return
        db.messageDao().insert(
            MessageEntity(
                accountId = acc.id, peer = peer, body = body,
                direction = MessageDirection.INCOMING, status = MessageStatus.RECEIVED, timestamp = ts,
            ),
        )
        Notifications.showMessage(appContext, peer, body)
    }

    private suspend fun persistMissed(peer: String, ts: Long) {
        val acc = db.accountDao().firstEnabled() ?: return
        db.callLogDao().insert(
            CallLogEntity(
                accountId = acc.id, peer = peer, incoming = true,
                answered = false, durationSec = 0, timestamp = ts,
            ),
        )
        Notifications.showMissedCall(appContext, peer)
    }

    suspend fun ensureTransport() {
        val acc = db.accountDao().firstEnabled()
        transport.initialize(acc?.let {
            TransportAccount(it.username, it.password, it.host, it.port, it.transport.name.lowercase())
        })
    }

    suspend fun saveAccount(account: SipAccountEntity): Long {
        val id = db.accountDao().upsert(account)
        ensureTransport()
        return id
    }

    /** Optimistic send: write PENDING immediately, then resolve to SENT/FAILED. */
    suspend fun send(peer: String, body: String) {
        val acc = db.accountDao().firstEnabled() ?: run {
            Log.w(TAG, "send with no account"); return
        }
        if (body.isBlank()) return
        db.messageDao().insert(
            MessageEntity(
                accountId = acc.id, peer = peer, body = body.trim(),
                direction = MessageDirection.OUTGOING, status = MessageStatus.PENDING,
                timestamp = System.currentTimeMillis(),
            ),
        )
        val ok = runCatching { transport.sendMessage(peer, body.trim()) }.getOrDefault(false)
        db.messageDao().markLatestPending(peer, if (ok) MessageStatus.SENT.name else MessageStatus.FAILED.name)
    }

    /**
     * Pull anything the server queued while we were asleep, persist it, and notify.
     * This is the heart of the no-FCM, text-first model.
     */
    suspend fun sync(): SyncResult {
        val acc = db.accountDao().firstEnabled() ?: return SyncResult(0, 0, System.currentTimeMillis())
        if (transport.registration.value != RegState.REGISTERED) ensureTransport()

        val msgs = runCatching { transport.pollInbound() }.getOrDefault(emptyList())
        for (m in msgs) persistInbound(m.peer, m.body, m.timestamp)

        val calls = runCatching { transport.pollMissedCalls() }.getOrDefault(emptyList())
        for (c in calls) persistMissed(c.peer, c.timestamp)

        Log.i(TAG, "sync: ${msgs.size} msgs, ${calls.size} missed")
        return SyncResult(msgs.size, calls.size, System.currentTimeMillis())
    }

    companion object { private const val TAG = "WearSip" }
}
