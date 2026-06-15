package org.reelhelm.wear.ui

import android.app.RemoteInput
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.wear.input.RemoteInputIntentHelper

private const val KEY = "wear_text"

/**
 * Idiomatic Wear text entry: launches the system input activity (voice dictation
 * + on-watch keyboard) and returns the typed/spoken text. Used for composing
 * messages and editing account fields, since inline TextFields are painful on a
 * round watch screen.
 */
@Composable
fun rememberTextInput(onResult: (String) -> Unit): (String) -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = RemoteInput.getResultsFromIntent(result.data)
            ?.getCharSequence(KEY)?.toString()
        if (!text.isNullOrBlank()) onResult(text)
    }
    return remember(launcher) {
        { prompt: String ->
            val remoteInputs = listOf(
                RemoteInput.Builder(KEY).setLabel(prompt).build(),
            )
            val intent: Intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
            RemoteInputIntentHelper.putRemoteInputsExtra(intent, remoteInputs)
            launcher.launch(intent)
        }
    }
}
