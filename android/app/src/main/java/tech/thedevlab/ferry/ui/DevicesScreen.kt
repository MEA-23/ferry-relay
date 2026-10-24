package tech.thedevlab.ferry.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Every device on the account. [onBack] is null when this is a tab rather than a pushed screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(vm: AppViewModel, onBack: (() -> Unit)?, onPair: () -> Unit, modifier: Modifier = Modifier) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<DeviceItem?>(null) }
    LaunchedEffect(Unit) { vm.refreshDevices() }

    Column(modifier) {
        CenterAlignedTopAppBar(
            title = { Text("Devices") },
            navigationIcon = { onBack?.let { IconButton(it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } } },
            actions = { IconButton(onPair) { Icon(Icons.Default.Add, "Pair a device") } },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            windowInsets = WindowInsets(0),
        )
        PullToRefreshBox(busy, { vm.refreshDevices() }, Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                item {
                    FormSection(footer = "Every device shares your encryption key. If a phone is lost, remove it here, then re-pair the rest from a fresh account.") {
                        devices.forEachIndexed { i, d ->
                            if (i > 0) FormDivider(start = 64.dp)
                            DeviceRow(d) { confirm = d }
                        }
                    }
                }
            }
        }
    }

    confirm?.let { d ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Remove ${d.name}?") },
            text = {
                Text(
                    if (d.isSelf) {
                        "This phone stops sending and receiving through your account, and you'll need to pair it again."
                    } else {
                        "It stops sending and receiving through your account. It still knows your key — re-pairing everything under a new account is the only way to fully lock it out."
                    },
                )
            },
            confirmButton = {
                TextButton({
                    vm.revoke(d.id)
                    confirm = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DeviceRow(d: DeviceItem, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val relays = "source" in d.roles
    // Sources heartbeat at least every 6 hours (§4.5); warn after 12 hours of silence.
    val stale = relays && !d.isSelf && System.currentTimeMillis() - d.lastSeenAt > 12 * 3_600_000L
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (d.platform == "ios") Icons.Default.PhoneIphone else Icons.Default.PhoneAndroid,
            null,
            Modifier.width(36.dp).size(28.dp),
            tint = colors.primary,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(d.name, style = MaterialTheme.typography.titleMedium)
                if (d.isSelf) Pill("This phone", colors.onSurfaceVariant)
            }
            Text(if (relays) "Relays texts, calls & notifications" else "Receives", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (!d.isSelf) {
                Text("Last seen ${relative(d.lastSeenAt)}", style = MaterialTheme.typography.bodySmall, color = if (stale) Color(0xFFFF9500) else colors.onSurfaceVariant)
            }
        }
        // Anchored to its button at the end of the row, so the menu opens there instead of over the name.
        Box {
            IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More for ${d.name}", tint = colors.onSurfaceVariant) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Remove", color = colors.error) }, {
                    menu = false
                    onRemove()
                }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = colors.error) })
            }
        }
    }
}
