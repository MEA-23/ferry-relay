package tech.thedevlab.ferry.ui

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import tech.thedevlab.ferry.data.Role

private const val PAIRING_TTL_MS = 5 * 60_000f

/**
 * Shows a pairing QR: §5.3 for a phone that reads messages (it contains the encryption key), or
 * §5.6 flow B for a phone that relays its SIMs. Either one lets whoever scans it join the account,
 * so it's only shown after the phone's lock screen check, screenshots are blocked, and it
 * disappears after 5 minutes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairDeviceScreen(vm: AppViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val activity = LocalActivity.current as FragmentActivity
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    // A node can only add other nodes (§5.6 flow C); phones that read need a key it doesn't have.
    val node = vm.store.currentUmk == null
    var newRole by remember { mutableStateOf(if (node) Role.SOURCE else Role.RECEIVER) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    DisposableEffect(Unit) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            vm.clearPairing()
        }
    }
    LaunchedEffect(pairing) {
        while (pairing != null) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        TopAppBar(
            title = { Text("Pair a device") },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            windowInsets = WindowInsets(0),
        )
        val current = pairing?.takeIf { it.paired || it.expiresAt > now }
        val relay = newRole == Role.SOURCE
        AnimatedContent(current, contentKey = { if (it == null) 0 else if (it.paired) 2 else 1 }, label = "pairing") { shown ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    shown == null -> {
                        Text(if (node) "Add a relaying phone" else "What will the new device do?", style = MaterialTheme.typography.titleLarge)
                        if (!node) {
                            ChoiceRow(
                                Icons.Default.PhoneIphone, Brand.Ocean, "Read messages",
                                "The phone you carry — iPhone or Android.", selected = newRole == Role.RECEIVER,
                            ) { newRole = Role.RECEIVER }
                        }
                        ChoiceRow(
                            Icons.Default.SimCard, Brand.Teal, "Relay its own SIMs",
                            "Another Android phone with SIM cards.", selected = relay,
                        ) { newRole = Role.SOURCE }
                        if (node) {
                            Text(
                                "To add a phone that reads messages, pair it from a phone that already reads them.",
                                Modifier.padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FerryCard {
                            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconBadge(Icons.Default.Lock, Brand.Coral)
                                Text(
                                    if (relay) {
                                        "Whoever scans the code first joins your account as a relaying phone. Only show it to your own phones."
                                    } else {
                                        "The code contains your encryption key. Only show it to your own devices."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Button({ authenticate(activity) { vm.requestPairing(setOf(newRole)) } }, Modifier.fillMaxWidth().height(52.dp), enabled = !busy) {
                            Icon(Icons.Default.Fingerprint, null)
                            Spacer(Modifier.size(8.dp))
                            Text(if (pairing != null) "Code expired — show a new one" else "Show pairing code")
                        }
                    }
                    shown.paired -> {
                        FerryCard {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Phone paired", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    if (node) {
                                        "Your phones that read messages will show it with a notification, and start receiving from it."
                                    } else {
                                        "Its messages will arrive here once it's set up. Its name and SIM labels come from that phone."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Button(onBack, Modifier.fillMaxWidth().height(52.dp)) { Text("Done") }
                    }
                    else -> {
                        QrPanel(shown.link, shown.expiresAt - now)
                        Steps(
                            if (relay) {
                                listOf(
                                    "Install Ferry on the Android phone with the SIM cards and open it.",
                                    "Choose “It has the SIM cards”, then “No, pair with my device”.",
                                    "Scan this code. The phone makes its own key and checks in here.",
                                )
                            } else {
                                listOf(
                                    "Install Ferry on the new device and open it.",
                                    "Choose what the device does, then “pair with my device”.",
                                    "Scan this code. That's it — the key travels only through the camera.",
                                )
                            },
                        )
                        TextButton({ vm.clearPairing() }, Modifier.align(Alignment.CenterHorizontally)) { Text("Hide code") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun QrPanel(link: String, leftMs: Long, title: String = "Scan with Ferry on the new device") {
    val bitmap = remember(link) { qrBitmap(link) }
    val left = leftMs / 1000
    FerryCard {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            // Always black on white, so cameras read it in dark mode too.
            Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(Color.White).padding(16.dp)) {
                Image(bitmap, "Pairing code", Modifier.fillMaxWidth().aspectRatio(1f))
            }
            LinearProgressIndicator(
                progress = { (leftMs / PAIRING_TTL_MS).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().clip(CircleShape),
                drawStopIndicator = {},
            )
            Text(
                "Expires in ${left / 60}:${"%02d".format(left % 60)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun Steps(steps: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
        steps.forEachIndexed { i, text ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun authenticate(activity: FragmentActivity, onSuccess: () -> Unit) {
    val authenticators = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    // No screen lock set up: nothing to check against.
    if (BiometricManager.from(activity).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
        onSuccess()
        return
    }
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Show pairing code")
            .setSubtitle("The code contains your encryption key")
            .setAllowedAuthenticators(authenticators)
            .build(),
    )
}
