package org.reelhelm.weartest

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import org.reelhelm.weartest.net.SocketProbeService
import org.reelhelm.weartest.telecom.TestConnectionService

class MainActivity : ComponentActivity() {

    private val requestPostNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            ProbeLog.add("PERM: POST_NOTIFICATIONS granted=$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProbeLog.add("BOOT: probe app started on ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPostNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent { ProbeScreen() }
    }
}

@Composable
private fun ProbeScreen() {
    val ctx = LocalContext.current
    MaterialTheme {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { ProbeChip("1. Register Telecom acct") { TestConnectionService.registerAccount(ctx) } }
            item { ProbeChip("2. Simulate INCOMING call") { TestConnectionService.simulateIncoming(ctx) } }
            item { ProbeChip("3. Start socket probe") { SocketProbeService.start(ctx) } }
            item { ProbeChip("4. Stop socket probe") { SocketProbeService.stop(ctx) } }
            item { Text("— log —", fontSize = 11.sp) }
            items(ProbeLog.lines) { line ->
                Text(line, fontSize = 10.sp, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ProbeChip(label: String, onClick: () -> Unit) {
    Chip(
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, fontSize = 12.sp) },
        onClick = onClick,
        colors = ChipDefaults.primaryChipColors(),
    )
}
