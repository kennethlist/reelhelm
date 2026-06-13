package org.reelhelm.sip.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** One row per conversation for the message list (latest message + count). */
data class ConversationSummary(
    val peer: String,
    val lastBody: String,
    val lastTimestamp: Long,
    val accountId: Long,
)

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY id")
    fun observeAll(): Flow<List<SipAccountEntity>>

    @Query("SELECT * FROM accounts WHERE enabled = 1 ORDER BY id")
    suspend fun enabled(): List<SipAccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun byId(id: Long): SipAccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: SipAccountEntity): Long

    @Update
    suspend fun update(account: SipAccountEntity)

    @Delete
    suspend fun delete(account: SipAccountEntity)
}

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Update
    suspend fun update(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE peer = :peer ORDER BY timestamp")
    fun observeThread(peer: String): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT peer AS peer, body AS lastBody, timestamp AS lastTimestamp, accountId AS accountId
        FROM messages
        WHERE id IN (SELECT MAX(id) FROM messages GROUP BY peer)
        ORDER BY lastTimestamp DESC
        """
    )
    fun observeConversations(): Flow<List<ConversationSummary>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun byId(id: Long): MessageEntity?

    /**
     * Resolve the delivery state of the most recent still-pending outgoing
     * message to [peer]. Page-mode SIP MESSAGE has no per-message token, so we
     * match the newest PENDING one for the conversation (best-effort).
     */
    @Query(
        """
        UPDATE messages SET status = :status
        WHERE id = (
            SELECT id FROM messages
            WHERE peer = :peer AND direction = 'OUTGOING' AND status = 'PENDING'
            ORDER BY timestamp DESC LIMIT 1
        )
        """
    )
    suspend fun markLatestPending(peer: String, status: String)
}

@Dao
interface CallLogDao {
    @Insert
    suspend fun insert(entry: CallLogEntity): Long

    @Query("SELECT * FROM call_logs ORDER BY timestamp DESC LIMIT 500")
    fun observeRecent(): Flow<List<CallLogEntity>>

    @Query("DELETE FROM call_logs")
    suspend fun clear()
}
