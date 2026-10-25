package tech.thedevlab.ferry.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tech.thedevlab.ferry.data.Role

enum class Screen { Home, Devices, Apps, Pair, History, NewMessage }

/** Where the app is. Tabs aren't part of it: switching tabs is instant, moving between these slides. */
private data class Dest(val onboarding: Boolean, val screen: Screen, val thread: ThreadTarget?, val awaitingKey: Boolean = false) {
    val depth get() = if (onboarding) -1 else if (thread != null) 2 else if (screen != Screen.Home) 1 else 0
}

/** A receiver's tabs, as on iOS; phones that also relay get their relay dashboard as a fourth. */
private enum class Tab { Inbox, Devices, Settings, Phone }

@Composable
fun FerryRoot(
    deepLink: String?,
    onDeepLinkUsed: () -> Unit,
    threadLink: String?,
    onThreadLinkUsed: () -> Unit,
    removeLink: String? = null,
    onRemoveLinkUsed: () -> Unit = {},
    vm: AppViewModel = viewModel(),
) {
    // Re-read the store whenever it changes.
    val changes by vm.store.changes.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val unread by vm.unread.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var screen by remember { mutableStateOf(Screen.Home) }
    var tab by rememberSaveable { mutableStateOf(Tab.Inbox) }
    var thread by remember { mutableStateOf<ThreadTarget?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.message.value = null
        }
    }

    // A tapped message notification opens its thread at the message it was about: the newest.
    LaunchedEffect(threadLink) {
        threadLink?.let {
            screen = Screen.Home
            tab = Tab.Inbox
            thread = ThreadTarget(it, latest = true)
            onThreadLinkUsed()
        }
    }

    // An introduction's Remove button: open Devices and ask about that device (§4.7).
    LaunchedEffect(removeLink) {
        removeLink?.let {
            screen = Screen.Home
            thread = null
            tab = Tab.Devices
            vm.removeRequest.value = it
            onRemoveLinkUsed()
        }
    }

    // Read here, not inside Scaffold's content lambda, so a store change re-evaluates it.
    // A new node waiting for its first receiver (§5.6 flow A) is still setting up.
    val awaitingKey = changes.let { vm.store.awaitingKey }
    val onboarding = changes.let { !vm.store.isPaired && !vm.store.revoked } || awaitingKey
    val roles = changes.let { vm.store.roles }
    val inbox = Role.RECEIVER in roles && !vm.store.revoked
    // A fresh setup starts at the inbox, and a phone that stopped relaying loses its tab.
    LaunchedEffect(onboarding, roles) {
        if (onboarding || (tab == Tab.Phone && Role.SOURCE !in roles)) tab = Tab.Inbox
    }

    val tabBar: @Composable () -> Unit = {
        NavigationBar {
            NavigationBarItem(
                tab == Tab.Inbox,
                { tab = Tab.Inbox },
                icon = {
                    BadgedBox(badge = { if (unread > 0) Badge { Text(if (unread > 99) "99+" else unread.toString()) } }) {
                        Icon(Icons.Default.Inbox, null)
                    }
                },
                label = { Text("Inbox") },
            )
            NavigationBarItem(tab == Tab.Devices, { tab = Tab.Devices }, icon = { Icon(Icons.Default.Devices, null) }, label = { Text("Devices") })
            NavigationBarItem(tab == Tab.Settings, { tab = Tab.Settings }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
            if (Role.SOURCE in roles) {
                NavigationBarItem(tab == Tab.Phone, { tab = Tab.Phone }, icon = { Icon(Icons.Default.PhoneAndroid, null) }, label = { Text("This phone") })
            }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        if (!onboarding) {
            BackHandler(thread != null) { thread = null }
            BackHandler(thread == null && screen != Screen.Home) { screen = Screen.Home }
        }
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        AnimatedContent(
            Dest(onboarding, screen, thread, awaitingKey),
            // The bottom inset is left to each screen: the tab bar belongs to the tabbed screen and
            // slides away with it, so the content area never resizes mid-transition.
            Modifier.padding(top = padding.calculateTopPadding()),
            transitionSpec = {
                // Like a navigation push: the new screen slides in over the old one, which drifts back a little.
                val forward = targetState.depth > initialState.depth
                val dir = if (rtl) -1 else 1
                when {
                    initialState.onboarding || targetState.onboarding -> fadeIn(tween(250)) togetherWith fadeOut(tween(250))
                    forward -> slideInHorizontally(tween(320)) { it * dir } togetherWith slideOutHorizontally(tween(320)) { -it * dir / 4 } + fadeOut(tween(320), 0.6f)
                    else -> slideInHorizontally(tween(280)) { -it * dir / 4 } + fadeIn(tween(280), 0.6f) togetherWith slideOutHorizontally(tween(280)) { it * dir }
                }.apply { targetContentZIndex = targetState.depth.toFloat() }
            },
            contentKey = { if (it.thread != null) "thread:${it.thread.key}" else "${it.onboarding}:${it.awaitingKey}:${it.screen}" },
            label = "navigation",
        ) { d ->
            // Opaque, so the screen underneath doesn't show through while one slides over it.
            val page = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            val modifier = page.padding(bottom = padding.calculateBottomPadding())
            when {
                d.awaitingKey -> NodeKeyScreen(vm, modifier)
                d.onboarding -> OnboardingScreen(vm, deepLink, onDeepLinkUsed, modifier)
                d.thread != null -> ThreadScreen(vm, d.thread, onBack = { thread = null }, modifier)
                else -> when (d.screen) {
                    // `changes` makes Home recompose (and re-read the store) whenever settings change.
                    Screen.Home ->
                        if (!inbox) {
                            HomeScreen(vm, changes, onNavigate = { screen = it }, modifier)
                        } else {
                            Column(page) {
                                val content = Modifier.weight(1f).fillMaxWidth()
                                when (tab) {
                                    Tab.Inbox -> InboxScreen(vm, changes, onOpen = { thread = it }, onNavigate = { screen = it }, content)
                                    Tab.Devices -> DevicesScreen(vm, onBack = null, onPair = { screen = Screen.Pair }, content)
                                    Tab.Settings -> SettingsScreen(vm, changes, content)
                                    Tab.Phone -> HomeScreen(vm, changes, onNavigate = { screen = it }, content)
                                }
                                tabBar()
                            }
                        }
                    Screen.Devices -> DevicesScreen(vm, onBack = { screen = Screen.Home }, onPair = { screen = Screen.Pair }, modifier)
                    Screen.Apps -> AppsScreen(vm, onBack = { screen = Screen.Home }, modifier)
                    Screen.Pair -> PairDeviceScreen(vm, onBack = { screen = Screen.Home }, modifier)
                    Screen.History -> HistoryScreen(vm, changes, onBack = { screen = Screen.Home }, modifier)
                    // Back from the conversation it opens goes to the inbox, not back here.
                    Screen.NewMessage -> NewMessageScreen(vm, onBack = { screen = Screen.Home }, onSent = {
                        screen = Screen.Home
                        thread = ThreadTarget(it, latest = true)
                    }, modifier)
                }
            }
        }
    }
}
