package tech.thedevlab.ferry.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.thedevlab.ferry.service.HeartbeatWorker
import tech.thedevlab.ferry.service.RelayService

private class AppEntry(val packageName: String, val label: String, val icon: ImageBitmap?)

/** Notification allowlist (§4.4): nothing is relayed until an app is switched on here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(vm: AppViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val iconPx = with(LocalDensity.current) { 40.dp.roundToPx() }
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var allowed by remember { mutableStateOf(vm.store.allowedPackages) }
    // Order is fixed when the screen opens, so rows don't jump while toggling.
    val initiallyAllowed = remember { vm.store.allowedPackages }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .distinctBy { it.activityInfo.packageName }
                .filter { it.activityInfo.packageName != context.packageName }
                .map {
                    val icon = runCatching { it.loadIcon(pm).toBitmap(iconPx, iconPx).asImageBitmap() }.getOrNull()
                    AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString(), icon)
                }
                .sortedBy { it.label.lowercase() }
        }
    }

    Column(modifier) {
        TopAppBar(
            title = { Text("Notification apps") },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            windowInsets = WindowInsets(0),
        )
        Text(
            "Ferry relays notifications only from the apps you turn on. Off for everything by default.",
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            query,
            { query = it },
            Modifier.fillMaxWidth().padding(16.dp),
            placeholder = { Text("Search apps") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(50),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        )
        val list = apps
        if (list == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            return@Column
        }
        val shown = list.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
            .sortedByDescending { it.packageName in initiallyAllowed }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            items(shown, key = { it.packageName }) { app ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    app.icon?.let { Image(it, null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))) }
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.titleSmall)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(app.packageName in allowed, { on ->
                        allowed = if (on) allowed + app.packageName else allowed - app.packageName
                        vm.store.allowedPackages = allowed
                        RelayService.start(context)
                        HeartbeatWorker.reportIfChanged(context)
                    })
                }
            }
        }
    }
}
