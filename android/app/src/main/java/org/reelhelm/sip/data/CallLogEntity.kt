package org.reelhelm.sip.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One entry in the call history. [incoming] + [answered] distinguish the three
 * states the UI shows: outgoing, incoming-answered, and incoming-missed.
 * [durationSec] is 0 for calls that never connected.
 */
@Entity(tableName = "call_logs", indices = [Index(value = ["timestamp"])])
data class CallLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    /** Remote party (E.164 number or SIP user). */
    val peer: String,
    val incoming: Boolean,
    val answered: Boolean,
    val durationSec: Int,
    /** Call start time (epoch millis). */
    val timestamp: Long,
)
