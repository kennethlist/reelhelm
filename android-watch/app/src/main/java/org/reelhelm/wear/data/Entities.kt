package org.reelhelm.wear.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room schema mirrors the phone app (org.reelhelm.sip) 1:1 so a real shared DB /
 * server sync can drop in later without a migration. Tables: accounts, messages,
 * call_logs.
 */

enum class MessageDirection { INCOMING, OUTGOING }
enum class MessageStatus { PENDING, SENT, FAILED, RECEIVED }
enum class SipTransportProto { UDP, TCP, TLS }

@Entity(tableName = "accounts")
data class SipAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val username: String,
    val password: String,
    val host: String,
    val port: Int = 5060,
    val transport: SipTransportProto = SipTransportProto.TCP,
    val enabled: Boolean = true,
)

@Entity(
    tableName = "messages",
    indices = [Index(value = ["peer"]), Index(value = ["accountId"])],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val peer: String,
    val body: String,
    val direction: MessageDirection,
    val status: MessageStatus,
    val timestamp: Long,
)

@Entity(tableName = "call_logs", indices = [Index(value = ["timestamp"])])
data class CallLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val peer: String,
    val incoming: Boolean,
    val answered: Boolean,
    val durationSec: Int,
    val timestamp: Long,
)

/** Projection for the conversation list (one row per peer, newest message). */
data class ConversationSummary(
    val peer: String,
    val lastBody: String,
    val lastTimestamp: Long,
    val accountId: Long,
)
