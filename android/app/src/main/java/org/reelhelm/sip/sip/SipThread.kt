package org.reelhelm.sip.sip

import android.os.Handler
import android.os.HandlerThread
import org.pjsip.pjsua2.Endpoint
import java.util.concurrent.CountDownLatch

/**
 * A single dedicated thread that owns ALL interaction with pjsua2.
 *
 * pjsua2 requires every thread that calls into the native library to be
 * registered first via [Endpoint.libRegisterThread]; calling from an
 * unregistered JVM thread crashes the process. Rather than register threads ad
 * hoc, we funnel every call-in through this one thread, registered once.
 *
 * PJSIP's own worker threads (which deliver callbacks) are already registered by
 * the library, so callbacks don't need this — only our calls *into* pjsua2 do.
 */
class SipThread(name: String = "pjsip-worker") {

    private val thread = HandlerThread(name).apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var registered = false

    /** Register this thread with the endpoint. Call once, after libInit/libStart. */
    fun registerWith(endpoint: Endpoint) = post {
        if (!registered) {
            endpoint.libRegisterThread(thread.name)
            registered = true
        }
    }

    /** Fire-and-forget work on the SIP thread. Exceptions are swallowed-logged. */
    fun post(block: () -> Unit) {
        handler.post {
            try {
                block()
            } catch (t: Throwable) {
                android.util.Log.e("SipThread", "pjsua2 task failed", t)
            }
        }
    }

    /** Run [block] on the SIP thread and block the caller until it finishes. */
    fun <T> runBlockingOn(block: () -> T): T {
        if (Thread.currentThread() === thread) return block()
        val latch = CountDownLatch(1)
        var result: T? = null
        var error: Throwable? = null
        handler.post {
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    fun quit() {
        thread.quitSafely()
    }
}
