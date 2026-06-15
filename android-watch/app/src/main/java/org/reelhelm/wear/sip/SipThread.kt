package org.reelhelm.wear.sip

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.pjsip.pjsua2.Endpoint
import java.util.concurrent.CountDownLatch

/**
 * Single dedicated thread that owns ALL calls *into* pjsua2. pjsua2 crashes if
 * you call it from a JVM thread it hasn't registered, so we funnel every call-in
 * through this one registered thread. (PJSIP's own callback threads are already
 * registered by the library.) Ported from the phone app.
 */
class SipThread(name: String = "pjsip-worker") {
    private val thread = HandlerThread(name).apply { start() }
    private val handler = Handler(thread.looper)
    val threadName: String get() = thread.name

    fun post(block: () -> Unit) {
        handler.post {
            try {
                block()
            } catch (t: Throwable) {
                Log.e("WearSip", "pjsua2 task failed", t)
            }
        }
    }

    fun <T> runBlockingOn(block: () -> T): T {
        if (Thread.currentThread() === thread) return block()
        val latch = CountDownLatch(1)
        var result: T? = null
        var error: Throwable? = null
        handler.post {
            try { result = block() } catch (t: Throwable) { error = t } finally { latch.countDown() }
        }
        latch.await()
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    fun quit() = thread.quitSafely()
}

/** Extract the user part of a SIP URI for display/conversation keys. */
object SipUri {
    fun userPart(uri: String): String {
        val start = uri.indexOf("sip:")
        if (start < 0) return uri.trim().trim('<', '>', '"')
        val at = uri.indexOf('@', start)
        return (if (at > start) uri.substring(start + 4, at) else uri.substring(start + 4)).trim()
    }
}
