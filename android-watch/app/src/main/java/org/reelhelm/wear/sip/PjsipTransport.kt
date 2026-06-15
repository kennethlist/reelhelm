package org.reelhelm.wear.sip

import android.content.Context
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.pjsip.pjsua2.Account
import org.pjsip.pjsua2.AccountConfig
import org.pjsip.pjsua2.AudDevManager
import org.pjsip.pjsua2.AuthCredInfo
import org.pjsip.pjsua2.Buddy
import org.pjsip.pjsua2.BuddyConfig
import org.pjsip.pjsua2.Call
import org.pjsip.pjsua2.CallOpParam
import org.pjsip.pjsua2.Endpoint
import org.pjsip.pjsua2.EpConfig
import org.pjsip.pjsua2.OnIncomingCallParam
import org.pjsip.pjsua2.OnInstantMessageParam
import org.pjsip.pjsua2.OnInstantMessageStatusParam
import org.pjsip.pjsua2.OnRegStateParam
import org.pjsip.pjsua2.SendInstantMessageParam
import org.pjsip.pjsua2.TransportConfig
import org.pjsip.pjsua2.pjsip_status_code
import org.pjsip.pjsua2.pjsip_transport_type_e
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * REAL SIP transport over PJSIP/pjsua2 — the production path that replaces
 * MockTransport. Ports the phone app's SipManager/MyAccount/SipThread, scoped to
 * the text-first feature set: registration + SIP MESSAGE send/receive + logging
 * inbound calls as "missed" (we don't answer — calls aren't real-time on a watch).
 *
 * All calls *into* pjsua2 run on [sipThread]; callbacks arrive on PJSIP worker
 * threads (already registered) and are fanned out to flows/deferreds.
 */
class PjsipTransport(private val appContext: Context) : SipTransport {

    private val sipThread = SipThread()
    private var endpoint: Endpoint? = null
    private var account: MsgAccount? = null

    private val _registration = MutableStateFlow(RegState.UNREGISTERED)
    override val registration: StateFlow<RegState> = _registration

    private val _incoming = MutableSharedFlow<InboundMessage>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<InboundMessage> = _incoming
    private val _missed = MutableSharedFlow<MissedCall>(extraBufferCapacity = 32)
    override val missed: SharedFlow<MissedCall> = _missed

    private val _calls = MutableStateFlow<List<CallSnapshot>>(emptyList())
    override val calls: StateFlow<List<CallSnapshot>> = _calls
    private val _callLog = MutableSharedFlow<CallLogRecord>(extraBufferCapacity = 16)
    override val callLog: SharedFlow<CallLogRecord> = _callLog

    private val sessions = ConcurrentHashMap<String, CallSession>()
    private val audioManager by lazy { appContext.getSystemService(AudioManager::class.java) }

    // Outgoing-send → delivery-status correlation. Page-mode MESSAGE has no token,
    // so we match by peer FIFO (same best-effort approach as the phone app).
    private val pending = HashMap<String, ArrayDeque<CompletableDeferred<Boolean>>>()

    fun audDevManager(): AudDevManager? = endpoint?.audDevManager()

    override suspend fun initialize(account: TransportAccount?) {
        if (account == null) {
            _registration.value = RegState.UNREGISTERED
            return
        }
        ensureEndpoint()
        sipThread.post {
            // Drop any previous account before (re)registering.
            this.account?.let { old -> runCatching { old.setRegistration(false); old.delete() } }
            this.account = null

            val transportParam = when (account.transport.lowercase()) {
                "udp" -> ";transport=udp"
                "tls" -> ";transport=tls"
                else -> ";transport=tcp"
            }
            val cfg = MsgAccount.config(
                account.username, account.password, account.host, account.port, transportParam,
            )
            val acc = MsgAccount(this, account.host)
            runCatching {
                acc.create(cfg)
                this.account = acc
                Log.i(TAG, "account created ${account.username}@${account.host}:${account.port} $transportParam")
            }.onFailure { Log.e(TAG, "account create failed", it); _registration.value = RegState.FAILED }
        }
        _registration.value = RegState.REGISTERING
    }

