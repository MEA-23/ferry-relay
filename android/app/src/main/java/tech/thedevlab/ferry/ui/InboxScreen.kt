package tech.thedevlab.ferry.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Button
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tech.thedevlab.ferry.R
import tech.thedevlab.ferry.data.Message
import tech.thedevlab.ferry.data.TextTools
import tech.thedevlab.ferry.data.ThreadSummary
import tech.thedevlab.ferry.granted
import tech.thedevlab.ferry.push.Fcm
import tech.thedevlab.ferry.push.Push

/**
 * An open conversation. [focus] is a message to scroll to, and [query] a search to show in it.
 * [latest] opens at the newest message (from a notification) instead of the first unread one.
 */
data class ThreadTarget(val key: String, val focus: String? = null, val query: String = "", val latest: Boolean = false)

private enum class Filter(val label: String, val type: String?) {
    All("All", null),
    Texts("Texts", "sms"),
    Calls("Calls", "call"),
    Apps("Apps", "notification"),
}

/** The receiver's inbox: one row per conversation, with search across every message. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(vm: AppViewModel, storeVersion: Long, onOpen: (ThreadTarget) -> Unit, onNavigate: (Screen) -> Unit, modifier: Modifier = Modifier) {
    val threads by vm.threads.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(query) { vm.search(query) }
    fun cancelSearch() {
        query = ""
        focusManager.clearFocus()
    }
    BackHandler(enabled = searchFocused || query.isNotEmpty()) { cancelSearch() }

    Column(modifier) {
        // A fixed header, like iOS: the search field is always there, and pulling to refresh moves only the list.
        CenterAlignedTopAppBar(
            title = { Text("Inbox") },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            windowInsets = WindowInsets(0),
        )
        Row(Modifier.padding(start = 16.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchField(query, { query = it }, "Search messages", Modifier.weight(1f).padding(end = 8.dp), onFocus = { searchFocused = it })
            if (searchFocused || query.isNotEmpty()) TextButton(::cancelSearch) { Text("Cancel") }
        }
        if (query.isNotBlank()) {
            SearchResults(vm, threads, query.trim(), onOpen)
        } else {
            val canCompose = remember(storeVersion, threads) { vm.sendOptions().isNotEmpty() }
            Box(Modifier.weight(1f)) {
                ThreadList(vm, storeVersion, threads, onOpen, onNavigate)
                if (canCompose) {
                    FloatingActionButton({ onNavigate(Screen.NewMessage) }, Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                        Icon(Icons.Default.Edit, "New message")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThreadList(
    vm: AppViewModel,
    storeVersion: Long,
    threads: List<ThreadSummary>,
    onOpen: (ThreadTarget) -> Unit,
    onNavigate: (Screen) -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val visible = threads.filter { filter.type == null || it.last.type == filter.type }
    // Empty until the device list loads (it always holds this phone), so unknown reads as "has phones".
    val devices by vm.devices.collectAsStateWithLifecycle()
    val noPhones = devices.isNotEmpty() && devices.none { "source" in it.roles && !it.isSelf }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            scope.launch {
                vm.refresh()
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        // Room under the last row for the new message button.
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
            item(key = "setup") { ReceiverSetup(vm, storeVersion) }
            if (threads.isNotEmpty()) {
                item(key = "filters") {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Filter.entries.forEach { f -> CapsuleChip(f.label, filter == f) { filter = f } }
                    }
                }
            }
            if (visible.isEmpty()) {
                item(key = "empty") {
                    when {
                        threads.isNotEmpty() -> EmptyState(R.drawable.art_empty, "Nothing here", "No ${filter.label.lowercase()} yet.", Modifier.padding(horizontal = 16.dp))
                        noPhones -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            EmptyState(
                                R.drawable.art_empty,
                                "No phones yet",
                                "Pair an Android phone and its texts, calls and notifications show up here.",
                                Modifier.padding(horizontal = 16.dp),
                            )
                            Button({ onNavigate(Screen.Pair) }, Modifier.padding(top = 8.dp)) { Text("Pair a phone") }
                        }
                        else -> EmptyState(R.drawable.art_empty, "All caught up", "Texts, calls and notifications from your other phones show up here.", Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
            items(visible, key = { it.last.threadKey }) { t ->
                ThreadRow(
                    t,
                    origin = vm.origin(t.last.sourceDeviceId, t.last.simSlot),
                    onClick = { onOpen(ThreadTarget(t.last.threadKey)) },
                    onMarkRead = { vm.markRead(t.last.threadKey) },
                    onDelete = { vm.deleteThread(t.last.threadKey) },
                )
            }
        }
    }
}

@Composable
private fun SearchResults(vm: AppViewModel, threads: List<ThreadSummary>, query: String, onOpen: (ThreadTarget) -> Unit) {
    val messages by vm.searchResults.collectAsStateWithLifecycle()
    val conversations = remember(threads, query) { threads.filter { TextTools.matches(it.last.threadTitle, query).isNotEmpty() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (conversations.isEmpty() && messages.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Search, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No results for “$query”", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text(
                        "Check the spelling or try a new search.",
                        Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        if (conversations.isNotEmpty()) {
            item { SectionHeader("Conversations", Modifier.padding(horizontal = 16.dp)) }
            items(conversations, key = { "c:" + it.last.threadKey }) { t ->
                ThreadRow(
                    t,
                    origin = vm.origin(t.last.sourceDeviceId, t.last.simSlot),
                    titleQuery = query,
                    onClick = { onOpen(ThreadTarget(t.last.threadKey)) },
                    onMarkRead = { vm.markRead(t.last.threadKey) },
                    onDelete = { vm.deleteThread(t.last.threadKey) },
                )
            }
        }
        if (messages.isNotEmpty()) {
            item { SectionHeader("Messages", Modifier.padding(horizontal = 16.dp)) }
            items(messages, key = { "m:" + it.rowKey }) { m ->
                ResultRow(m, query) { onOpen(ThreadTarget(m.threadKey, focus = m.rowKey, query = query)) }
            }
        }
    }
}

// ── rows ─────────────────────────────────────────────────────────────────────

@Composable
private fun ThreadRow(
    t: ThreadSummary,
    origin: String,
    onClick: () -> Unit,
    onMarkRead: () -> Unit,
    onDelete: () -> Unit,
    titleQuery: String = "",
) {
    // Swipe from the start to mark read, from the end to delete; the long-press menu offers both too.
    val swipe = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        swipe,
        backgroundContent = { SwipeBackground(swipe.dismissDirection) },
        enableDismissFromStartToEnd = t.unreadCount > 0,
        onDismiss = { direction ->
            if (direction == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
            } else {
                onMarkRead()
                scope.launch { swipe.reset() }
            }
        },
    ) {
        ThreadRowContent(t, origin, titleQuery, onClick, onMarkRead, onDelete)
    }
}

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue) {
    if (direction == SwipeToDismissBoxValue.Settled) return
    val delete = direction == SwipeToDismissBoxValue.EndToStart
    Box(
        Modifier.fillMaxSize().background(if (delete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary).padding(horizontal = 24.dp),
        contentAlignment = if (delete) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Icon(if (delete) Icons.Default.Delete else Icons.Default.DoneAll, if (delete) "Delete" else "Mark as read", tint = Color.White)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadRowContent(
    t: ThreadSummary,
    origin: String,
    titleQuery: String,
    onClick: () -> Unit,
    onMarkRead: () -> Unit,
    onDelete: () -> Unit,
) {
    val m = t.last
    val unread = t.unreadCount > 0
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box(Modifier.background(colors.background)) {
        Column(Modifier.combinedClickable(onClick = onClick, onLongClick = { menu = true })) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KindIcon(m.type)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            highlight(m.threadTitle, titleQuery),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = layoutStart(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(listTime(m.occurredAt), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                    Text(
                        m.preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        textAlign = layoutStart(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(origin, style = MaterialTheme.typography.labelSmall, color = colors.primary, maxLines = 1)
                }
                if (unread) {
                    Box(Modifier.padding(top = 6.dp).size(9.dp).clip(CircleShape).background(colors.primary).semantics { contentDescription = "Unread" })
                }
            }
            HorizontalDivider(Modifier.padding(start = 68.dp), color = colors.outlineVariant.copy(alpha = 0.5f))
        }
        DropdownMenu(menu, { menu = false }) {
            if (unread) {
                DropdownMenuItem({ Text("Mark as read") }, { menu = false; onMarkRead() }, leadingIcon = { Icon(Icons.Default.DoneAll, null) })
            }
            DropdownMenuItem({ Text("Delete conversation") }, { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Default.Delete, null) })
        }
    }
}

@Composable
private fun ResultRow(m: Message, query: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KindIcon(m.type)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.threadTitle, style = MaterialTheme.typography.titleMedium, textAlign = layoutStart(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.size(8.dp))
                    Text(listTime(m.occurredAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    snippet(if (m.outgoing) "You: ${m.body}" else m.body, query),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = layoutStart(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

// ── receiver setup ───────────────────────────────────────────────────────────

/** Notification permission and instant delivery, shown above the inbox until both are sorted. */
@Composable
private fun ReceiverSetup(vm: AppViewModel, storeVersion: Long) {
    val context = LocalContext.current
    var checks by remember { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { checks++ }
    val needsPermission = remember(checks) { Build.VERSION.SDK_INT >= 33 && !context.granted(Manifest.permission.POST_NOTIFICATIONS) }
    val provider = remember(storeVersion) { vm.store.pushProvider }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    val distributors = remember(checks) { Push.distributors(context) }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (needsPermission) {
            SetupBanner(Icons.Default.NotificationsActive, "Allow notifications", "So new messages can alert you.") {
                FilledTonalButton({ permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow") }
            }
        }
        if (provider == null && !dismissed) {
            val body = when {
                distributors.isNotEmpty() -> "Use your UnifiedPush app to get messages the moment they arrive. Until then, Ferry checks every 15 minutes."
                Fcm.configured -> "Connecting to Google's push service… If this stays, install a UnifiedPush app such as ntfy."
                else -> "Install a UnifiedPush app such as ntfy to get messages the moment they arrive. Until then, Ferry checks every 15 minutes."
            }
            SetupBanner(Icons.Default.Bolt, "Get messages instantly", body) {
                TextButton({ dismissed = true }) { Text("Not now") }
                if (distributors.isNotEmpty()) {
                    FilledTonalButton({
                        context.findActivity()?.let { Push.useUnifiedPush(it) { checks++ } }
                    }) { Text("Turn on") }
                } else {
                    FilledTonalButton({
                        context.startActivity(Intent(Intent.ACTION_VIEW, "https://unifiedpush.org/users/distributors/".toUri()))
                    }) { Text("Get an app") }
                }
            }
        }
    }
}

@Composable
private fun SetupBanner(icon: ImageVector, title: String, body: String, actions: @Composable RowScope.() -> Unit) {
    FerryCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon, MaterialTheme.colorScheme.primary, size = 36.dp)
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), content = actions)
        }
    }
}

