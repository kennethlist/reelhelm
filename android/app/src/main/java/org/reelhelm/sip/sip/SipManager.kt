package org.reelhelm.sip.sip

import android.content.Context
import android.content.Intent
import android.util.Log
import org.reelhelm.sip.ui.IncomingCallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.pjsip.pjsua2.AudDevManager
import org.pjsip.pjsua2.CallOpParam
import org.pjsip.pjsua2.Endpoint
import org.pjsip.pjsua2.EpConfig
import org.pjsip.pjsua2.IpChangeParam
import org.pjsip.pjsua2.TransportConfig
import org.pjsip.pjsua2.pjsip_ssl_method
import org.pjsip.pjsua2.pjsip_status_code
import org.pjsip.pjsua2.pjsip_transport_type_e
import org.reelhelm.sip.data.AppDatabase
import org.reelhelm.sip.data.CallLogEntity
import org.reelhelm.sip.data.ContactsRepository
import org.reelhelm.sip.data.MessageDirection
import org.reelhelm.sip.data.MessageEntity
import org.reelhelm.sip.data.MessageStatus
import org.reelhelm.sip.data.SipAccountEntity
import org.reelhelm.sip.data.SipTransport
import java.util.concurrent.ConcurrentHashMap

data class RegStatus(val state: RegState, val code: Int = 0, val reason: String = "")

/**
 * The single hub for all SIP activity. Owns the pjsua2 [Endpoint], one
 * [MyAccount] per enabled DB row, and every live [CallSession]. Everything that
 * touches pjsua2 is dispatched onto [sipThread]; everything the UI/Telecom needs
 * is exposed as [StateFlow]s or simple methods.
 */
