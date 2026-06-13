package org.reelhelm.sip.ui

import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.reelhelm.sip.sip.Notifications

/**
 * The only activity allowed to draw over the keyguard (showWhenLocked +
 * turnScreenOn are declared on it in the manifest). Launched by the
 * full-screen-intent CallStyle notification for an incoming call.
 *
 * It shows ONLY the call — answer/decline and the in-call screen — so a locked
 * device exposes nothing else of the app. It finishes itself once the call ends,
 * returning to the lock screen. The rest of the app (MainActivity) is never
 * lock-screen-capable, so no other entry point can surface it while locked.
 */
class IncomingCallActivity : ComponentActivity() {

    // The ringing session this activity is showing; updated if a fresh incoming
    // intent is delivered to this single-instance activity.
    private val sessionId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeScreen()
        sessionId.value = intent.getStringExtra(Notifications.EXTRA_INCOMING_SESSION)
        setContent {
            ReelhelmTheme {
                val vm: SipViewModel = viewModel()
                val calls by vm.calls.collectAsStateWithLifecycle()
                val sid by sessionId
                val call = calls.firstOrNull { it.id == sid } ?: calls.firstOrNull()
                // When the call is gone (declined/ended/hung up), close and return
                // to the lock screen — never fall through to the rest of the app.
                LaunchedEffect(call == null) {
                    if (call == null) finish()
                }
                if (call != null) InCallScreen(vm, call)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sessionId.value = intent.getStringExtra(Notifications.EXTRA_INCOMING_SESSION)
    }

    /**
     * Force the display on for the incoming call. The manifest's turnScreenOn
     * should do this, but it's unreliable on some devices/emulators, so back it
     * with a brief ACQUIRE_CAUSES_WAKEUP wake lock that self-releases.
     */
    private fun wakeScreen() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        @Suppress("DEPRECATION")
        val wl = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "reelhelm:incoming-call",
        )
        runCatching { wl.acquire(5_000) }
    }
}
