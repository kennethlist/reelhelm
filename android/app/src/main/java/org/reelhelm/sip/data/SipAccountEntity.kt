package org.reelhelm.sip.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** SIP transport. UDP is the Asterisk default; TCP is more reliable through
 *  mobile NAT and is recommended for keep-alive. */
enum class SipTransport { UDP, TCP, TLS }

/**
 * One SIP account = one registration against the reelhelm Asterisk (or any
 * registrar). The app keeps every [enabled] account registered at once.
 */
@Entity(tableName = "accounts")
data class SipAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Friendly label shown in the UI, e.g. "Home line". */
    val displayName: String,
    /** SIP auth user / extension id, e.g. "1001". */
    val username: String,
    val password: String,
    /** Registrar host, e.g. the Asterisk LAN IP or hostname. */
    val host: String,
    val port: Int = 5060,
    val transport: SipTransport = SipTransport.UDP,
    /** Whether to register this account on service start. */
    val enabled: Boolean = true,
)
