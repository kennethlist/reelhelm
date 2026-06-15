package org.reelhelm.wear

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.reelhelm.wear.data.AppDatabase
import org.reelhelm.wear.data.SipAccountEntity
import org.reelhelm.wear.data.SipTransportProto
import org.reelhelm.wear.repo.MessageRepository
import org.reelhelm.wear.sip.PjsipTransport
import org.reelhelm.wear.sync.SyncWorker

/**
 * App-wide service locator (small app, no DI framework needed). Owns the DB,
 * the SipTransport (mock for now), and the repository.
 */
class WearApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database by lazy { AppDatabase.get(this) }
    // Real SIP now. Swap back to MockTransport() to demo the UI without a server.
    val transport by lazy { PjsipTransport(this) }
    val repository by lazy { MessageRepository(this, database, transport) }

    override fun onCreate() {
        super.onCreate()
        repository.startLiveCollection(appScope)
        appScope.launch {
            seedEmptyAccountIfNone()
            repository.ensureTransport()
        }
        SyncWorker.schedulePeriodic(this)
    }

    /** Test hooks: drive the app from adb without UI taps (see DebugReceiver). */
    fun debugSend(peer: String, body: String) = appScope.launch { repository.send(peer, body) }
    fun debugCall(peer: String) = repository.placeCall(peer)
    fun debugAnswer() = repository.calls.value.firstOrNull()?.let { repository.answerCall(it.id) }
    fun debugHangup() = repository.calls.value.firstOrNull()?.let { repository.hangupCall(it.id) }

    /**
     * Seed an EMPTY account row so the Account screen has something to edit on
     * first run. No credentials are hardcoded — the user enters host/ext/password
     * on the watch; registration stays UNREGISTERED until they do.
     */
    private suspend fun seedEmptyAccountIfNone() {
        if (database.accountDao().firstEnabled() == null) {
            database.accountDao().upsert(
                SipAccountEntity(
                    displayName = "SIP account",
                    username = "",
                    password = "",
                    host = "",
                    port = 5060,
                    transport = SipTransportProto.UDP,
                    enabled = true,
                ),
            )
        }
    }
}
