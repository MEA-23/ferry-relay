package tech.thedevlab.ferry.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.thedevlab.ferry.data.Message
import tech.thedevlab.ferry.data.TextTools
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

/** One entry in the conversation list: a day header, the "new messages" divider, or a message. */
private sealed interface Entry {
    val key: String

    data class Day(val label: String, override val key: String) : Entry
    data object NewDivider : Entry {
        override val key = "new"
    }
    data class Bubble(val m: Message) : Entry {
        override val key = m.rowKey
    }
}

/**
 * A conversation. Opens at the first unread message (under a "New messages" divider), or at the
 * newest one when everything was read or it was opened from a notification, or at
 * [ThreadTarget.focus] when opened from a search.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(vm: AppViewModel, target: ThreadTarget, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val loaded by remember(target.key) { vm.thread(target.key) }.collectAsStateWithLifecycle(initialValue = null)
    val messages = loaded ?: emptyList()
    val listState = rememberLazyListState()

    // Captured once, before the thread is marked read, so the divider stays put while it's open.
    var firstUnread by remember(target.key) { mutableStateOf<String?>(null) }
    var positioned by remember(target.key) { mutableStateOf(false) }
    val entries = remember(messages, firstUnread) { entries(messages, firstUnread) }

    var searching by rememberSaveable(target.key) { mutableStateOf(target.query.isNotBlank()) }
    var query by rememberSaveable(target.key) { mutableStateOf(target.query) }
    val hits = remember(messages, query) {
        if (query.isBlank()) emptyList() else messages.filter { TextTools.matches(it.body, query).isNotEmpty() }.map { it.rowKey }
    }
    var current by remember(target.key) { mutableIntStateOf(-1) }
    fun indexOf(rowKey: String) = entries.indexOfFirst { it is Entry.Bubble && it.m.rowKey == rowKey }

    LaunchedEffect(loaded != null) {
        val all = loaded ?: return@LaunchedEffect
        if (positioned) return@LaunchedEffect
        val unread = all.firstOrNull { it.unread }?.rowKey
        firstUnread = unread
        val list = entries(all, unread)
        val focus = target.focus ?: unread.takeUnless { target.latest }
        val index = focus?.let { k -> list.indexOfFirst { it is Entry.Bubble && it.m.rowKey == k } }?.takeIf { it >= 0 }
        // Show the divider (or the day header) just above the first unread message.
        listState.scrollToItem(if (index == null) list.lastIndex.coerceAtLeast(0) else (index - 1).coerceAtLeast(0))
        if (target.focus != null) current = hits.indexOf(target.focus)
        positioned = true
    }

    // Read while open: clears the unread badge and this thread's notifications.
    LaunchedEffect(messages, positioned) {
        if (positioned && messages.any { it.unread }) vm.markRead(target.key)
    }

    // Follow new messages when already at the bottom.
    var lastCount by remember(target.key) { mutableIntStateOf(0) }
    LaunchedEffect(entries.size) {
        val atEnd = (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= lastCount - 2
        if (positioned && !searching && lastCount in 1 until entries.size && atEnd) listState.animateScrollToItem(entries.lastIndex)
        lastCount = entries.size
    }

    // A new query jumps to its newest match; the arrows step through the rest.
    LaunchedEffect(query) {
        if (positioned) current = hits.lastIndex
    }
    LaunchedEffect(current) {
        val key = hits.getOrNull(current) ?: return@LaunchedEffect
        val index = indexOf(key)
        if (positioned && index >= 0) listState.animateScrollToItem((index - 1).coerceAtLeast(0))
    }

    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }

    val last = messages.lastOrNull()
    val mutes by vm.mutes.collectAsStateWithLifecycle()
    val threads by vm.threads.collectAsStateWithLifecycle()
    val archived = threads.any { it.last.threadKey == target.key && it.archived }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(searching) { if (searching && target.query.isBlank()) focus.requestFocus() }

    Column(modifier) {
        CenterAlignedTopAppBar(
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = { Text(last?.threadTitle.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            actions = {
                IconButton({ searching = true }) { Icon(Icons.Default.Search, "Search in conversation") }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        val number = messages.lastOrNull { it.number != null }?.number
                        if (number != null && TextTools.acceptsReplies(number)) {
                            DropdownMenuItem({ Text("Copy number") }, {
                                menu = false
                                copy(context, number)
                            }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) })
                            DropdownMenuItem({ Text("Call from this phone") }, {
                                menu = false
                                context.startActivity(Intent(Intent.ACTION_DIAL, "tel:${number.filter { it in "+0123456789" }}".toUri()))
                            }, leadingIcon = { Icon(Icons.Default.Call, null) })
                        }
                        MuteMenuItem(vm, target.key, last?.threadTitle.orEmpty(), vm.isMuted(target.key, mutes)) { menu = false }
                        DropdownMenuItem({ Text(if (archived) "Unarchive" else "Archive") }, {
                            menu = false
                            vm.archive(target.key, !archived)
                            if (!archived) onBack()
                        }, leadingIcon = { Icon(if (archived) Icons.Default.Unarchive else Icons.Default.Archive, null) })
                        DropdownMenuItem({ Text("Delete conversation") }, {
                            menu = false
                            confirmDelete = true
                        }, leadingIcon = { Icon(Icons.Default.Delete, null) })
                    }
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            windowInsets = WindowInsets(0),
        )
        // Only while searching, pinned under the header, as on iOS.
        if (searching) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchField(query, { query = it }, "Search in conversation", Modifier.weight(1f).padding(end = 8.dp).focusRequester(focus))
                TextButton({
                    searching = false
                    query = ""
                }) { Text("Cancel") }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        LazyColumn(
            // Tapping the messages closes the keyboard, as on iOS.
            Modifier.weight(1f).fillMaxWidth().alpha(if (positioned) 1f else 0f).pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(entries, key = { it.key }) { entry ->
                when (entry) {
                    is Entry.Day -> DayHeader(entry.label)
                    Entry.NewDivider -> NewDivider()
                    is Entry.Bubble -> MessageBubble(
                        entry.m,
                        query = if (searching) query else "",
                        current = searching && hits.getOrNull(current) == entry.m.rowKey,
                        origin = vm.origin(entry.m.sourceDeviceId, entry.m.simSlot),
                    )
                }
            }
        }
        // Replies go out through the node the conversation came from (§6.6).
        if (!searching && vm.canReply(last)) {
            val number = messages.lastOrNull { it.number != null && !it.outgoing }?.number ?: last?.number
            when {
                number == null -> {}
                TextTools.acceptsReplies(number) -> ReplyBar { vm.sendSms(target.key, last!!.sourceDeviceId, number, it) }
                else -> {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Text(
                        "This sender doesn't accept replies.",
                        Modifier.fillMaxWidth().padding(vertical = 14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        if (searching && query.isNotBlank()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (hits.isEmpty()) "No matches" else "${current + 1} of ${hits.size}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton({ current-- }, enabled = current > 0) { Icon(Icons.Default.KeyboardArrowUp, "Older match") }
                IconButton({ current++ }, enabled = current in 0 until hits.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Newer match") }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete conversation?") },
            text = { Text("Removes it from this phone only. The original messages stay on the phone they came from.") },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    vm.deleteThread(target.key)
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun entries(messages: List<Message>, firstUnread: String?): List<Entry> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    var day: LocalDate? = null
    return buildList {
        for (m in messages) {
            val d = Instant.ofEpochMilli(m.occurredAt).atZone(zone).toLocalDate()
            if (d != day) {
                day = d
                val label = when (d) {
                    today -> "Today"
                    today.minusDays(1) -> "Yesterday"
                    else -> d.format(format)
                }
                add(Entry.Day(label, "day:$d"))
            }
            if (m.rowKey == firstUnread) add(Entry.NewDivider)
            add(Entry.Bubble(m))
        }
    }
}

@Composable
private fun DayHeader(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(label, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NewDivider() {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        Text("New messages", Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
    }
}

@Composable
private fun MessageBubble(m: Message, query: String, current: Boolean, origin: String) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    // Right-to-left text (Arabic, Hebrew) lines up on the right inside its bubble, wherever the bubble sits.
    val rtl = remember(m.body) { TextTools.isRtl(m.body) }
    val shape = RoundedCornerShape(16.dp)
    val code = remember(m.body) { oneTimeCode(m) }

    Row(
        Modifier.fillMaxWidth().padding(start = if (m.outgoing) 40.dp else 0.dp, end = if (m.outgoing) 0.dp else 40.dp),
        horizontalArrangement = if (m.outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier
                .clip(shape)
                .background(if (m.outgoing) colors.primary.copy(alpha = 0.18f) else colors.surfaceContainerHigh)
                .then(if (current) Modifier.border(2.dp, colors.primary, shape) else Modifier)
                .padding(12.dp),
            horizontalAlignment = if (rtl) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            m.notificationTitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            }
            if (m.type == "call") {
                val tint = if (m.isMissedCall) colors.error else colors.onSurface
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (m.isMissedCall) Icons.AutoMirrored.Filled.CallMissed else Icons.Default.Call, null, Modifier.size(18.dp), tint = tint)
                    Text(m.body, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = tint)
                }
            } else {
                SelectionContainer {
                    Text(
                        highlight(m.body, query, current),
                        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                        color = colors.onSurface,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (m.outgoing) Icon(Icons.AutoMirrored.Filled.CallMade, "Sent", Modifier.size(12.dp), tint = colors.onSurfaceVariant)
                m.status?.let { StatusLabel(it) }
                Text(
                    DateFormat.getTimeFormat(context).format(Date(m.occurredAt)) + " · " + origin,
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (code != null) {
                    Spacer(Modifier.width(2.dp))
                    Surface(
                        onClick = { copy(context, code) },
                        shape = RoundedCornerShape(50),
                        color = colors.primary.copy(alpha = 0.14f),
                    ) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.ContentCopy, null, Modifier.size(12.dp), tint = colors.primary)
                            Text("Copy $code", style = MaterialTheme.typography.labelMedium, color = colors.primary, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** A message box that sends through the node; [onSend] gets the trimmed text. */
