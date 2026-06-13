package org.reelhelm.sip.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.reelhelm.sip.sip.Notifications
import org.reelhelm.sip.sip.SipService

class MainActivity : ComponentActivity() {

    private val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }.toTypedArray()

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        SipService.start(this)
        maybeRequestBatteryExemption()
    }

    /** Each special-access permission is requested at most once per launch. */
    private var askedFullScreenIntent = false
    private var askedOverlay = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPerms.launch(permissions)
        val openConversation = intent.getStringExtra("openConversation")
        setContent { ReelhelmTheme { AppRoot(openConversation) } }
    }

    override fun onResume() {
        super.onResume()
        // Make sure the keep-alive service (and its persistent notification) is
        // up whenever the app is opened — restart it if the notification is gone.
        ensureServiceRunning()
        maybeRequestExtraPermissions()
    }

    /**
     * The incoming-call-over-lock-screen flow needs two special-access permissions
     * that aren't covered by the normal runtime dialog. Each opens its own settings
     * page, so we request them one at a time and chain across resumes (the user
     * returns, the next one is requested) to avoid stacking two settings screens:
     *  - USE_FULL_SCREEN_INTENT: lets the call notification's full-screen intent fire
     *    (else it's demoted to a heads-up banner that won't wake / show over lock).
     *  - SYSTEM_ALERT_WINDOW ("display over other apps"): lets us launch the call
     *    screen directly over the lock screen as a backstop when an OEM suppresses
     *    the full-screen intent.
     */
    private fun maybeRequestExtraPermissions() {
        if (maybeRequestFullScreenIntentPermission()) return
        maybeRequestOverlayPermission()
    }

    /** Returns true if it opened the settings page (so we don't also open another). */
    private fun maybeRequestFullScreenIntentPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        if (askedFullScreenIntent) return false
        val nm = getSystemService(NotificationManager::class.java) ?: return false
        if (nm.canUseFullScreenIntent()) return false
        askedFullScreenIntent = true
        return runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:$packageName"),
                ),
            )
            true
        }.getOrDefault(false)
    }

    /** Returns true if it opened the "display over other apps" settings page. */
    private fun maybeRequestOverlayPermission(): Boolean {
        if (askedOverlay) return false
        if (Settings.canDrawOverlays(this)) return false
        askedOverlay = true
        return runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            true
        }.getOrDefault(false)
    }

    /** Start the service if its foreground notification isn't currently showing. */
    private fun ensureServiceRunning() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val active = runCatching {
            nm.activeNotifications.any { it.id == Notifications.ID_SERVICE }
        }.getOrDefault(false)
        if (!active) SipService.start(this)
    }

    /**
     * One-time prompt to exempt the app from battery optimization, so the OS
     * doesn't doze/kill the keep-alive service (needed for reliable inbound calls
     * without push). Only asked once per install, and never if already exempt.
     */
    private fun maybeRequestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val prefs = getSharedPreferences("reelhelm_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("asked_battery_opt", false)) return
        prefs.edit().putBoolean("asked_battery_opt", true).apply()
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    DIALER("dialer", "Dialer", Icons.Filled.Call),
    RECENTS("recents", "Recents", Icons.Filled.History),
    MESSAGES("messages", "Messages", Icons.Filled.Message),
    CONTACTS("contacts", "Contacts", Icons.Filled.Person),
    SETTINGS("settings", "Accounts", Icons.Filled.ManageAccounts),
}

/**
 * True while the soft keyboard is up. The activity uses adjustResize, so the
 * window (and this view) shrinks when the IME shows — a large drop from the
 * tallest height seen means the keyboard is open.
 */
@Composable
private fun rememberImeOpen(): Boolean {
    val view = LocalView.current
    var maxHeight by remember { mutableIntStateOf(0) }
    var height by remember { mutableIntStateOf(0) }
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val h = view.height
            if (h > maxHeight) maxHeight = h
            height = h
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return maxHeight > 0 && height in 1 until (maxHeight - 150)
}

@Composable
private fun AppRoot(openConversation: String?) {
    val vm: SipViewModel = viewModel()
    val nav = rememberNavController()
    val calls by vm.calls.collectAsStateWithLifecycle()

    // An active/incoming call takes over the whole screen.
    val activeCall = calls.firstOrNull()
    if (activeCall != null) {
        InCallScreen(vm, activeCall)
        return
    }

    val imeOpen = rememberImeOpen()

    Scaffold(
        bottomBar = {
            // Hide the tab bar while typing so the keyboard has more room.
            if (!imeOpen) {
                val backStack by nav.currentBackStackEntryAsState()
                val current = backStack?.destination
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = current?.hierarchy?.any { it.route == tab.route } == true,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = openConversation?.let { "conversation/$it" } ?: Tab.DIALER.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Tab.DIALER.route) { DialerScreen(vm) }
            composable(Tab.RECENTS.route) { CallLogScreen(vm) }
            composable(Tab.MESSAGES.route) { MessagesScreen(vm, nav) }
            composable(Tab.CONTACTS.route) { ContactsScreen(vm, nav) }
            composable(Tab.SETTINGS.route) { AccountsScreen(vm) }
            composable("conversation/{peer}") { entry ->
                ConversationScreen(vm, entry.arguments?.getString("peer").orEmpty())
            }
        }
    }
}
