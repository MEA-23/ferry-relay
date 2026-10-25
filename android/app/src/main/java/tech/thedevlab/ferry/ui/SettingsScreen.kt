package tech.thedevlab.ferry.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import tech.thedevlab.ferry.BuildConfig
import tech.thedevlab.ferry.crypto.FerryCrypto
import tech.thedevlab.ferry.push.Push

/** The receiver's settings, laid out like the iPhone app's: notifications, account, and leaving. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, storeVersion: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = vm.store
    // Notification settings can change outside the app; re-check on every resume and after each prompt.
    var checks by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { checks++ }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { checks++ }
    val notificationsOn = remember(checks) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val provider = remember(storeVersion) { store.pushProvider }
    val distributors = remember(checks) { Push.distributors(context) }
    var confirmLeave by remember { mutableStateOf(false) }

    Column(modifier) {
        CenterAlignedTopAppBar(
            title = { Text("Settings") },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            windowInsets = WindowInsets(0),
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item {
                FormSection(footer = "Notifications are decrypted on this phone. The push service and the server never see what a message says.") {
                    if (notificationsOn) {
                        IconRow(Icons.Default.NotificationsActive, "Notifications on", tint = Color(0xFF34C759))
                    } else {
                        IconRow(Icons.Default.NotificationsOff, "Allow notifications", tint = MaterialTheme.colorScheme.primary) {
                            if (Build.VERSION.SDK_INT >= 33) {
                                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else if (Build.VERSION.SDK_INT >= 26) {
                                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                            } else {
                                // No per-app notification screen before Android 8; its App info page has the switch.
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                            }
                        }
                    }
                    FormDivider(start = 56.dp)
                    if (provider != null) {
                        IconRow(Icons.Default.Bolt, "Instant delivery", value = "On", tint = Color(0xFF34C759))
                    } else {
                        IconRow(Icons.Default.Bolt, "Get messages instantly", value = "Every 15 min", tint = MaterialTheme.colorScheme.primary) {
                            if (distributors.isNotEmpty()) {
                                context.findActivity()?.let { Push.useUnifiedPush(it) { checks++ } }
                            } else {
                                context.startActivity(Intent(Intent.ACTION_VIEW, "https://unifiedpush.org/users/distributors/".toUri()))
                            }
                        }
                    }
                }
            }
            item {
                FormSection("Account") {
                    val server = store.serverUrl.orEmpty()
                    // The hosted server by the name it had at setup; a self-hosted one by its address.
                    LabeledRow("Server", if (server.trimEnd('/') == BuildConfig.DEFAULT_SERVER) "Ferry Relay" else server.toUri().host ?: server)
                    FormDivider()
                    LabeledRow("Account", store.userId.orEmpty().takeLast(12))
                    FormDivider()
                    LabeledRow("Key ID", (store.currentUmk?.let(FerryCrypto::keyId) ?: store.nodeRoot?.let(FerryCrypto::nodeKeyId)).orEmpty(), monospace = true)
                }
            }
            item {
                FormSection(footer = "Ferry ${BuildConfig.VERSION_NAME} · end-to-end encrypted") {
                    Text(
                        "Remove this phone from the account",
                        Modifier.fillMaxWidth().clickable { confirmLeave = true }.padding(horizontal = 16.dp, vertical = 14.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Remove this phone?") },
            text = { Text("Its key and every decrypted message are deleted from this phone. You can pair again later.") },
            confirmButton = {
                TextButton({
                    confirmLeave = false
                    vm.leave()
                }) { Text("Remove and delete inbox", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirmLeave = false }) { Text("Cancel") } },
        )
    }
}

/** A settings row: an icon, a title, and an optional value on the right; tappable when [onClick] is set. */
@Composable
private fun IconRow(icon: ImageVector, title: String, tint: Color, value: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = tint)
        Text(
            title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        value?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
