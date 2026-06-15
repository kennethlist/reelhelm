package org.reelhelm.wear.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.reelhelm.wear.WearApp
import org.reelhelm.wear.data.CallLogEntity
import org.reelhelm.wear.data.ConversationSummary
import org.reelhelm.wear.data.MessageEntity
import org.reelhelm.wear.data.SipAccountEntity
import org.reelhelm.wear.data.SipTransportProto
import org.reelhelm.wear.repo.SyncResult
import org.reelhelm.wear.sip.RegState
import org.reelhelm.wear.sync.SyncWorker

class WearViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as WearApp).repository

    val conversations: StateFlow<List<ConversationSummary>> =
        repo.conversations.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val missedCalls: StateFlow<List<CallLogEntity>> =
        repo.missedCalls.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val account: StateFlow<SipAccountEntity?> =
        repo.account.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val registration: StateFlow<RegState> = repo.registration

    val calls: StateFlow<List<org.reelhelm.wear.sip.CallSnapshot>> = repo.calls

    fun placeCall(peer: String) = repo.placeCall(peer)
    fun answerCall(id: String) = repo.answerCall(id)
    fun hangupCall(id: String) = repo.hangupCall(id)
    fun toggleMute(id: String, muted: Boolean) = repo.setMuted(id, muted)
    fun toggleSpeaker(id: String, on: Boolean) = repo.setSpeaker(id, on)

    private val _lastSync = MutableStateFlow<SyncResult?>(null)
    val lastSync: StateFlow<SyncResult?> = _lastSync

    fun thread(peer: String): StateFlow<List<MessageEntity>> =
        repo.thread(peer).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun send(peer: String, body: String) = viewModelScope.launch { repo.send(peer, body) }

    fun saveAccount(displayName: String, username: String, host: String, port: Int, password: String) =
        viewModelScope.launch {
            val existing = account.value
            repo.saveAccount(
                (existing ?: SipAccountEntity(
                    displayName = displayName, username = username, password = password,
                    host = host, port = port, transport = SipTransportProto.TCP, enabled = true,
                )).copy(
                    displayName = displayName, username = username, host = host,
                    port = port, password = password,
                ),
            )
        }

    /** Manual sync from the UI; the real heartbeat is the periodic worker. */
    fun syncNow() = viewModelScope.launch {
        _lastSync.value = repo.sync()
        SyncWorker.syncNow(getApplication())
    }

    /** Called on app resume — pull anything queued while we were away. */
    fun syncOnResume() = viewModelScope.launch { _lastSync.value = repo.sync() }
}
