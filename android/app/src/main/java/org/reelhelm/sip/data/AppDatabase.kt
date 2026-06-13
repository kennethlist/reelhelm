package org.reelhelm.sip.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter fun toTransport(v: String) = SipTransport.valueOf(v)
    @TypeConverter fun fromTransport(v: SipTransport) = v.name
    @TypeConverter fun toDirection(v: String) = MessageDirection.valueOf(v)
    @TypeConverter fun fromDirection(v: MessageDirection) = v.name
    @TypeConverter fun toStatus(v: String) = MessageStatus.valueOf(v)
    @TypeConverter fun fromStatus(v: MessageStatus) = v.name
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

        // v1 -> v2: add the call_logs table (must match Room's generated schema
        // exactly). Preserves existing accounts and messages.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `call_logs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`accountId` INTEGER NOT NULL, " +
                        "`peer` TEXT NOT NULL, " +
                        "`incoming` INTEGER NOT NULL, " +
                        "`answered` INTEGER NOT NULL, " +
                        "`durationSec` INTEGER NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_call_logs_timestamp` " +
                        "ON `call_logs` (`timestamp`)",
                )
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "reelhelm-sip.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
