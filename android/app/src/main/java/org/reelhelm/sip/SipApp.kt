package org.reelhelm.sip

import android.app.Application
import org.reelhelm.sip.sip.Notifications

class SipApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        // The service is started from MainActivity once runtime permissions are
        // granted (POST_NOTIFICATIONS / RECORD_AUDIO / READ_CONTACTS), and again
        // on boot via BootReceiver.
    }
}
