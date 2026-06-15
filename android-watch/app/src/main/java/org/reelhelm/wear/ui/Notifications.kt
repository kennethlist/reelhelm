package org.reelhelm.wear.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.reelhelm.wear.MainActivity
import org.reelhelm.wear.R

/**
 * Inbound message + missed-call notifications. On the watch these buzz the wrist
 * and show on the watch face — and posting works fine from a Doze maintenance
 * window / background sync (Doze gates network + CPU, not notifications).
 */
object Notifications {
    private const val CH_MESSAGES = "wear_messages"
    private const val CH_MISSED = "wear_missed_calls"
    private var nextId = 1000

    private fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MISSED, "Missed calls", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun showMessage(ctx: Context, peer: String, body: String) {
        ensureChannels(ctx)
        val pi = openConversation(ctx, peer)
        val n = Notification.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(peer)
            .setContentText(body)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(nextId++, n)
    }

    fun showMissedCall(ctx: Context, peer: String) {
        ensureChannels(ctx)
        val n = Notification.Builder(ctx, CH_MISSED)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Missed call")
            .setContentText(peer)
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setContentIntent(openConversation(ctx, peer))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(nextId++, n)
    }

    private fun openConversation(ctx: Context, peer: String): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("openConversation", peer)
        return PendingIntent.getActivity(
            ctx, peer.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
