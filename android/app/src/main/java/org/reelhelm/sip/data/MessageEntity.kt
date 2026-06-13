package org.reelhelm.sip.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MessageDirection { INCOMING, OUTGOING }

/** Delivery state for outgoing SIP MESSAGEs (from onInstantMessageStatus). */
enum class MessageStatus { PENDING, SENT, FAILED, RECEIVED }

/**
 * A single SMS-over-SIP (RFC 3428 page-mode) message. Conversations are keyed by
 * [peer] — the remote phone number / SIP user, normalized to E.164 when possible.
 */
@Entity(
    tableName = "messages",
    indices = [Index(value = ["peer"]), Index(value = ["accountId"])],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Which local account sent/received this. */
    val accountId: Long,
    /** Remote party (E.164 number or SIP user) — the conversation key. */
    val peer: String,
    val body: String,
    val direction: MessageDirection,
    val status: MessageStatus,
    val timestamp: Long,
)
