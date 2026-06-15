package org.reelhelm.weartest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.reelhelm.weartest.net.SocketProbeService
import org.reelhelm.weartest.telecom.TestConnectionService

/**
 * Lets the test harness drive each probe action deterministically from adb,
 * instead of calibrating screen taps:
 *
 *   adb shell am broadcast -a org.reelhelm.weartest.PROBE --es action register
 *   adb shell am broadcast -a org.reelhelm.weartest.PROBE --es action incoming
 *   adb shell am broadcast -a org.reelhelm.weartest.PROBE --es action net_start
 *   adb shell am broadcast -a org.reelhelm.weartest.PROBE --es action net_stop
 */
class ProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra("action")) {
            "register" -> TestConnectionService.registerAccount(context)
            "incoming" -> TestConnectionService.simulateIncoming(context)
            "net_start" -> SocketProbeService.start(context)
            "net_stop" -> SocketProbeService.stop(context)
            else -> ProbeLog.add("RECV: unknown action ${intent.getStringExtra("action")}")
        }
    }
}
