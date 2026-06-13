package org.reelhelm.sip.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Message
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import org.reelhelm.sip.data.Contact

@Composable
fun ContactsScreen(vm: SipViewModel, nav: NavController) {
    // Loaded once; READ_CONTACTS is requested at startup.
    val contacts by produceState(initialValue = emptyList<Contact>()) {
        value = runCatching { vm.contacts() }.getOrDefault(emptyList())
    }

    if (contacts.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No contacts (or permission not granted)")
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        items(contacts, key = { it.name + it.number }) { contact ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(contact.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        contact.number,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = {
                    vm.defaultAccountId()?.let { vm.call(it, contact.number) }
                }) {
                    Icon(Icons.Filled.Call, contentDescription = "Call ${contact.name}")
                }
                IconButton(onClick = { nav.navigate("conversation/${contact.number}") }) {
                    Icon(Icons.Filled.Message, contentDescription = "Message ${contact.name}")
                }
            }
        }
    }
}
