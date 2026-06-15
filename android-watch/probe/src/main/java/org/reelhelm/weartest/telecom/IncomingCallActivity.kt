package org.reelhelm.weartest.telecom

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import org.reelhelm.weartest.ProbeLog

/**
 * The activity launched by the full-screen intent. If this appears on the watch
 * (especially screen-off / "locked"), it proves a third-party app CAN own an
 * incoming-call surface on Wear OS via the FSI path.
 */
class IncomingCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProbeLog.add("TELECOM: IncomingCallActivity.onCreate — FSI DID surface a call screen on the watch")
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("INCOMING (probe)")
                }
            }
        }
    }
}
