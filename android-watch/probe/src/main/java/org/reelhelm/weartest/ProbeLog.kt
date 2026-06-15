package org.reelhelm.weartest

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateListOf

/**
 * Tiny shared, observable log. Everything the probes do is appended here so it
 * shows both in the watch UI and in `adb logcat -s WEARPROBE`.
 *
 * Each line is stamped with elapsedRealtime seconds so we can correlate events
 * with forced-Doze windows (Doze is driven from adb during the test).
 */
object ProbeLog {
    const val TAG = "WEARPROBE"

    val lines = mutableStateListOf<String>()
    private val main = Handler(Looper.getMainLooper())

    fun add(msg: String) {
        val stamped = "[${SystemClock.elapsedRealtime() / 1000}s] $msg"
        Log.i(TAG, msg)
        main.post {
            lines.add(0, stamped)
            while (lines.size > 300) lines.removeAt(lines.size - 1)
        }
    }
}
