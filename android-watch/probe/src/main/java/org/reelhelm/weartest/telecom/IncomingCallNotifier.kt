package org.reelhelm.weartest.telecom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import org.reelhelm.weartest.ProbeLog
import org.reelhelm.weartest.R

/**
 * Posts a CATEGORY_CALL notification with a full-screen intent — the classic
 * "wake the screen and show an incoming-call activity" pattern. On a phone this
 * draws over the lock screen. The probe checks whether Wear OS honors the FSI
 * and surfaces [IncomingCallActivity] on the watch face.
 */
object IncomingCallNotifier {
    private const val CHANNEL = "probe-incoming-call"
    private const val NOTIF_ID = 42

    fun postFullScreen(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Probe Incoming Call", NotificationManager.IMPORTANCE_HIGH),
        )
        val fullScreen = PendingIntent.getActivity(
            ctx,
            1,
            Intent(ctx, IncomingCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Incoming SIP call (probe)")
            .setContentText("sip:probe@reelhelm")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setFullScreenIntent(fullScreen, true)
            .build()
        nm.notify(NOTIF_ID, notif)
        ProbeLog.add("TELECOM: posted CATEGORY_CALL notification with full-screen intent")

        // The phone app's actual approach to beat FSI suppression: wake the
        // screen with a wakelock and directly launch the call activity.
        @Suppress("DEPRECATION")
        val wl = ctx.getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "probe:incoming",
        )
        try {
            wl.acquire(5_000)
            ProbeLog.add("TELECOM: acquired ACQUIRE_CAUSES_WAKEUP wakelock")
        } catch (e: Exception) {
            ProbeLog.add("TELECOM: wakelock failed: $e")
        }
        try {
            ctx.startActivity(
                Intent(ctx, IncomingCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            ProbeLog.add("TELECOM: direct startActivity(IncomingCallActivity) called")
        } catch (e: Exception) {
            ProbeLog.add("TELECOM: direct startActivity FAILED: $e")
        }
    }

    fun clear(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }
}
