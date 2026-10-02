package com.repdayrise.app.ui.partners

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repdayrise.app.data.sharing.Outgoing
import com.repdayrise.app.data.sharing.Partner
import com.repdayrise.app.data.sharing.SharingRepository
import com.repdayrise.app.ui.components.GlassTopBar
import com.repdayrise.app.ui.components.GlowCard
import com.repdayrise.app.ui.components.HabitIcons
import com.repdayrise.app.ui.components.IconBadge
import com.repdayrise.app.ui.components.MiniSunrise
import com.repdayrise.app.ui.components.SectionTitle
import com.repdayrise.app.ui.components.SunrisePill
import com.repdayrise.app.ui.components.SystemBars
import com.repdayrise.app.ui.components.backdropSource
import com.repdayrise.app.ui.components.dayriseBackground
import com.repdayrise.app.ui.components.rememberBackdrop
import com.repdayrise.app.ui.theme.HabitColors
import kotlin.math.roundToInt

/** The app's primary action: a sunrise-gradient pill. */
@Composable
internal fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent, contentColor = Color.White,
            disabledContainerColor = Color.Transparent, disabledContentColor = Color.White,
        ),
        modifier = modifier.graphicsLayer { alpha = if (enabled) 1f else 0.5f }.background(SunrisePill, CircleShape),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
}

/** Refresh action that turns into a spinner while a request is in flight. */
@Composable
internal fun RefreshAction(busy: Boolean, onRefresh: () -> Unit) {
    IconButton(onClick = onRefresh, enabled = !busy) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
    }
}

private sealed interface PartnersDialog {
    data object Share : PartnersDialog
    data class Follow(val code: String) : PartnersDialog
    data object Server : PartnersDialog
    data object StopSharing : PartnersDialog
    data object NewCode : PartnersDialog
    data class RemovePartner(val partner: Partner) : PartnersDialog
    data class Unfollow(val card: PartnerCard) : PartnersDialog
}

