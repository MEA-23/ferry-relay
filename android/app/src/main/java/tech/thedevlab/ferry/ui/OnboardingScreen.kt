package tech.thedevlab.ferry.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.thedevlab.ferry.BuildConfig
import tech.thedevlab.ferry.R
import tech.thedevlab.ferry.data.Role
import tech.thedevlab.ferry.granted

private enum class Step(val progress: Float) { Welcome(0f), Role(0.25f), Method(0.5f), Create(0.75f), Join(0.75f), Scan(0.9f) }

@Composable
fun OnboardingScreen(vm: AppViewModel, deepLink: String?, onDeepLinkUsed: () -> Unit, modifier: Modifier = Modifier) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableStateOf(Step.Welcome) }
    var source by rememberSaveable { mutableStateOf(true) }
    var ownServer by rememberSaveable { mutableStateOf(false) }
    var server by rememberSaveable { mutableStateOf("") }
    var invite by rememberSaveable { mutableStateOf("") }
    var link by rememberSaveable { mutableStateOf("") }
    val roles = if (source) setOf(Role.SOURCE) else setOf(Role.RECEIVER)

    LaunchedEffect(deepLink) {
        if (deepLink != null) {
            link = deepLink
            step = Step.Role
            onDeepLinkUsed()
        }
    }

    val back: Step? = when (step) {
        Step.Welcome -> null
        Step.Role -> Step.Welcome
        Step.Method -> Step.Role
        Step.Create, Step.Join -> if (link.isNotEmpty() && step == Step.Join) Step.Role else Step.Method
        Step.Scan -> Step.Join
    }
    if (back != null) BackHandler { step = back }

    val context = LocalContext.current
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) step = Step.Scan
    }

    Column(modifier.fillMaxSize()) {
        if (step != Step.Welcome) {
            Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ back?.let { step = it } }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                LinearProgressIndicator(
                    progress = { step.progress },
                    modifier = Modifier.weight(1f).padding(end = 24.dp).clip(MaterialTheme.shapes.small),
                    drawStopIndicator = {},
                )
            }
        }

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn())
                    .togetherWith(slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
            },
            label = "onboarding",
        ) { current ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (current) {
                    Step.Welcome -> Welcome { step = Step.Role }

                    Step.Role -> {
                        Title("What is this phone?", "You can run Ferry on as many phones as you like.")
                        ChoiceRow(Icons.Default.SimCard, Brand.Teal, "It has the SIM cards", "Relays its SMS, calls and the notifications you choose.") {
                            source = true
                            step = if (link.isNotEmpty()) Step.Join else Step.Method
                        }
                        ChoiceRow(Icons.Default.PhoneAndroid, Brand.Ocean, "I read messages on it", "Receives and decrypts messages from your other phones.") {
                            source = false
                            step = if (link.isNotEmpty()) Step.Join else Step.Method
                        }
                    }

                    Step.Method -> {
                        if (source) Illustration(R.drawable.art_source, Modifier.fillMaxWidth())
                        Title("Is this your first Ferry device?", null)
                        ChoiceRow(
                            Icons.Default.AddCircleOutline, Brand.Teal, "Yes, start fresh",
                            if (source) "Creates your account here." else "Creates your account and a new encryption key on this phone.",
                        ) {
                            step = Step.Create
                        }
                        ChoiceRow(Icons.Default.QrCodeScanner, Brand.Ocean, "No, pair with my device", "Scan the code shown by a phone that's already set up.") {
                            step = Step.Join
                        }
                    }

                    Step.Create -> {
                        Title(
                            "Create your account",
                            if (source) {
                                "Next, you'll scan this phone's code with the phone you read messages on."
                            } else {
                                "A new encryption key is made on this phone. It never leaves your devices — the server only ever sees scrambled data."
                            },
                        )
                        Text("Server", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ChoiceRow(
                            Icons.Default.VerifiedUser, Brand.Teal, "Ferry server",
                            "Recommended. Run by Ferry; it only ever sees encrypted data.", selected = !ownServer,
                        ) { ownServer = false }
                        ChoiceRow(
                            Icons.Default.Dns, Brand.Ocean, "My own server",
                            "A Ferry server you host yourself.", selected = ownServer,
                        ) { ownServer = true }
                        AnimatedVisibility(ownServer) {
                            FerryField(
                                "Server address", Icons.Default.Language, server, { server = it },
                                placeholder = "ferry.example.com", hint = "https:// is added for you.",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                            )
                        }
                        FerryField(
                            "Invite code", Icons.Default.Key, invite, { invite = it },
                            placeholder = "Optional", hint = "Only needed if the server asks for one.", monospace = true,
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        )
                        PrimaryButton("Create account", busy, enabled = !ownServer || server.isNotBlank()) {
                            vm.createAccount(if (ownServer) server else BuildConfig.DEFAULT_SERVER, invite, roles)
                        }
                    }

                    Step.Join -> {
                        Title(
                            "Pair with your device",
                            if (source) {
                                "On a phone that reads your messages, open Ferry → Devices → Pair a device → Relay its own SIMs, then scan the code it shows."
                            } else {
                                "Scan the code on a phone that's already set up: Ferry → Pair a device on one you read messages on, or the code a new relaying phone shows."
                            },
                        )
                        Button(
                            {
                                if (context.granted(Manifest.permission.CAMERA)) step = Step.Scan else cameraPermission.launch(Manifest.permission.CAMERA)
                            },
                            Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Icon(Icons.Default.QrCodeScanner, null)
                            Spacer(Modifier.size(8.dp))
                            Text("Scan pairing code")
                        }
                        FerryField(
                            "…or paste the pairing link", Icons.Default.Link, link, { link = it },
                            placeholder = "ferry://…", singleLine = false, monospace = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                        )
                        if (link.isNotBlank()) {
                            OutlinedButton({ vm.join(link, roles) }, Modifier.fillMaxWidth().height(52.dp), enabled = !busy) {
                                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Pair")
                            }
                        }
                    }

                    Step.Scan -> {
                        Title("Scan the pairing code", "Point the camera at the code on your other device.")
                        QrScanner(Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large)) { text ->
                            link = text
                            step = Step.Join
                            vm.join(text, roles)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Welcome(onStart: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Illustration(R.drawable.art_hero, Modifier.fillMaxWidth())
    Text("Ferry", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
    Text("All your phones' messages, carried to one inbox.", style = MaterialTheme.typography.titleMedium)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        Feature(Icons.Default.Lock, "End-to-end encrypted", "Only your devices hold the key.")
        Feature(Icons.Default.SimCard, "Every SIM, every phone", "SMS, calls and notifications, tagged by SIM.")
        Feature(Icons.Default.Inbox, "One inbox", "Read everything on the phone you carry.")
    }
    Button(onStart, Modifier.fillMaxWidth().height(52.dp)) { Text("Get started") }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, MaterialTheme.colorScheme.primary)
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Title(title: String, body: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun PrimaryButton(text: String, busy: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().height(52.dp), enabled = enabled && !busy) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) else Text(text)
    }
}
