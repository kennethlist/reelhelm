package org.reelhelm.sip.telecom

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import org.reelhelm.sip.sip.CallState
import org.reelhelm.sip.sip.SipManager

/**
 * Self-managed ConnectionService. The OS calls these factory methods; we look up
 * the pre-created [org.reelhelm.sip.sip.CallSession] by the session id we stashed
 * in the call extras, wrap it in a [SipConnection], and (for outgoing) start the
 * actual SIP dial.
 */
class SipConnectionService : ConnectionService() {

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest,
    ): Connection {
        val manager = SipManager.get(this)
        val sessionId = request.extras?.getString(TelecomConst.EXTRA_SESSION_ID)
        val session = sessionId?.let { manager.sessionById(it) }
            ?: return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))

        val connection = SipConnection(manager, session)
        connection.setDialing()
        // Kick off the real SIP INVITE now that Telecom has accepted the call.
        manager.dial(session)
        return connection
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest,
    ): Connection {
        val manager = SipManager.get(this)
        // For incoming calls Telecom does NOT flatten EXTRA_INCOMING_CALL_EXTRAS
        // into the request extras (unlike EXTRA_OUTGOING_CALL_EXTRAS for outgoing),
        // so our session id arrives nested inside that bundle. Read it there first,
        // falling back to the top level. Missing it makes this return a failed
        // connection, which tears the call down before the incoming UI can show.
        val sessionId = request.extras
            ?.getBundle(android.telecom.TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)
            ?.getString(TelecomConst.EXTRA_SESSION_ID)
            ?: request.extras?.getString(TelecomConst.EXTRA_SESSION_ID)
        val session = sessionId?.let { manager.sessionById(it) }
            ?: return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))

        val connection = SipConnection(manager, session)
        connection.setRinging()
        return connection
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest,
    ) {
        val manager = SipManager.get(this)
        request.extras?.getString(TelecomConst.EXTRA_SESSION_ID)
            ?.let { manager.sessionById(it) }
            ?.let { manager.hangup(it); manager.onCallStateChanged(it, CallState.ENDED) }
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest,
    ) {
        val manager = SipManager.get(this)
        request.extras?.getString(TelecomConst.EXTRA_SESSION_ID)
            ?.let { manager.sessionById(it) }
            ?.let { manager.hangup(it); manager.onCallStateChanged(it, CallState.ENDED) }
    }
}