@Composable
fun PartnersScreen(viewModel: PartnersViewModel, joinCode: String?, onBack: () -> Unit, onOpenPartner: (String) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val backdrop = rememberBackdrop()
    val snackbar = remember { SnackbarHostState() }
    // An invite link opens straight into the follow dialog, once.
    var dialog by remember { mutableStateOf<PartnersDialog?>(null) }
    var joinHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(joinCode) {
        if (!joinCode.isNullOrBlank() && !joinHandled) { joinHandled = true; dialog = PartnersDialog.Follow(joinCode) }
    }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.messageShown() }
    }
    SystemBars()
    val hasServer = state.serverUrl.isNotBlank()
    val outgoing = state.sharing.outgoing

    Scaffold(
        modifier = Modifier.dayriseBackground(),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            GlassTopBar(
                backdrop = backdrop,
                title = { Text("Partners") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                actions = { if (hasServer) RefreshAction(state.busy) { viewModel.refresh() } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .backdropSource(backdrop)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = padding.calculateTopPadding() + 4.dp, bottom = 40.dp + padding.calculateBottomPadding()),
        ) {
            if (!hasServer) {
                Intro(
                    icon = Icons.Rounded.Dns,
                    title = "Connect a server",
                    body = "Sharing runs on a tiny server you control. Deploy the one in this project's backend folder, then add its address here.",
                ) { PillButton("Add server address", onClick = { dialog = PartnersDialog.Server }) }
                Spacer(Modifier.height(20.dp))
            }

            SectionTitle("Your list")
            Spacer(Modifier.height(10.dp))
            if (outgoing == null) {
                Intro(
                    icon = Icons.Rounded.WbTwilight,
                    title = "Rise together",
                    body = "Give someone you trust a code and they can watch your sunrise climb. They see your habits and progress, never your notes or reminders.",
                ) { PillButton("Share my habits", onClick = { dialog = PartnersDialog.Share }, icon = Icons.Rounded.IosShare, enabled = hasServer && !state.busy) }
            } else {
                OutgoingCard(
                    outgoing = outgoing,
                    state = state,
                    onCopy = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Dayrise invite code", outgoing.code))
                    },
                    onInvite = { context.shareInvite(outgoing) },
                    onRemove = { dialog = PartnersDialog.RemovePartner(it) },
                    onToggleHabit = viewModel::setHabitShared,
                    onNewCode = { dialog = PartnersDialog.NewCode },
                    onStop = { dialog = PartnersDialog.StopSharing },
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionTitle("Following")
            Spacer(Modifier.height(10.dp))
            if (state.cards.isEmpty()) {
                GlowCard {
                    Text(
                        "When a partner sends you their code, their day shows up here.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            }
            state.cards.forEach { card ->
                GlowCard(modifier = Modifier.padding(bottom = 10.dp), glow = MaterialTheme.colorScheme.primary, onClick = { onOpenPartner(card.shareId) }) {
                    Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(46.dp).clip(CircleShape)) { MiniSunrise(progress = card.progress, date = state.today, modifier = Modifier.fillMaxSize()) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(card.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when {
                                    card.ended -> "No longer shared with you"
                                    !card.hasData -> "Waiting for their first sync"
                                    card.total == 0 -> "Nothing scheduled today · ${relativeTime(card.updatedAt)}"
                                    else -> "${card.done} of ${card.total} today · ${relativeTime(card.updatedAt)}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (card.ended) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (card.hasData && !card.ended) {
                            Text("${(card.progress * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { dialog = PartnersDialog.Unfollow(card) }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Stop following ${card.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(if (state.cards.isEmpty()) 12.dp else 2.dp))
            FilledTonalButton(onClick = { dialog = PartnersDialog.Follow("") }, enabled = hasServer && !state.busy, shape = CircleShape) {
                Icon(Icons.Rounded.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Follow with a code")
            }

            if (hasServer) {
                Spacer(Modifier.height(24.dp))
                GlowCard(onClick = { dialog = PartnersDialog.Server }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Server", style = MaterialTheme.typography.titleSmall)
                            Text(state.serverUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        PartnersDialog.Share -> TextDialog(
            title = "Share my habits",
            body = "Partners will see this name next to your list.",
            fields = listOf(Field("Your name", state.sharing.displayName)),
            confirm = "Start sharing",
            onDismiss = { dialog = null },
        ) { dialog = null; viewModel.startSharing(it[0]) }
        is PartnersDialog.Follow -> TextDialog(
            title = "Follow a partner",
            body = "Enter the code they sent you. They'll see your name in their partner list.",
            fields = listOf(Field("Invite code", d.code, code = true), Field("Your name", state.sharing.displayName)),
            confirm = "Follow",
            onDismiss = { dialog = null },
        ) { dialog = null; viewModel.follow(it[0], it[1]) }
        PartnersDialog.Server -> TextDialog(
            title = "Server address",
            body = "The address your sharing backend was deployed to. Lists you already share or follow stay on the server they started on.",
            fields = listOf(Field("https://…", state.serverUrl, url = true)),
            confirm = "Save",
            allowBlank = true,
            onDismiss = { dialog = null },
        ) { dialog = null; viewModel.setServerUrl(it[0]) }
        PartnersDialog.StopSharing -> ConfirmDialog(
            "Stop sharing?", "Your list is deleted from the server and every partner loses access. Your habits on this phone aren't touched.",
            "Stop sharing", { dialog = null },
        ) { dialog = null; viewModel.stopSharing() }
        PartnersDialog.NewCode -> ConfirmDialog(
            "Make a new code?", "The current code stops working. Partners who already follow you keep their access.",
            "New code", { dialog = null }, destructive = false,
        ) { dialog = null; viewModel.rotateCode() }
        is PartnersDialog.RemovePartner -> ConfirmDialog(
            "Remove ${d.partner.name}?", "They'll stop receiving your updates right away.",
            "Remove", { dialog = null },
        ) { dialog = null; viewModel.removePartner(d.partner.id) }
        is PartnersDialog.Unfollow -> ConfirmDialog(
            "Stop following ${d.card.name}?", "You'll need a new code from them to follow again.",
            "Stop following", { dialog = null },
        ) { dialog = null; viewModel.unfollow(d.card.shareId) }
    }
}

private fun Context.shareInvite(outgoing: Outgoing) {
    val link = SharingRepository.inviteLink(outgoing.serverUrl, outgoing.code)
    val text = "Be my accountability partner on Dayrise.\n$link\n\nOr open Partners in the app and enter the code ${outgoing.code}."
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(intent, "Invite a partner"))
}

@Composable
private fun Intro(icon: ImageVector, title: String, body: String, action: @Composable () -> Unit) {
    GlowCard(glow = MaterialTheme.colorScheme.primary) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            IconBadge(icon, MaterialTheme.colorScheme.primary, size = 56.dp, iconSize = 28.dp, filled = true)
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
private fun OutgoingCard(
    outgoing: Outgoing,
    state: PartnersUiState,
    onCopy: () -> Unit,
    onInvite: () -> Unit,
    onRemove: (Partner) -> Unit,
    onToggleHabit: (Long, Boolean) -> Unit,
    onNewCode: () -> Unit,
    onStop: () -> Unit,
) {
    var habitsOpen by rememberSaveable { mutableStateOf(false) }
    val sharedCount = state.habits.count { it.id !in outgoing.excludedHabitIds }
    GlowCard(glow = MaterialTheme.colorScheme.primary) {
        Column {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp)) {
                Text("Invite code", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        outgoing.code,
                        style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 3.sp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy code") }
                }
                Text(
                    outgoing.lastError ?: "Synced ${relativeTime(outgoing.lastSyncedAt)} · updates as you complete habits",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (outgoing.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                PillButton("Send invite", onClick = onInvite, icon = Icons.Rounded.IosShare, modifier = Modifier.fillMaxWidth())
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Partners
            if (outgoing.partners.isEmpty()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Group, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(14.dp))
                    Text("No partners yet. Anyone you send the code to appears here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            outgoing.partners.forEach { partner ->
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.Person, MaterialTheme.colorScheme.secondary, size = 36.dp, iconSize = 18.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(partner.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            partner.lastSeenAt?.let { "Checked in ${relativeTime(it)}" } ?: "Hasn't checked in yet",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onRemove(partner) }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Remove ${partner.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Which habits are shared
            val chevron by animateFloatAsState(if (habitsOpen) 180f else 0f, label = "chev")
            Row(Modifier.fillMaxWidth().clickable { habitsOpen = !habitsOpen }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Shared habits", style = MaterialTheme.typography.titleSmall)
                    Text("$sharedCount of ${state.habits.size} visible to partners", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.ExpandMore, contentDescription = if (habitsOpen) "Collapse" else "Expand", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.rotate(chevron))
            }
            AnimatedVisibility(visible = habitsOpen) {
                Column(Modifier.padding(bottom = 6.dp)) {
                    state.habits.forEach { habit ->
                        val shared = habit.id !in outgoing.excludedHabitIds
                        Row(
                            Modifier.fillMaxWidth().clickable { onToggleHabit(habit.id, !shared) }.padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconBadge(HabitIcons[habit.icon], HabitColors.of(habit.colorIndex), size = 36.dp, iconSize = 18.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(habit.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Switch(checked = shared, onCheckedChange = { onToggleHabit(habit.id, it) })
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onNewCode, enabled = !state.busy) { Text("New code") }
                TextButton(onClick = onStop, enabled = !state.busy) { Text("Stop sharing", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private class Field(val label: String, val initial: String, val code: Boolean = false, val url: Boolean = false)

@Composable
private fun TextDialog(
    title: String,
    body: String,
    fields: List<Field>,
    confirm: String,
    onDismiss: () -> Unit,
    allowBlank: Boolean = false,
    onConfirm: (List<String>) -> Unit,
) {
    val values = remember { fields.map { mutableStateOf(it.initial) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                fields.forEachIndexed { i, field ->
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = values[i].value,
                        onValueChange = { values[i].value = if (field.code || field.url) it else it.take(40) },
                        label = { Text(field.label) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        keyboardOptions = when {
                            field.code -> KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false)
                            field.url -> KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false)
                            else -> KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(values.map { it.value.trim() }) }, enabled = allowBlank || values.all { it.value.isNotBlank() }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, destructive: Boolean = true, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
