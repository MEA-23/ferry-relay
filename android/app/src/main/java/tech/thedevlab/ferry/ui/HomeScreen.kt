package tech.thedevlab.ferry.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.thedevlab.ferry.R
import tech.thedevlab.ferry.capture.NotificationCapture
import tech.thedevlab.ferry.capture.Sims
import tech.thedevlab.ferry.data.Activity
import tech.thedevlab.ferry.data.Role
import tech.thedevlab.ferry.granted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel, storeVersion: Long, onNavigate: (Screen) -> Unit, modifier: Modifier = Modifier) {
    val store = vm.store
    val context = LocalContext.current
    val pending by vm.outboxCount.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    // Permissions can change in Settings; re-check on every resume and after each dialog.
    var checks by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { checks++ }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checks++ }
    val setup = remember(checks, storeVersion) { setupItems(context) }
    val sims = remember(checks) { Sims.active(context) }
    val source = Role.SOURCE in store.roles

    Column(modifier) {
        TopAppBar(
            title = {
                Column {
                    Text("Ferry")
                    Text(store.deviceName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            actions = {
                IconButton({ onNavigate(Screen.Pair) }) { Icon(Icons.Default.QrCode2, "Pair a device") }
                IconButton({ onNavigate(Screen.Devices) }) { Icon(Icons.Default.Devices, "Devices") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            windowInsets = WindowInsets(0),
        )
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (store.revoked) {
                item { RevokedCard(vm) }
                return@LazyColumn
            }
            if (source) {
                item(key = "hero") {
                    key(storeVersion) {
                        val missing = setup.count { !it.ok && it.required }
                        val simNames = sims.map { store.simLabel(it.simSlotIndex) }
                        SourceHero(store.lastError, missing, pending, store.lastUploadAt, simNames, store.allowedPackages.size)
                    }
                }
                if (setup.any { !it.ok }) {
                    item(key = "setup") { SetupCard(setup, onRequest = { launcher.launch(it.toTypedArray()) }) }
                }
                item(key = "sims") { key(storeVersion) { SimsRow(vm, sims) } }
                item(key = "actions") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        QuickAction(Icons.Default.QrCode2, "Pair a device", "Add the phone you carry", Modifier.weight(1f)) { onNavigate(Screen.Pair) }
                        val apps = store.allowedPackages.size
                        QuickAction(Icons.Default.Apps, "Apps", if (apps == 0) "None relayed" else "$apps relayed", Modifier.weight(1f)) { onNavigate(Screen.Apps) }
                    }
                }
                item(key = "history") { HistoryRow(vm) { onNavigate(Screen.History) } }
            }

            item { SectionHeader("Recent") }
            if (activity.isEmpty()) {
                item {
                    EmptyState(
                        R.drawable.art_empty,
                        "All quiet",
                        "Texts, calls and notifications this phone relays will show up here.",
                    )
                }
            }
            items(activity, key = { it.rowId }) { ActivityRow(it, store.deviceNames[it.sourceDeviceId], store::simLabel) }
        }
    }
}

// ── status ───────────────────────────────────────────────────────────────────

private val heroBrush = Brush.linearGradient(listOf(Brand.DeepTeal, Brand.Teal, Brand.Ocean))

@Composable
private fun Hero(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(heroBrush).padding(20.dp).animateContentSize(),
    ) { content() }
}

@Composable
private fun SourceHero(error: String?, missing: Int, pending: Int, lastUploadAt: Long, sims: List<String>, apps: Int) {
    val (title, dot) = when {
        error != null -> "Needs attention" to Brand.Coral
        missing > 0 -> "Almost ready" to Color(0xFFFFD27A)
        else -> "Relaying" to Brand.Mint
    }
    Hero {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseDot(dot)
                Spacer(Modifier.size(6.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
            }
            Text(
                error ?: when {
                    missing > 0 -> "Allow $missing more permission${if (missing == 1) "" else "s"} below to start relaying."
                    sims.isEmpty() -> "Relaying SMS and calls from this phone."
                    else -> "Relaying SMS and calls from ${sims.joinToString(" and ")}."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Stat(if (lastUploadAt == 0L) "—" else shortTime(lastUploadAt), "Last sent")
                Stat(pending.toString(), "In queue")
                Stat(apps.toString(), "Apps")
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge, color = Color.White)
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.75f))
    }
}

// ── setup checklist ──────────────────────────────────────────────────────────

private class SetupItem(
    val icon: ImageVector,
    val title: String,
    val detail: String,
    val ok: Boolean,
    val required: Boolean = true,
    val permissions: List<String> = emptyList(),
    val open: ((Context) -> Unit)? = null,
)

private fun setupItems(context: Context): List<SetupItem> {
    val phone = listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_NUMBERS)
    fun perm(icon: ImageVector, title: String, detail: String, permissions: List<String>, required: Boolean = true) =
        SetupItem(icon, title, detail, permissions.all(context::granted), required, permissions)

    return buildList {
        add(perm(Icons.Default.Sms, "SMS", "Read incoming texts", listOf(Manifest.permission.RECEIVE_SMS)))
        add(perm(Icons.Default.Call, "Calls & SIMs", "Incoming calls and which SIM they hit", phone))
        if (Build.VERSION.SDK_INT >= 33) {
            add(perm(Icons.Default.NotificationsActive, "Status notification", "Shows that Ferry is relaying", listOf(Manifest.permission.POST_NOTIFICATIONS)))
        }
        add(
            SetupItem(
                Icons.Default.BatteryChargingFull, "Run in background", "Stops Android from pausing Ferry",
                context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
            ) {
                @Suppress("BatteryLife")
                it.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${it.packageName}".toUri()))
            },
        )
        add(
            SetupItem(
                Icons.Default.Notifications, "Notification access", "Optional — only for apps you pick",
                context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context),
                required = false,
                open = ::openListenerSettings,
            ),
        )
        add(perm(Icons.Default.Contacts, "Contacts", "Optional — names instead of numbers", listOf(Manifest.permission.READ_CONTACTS), required = false))
    }
}

