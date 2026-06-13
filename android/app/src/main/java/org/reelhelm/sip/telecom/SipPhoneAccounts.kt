package org.reelhelm.sip.telecom

import android.content.ComponentName
import android.content.Context
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.content.getSystemService
import org.reelhelm.sip.data.SipAccountEntity

object TelecomConst {
    const val EXTRA_SESSION_ID = "org.reelhelm.sip.SESSION_ID"
}

/**
 * Registers one self-managed [PhoneAccount] per SIP account so the OS Telecom
 * stack drives call control, audio focus, and Bluetooth/headset routing.
 *
 * Self-managed (not CALL_PROVIDER) is Google's recommended path for third-party
 * VoIP apps. The system does NOT render an incoming-call screen for a third-party
 * account (CALL_PROVIDER relies on the OEM dialer's InCallService, which Samsung
 * won't show for us), so we provide the incoming UI ourselves: a full-screen-intent
 * CallStyle notification that launches [IncomingCallActivity] over the keyguard
 * (see Notifications.showIncomingCall).
 */
object SipPhoneAccounts {

    fun handleFor(context: Context, accountId: Long): PhoneAccountHandle =
        PhoneAccountHandle(
            ComponentName(context, SipConnectionService::class.java),
            accountId.toString(),
        )

    fun register(context: Context, account: SipAccountEntity) {
        val tm = context.getSystemService<TelecomManager>() ?: return
        val handle = handleFor(context, account.id)
        val phoneAccount = PhoneAccount.builder(handle, account.displayName)
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .addSupportedUriScheme(PhoneAccount.SCHEME_SIP)
            .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
            .build()
        try {
            tm.registerPhoneAccount(phoneAccount)
        } catch (_: IllegalArgumentException) {
            // Telecom forbids re-registering a handle while changing its *kind*. An
            // install that previously used a different kind (e.g. the CALL_PROVIDER
            // experiment) leaves a stale registration that triggers this — drop it
            // and register fresh. A normal same-kind re-register succeeds above.
            runCatching { tm.unregisterPhoneAccount(handle) }
            runCatching { tm.registerPhoneAccount(phoneAccount) }
        }
    }

    fun unregister(context: Context, accountId: Long) {
        val tm = context.getSystemService<TelecomManager>() ?: return
        tm.unregisterPhoneAccount(handleFor(context, accountId))
    }
}
