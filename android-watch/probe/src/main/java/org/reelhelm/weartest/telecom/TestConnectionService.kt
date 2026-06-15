package org.reelhelm.weartest.telecom

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import org.reelhelm.weartest.ProbeLog

/**
 * UNKNOWN #1 — Telecom / call screen.
 *
 * Registers a SELF_MANAGED PhoneAccount (the only realistic third-party VoIP
 * path; CALL_PROVIDER requires being the system dialer) and routes a simulated
 * incoming call through Android Telecom. We log every callback to discover what
 * actually happens on Wear OS:
 *   - Does onCreateIncomingConnection fire at all? (Telecom present on watch?)
 *   - Does the system render its own incoming-call UI, or does it call
 *     onShowIncomingCallUi expecting US to draw it?
 *   - Does a full-screen-intent notification surface a call screen on the watch?
 */
class TestConnectionService : ConnectionService() {

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection {
        ProbeLog.add("TELECOM: onCreateIncomingConnection fired (Telecom IS wired up on this device)")
        val conn = ProbeConnection(applicationContext)
        conn.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED)
        conn.audioModeIsVoip = true
        conn.setAddress(Uri.fromParts("sip", "probe@reelhelm", null), TelecomManager.PRESENTATION_ALLOWED)
        conn.setCallerDisplayName("Probe Caller", TelecomManager.PRESENTATION_ALLOWED)
        conn.setRinging()
        ProbeLog.add("TELECOM: connection set to RINGING — watch for a system call screen now")
        return conn
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        ProbeLog.add("TELECOM: onCreateIncomingConnectionFailed — OS REJECTED the incoming call")
    }

    private class ProbeConnection(val ctx: Context) : Connection() {
        override fun onShowIncomingCallUi() {
            // Self-managed apps get this when THEY must draw the incoming UI.
            ProbeLog.add("TELECOM: onShowIncomingCallUi() -> app must draw its OWN UI; posting FSI notification")
            IncomingCallNotifier.postFullScreen(ctx)
        }

        override fun onAnswer() {
            ProbeLog.add("TELECOM: onAnswer() -> call answered")
            setActive()
        }

        override fun onReject() {
            ProbeLog.add("TELECOM: onReject()")
            setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
            destroy()
        }

        override fun onDisconnect() {
            ProbeLog.add("TELECOM: onDisconnect()")
            setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            destroy()
        }
    }

    companion object {
        private const val ACCOUNT_ID = "wear-sip-probe"

        fun handle(ctx: Context): PhoneAccountHandle =
            PhoneAccountHandle(ComponentName(ctx, TestConnectionService::class.java), ACCOUNT_ID)

        fun registerAccount(ctx: Context) {
            val tm = ctx.getSystemService(TelecomManager::class.java)
            if (tm == null) {
                ProbeLog.add("TELECOM: TelecomManager is NULL — no telephony/Telecom on this device")
                return
            }
            val account = PhoneAccount.builder(handle(ctx), "Wear SIP Probe")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .addSupportedUriScheme("sip")
                .build()
            try {
                tm.registerPhoneAccount(account)
                ProbeLog.add("TELECOM: registerPhoneAccount OK (SELF_MANAGED) — account accepted")
            } catch (e: Exception) {
                ProbeLog.add("TELECOM: registerPhoneAccount FAILED: $e")
            }
        }

        fun simulateIncoming(ctx: Context) {
            val tm = ctx.getSystemService(TelecomManager::class.java) ?: run {
                ProbeLog.add("TELECOM: no TelecomManager"); return
            }
            val extras = Bundle().apply {
                putParcelable(
                    TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                    Uri.fromParts("sip", "probe@reelhelm", null),
                )
            }
            try {
                tm.addNewIncomingCall(handle(ctx), extras)
                ProbeLog.add("TELECOM: addNewIncomingCall() called — awaiting OS callback")
            } catch (e: SecurityException) {
                ProbeLog.add("TELECOM: addNewIncomingCall SecurityException (account not enabled?): $e")
            } catch (e: Exception) {
                ProbeLog.add("TELECOM: addNewIncomingCall FAILED: $e")
            }
        }
    }
}
