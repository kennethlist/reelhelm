package org.reelhelm.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import org.reelhelm.wear.ui.AccountScreen
import org.reelhelm.wear.ui.ConversationScreen
import org.reelhelm.wear.ui.ConversationsScreen
import org.reelhelm.wear.ui.HomeScreen
import org.reelhelm.wear.ui.MissedCallsScreen
import org.reelhelm.wear.ui.StatusScreen
import org.reelhelm.wear.ui.WearViewModel

class MainActivity : ComponentActivity() {

    private val requestPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
        }
        if (needed.isNotEmpty()) requestPerms.launch(needed.toTypedArray())

        val openConversation = intent?.getStringExtra("openConversation")

        setContent {
            val vm: WearViewModel = viewModel()
            val nav = rememberSwipeDismissableNavController()

            // Pull anything queued while we were away, every time we come to the foreground.
            val owner = LocalLifecycleOwner.current
            LaunchedEffect(owner) {
                val obs = LifecycleEventObserver { _, e ->
                    if (e == Lifecycle.Event.ON_RESUME) vm.syncOnResume()
                }
                owner.lifecycle.addObserver(obs)
            }

            // Deep-link from a notification: start at home, then navigate so the
            // {peer} nav arg is properly extracted (a concrete startDestination
            // path does not populate typed args).
            LaunchedEffect(openConversation) {
                if (!openConversation.isNullOrBlank()) nav.navigate("conversation/$openConversation")
            }

            // A live call takes over the whole screen (incoming or in-call).
            val calls by vm.calls.collectAsStateWithLifecycle()
            val activeCall = calls.firstOrNull()
            if (activeCall != null) {
                org.reelhelm.wear.ui.CallScreen(activeCall, vm)
                return@setContent
            }

            Scaffold(timeText = { TimeText() }) {
                SwipeDismissableNavHost(
                    navController = nav,
                    startDestination = "home",
                ) {
                    composable("home") {
                        HomeScreen(
                            vm,
                            onMessages = { nav.navigate("conversations") },
                            onMissed = { nav.navigate("missed") },
                            onAccount = { nav.navigate("account") },
                            onStatus = { nav.navigate("status") },
                        )
                    }
                    composable("conversations") {
                        ConversationsScreen(vm, onOpen = { nav.navigate("conversation/$it") })
                    }
                    composable(
                        "conversation/{peer}",
                        arguments = listOf(navArgument("peer") { type = NavType.StringType }),
                    ) { entry ->
                        ConversationScreen(vm, entry.arguments?.getString("peer").orEmpty())
                    }
                    composable("missed") {
                        MissedCallsScreen(vm, onMessage = { nav.navigate("conversation/$it") })
                    }
                    composable("account") { AccountScreen(vm) }
                    composable("status") { StatusScreen(vm) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Re-launch to honor a fresh openConversation deep-link from a notification.
        intent.getStringExtra("openConversation")?.let {
            setIntent(intent)
            recreate()
        }
    }
}
