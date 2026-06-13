package org.reelhelm.sip.sip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restart the keep-alive service after reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> SipService.start(context)
        }
    }
}
