package org.reelhelm.sip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.reelhelm.sip.data.SipAccountEntity
import org.reelhelm.sip.data.SipTransport
import org.reelhelm.sip.sip.RegState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(vm: SipViewModel) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val regStates by vm.regStates.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<SipAccountEntity?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showEditor = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add account")
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { CallPermissionsBanner() }
            items(accounts, key = { it.id }) { acc ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(acc.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${acc.username}@${acc.host}:${acc.port} • ${acc.transport}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val reg = regStates[acc.id]?.state ?: RegState.UNREGISTERED
                        AssistChip(onClick = {}, label = { Text(reg.name) })
                    }
                    TextButton(onClick = { editing = acc; showEditor = true }) { Text("Edit") }
                    IconButton(onClick = { vm.deleteAccount(acc) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                }
            }
        }
    }

    if (showEditor) {
        AccountEditor(
            initial = editing,
            onDismiss = { showEditor = false },
            onSave = { vm.saveAccount(it); showEditor = false },
        )
    }
}

/**
 * Warns when a special-access permission needed for incoming calls to show on the
 * lock screen is missing, with a button to grant it. Re-checks on every resume, so
 * it updates (and disappears) as the user returns from each settings page.
 *  - Full-screen notifications (USE_FULL_SCREEN_INTENT): lets the call notification
 *    take over the screen.
 *  - Display over other apps (SYSTEM_ALERT_WINDOW): lets us launch the call screen
 *    over the lock screen as a backstop when an OEM suppresses the full-screen intent.
 */
@Composable
private fun CallPermissionsBanner() {
    val context = LocalContext.current
    var fsiOk by remember { mutableStateOf(true) }
    var overlayOk by remember { mutableStateOf(true) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                fsiOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                    context.getSystemService(NotificationManager::class.java)
                        ?.canUseFullScreenIntent() == true
                overlayOk = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (fsiOk && overlayOk) return

    val missing = buildList {
        if (!fsiOk) add("Full-screen notifications")
        if (!overlayOk) add("Display over other apps")
    }.joinToString(" and ")

    ElevatedCard(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Incoming calls may not show on the lock screen",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "Grant “$missing” so incoming calls wake the screen and appear over the lock screen.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = {
                // Open the first missing permission's settings page; on return the
                // banner re-checks and offers the next one (or hides).
                val intent = if (!fsiOk &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                ) {
                    Intent(
                        Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:${context.packageName}"),
                    )
                } else {
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                }
                runCatching { context.startActivity(intent) }
            }) { Text("Grant permissions") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountEditor(
    initial: SipAccountEntity?,
    onDismiss: () -> Unit,
    onSave: (SipAccountEntity) -> Unit,
) {
    var displayName by remember { mutableStateOf(initial?.displayName ?: "") }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf((initial?.port ?: 5060).toString()) }
    var transport by remember { mutableStateOf(initial?.transport ?: SipTransport.UDP) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = username.isNotBlank() && host.isNotBlank(),
                onClick = {
                    onSave(
                        SipAccountEntity(
                            id = initial?.id ?: 0,
                            displayName = displayName.ifBlank { username },
                            username = username.trim(),
                            password = password,
                            host = host.trim(),
                            port = port.toIntOrNull() ?: 5060,
                            transport = transport,
                            enabled = enabled,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(if (initial == null) "Add account" else "Edit account") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(displayName, { displayName = it }, label = { Text("Display name") })
                OutlinedTextField(username, { username = it }, label = { Text("Username / extension") })
                OutlinedTextField(
                    password, { password = it }, label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(host, { host = it }, label = { Text("Server host / IP") })
                OutlinedTextField(
                    port, { port = it }, label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SipTransport.entries.forEach { t ->
                        FilterChip(
                            selected = transport == t,
                            onClick = { transport = t },
                            label = { Text(t.name) },
                        )
                    }
                }
                if (transport == SipTransport.TLS) {
                    Text(
                        "Encrypted: TLS signalling + mandatory SRTP media. The server " +
                            "must offer TLS+SRTP on this extension (reelhelm: " +
                            "settings.client_encryption); use the TLS port (usually 5061).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                    Text("Enabled", Modifier.padding(start = 8.dp))
                }
            }
        },
    )
}
