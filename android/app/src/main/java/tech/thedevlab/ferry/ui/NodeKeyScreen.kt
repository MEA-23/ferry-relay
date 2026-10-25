package tech.thedevlab.ferry.ui

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/**
 * A new node's last setup step (§5.6 flow A): it shows its code until the phone that reads
 * messages scans it, makes the account's key and answers. Until then it can't encrypt anything.
 */
@Composable
fun NodeKeyScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val activity = LocalActivity.current
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Whoever scans the code first becomes the account's reader, so keep it out of screenshots.
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(Unit) { if (pairing == null) vm.showNodeCode() }
    LaunchedEffect(pairing) {
        while (pairing != null) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Pair your phone", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Scan this code with the phone you read messages on.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val current = pairing?.takeIf { it.expiresAt > now }
        if (current != null) {
            QrPanel(current.link, current.expiresAt - now, title = "Waiting for your other phone…")
            Steps(
                listOf(
                    "Install Ferry on the phone you read messages on and open it.",
                    "iPhone: “Pair with my Android phone”. Android: “I read messages on it”, then “No, pair with my device”.",
                    "Scan this code. This phone starts relaying as soon as the other one answers.",
                ),
            )
        } else {
            Button({ vm.showNodeCode() }, Modifier.fillMaxWidth().height(52.dp), enabled = !busy) {
                Text(if (pairing != null) "Code expired — show a new one" else "Show the code")
            }
        }
        TextButton({ vm.reset() }, Modifier.align(Alignment.CenterHorizontally)) { Text("Start over") }
    }
}