    private fun ensureEndpoint() = sipThread.post {
        if (endpoint != null) return@post
        runCatching {
            val ep = Endpoint()
            ep.libCreate()
            val cfg = EpConfig()
            cfg.uaConfig.userAgent = "ReelhelmWear"
            cfg.logConfig.level = 3L
            ep.libInit(cfg)
            cfg.delete()
            ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, TransportConfig())
            ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TCP, TransportConfig())
            ep.libStart()
            // Register THIS (the sip) thread with pjsua2 — we're running on it now.
            ep.libRegisterThread(sipThread.threadName)
            ep.audDevManager().setNullDev() // text-first: never grab the mic
            endpoint = ep
            Log.i(TAG, "endpoint started")
        }.onFailure { Log.e(TAG, "endpoint init failed", it); _registration.value = RegState.FAILED }
    }

    override suspend fun shutdown() = sipThread.runBlockingOn {
        runCatching { account?.let { it.setRegistration(false); it.delete() } }
        account = null
        runCatching { endpoint?.libDestroy() }
        endpoint = null
        _registration.value = RegState.UNREGISTERED
    }

    override suspend fun sendMessage(peer: String, body: String): Boolean {
        val acc = account ?: return false
        val deferred = CompletableDeferred<Boolean>()
        synchronized(pending) { pending.getOrPut(peer) { ArrayDeque() }.addLast(deferred) }
        sipThread.post {
            runCatching { acc.sendMessage(peer, body) }
                .onFailure { Log.e(TAG, "sendMessage failed", it); completeOldest(peer, false) }
        }
        return withTimeoutOrNull(15_000) { deferred.await() } ?: run {
            synchronized(pending) { pending[peer]?.remove(deferred) }
            false
        }
    }

    // Real SIP pushes inbound live; there's no server-side queue yet, so the
    // store-and-forward poll returns nothing. (Wire a server queue here later.)
    override suspend fun pollInbound(): List<InboundMessage> = emptyList()
    override suspend fun pollMissedCalls(): List<MissedCall> = emptyList()

    // ---- callbacks from MsgAccount (PJSIP worker threads) ----

    fun onRegState(state: RegState, code: Int, reason: String) {
        Log.i(TAG, "reg state=$state code=$code reason=$reason")
        _registration.value = state
    }

    fun onIncomingMessage(peer: String, body: String) {
        Log.i(TAG, "RX MESSAGE from $peer: $body")
        _incoming.tryEmit(InboundMessage(peer, body, System.currentTimeMillis()))
    }

    fun onMessageStatus(peer: String, ok: Boolean) {
        Log.i(TAG, "MESSAGE status peer=$peer ok=$ok")
        completeOldest(peer, ok)
    }

    /**
     * Inbound INVITE: ring (180) and surface the call so the user can answer when
     * the watch is awake. If never answered, onCallStateChanged logs it as missed.
     */
    fun onIncomingCall(acc: MsgAccount, callId: Int) {
        runCatching {
            val session = CallSession(accountId = acc.accountId, direction = CallDirection.INCOMING, peer = "")
            val call = MyCall(this, acc, session, callId)
            session.pjCall = call
            session.peer = runCatching { SipUri.userPart(call.info.remoteUri) }.getOrDefault("unknown")
            sessions[session.id] = session
            ensureAudioDevice()
            runCatching {
                call.answer(CallOpParam().apply { statusCode = pjsip_status_code.PJSIP_SC_RINGING })
            }
            Log.i(TAG, "INCOMING call from ${session.peer} session=${session.id}")
            publishCalls()
        }.onFailure { Log.e(TAG, "onIncomingCall failed", it) }
    }

    // ---- call control ----

    override fun placeCall(peer: String): String? {
        val acc = account ?: return null
        val session = CallSession(accountId = acc.accountId, direction = CallDirection.OUTGOING, peer = peer)
        sessions[session.id] = session
        publishCalls()
        sipThread.post {
            ensureAudioDevice()
            val call = MyCall(this, acc, session)
            session.pjCall = call
            runCatching { call.makeCall("sip:$peer@${acc.host}", CallOpParam(true)) }
                .onFailure { Log.e(TAG, "makeCall failed", it); endSession(session) }
        }
        return session.id
    }

    override fun answerCall(sessionId: String) = sipThread.post {
        ensureAudioDevice()
        sessions[sessionId]?.pjCall?.let { call ->
            runCatching { call.answer(CallOpParam().apply { statusCode = pjsip_status_code.PJSIP_SC_OK }) }
        }
    }

    override fun hangupCall(sessionId: String) = sipThread.post {
        sessions[sessionId]?.pjCall?.let { call ->
            runCatching { call.hangup(CallOpParam().apply { statusCode = pjsip_status_code.PJSIP_SC_DECLINE }) }
        }
    }

    override fun setMuted(sessionId: String, muted: Boolean) = sipThread.post {
        sessions[sessionId]?.let { s ->
            s.muted = muted
            runCatching { s.pjCall?.setMicMuted(muted, audDevManager()) }
            publishCalls()
        }
    }

    override fun setSpeaker(sessionId: String, on: Boolean) {
        sessions[sessionId]?.let { s ->
            s.speakerOn = on
            runCatching {
                @Suppress("DEPRECATION")
                audioManager?.isSpeakerphoneOn = on
            }
            publishCalls()
        }
    }

    /** Call state machine (from MyCall, on a PJSIP thread). */
    fun onCallStateChanged(session: CallSession, newState: CallState) {
        session.state = newState
        if (newState == CallState.ACTIVE && session.connectedAt == 0L) {
            session.connectedAt = System.currentTimeMillis()
        }
        if (newState == CallState.ENDED) endSession(session)
        publishCalls()
    }

    private fun endSession(session: CallSession) {
        if (!session.loggedToHistory) {
            session.loggedToHistory = true
            val answered = session.connectedAt > 0L
            val dur = if (answered) ((System.currentTimeMillis() - session.connectedAt) / 1000).toInt() else 0
            // The call-log row itself represents a missed call when incoming &&
            // !answered (the missed-calls screen queries exactly that), so we don't
            // also emit on _missed — that's only for the store-and-forward poll path.
            _callLog.tryEmit(
                CallLogRecord(session.peer, session.direction == CallDirection.INCOMING, answered, dur, session.startedAt),
            )
        }
        sessions.remove(session.id)
        if (sessions.isEmpty()) {
            sipThread.post { runCatching { audDevManager()?.setNullDev() } }
            stopCommAudio()
        }
        publishCalls()
    }

    private fun ensureAudioDevice() {
        runCatching { audDevManager()?.setCaptureDev(0); audDevManager()?.setPlaybackDev(0) }
        runCatching {
            @Suppress("DEPRECATION")
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
        }
    }

    private fun stopCommAudio() {
        runCatching {
            @Suppress("DEPRECATION")
            audioManager?.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            audioManager?.isSpeakerphoneOn = false
        }
    }

    private fun publishCalls() {
        _calls.value = sessions.values.map { it.snapshot() }
    }

    private fun completeOldest(peer: String, ok: Boolean) {
        val d = synchronized(pending) { pending[peer]?.pollFirst() }
        d?.complete(ok)
    }

    companion object { private const val TAG = "WearSip" }
}

