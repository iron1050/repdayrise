package com.repdayrise.app.ui.settings

import android.app.Activity
import android.content.ClipData
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.provider.Settings
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.repdayrise.app.data.cloud.CloudBackupRepository
import com.repdayrise.app.data.cloud.CloudBackupState
import com.repdayrise.app.data.cloud.NfcTransfer
import com.repdayrise.app.data.cloud.RestorePreview
import com.repdayrise.app.data.model.countLabel
import kotlinx.coroutines.launch

private sealed interface CloudStep {
    data object Main : CloudStep
    data object ShowCode : CloudStep
    data object EnterCode : CloudStep
    data class ConfirmRestore(val preview: RestorePreview) : CloudStep
    data object ConfirmOff : CloudStep
    data object NfcSend : CloudStep
    data object NfcReceive : CloudStep
}

/** One-line status for the Settings row. */
fun cloudBackupSubtitle(state: CloudBackupState): String = when {
    !state.enabled -> "Off. Keep a copy of your habits on the server"
    state.lastError != null -> state.lastError
    state.lastSyncedAt > 0 -> "Backed up ${DateUtils.getRelativeTimeSpanString(state.lastSyncedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}"
    else -> "Waiting for the first backup"
}

@Composable
fun CloudBackupDialog(state: CloudBackupState, cloud: CloudBackupRepository, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<CloudStep>(CloudStep.Main) }
    var busy by remember { mutableStateOf(false) }
    val nfc = remember { NfcAdapter.getDefaultAdapter(context) }
    val canSendByTap = nfc != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)

    fun perform(block: suspend () -> Result<*>, onSuccess: () -> Unit = {}) {
        busy = true
        scope.launch {
            block().onSuccess { onSuccess() }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
            busy = false
        }
    }

    when (val s = step) {
        CloudStep.Main -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Cloud backup") },
            text = {
                Column {
                    when {
                        !state.enabled -> Text(
                            "Dayrise uploads your habits, history, groups and reminders a few seconds after each change. " +
                                "You get a recovery code: enter it on a new phone to bring everything back.",
                        )
                        state.conflict -> Text(
                            "Another phone has been backing up with the same recovery code, so automatic backups are paused. " +
                                "Keep this phone's data to overwrite the cloud copy, or restore the cloud copy here.",
                        )
                        else -> {
                            Text(cloudBackupSubtitle(state))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Keep your recovery code somewhere safe. Anyone with it can read this backup.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    when {
                        !state.enabled -> {
                            DialogAction("Turn on", !busy) { perform({ cloud.turnOn() }) { step = CloudStep.ShowCode } }
                            if (nfc != null) DialogAction("Restore by tapping your other phone", !busy) { step = CloudStep.NfcReceive }
                            DialogAction("Restore with a recovery code", !busy) { step = CloudStep.EnterCode }
                        }
                        state.conflict -> {
                            DialogAction("Keep this phone's data", !busy) {
                                perform({ cloud.keepThisPhone() }) { toast(context, "Backed up") }
                            }
                            DialogAction("Restore from cloud", !busy) {
                                perform({ cloud.preview().onSuccess { step = CloudStep.ConfirmRestore(it) } })
                            }
                            DialogAction("Show recovery code", true) { step = CloudStep.ShowCode }
                            DialogAction("Turn off", !busy) { step = CloudStep.ConfirmOff }
                        }
                        else -> {
                            if (!state.gone) DialogAction("Back up now", !busy) { perform({ cloud.backUpNow() }) { toast(context, "Backed up") } }
                            if (!state.gone) DialogAction("Show recovery code", true) { step = CloudStep.ShowCode }
                            if (!state.gone && canSendByTap) DialogAction("Send to a phone by tapping", true) { step = CloudStep.NfcSend }
                            DialogAction("Turn off", !busy) { step = CloudStep.ConfirmOff }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        )

        CloudStep.ShowCode -> AlertDialog(
            onDismissRequest = { step = CloudStep.Main },
            title = { Text("Your recovery code") },
            text = {
                Column {
                    Text(
                        state.code.replace("-", " "),
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.sp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Write it down or save it in a password manager. It's the only way to restore this backup, " +
                            "and anyone who has it can read your habits.",
                    )
                }
            },
            confirmButton = { TextButton(onClick = { step = CloudStep.Main }) { Text("Done") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { context.copySecret(state.code); toast(context, "Copied") }) { Text("Copy") }
                    TextButton(onClick = { context.shareText(state.code) }) { Text("Save…") }
                }
            },
        )

        CloudStep.EnterCode -> {
            var text by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { step = CloudStep.Main },
                title = { Text("Restore a backup") },
                text = {
                    Column {
                        Text("Enter the recovery code from your other phone.")
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = { Text("Recovery code") },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(enabled = !busy && text.isNotBlank(), onClick = {
                        perform({ cloud.preview(text).onSuccess { step = CloudStep.ConfirmRestore(it) } })
                    }) { Text("Next") }
                },
                dismissButton = { TextButton(onClick = { step = CloudStep.Main }) { Text("Cancel") } },
            )
        }

        is CloudStep.ConfirmRestore -> {
            val summary = s.preview.summary
            AlertDialog(
                onDismissRequest = { step = CloudStep.Main },
                title = { Text("Replace everything?") },
                text = {
                    Text(
                        "This backup has ${countLabel(summary.habits, "habits")}, ${countLabel(summary.entries, "entries")} and " +
                            "${countLabel(summary.groups, "groups")}, last saved " +
                            "${DateUtils.getRelativeTimeSpanString(s.preview.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}. " +
                            "Restoring deletes all the habits and history on this device first.",
                    )
                },
                confirmButton = {
                    TextButton(enabled = !busy, onClick = {
                        perform({ cloud.restore(s.preview) }) {
                            toast(context, "Restored ${countLabel(summary.habits, "habits")}")
                            onDismiss()
                        }
                    }) { Text("Replace") }
                },
                dismissButton = { TextButton(onClick = { step = CloudStep.Main }) { Text("Cancel") } },
            )
        }

        CloudStep.NfcSend -> {
            // Only answer taps while this screen is open, and for a minute at most.
            DisposableEffect(Unit) {
                val payload = cloud.nfcPayload()
                if (payload != null) {
                    NfcTransfer.offer(payload) {
                        scope.launch {
                            toast(context, "Sent to the other phone")
                            step = CloudStep.Main
                        }
                    }
                }
                onDispose { NfcTransfer.withdraw() }
            }
            AlertDialog(
                onDismissRequest = { step = CloudStep.Main },
                title = { Text("Tap your other phone") },
                text = {
                    Column {
                        NfcOffNotice(nfc, context)
                        Text(
                            "On the other phone, open Dayrise, go to Settings, Cloud backup, Restore by tapping your other phone. " +
                                "Then hold the two phones back to back. Keep this phone unlocked.",
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { step = CloudStep.Main }) { Text("Cancel") } },
            )
        }

        CloudStep.NfcReceive -> {
            var status by remember { mutableStateOf<String?>(null) }
            DisposableEffect(Unit) {
                val activity = context.findActivity()
                if (activity != null && nfc != null) {
                    nfc.enableReaderMode(
                        activity,
                        { tag ->
                            // Binder thread: the read blocks for the few milliseconds the exchange takes.
                            val result = runCatching { NfcTransfer.read(tag) }
                            scope.launch {
                                result
                                    .onSuccess { payload ->
                                        status = "Got it. Checking the backup…"
                                        cloud.preview(payload.code, payload.server)
                                            .onSuccess { step = CloudStep.ConfirmRestore(it) }
                                            .onFailure { status = it.message }
                                    }
                                    .onFailure {
                                        status = (it as? NfcTransfer.TransferException)?.message ?: "Couldn't read the other phone. Hold them still and try again."
                                    }
                            }
                        },
                        NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                        null,
                    )
                }
                onDispose { if (activity != null) nfc?.disableReaderMode(activity) }
            }
            AlertDialog(
                onDismissRequest = { step = CloudStep.Main },
                title = { Text("Tap your other phone") },
                text = {
                    Column {
                        NfcOffNotice(nfc, context)
                        Text(
                            "On the phone that has your habits, open Settings, Cloud backup, Send to a phone by tapping. " +
                                "Then hold the two phones back to back.",
                        )
                        status?.let {
                            Spacer(Modifier.height(12.dp))
                            Text(it, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { step = CloudStep.Main }) { Text("Cancel") } },
            )
        }

        CloudStep.ConfirmOff -> {
            var delete by remember { mutableStateOf(true) }
            AlertDialog(
                onDismissRequest = { step = CloudStep.Main },
                title = { Text("Turn off cloud backup?") },
                text = {
                    Column {
                        Text("Your habits stay on this phone.")
                        Row(
                            Modifier.fillMaxWidth().clickable { delete = !delete }.padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = delete, onCheckedChange = { delete = it })
                            Text("Also delete the copy on the server")
                        }
                    }
                },
                confirmButton = {
                    TextButton(enabled = !busy, onClick = { perform({ cloud.turnOff(delete) }) { step = CloudStep.Main } }) { Text("Turn off") }
                },
                dismissButton = { TextButton(onClick = { step = CloudStep.Main }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun NfcOffNotice(nfc: NfcAdapter?, context: Context) {
    var enabled by remember { mutableStateOf(nfc?.isEnabled ?: true) }
    // Re-check when the person comes back from the system NFC settings.
    LifecycleResumeEffect(nfc) {
        enabled = nfc?.isEnabled ?: true
        onPauseOrDispose { }
    }
    if (enabled) return
    Text("NFC is off on this phone.", color = MaterialTheme.colorScheme.error)
    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }) { Text("Turn on NFC") }
    Spacer(Modifier.height(8.dp))
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun DialogAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.fillMaxWidth())
    }
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

/** Copies the code and, on Android 13+, marks it sensitive so the clipboard preview hides it. */
private fun Context.copySecret(code: String) {
    val clip = ClipData.newPlainText("Dayrise recovery code", code)
    if (Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

private fun Context.shareText(code: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "Dayrise recovery code: $code")
    }
    startActivity(Intent.createChooser(intent, "Save recovery code"))
}
