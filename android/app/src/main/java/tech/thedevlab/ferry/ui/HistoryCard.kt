package tech.thedevlab.ferry.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import java.text.NumberFormat
import java.util.concurrent.TimeUnit
import tech.thedevlab.ferry.capture.SmsHistory
import tech.thedevlab.ferry.relay.HistoryImportWorker
import tech.thedevlab.ferry.service.RelayService

private enum class Range(val label: String, val days: Long?) {
    Month("30 days", 30),
    HalfYear("6 months", 182),
    All("Everything", null),
    ;

    fun since(now: Long) = days?.let { now - TimeUnit.DAYS.toMillis(it) } ?: 0L
}

/** Imports the SMS already on this phone (§4.2). Also where READ_SMS is granted for outgoing capture. */
@Composable
fun HistoryCard(vm: AppViewModel, storeVersion: Long) {
    val context = LocalContext.current
    val store = vm.store
    var canRead by remember { mutableStateOf(SmsHistory.canRead(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        canRead = ok
        // Lets the relay service start watching for messages sent from this phone.
        if (ok) RelayService.start(context)
    }
    val work by vm.historyImport.collectAsStateWithLifecycle()
    val running = work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED
    var range by rememberSaveable { mutableStateOf(Range.All) }
    var available by remember { mutableStateOf<Int?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val numbers = remember { NumberFormat.getIntegerInstance() }

    LaunchedEffect(range, canRead, running, storeVersion, refresh) {
        available = if (canRead && !running) vm.historyCount(range.since(System.currentTimeMillis())) else null
    }

    FerryCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Default.History, MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text("Message history", style = MaterialTheme.typography.titleMedium)
                    val imported = store.historyImportedCount
                    Text(
                        if (imported > 0) "${numbers.format(imported)} imported so far" else "Texts already on this phone",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when {
                !canRead -> {
                    Text(
                        "Copy the texts already on this phone to your other devices, and relay the ones you send from now on.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton({ permission.launch(Manifest.permission.READ_SMS) }) { Text("Allow SMS access") }
                }

                running -> {
                    val done = work?.progress?.getInt(HistoryImportWorker.KEY_DONE, 0) ?: 0
                    val total = work?.progress?.getInt(HistoryImportWorker.KEY_TOTAL, 0) ?: 0
                    Text(
                        if (total == 0) "Getting ready…" else "Encrypting ${numbers.format(done)} of ${numbers.format(total)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { if (total == 0) 0f else done / total.toFloat() },
                        modifier = Modifier.fillMaxWidth().clip(CircleShape),
                        drawStopIndicator = {},
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Newest first. Uploads continue in the background.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton({ vm.cancelHistoryImport() }) { Text("Cancel") }
                    }
                }

                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Range.entries.forEach { r ->
                            FilterChip(range == r, { range = r }, label = { Text(r.label) })
                        }
                    }
                    val count = available
                    Text(
                        when {
                            count == null -> "Counting…"
                            count == 0 -> "Nothing new to import from this range."
                            else -> "${numbers.format(count)} message${if (count == 1) "" else "s"}, sent and received. They arrive quietly — no notifications."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        {
                            vm.startHistoryImport(range.since(System.currentTimeMillis()))
                            refresh++
                        },
                        enabled = (count ?: 0) > 0,
                    ) { Text(if ((count ?: 0) > 0) "Import ${numbers.format(count)}" else "Import") }
                }
            }
        }
    }
}

/** One-line entry on the home screen; shows progress while an import runs. */
@Composable
fun HistoryRow(vm: AppViewModel, onClick: () -> Unit) {
    val work by vm.historyImport.collectAsStateWithLifecycle()
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val running = work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED
    val subtitle = when {
        running -> {
            val done = work?.progress?.getInt(HistoryImportWorker.KEY_DONE, 0) ?: 0
            val total = work?.progress?.getInt(HistoryImportWorker.KEY_TOTAL, 0) ?: 0
            if (total == 0) "Importing…" else "Importing ${numbers.format(done)} of ${numbers.format(total)}"
        }
        vm.store.historyImportedCount > 0 -> "${numbers.format(vm.store.historyImportedCount)} imported"
        else -> "Copy texts already on this phone"
    }
    ChoiceRow(Icons.Default.History, MaterialTheme.colorScheme.primary, "Import older messages", subtitle, onClick = onClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: AppViewModel, storeVersion: Long, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        TopAppBar(
            title = { Text("Import older messages") },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            windowInsets = WindowInsets(0),
        )
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Sends the texts already on this phone — received and sent — to your other devices, encrypted like everything else. " +
                    "They arrive quietly, without notifications, and nothing is ever imported twice.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HistoryCard(vm, storeVersion)
        }
    }
}
