package org.reelhelm.sip.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.reelhelm.sip.data.AppDatabase
import org.reelhelm.sip.data.CallLogEntity
import org.reelhelm.sip.data.Contact
import org.reelhelm.sip.data.ConversationSummary
import org.reelhelm.sip.data.MessageEntity
import org.reelhelm.sip.data.SipAccountEntity
import org.reelhelm.sip.sip.CallSnapshot
import org.reelhelm.sip.sip.RegStatus
import org.reelhelm.sip.sip.SipManager
import org.reelhelm.sip.sip.SipService

/**
 * Single view-model for this small app: exposes accounts, registration status,
 * live calls, conversations, and contacts, plus the actions to drive them.
 */
class SipViewModel(app: Application) : AndroidViewModel(app) {

    private val manager = SipManager.get(app)
    private val db = AppDatabase.get(app)

    val accounts: StateFlow<List<SipAccountEntity>> =
        db.accountDao().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val regStates: StateFlow<Map<Long, RegStatus>> = manager.regStates

    val calls: StateFlow<List<CallSnapshot>> = manager.calls

    val conversations: StateFlow<List<ConversationSummary>> =
        db.messageDao().observeConversations()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val callLog: StateFlow<List<CallLogEntity>> =
        db.callLogDao().observeRecent()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun clearCallLog() = viewModelScope.launch { db.callLogDao().clear() }

    // ---- accounts ---------------------------------------------------------

    fun saveAccount(account: SipAccountEntity) = viewModelScope.launch {
        db.accountDao().upsert(account)
        manager.reloadAccounts()
        // Restart the service so it (re)registers Telecom phone accounts too.
        SipService.start(getApplication())
    }

    fun deleteAccount(account: SipAccountEntity) = viewModelScope.launch {
        db.accountDao().delete(account)
        manager.reloadAccounts()
    }

    // ---- calls ------------------------------------------------------------

    fun call(accountId: Long, number: String) {
        if (number.isBlank()) return
        manager.startOutgoing(accountId, number.trim())
    }

    fun answer(sessionId: String) = manager.sessionById(sessionId)?.let { manager.answer(it) }
    fun hangup(sessionId: String) = manager.sessionById(sessionId)?.let { manager.hangup(it) }
    fun setMuted(sessionId: String, muted: Boolean) =
        manager.sessionById(sessionId)?.let { manager.setMuted(it, muted) }
    fun setHold(sessionId: String, hold: Boolean) =
        manager.sessionById(sessionId)?.let { manager.setHold(it, hold) }
    fun setSpeaker(sessionId: String, on: Boolean) =
        manager.sessionById(sessionId)?.let { manager.setSpeaker(it, on) }
    fun dtmf(sessionId: String, digit: String) =
        manager.sessionById(sessionId)?.let { manager.sendDtmf(it, digit) }

    fun defaultAccountId(): Long? = accounts.value.firstOrNull { it.enabled }?.id

    // ---- messages ---------------------------------------------------------

    fun thread(peer: String): StateFlow<List<MessageEntity>> =
        db.messageDao().observeThread(peer)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun sendMessage(peer: String, body: String) {
        val accId = defaultAccountId() ?: return
        if (body.isBlank()) return
        manager.sendMessage(accId, peer, body.trim())
    }

    // ---- contacts ---------------------------------------------------------

    suspend fun contacts(): List<Contact> = manager.contacts.all()
    suspend fun resolveName(number: String): String =
        manager.contacts.lookup(number)?.name ?: number

    /** Reverse-lookup the address book for a number; null if no match / no permission. */
    suspend fun resolveContact(number: String): Contact? = manager.contacts.lookup(number)
}
