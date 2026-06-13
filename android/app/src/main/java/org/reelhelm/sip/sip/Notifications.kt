package org.reelhelm.sip.sip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import org.reelhelm.sip.R
import org.reelhelm.sip.ui.IncomingCallActivity
import org.reelhelm.sip.ui.MainActivity

/** Centralized notification channels + helpers. */
object Notifications {
    const val CHANNEL_SERVICE = "sip_service"
    // v2: created with the ringtone sound + vibration for incoming calls.
    const val CHANNEL_CALLS = "sip_calls_v2"
    const val CHANNEL_MESSAGES = "sip_messages"

    const val ID_SERVICE = 1
    /** Extra carrying the ringing session id on the full-screen launch intent. */
    const val EXTRA_INCOMING_SESSION = "org.reelhelm.sip.INCOMING_SESSION"
    private var messageId = 1000

    /** Stable per-call notification id so we can cancel it when the call ends. */
    private fun callNotifId(sessionId: String) = 2000 + (sessionId.hashCode() and 0xffff)

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.sip_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.sip_channel_desc) },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALLS,
                context.getString(R.string.call_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                // Ring (loop via FLAG_INSISTENT on the notification) and vibrate
                // like a real incoming call. USAGE_NOTIFICATION_RINGTONE so the
                // system treats it as a ringtone, not a chirp.
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 1000)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                context.getString(R.string.message_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    /** The persistent keep-alive notification for the foreground service. */
    fun serviceNotification(context: Context, text: String): Notification {
        val open = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, 0, open,
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    fun showMessage(context: Context, peer: String, body: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val open = Intent(context, MainActivity::class.java).apply {
            putExtra("openConversation", peer)
        }
        val pi = PendingIntent.getActivity(
            context, peer.hashCode(), open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setContentTitle(peer)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(messageId++, n)
    }

    /**
     * Post the incoming-call notification. A self-managed ConnectionService gets no
     * system incoming-call UI, so we provide it: a full-screen intent wakes the
     * device and launches [IncomingCallActivity] (showWhenLocked + turnScreenOn)
     * over the keyguard, and a CallStyle gives Answer/Decline straight on the lock
     * screen. Loops the ringtone via FLAG_INSISTENT until answered/declined.
     *
     * Note: the full-screen intent only launches full-screen (waking the device /
     * over the lock screen) when USE_FULL_SCREEN_INTENT is granted; otherwise the
     * OS demotes it to a heads-up banner. MainActivity verifies that permission.
     */
    fun showIncomingCall(context: Context, sessionId: String, caller: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val who = caller.ifBlank { "Unknown" }

        val fullScreen = Intent(context, IncomingCallActivity::class.java).apply {
            // Flags per the AOSP self-managed incoming-call example: NEW_TASK to
            // launch the dedicated call task, NO_USER_ACTION so it isn't treated as
            // a user-initiated launch (e.g. won't trigger onUserLeaveHint).
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            putExtra(EXTRA_INCOMING_SESSION, sessionId)
        }
        val fullScreenPi = PendingIntent.getActivity(
            context, callNotifId(sessionId), fullScreen,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val answerPi = callActionIntent(context, SipService.ACTION_ANSWER, sessionId, 1)
        val declinePi = callActionIntent(context, SipService.ACTION_DECLINE, sessionId, 2)

        val person = Person.Builder().setName(who).build()
        val n = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(who)
            .setContentText("Incoming call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, declinePi, answerPi))
            .build()
        // Loop the ringtone for the life of the notification.
        n.flags = n.flags or Notification.FLAG_INSISTENT
        nm.notify(callNotifId(sessionId), n)
    }

    fun cancelIncomingCall(context: Context, sessionId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(callNotifId(sessionId))
    }

    /** A PendingIntent that delivers an answer/decline action to [SipService]. */
    private fun callActionIntent(
        context: Context,
        action: String,
        sessionId: String,
        reqOffset: Int,
    ): PendingIntent {
        val i = Intent(context, SipService::class.java).apply {
            this.action = action
            putExtra(SipService.EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getService(
            context, callNotifId(sessionId) + reqOffset, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
