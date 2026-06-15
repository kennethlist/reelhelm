package org.reelhelm.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Test-only hook to drive the app deterministically from adb (avoids screen-tap
 * calibration on the round face):
 *
 *   adb shell am broadcast -n org.reelhelm.wear/.DebugReceiver \
 *     -a org.reelhelm.wear.DEBUG --es action send --es peer 1001 --es body "hi"
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as WearApp
        when (intent.getStringExtra("action")) {
            "send" -> {
                val peer = intent.getStringExtra("peer") ?: return
                val body = intent.getStringExtra("body") ?: return
                app.debugSend(peer, body)
            }
            "call" -> intent.getStringExtra("peer")?.let { app.debugCall(it) }
            "answer" -> app.debugAnswer()
            "hangup" -> app.debugHangup()
        }
    }
}