class SipManager private constructor(
    private val appContext: Context,
    private val db: AppDatabase,
    val contacts: ContactsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sipThread = SipThread()

    private var endpoint: Endpoint? = null
    private val accounts = ConcurrentHashMap<Long, MyAccount>()
    private val sessions = ConcurrentHashMap<String, CallSession>()

    private val _regStates = MutableStateFlow<Map<Long, RegStatus>>(emptyMap())
    val regStates: StateFlow<Map<Long, RegStatus>> = _regStates.asStateFlow()

    private val _calls = MutableStateFlow<List<CallSnapshot>>(emptyList())
    val calls: StateFlow<List<CallSnapshot>> = _calls.asStateFlow()

    /** Set by SipService so the manager can hand incoming calls to Telecom. */
    @Volatile var telecom: TelecomBridge? = null

    fun audDevManager(): AudDevManager? = endpoint?.audDevManager()

    fun sessionById(id: String): CallSession? = sessions[id]
    fun accountById(id: Long): MyAccount? = accounts[id]

    // ---- lifecycle --------------------------------------------------------

    /** Initialize the endpoint and register all enabled accounts. Idempotent. */
    fun start() = sipThread.post {
        if (endpoint != null) return@post
        val ep = Endpoint()
        ep.libCreate()
        val cfg = EpConfig()
        cfg.uaConfig.userAgent = "ReelhelmSIP"
        cfg.logConfig.level = 3L // pjsua2 unsigned fields map to Java long
        ep.libInit(cfg)
        cfg.delete()
        ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, TransportConfig())
        ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TCP, TransportConfig())
        // TLS transport for encrypted signalling (paired with SRTP per account).
        // verifyServer = false: the reelhelm server uses a self-signed cert, so we
        // encrypt the link but don't authenticate the certificate chain. (Protect
        // the client leg with LAN/VPN as the server README advises; pin a CA here
        // later if you want authentication too.) Guarded so the app still starts
        // on a libpjsua2 built without an OpenSSL/TLS backend.
        runCatching {
            val tlsTc = TransportConfig()
            val tls = tlsTc.tlsConfig
            tls.method = pjsip_ssl_method.PJSIP_TLSV1_2_METHOD
            tls.verifyServer = false
            tlsTc.tlsConfig = tls
            ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, tlsTc)
        }.onFailure { Log.w("SipManager", "TLS transport unavailable (no TLS in build?)", it) }
        ep.libStart()
        endpoint = ep
        sipThread.registerWith(ep)
        // Don't hold the mic when idle.
        ep.audDevManager().setNullDev()
        scope.launch { reloadAccounts() }
    }

    /** Re-sync accounts: add new/changed, remove gone, leave existing in place. */
    suspend fun reloadAccounts() {
        val rows = db.accountDao().enabled().associateBy { it.id }
        sipThread.post {
            // Remove accounts no longer enabled.
            accounts.keys.filter { !rows.containsKey(it) }.forEach { removeAccount(it) }
            // Add accounts not yet registered.
            rows.values.filter { !accounts.containsKey(it.id) }.forEach { addAccount(it) }
        }
    }

    private fun addAccount(row: SipAccountEntity) {
        val ep = endpoint ?: return
        val transportParam = when (row.transport) {
            SipTransport.UDP -> ";transport=udp"
            SipTransport.TCP -> ";transport=tcp"
            SipTransport.TLS -> ";transport=tls"
        }
        val cfg = MyAccount.config(
            username = row.username,
            password = row.password,
            host = row.host,
            port = row.port,
            transportParam = transportParam,
            keepAliveSec = 30L,
            // Pair media encryption with the encrypted TLS signalling leg.
            srtp = row.transport == SipTransport.TLS,
        )
        Log.i("SipReg", "addAccount ${row.username}@${row.host}:${row.port} ${row.transport} registrar=sip:${row.host}:${row.port}$transportParam")
        val acc = MyAccount(this, row.id, row.host)
        acc.create(cfg)
        accounts[row.id] = acc
        _regStates.value = _regStates.value + (row.id to RegStatus(RegState.REGISTERING))
    }

    private fun removeAccount(id: Long) {
        accounts.remove(id)?.let { acc ->
            runCatching { acc.shutdownBuddies() }
            runCatching { acc.setRegistration(false) }
            runCatching { acc.delete() }
        }
        _regStates.value = _regStates.value - id
    }

    fun shutdown() = sipThread.post {
        runCatching { endpoint?.hangupAllCalls() }
        accounts.keys.toList().forEach { removeAccount(it) }
        runCatching { endpoint?.libDestroy() }
        endpoint = null
    }

    /** Re-register after a network change (Wi-Fi <-> cellular, IP change). */
    fun handleNetworkChange() = sipThread.post {
        val ep = endpoint ?: return@post
        runCatching { ep.handleIpChange(IpChangeParam()) }
    }

    // ---- registration callbacks (from MyAccount) --------------------------

    fun onRegStateChanged(accountId: Long, state: RegState, code: Int, reason: String) {
        Log.i("SipReg", "acct=$accountId state=$state code=$code reason=$reason")
        _regStates.value = _regStates.value + (accountId to RegStatus(state, code, reason))
    }

    // ---- outgoing calls ---------------------------------------------------

    /** UI entry point: returns a session id, then hands it to Telecom to place. */
    fun startOutgoing(accountId: Long, number: String): CallSession {
        val session = CallSession(accountId = accountId, direction = CallDirection.OUTGOING, peer = number)
        sessions[session.id] = session
        publishCalls()
        telecom?.placeOutgoing(session)
        return session
    }

    /** Called by the Telecom connection once the system has accepted the call. */
    fun dial(session: CallSession) = sipThread.post {
        val acc = accounts[session.accountId] ?: run { failSession(session); return@post }
        val host = acc.host
        val call = MyCall(this, acc, session)
        session.pjCall = call
        ensureAudioDevice()
        runCatching {
            call.makeCall("sip:${session.peer}@$host", CallOpParam(true))
        }.onFailure {
            Log.e("SipManager", "makeCall failed", it)
            failSession(session)
        }
    }

    // ---- incoming calls (from MyAccount, on a PJSIP thread) ---------------

    fun onIncomingCall(account: MyAccount, callId: Int) {
        val session = CallSession(
            accountId = account.accountId,
            direction = CallDirection.INCOMING,
            peer = "",
        )
        val call = MyCall(this, account, session, callId)
        session.pjCall = call
        // Fill in the remote party from the call info (now that the call exists).
        session.peer = runCatching { SipUri.userPart(call.info.remoteUri) }.getOrDefault("unknown")
        sessions[session.id] = session
        ensureAudioDevice()
        // Send 180 Ringing so the caller hears ringback.
        runCatching {
            val prm = CallOpParam()
            prm.statusCode = pjsip_status_code.PJSIP_SC_RINGING
            call.answer(prm)
        }
        Log.i("SipReg", "INCOMING call session=${session.id} from=${session.peer}")
        publishCalls()
        // Self-managed Telecom shows no incoming UI on its own — post a
        // full-screen-intent CallStyle notification so the screen wakes and the
        // user can answer even on the lock screen.
        Notifications.showIncomingCall(appContext, session.id, session.peer)
        // Backstop: launch the call screen directly too. Some OEM/AOSP SystemUI
        // builds silently suppress a notification's full-screen intent; a direct
        // background activity launch (allowed by SYSTEM_ALERT_WINDOW) guarantees the
        // call UI appears over the lock screen. IncomingCallActivity is the only
        // lock-screen surface and finishes itself when the call ends.
        runCatching {
            appContext.startActivity(
                Intent(appContext, IncomingCallActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                    putExtra(Notifications.EXTRA_INCOMING_SESSION, session.id)
                },
            )
        }.onFailure { Log.w("SipReg", "direct IncomingCallActivity launch failed", it) }
        telecom?.addIncoming(session)
    }

    // ---- call control (from Telecom / UI) ---------------------------------

    fun answer(session: CallSession) = sipThread.post {
        ensureAudioDevice()
        val prm = CallOpParam()
        prm.statusCode = pjsip_status_code.PJSIP_SC_OK
        runCatching { session.pjCall?.answer(prm) }
    }

    fun hangup(session: CallSession) = sipThread.post {
        val prm = CallOpParam()
        prm.statusCode = pjsip_status_code.PJSIP_SC_DECLINE
        runCatching { session.pjCall?.hangup(prm) }
    }

    fun setHold(session: CallSession, hold: Boolean) = sipThread.post {
        session.onHold = hold
        runCatching {
            if (hold) session.pjCall?.setHold(CallOpParam(true))
            else session.pjCall?.reinvite(CallOpParam(true))
        }
        publishCalls()
    }

    fun setMuted(session: CallSession, muted: Boolean) = sipThread.post {
        session.muted = muted
        runCatching { session.pjCall?.setMicMuted(muted, audDevManager()) }
        publishCalls()
    }

    fun sendDtmf(session: CallSession, digits: String) = sipThread.post {
        runCatching { session.pjCall?.dialDtmf(digits) }
    }

    /** Toggle speakerphone via the Telecom audio route (the system then routes the
     *  call audio). Optimistically reflects the choice; [updateSpeakerState] from
     *  the route callback reconciles it to the actual route. */
    fun setSpeaker(session: CallSession, on: Boolean) {
        session.telecomCallback?.onRequestSpeaker(on)
        sipThread.post { session.speakerOn = on; publishCalls() }
    }

    /** Sync the snapshot to the actual Telecom audio route (from SipConnection). */
    fun updateSpeakerState(session: CallSession, on: Boolean) = sipThread.post {
        if (session.speakerOn != on) {
            session.speakerOn = on
            publishCalls()
        }
    }

    // ---- call state callback (from MyCall) --------------------------------

    fun onCallStateChanged(session: CallSession, newState: CallState) {
        session.state = newState
        session.telecomCallback?.onSipStateChanged(newState)
        // Mark the moment the call connected (for the log's duration).
        if (newState == CallState.ACTIVE && session.connectedAt == 0L) {
            session.connectedAt = System.currentTimeMillis()
        }
        // Once answered or finished, drop the incoming notification (stops the
        // looping ringtone). No-op for outgoing calls / unknown ids.
        if (newState == CallState.ACTIVE || newState == CallState.HOLD ||
            newState == CallState.ENDED
        ) {
            Notifications.cancelIncomingCall(appContext, session.id)
        }
        if (newState == CallState.ENDED) {
            recordCallLog(session)
            sessions.remove(session.id)
            // Release the sound device when no calls remain.
            if (sessions.isEmpty()) sipThread.post { runCatching { audDevManager()?.setNullDev() } }
        }
        publishCalls()
    }

    /** Persist a finished call to the history. Logs each session once. */
    private fun recordCallLog(session: CallSession) {
        if (session.loggedToHistory) return
        session.loggedToHistory = true
        val answered = session.connectedAt > 0L
        val durationSec =
            if (answered) ((System.currentTimeMillis() - session.connectedAt) / 1000).toInt() else 0
        scope.launch {
            runCatching {
                db.callLogDao().insert(
                    CallLogEntity(
                        accountId = session.accountId,
                        peer = session.peer,
                        incoming = session.direction == CallDirection.INCOMING,
                        answered = answered,
                        durationSec = durationSec,
                        timestamp = session.startedAt,
                    ),
                )
            }.onFailure { Log.e("SipManager", "call log insert failed", it) }
        }
    }

    private fun failSession(session: CallSession) {
        session.telecomCallback?.onSipStateChanged(CallState.ENDED)
        recordCallLog(session)
        sessions.remove(session.id)
        publishCalls()
    }

    private fun ensureAudioDevice() {
        // Switch from the null device to the real one for an active call.
        runCatching { audDevManager()?.setCaptureDev(0); audDevManager()?.setPlaybackDev(0) }
    }

    private fun publishCalls() {
        _calls.value = sessions.values.map { it.snapshot() }
    }

    // ---- messaging (SMS over SIP MESSAGE) ---------------------------------

    /** UI entry point to send an SMS-over-SIP. Persists optimistically. */
    fun sendMessage(accountId: Long, peer: String, body: String) {
        scope.launch {
            db.messageDao().insert(
                MessageEntity(
                    accountId = accountId,
                    peer = peer,
                    body = body,
                    direction = MessageDirection.OUTGOING,
                    status = MessageStatus.PENDING,
                    timestamp = System.currentTimeMillis(),
                ),
            )
        }
        sipThread.post {
            val acc = accounts[accountId] ?: return@post
            runCatching { acc.sendMessage(peer, body) }
                .onFailure { Log.e("SipManager", "sendMessage failed", it) }
        }
    }

    fun onIncomingMessage(accountId: Long, peer: String, body: String) {
        scope.launch {
            db.messageDao().insert(
                MessageEntity(
                    accountId = accountId,
                    peer = peer,
                    body = body,
                    direction = MessageDirection.INCOMING,
                    status = MessageStatus.RECEIVED,
                    timestamp = System.currentTimeMillis(),
                ),
            )
            Notifications.showMessage(appContext, peer, body)
        }
    }

    fun onMessageStatus(accountId: Long, peer: String, ok: Boolean, reason: String) {
        // Best-effort: resolve the newest still-PENDING outgoing message to this
        // peer to SENT (server accepted, 2xx) or FAILED. Page-mode MESSAGE has no
        // per-message token, so we can't map more precisely than by conversation.
        scope.launch {
            val status = if (ok) MessageStatus.SENT else MessageStatus.FAILED
            runCatching { db.messageDao().markLatestPending(peer, status.name) }
                .onFailure { Log.e("SipManager", "markLatestPending failed", it) }
            Log.d("SipManager", "IM status peer=$peer ok=$ok reason=$reason")
        }
    }

    companion object {
        @Volatile private var instance: SipManager? = null

        fun get(context: Context): SipManager = instance ?: synchronized(this) {
            instance ?: SipManager(
                context.applicationContext,
                AppDatabase.get(context),
                ContactsRepository(context.applicationContext),
            ).also { instance = it }
        }
    }
}

/** Implemented by SipService/Telecom to route calls through the OS. */
interface TelecomBridge {
    fun placeOutgoing(session: CallSession)
    fun addIncoming(session: CallSession)
}
