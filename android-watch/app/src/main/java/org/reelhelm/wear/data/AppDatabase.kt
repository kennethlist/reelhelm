package org.reelhelm.wear.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

class Converters {
    @TypeConverter fun toTransport(v: String) = SipTransportProto.valueOf(v)
    @TypeConverter fun fromTransport(v: SipTransportProto) = v.name
    @TypeConverter fun toDirection(v: String) = MessageDirection.valueOf(v)
    @TypeConverter fun fromDirection(v: MessageDirection) = v.name
    @TypeConverter fun toStatus(v: String) = MessageStatus.valueOf(v)
    @TypeConverter fun fromStatus(v: MessageStatus) = v.name
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY id")
    fun observeAll(): Flow<List<SipAccountEntity>>

    @Query("SELECT * FROM accounts WHERE enabled = 1 ORDER BY id LIMIT 1")
    suspend fun firstEnabled(): SipAccountEntity?

    @Query("SELECT * FROM accounts ORDER BY id LIMIT 1")
    fun observeFirst(): Flow<SipAccountEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: SipAccountEntity): Long

    @Delete
    suspend fun delete(account: SipAccountEntity)
}

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE peer = :peer ORDER BY timestamp")
    fun observeThread(peer: String): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT peer AS peer, body AS lastBody, timestamp AS lastTimestamp, accountId AS accountId
        FROM messages
        WHERE id IN (SELECT MAX(id) FROM messages GROUP BY peer)
        ORDER BY lastTimestamp DESC
        """,
    )
    fun observeConversations(): Flow<List<ConversationSummary>>

    @Query(
        """
        UPDATE messages SET status = :status
        WHERE id = (
            SELECT id FROM messages
            WHERE peer = :peer AND direction = 'OUTGOING' AND status = 'PENDING'
            ORDER BY timestamp DESC LIMIT 1
        )
        """,
    )
    suspend fun markLatestPending(peer: String, status: String)
}

@Dao
interface CallLogDao {
    @Insert
    suspend fun insert(entry: CallLogEntity): Long

    @Query("SELECT * FROM call_logs WHERE incoming = 1 AND answered = 0 ORDER BY timestamp DESC LIMIT 200")
    fun observeMissed(): Flow<List<CallLogEntity>>

    @Query("DELETE FROM call_logs")
    suspend fun clear()
}

@Database(
    entities = [SipAccountEntity::class, MessageEntity::class, CallLogEntity::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun messageDao(): MessageDao
    abstract fun callLogDao(): CallLogDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        // Same db name as the phone app, so the schema is drop-in compatible.
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "reelhelm-sip.db",
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