/** pjsua2 Account subclass for messaging + registration. */
class MsgAccount(
    private val transport: PjsipTransport,
    val host: String,
) : Account() {
    var accountId: Long = 0
    private val buddies = HashMap<String, Buddy>()

    override fun onRegState(prm: OnRegStateParam) {
        val active = try { info.regIsActive } catch (e: Exception) { false }
        val code = prm.code
        val state = when {
            code / 100 == 2 && active -> RegState.REGISTERED
            code / 100 == 2 && !active -> RegState.UNREGISTERED
            code >= 400 -> RegState.FAILED
            else -> RegState.REGISTERING
        }
        transport.onRegState(state, code, prm.reason)
    }

    override fun onInstantMessage(prm: OnInstantMessageParam) {
        transport.onIncomingMessage(SipUri.userPart(prm.fromUri), prm.msgBody)
    }

    override fun onInstantMessageStatus(prm: OnInstantMessageStatusParam) {
        transport.onMessageStatus(SipUri.userPart(prm.toUri), prm.code / 100 == 2)
    }

    override fun onIncomingCall(prm: OnIncomingCallParam) {
        transport.onIncomingCall(this, prm.callId)
    }

    fun sendMessage(peer: String, body: String) {
        val buddy = buddies.getOrPut(peer) {
            val buddyUri = "sip:$peer@$host"
            Log.i("WearSip", "creating buddy uri='$buddyUri'")
            Buddy().apply {
                create(this@MsgAccount, BuddyConfig().apply { uri = buddyUri; subscribe = false })
            }
        }
        buddy.sendInstantMessage(SendInstantMessageParam().apply {
            content = body
            contentType = "text/plain"
        })
    }

    companion object {
        fun config(
            username: String,
            password: String,
            host: String,
            port: Int,
            transportParam: String,
        ): AccountConfig = AccountConfig().apply {
            val reg = "sip:$host:$port$transportParam"
            idUri = "sip:$username@$host"
            regConfig.registrarUri = reg
            regConfig.registerOnAdd = true
            sipConfig.authCreds.add(AuthCredInfo("digest", "*", username, 0, password))
            natConfig.udpKaIntervalSec = 30
            runCatching { sipConfig.proxies.add(reg) }
        }
    }
}
