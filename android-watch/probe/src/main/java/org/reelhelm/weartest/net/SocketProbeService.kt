package org.reelhelm.weartest.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import org.reelhelm.weartest.ProbeLog
import org.reelhelm.weartest.R
import java.io.BufferedReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * UNKNOWN #2 — background / inbound wakeup without push.
 *
 * A foreground service that opens a persistent TCP socket to a host listener
 * and reads server-pushed lines (standing in for a SIP server pushing an INVITE
 * down the registered connection). This is the "stay registered to catch inbound
 * calls without FCM" model.
 *
 * Test procedure (driven from adb, see tools/README):
 *   1. Start the host push server, start this service, confirm CONNECTED + RX ticks.
 *   2. Force Doze: `adb shell dumpsys deviceidle force-idle`.
 *   3. Watch whether RX ticks keep arriving (good) or stall until the maintenance
 *      window / Doze exit (bad — inbound would be delayed/dropped without push).
 *   4. `adb shell dumpsys deviceidle unforce` to release.
 *
 * The elapsedRealtime stamps on each RX line reveal the exact gap.
 */
class SocketProbeService : Service() {

    @Volatile private var running = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host = intent?.getStringExtra(EXTRA_HOST) ?: DEFAULT_HOST
        val port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT

        startForeground(NOTIF_ID, buildNotification("Probe: connecting $host:$port"))
        ProbeLog.add("NET: SocketProbeService started (FGS up), target $host:$port")

        if (!running) {
            running = true
            worker = Thread { loop(host, port) }.also { it.start() }
        }
        return START_STICKY
    }

    private fun loop(host: String, port: Int) {
        var lastRx = SystemClock.elapsedRealtime()
        while (running) {
            try {
                ProbeLog.add("NET: connecting to $host:$port ...")
                Socket().use { socket ->
                    socket.keepAlive = true
                    socket.connect(InetSocketAddress(host, port), 10_000)
                    ProbeLog.add("NET: socket CONNECTED")
                    updateNotification("Probe: connected $host:$port")
                    val reader: BufferedReader = socket.getInputStream().bufferedReader()
                    while (running) {
                        val line = reader.readLine() ?: break
                        val now = SystemClock.elapsedRealtime()
                        val gap = (now - lastRx) / 1000
                        lastRx = now
                        ProbeLog.add("NET: RX \"$line\" (gap ${gap}s since last RX)")
                    }
                }
            } catch (e: Exception) {
                ProbeLog.add("NET: socket error: $e")
            }
            if (running) {
                ProbeLog.add("NET: disconnected; retrying in 3s")
                updateNotification("Probe: reconnecting…")
                try { Thread.sleep(3000) } catch (_: InterruptedException) {}
            }
        }
        ProbeLog.add("NET: socket loop ended")
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        ProbeLog.add("NET: SocketProbeService destroyed")
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Socket Probe", NotificationManager.IMPORTANCE_LOW),
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Wear SIP Probe")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL = "probe-socket"
        private const val NOTIF_ID = 7
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"

        // 10.0.2.2 is the host loopback as seen from the emulator.
        const val DEFAULT_HOST = "10.0.2.2"
        const val DEFAULT_PORT = 9099

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, SocketProbeService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SocketProbeService::class.java))
        }
    }
}