@Composable
private fun ReplyBar(onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            text, { text = it },
            Modifier.weight(1f),
            placeholder = { Text("Text message") },
            maxLines = 5,
            shape = RoundedCornerShape(24.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
        )
        IconButton(
            {
                onSend(text.trim())
                text = ""
            },
            enabled = text.isNotBlank(),
        ) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (text.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Where a text sent through a node is (§4.8). */
@Composable
private fun StatusLabel(status: String) {
    val failed = status.startsWith("failed")
    val text = when (status) {
        "sending" -> "Sending…"
        "sent" -> "Sent"
        "delivered" -> "Delivered"
        else -> "Not sent: " + when (status.substringAfter(':')) {
            "expired" -> "took too long"
            "rate_limited" -> "too many texts"
            "unknown_number" -> "turn on Allow new numbers on that phone"
            "no_permission" -> "no permission on that phone"
            "no_sim" -> "that SIM isn't in"
            "not_queued" -> "couldn't reach the server"
            else -> "the network refused it"
        }
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A 4–8 digit code in an incoming text, Arabic-Indic digits included, offered as a one-tap copy. */
private fun oneTimeCode(m: Message): String? {
    if (m.type != "sms" || m.outgoing) return null
    val digit = "[0-9٠-٩۰-۹]"
    val match = Regex("(?<!$digit)($digit{4,8})(?!$digit)").find(m.body) ?: return null
    return TextTools.normalize(match.groupValues[1])
}

private fun copy(context: android.content.Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Ferry", text))
}
