package org.reelhelm.sip.sip

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.reelhelm.sip.data.AppDatabase
import org.reelhelm.sip.telecom.SipPhoneAccounts
import org.reelhelm.sip.telecom.TelecomConst

/**
 * Long-lived foreground service that keeps the PJSIP endpoint alive so accounts
 * stay registered and inbound calls/messages arrive while the app is in the
 * background. Also re-registers on network changes.
 */
class SipService : Service(), TelecomBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var manager: SipManager
    private var connectivity: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        startForegroundCompat()

        manager = SipManager.get(this)
        manager.telecom = this
        registerPhoneAccounts()
        manager.start()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-assert the foreground notification: idempotent if it's already up,
        // and re-posts it if the service was alive but the notification was lost.
        startForegroundCompat()
        when (intent?.action) {
            ACTION_ANSWER -> {
                val id = intent.getStringExtra(EXTRA_SESSION_ID)
                id?.let { sid ->
                    Notifications.cancelIncomingCall(this, sid)
                    manager.sessionById(sid)?.let { manager.answer(it) }
                    // Bring the call UI to the front (over the keyguard) so the
                    // user sees the in-call screen after answering from the
                    // notification action.
                    startActivity(
                        Intent(this, org.reelhelm.sip.ui.IncomingCallActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            putExtra(Notifications.EXTRA_INCOMING_SESSION, sid)
                        },
                    )
                }
            }
            ACTION_DECLINE -> {
                val id = intent.getStringExtra(EXTRA_SESSION_ID)
                id?.let { sid ->
                    Notifications.cancelIncomingCall(this, sid)
                    manager.sessionById(sid)?.let { manager.hangup(it) }
                }
            }
            else -> {
                // If account rows changed, the caller restarts us; re-sync.
                scope.launch { manager.reloadAccounts() }
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val n = Notifications.serviceNotification(this, "Keeping SIP accounts registered")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Notifications.ID_SERVICE,
                n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(Notifications.ID_SERVICE, n)
        }
    }

    private fun registerPhoneAccounts() {
        scope.launch {
            val accounts = AppDatabase.get(this@SipService).accountDao().enabled()
            accounts.forEach { SipPhoneAccounts.register(this@SipService, it) }
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService<ConnectivityManager>() ?: return
        connectivity = cm
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = manager.handleNetworkChange()
            override fun onLost(network: Network) = manager.handleNetworkChange()
        }
        networkCallback = cb
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            cb,
        )
    }

    // ---- TelecomBridge ----------------------------------------------------

    override fun placeOutgoing(session: CallSession) {
        val tm = getSystemService<TelecomManager>() ?: return
        val handle = SipPhoneAccounts.handleFor(this, session.accountId)
        val uri = android.net.Uri.fromParts(PhoneAccount.SCHEME_SIP, session.peer, null)
        val extras = android.os.Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            // Custom keys only reach ConnectionRequest.getExtras() when nested in
            // EXTRA_OUTGOING_CALL_EXTRAS — a top-level key is silently dropped, so
            // onCreateOutgoingConnection can't find the session and fails the call.
            val sessionExtras = android.os.Bundle().apply {
                putString(TelecomConst.EXTRA_SESSION_ID, session.id)
            }
            putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, sessionExtras)
        }
        runCatching { tm.placeCall(uri, extras) }
    }

    override fun addIncoming(session: CallSession) {
        val tm = getSystemService<TelecomManager>() ?: return
        val handle = SipPhoneAccounts.handleFor(this, session.accountId)
        val extras = android.os.Bundle().apply {
            putParcelable(
                TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                android.net.Uri.fromParts(PhoneAccount.SCHEME_SIP, session.peer, null),
            )
            val sessionExtras = android.os.Bundle().apply {
                putString(TelecomConst.EXTRA_SESSION_ID, session.id)
            }
            putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, sessionExtras)
        }
        runCatching { tm.addNewIncomingCall(handle, extras) }
    }

    override fun onDestroy() {
        networkCallback?.let { connectivity?.unregisterNetworkCallback(it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_ANSWER = "org.reelhelm.sip.action.ANSWER"
        const val ACTION_DECLINE = "org.reelhelm.sip.action.DECLINE"
        const val EXTRA_SESSION_ID = "org.reelhelm.sip.action.SESSION_ID"

        fun start(context: Context) {
            val i = Intent(context, SipService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }
    }
}