private fun openListenerSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= 30) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(context, NotificationCapture::class.java).flattenToString(),
        )
    } else {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
    context.startActivity(intent)
}

@Composable
private fun SetupCard(items: List<SetupItem>, onRequest: (List<String>) -> Unit) {
    val context = LocalContext.current
    val done = items.count { it.ok }
    val requiredLeft = items.any { !it.ok && it.required }
    FerryCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (requiredLeft) "Finish setup" else "Optional extras", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (requiredLeft) Text("$done of ${items.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            if (requiredLeft) {
                LinearProgressIndicator(
                    progress = { done / items.size.toFloat() },
                    modifier = Modifier.fillMaxWidth().clip(CircleShape),
                    drawStopIndicator = {},
                )
            }
            items.filter { !it.ok }.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconBadge(item.icon, if (item.required) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, size = 36.dp)
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.bodyLarge)
                        Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalButton({ item.open?.invoke(context) ?: onRequest(item.permissions) }) { Text("Allow") }
                }
            }
            if (requiredLeft || items.any { !it.ok && it.open != null }) {
                Text(
                    "Toggle greyed out in Settings? Android limits sideloaded apps — open App info → ⋮ → Allow restricted settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) },
                    contentPadding = PaddingValues(0.dp),
                ) { Text("Open App info") }
            }
        }
    }
}

// ── SIMs & quick actions ─────────────────────────────────────────────────────

fun simColor(slot: Int?): Color = when (slot) {
    0 -> Brand.Teal
    1 -> Brand.Ocean
    else -> Brand.Sky
}

@Composable
private fun SimsRow(vm: AppViewModel, sims: List<android.telephony.SubscriptionInfo>) {
    val store = vm.store
    var editing by remember { mutableStateOf(false) }

    Column {
        SectionHeader("This phone") {
            TextButton({ editing = true }) {
                Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("Rename")
            }
        }
        if (sims.isEmpty()) {
            FerryCard {
                Text(
                    "No SIMs found yet. Allow Calls & SIMs above so Ferry can tell your SIMs apart.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                sims.forEach { sim ->
                    FerryCard(Modifier.weight(1f), onClick = { editing = true }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                Modifier.size(28.dp).clip(CircleShape).background(simColor(sim.simSlotIndex)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("${sim.simSlotIndex + 1}", color = Color.White, style = MaterialTheme.typography.labelLarge)
                            }
                            Text(store.simLabel(sim.simSlotIndex), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                sim.carrierName?.toString() ?: "Unknown carrier",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }

    if (editing) {
        var name by remember { mutableStateOf(store.deviceName) }
        val labels = remember { mutableStateMapOf<Int, String>().apply { sims.forEach { put(it.simSlotIndex, store.simLabel(it.simSlotIndex)) } } }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Names") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Shown on your other devices. Encrypted — the server can't read them.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(name, { name = it }, label = { Text("This phone") }, singleLine = true)
                    labels.keys.sorted().forEach { slot ->
                        OutlinedTextField(labels[slot]!!, { labels[slot] = it }, label = { Text("SIM ${slot + 1}") }, singleLine = true)
                    }
                }
            },
            confirmButton = {
                TextButton({
                    vm.saveNames(name, labels.toMap())
                    editing = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton({ editing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun QuickAction(icon: ImageVector, title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    FerryCard(modifier, onClick) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            IconBadge(icon, MaterialTheme.colorScheme.primary, size = 36.dp)
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RevokedCard(vm: AppViewModel) {
    FerryCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            IconBadge(Icons.Default.Devices, MaterialTheme.colorScheme.error, size = 48.dp)
            Text("This phone was removed", style = MaterialTheme.typography.titleLarge)
            Text(
                "Another device removed it from your Ferry account. Its messages and keys were deleted from this phone. Reset to set it up again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button({ vm.reset() }) { Text("Reset Ferry") }
        }
    }
}

// ── activity ─────────────────────────────────────────────────────────────────

@Composable
private fun ActivityRow(a: Activity, deviceName: String?, simLabel: (Int) -> String) {
    val missed = a.type == "call" && (a.body.startsWith("Missed") || a.body.startsWith("Declined"))
    val (icon, tint) = when {
        a.type == "sms" -> Icons.Default.Sms to Brand.Teal
        missed -> Icons.AutoMirrored.Filled.CallMissed to Brand.Coral
        a.type == "call" -> Icons.Default.Call to Brand.Ocean
        else -> Icons.Default.Notifications to Brand.Sky
    }
    Column {
        Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(icon, tint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(shortTime(a.occurredAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    a.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (missed) Brand.Coral else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                    a.simSlot?.let { Pill(simLabel(it), simColor(it)) }
                    if (a.received && deviceName != null) Pill(deviceName, MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!a.received) Icon(Icons.Default.Check, "Sent", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 54.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

/** "now", "5m", "3h", "Yesterday", or a date. */
fun shortTime(time: Long): String {
    val diff = System.currentTimeMillis() - time
    return when {
        diff < DateUtils.MINUTE_IN_MILLIS -> "now"
        diff < DateUtils.HOUR_IN_MILLIS -> "${diff / DateUtils.MINUTE_IN_MILLIS}m"
        diff < DateUtils.DAY_IN_MILLIS -> "${diff / DateUtils.HOUR_IN_MILLIS}h"
        diff < 2 * DateUtils.DAY_IN_MILLIS -> "Yesterday"
        else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(time))
    }
}

fun relative(time: Long): String =
    DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