// ── shared bits ──────────────────────────────────────────────────────────────

/** Start of the screen, not of the text: keeps names and previews in a list lined up whatever their script. */
@Composable
fun layoutStart(): TextAlign = if (LocalLayoutDirection.current == LayoutDirection.Ltr) TextAlign.Left else TextAlign.Right

/** [text] with every match of [query] highlighted. */
@Composable
fun highlight(text: String, query: String, current: Boolean = false): AnnotatedString {
    val ranges = remember(text, query) { if (query.isBlank()) emptyList() else TextTools.matches(text, query) }
    if (ranges.isEmpty()) return AnnotatedString(text)
    val style = SpanStyle(
        background = MaterialTheme.colorScheme.primary.copy(alpha = if (current) 0.45f else 0.22f),
        fontWeight = FontWeight.SemiBold,
    )
    return buildAnnotatedString {
        append(text)
        ranges.forEach { addStyle(style, it.first, it.last + 1) }
    }
}

/** The part of [text] around the first match of [query], highlighted, so the match is visible in two lines. */
@Composable
private fun snippet(text: String, query: String): AnnotatedString {
    val first = remember(text, query) { TextTools.matches(text, query).firstOrNull() }
    if (first == null || first.first < 40) return highlight(text, query)
    var start = first.first - 30
    if (Character.isLowSurrogate(text[start])) start--
    val space = text.lastIndexOf(' ', start).takeIf { it >= 0 && start - it < 12 }
    return highlight("…" + text.substring(space?.plus(1) ?: start), query)
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

// ── message display ──────────────────────────────────────────────────────────

/** A thread's name: the contact or number, or the app for relayed notifications. */
val Message.threadTitle: String get() = if (type == "notification") title.substringBefore(" · ") else title

/** For a relayed notification, its own title (e.g. the chat it came from); null otherwise. */
val Message.notificationTitle: String? get() = if (type == "notification") title.substringAfter(" · ", "").ifBlank { null } else null

val Message.isMissedCall: Boolean get() = type == "call" && (body.startsWith("Missed") || body.startsWith("Declined"))

/** One line for the inbox list. */
val Message.preview: String
    get() = when {
        outgoing -> "You: $body"
        notificationTitle != null -> "$notificationTitle: $body"
        else -> body
    }
