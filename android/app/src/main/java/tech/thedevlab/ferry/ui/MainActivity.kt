package tech.thedevlab.ferry.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import tech.thedevlab.ferry.Notifications
import tech.thedevlab.ferry.graph
import tech.thedevlab.ferry.relay.PullWorker
import tech.thedevlab.ferry.service.Roles

/** FragmentActivity (not ComponentActivity) because BiometricPrompt needs one. */
class MainActivity : FragmentActivity() {
    private val deepLink = mutableStateOf<String?>(null)
    private val threadLink = mutableStateOf<String?>(null)
    private val removeLink = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLink.value = pairingLink(intent)
        // Only on a fresh start: after a rotation the thread was already opened.
        if (savedInstanceState == null) {
            threadLink.value = intent?.getStringExtra(Notifications.EXTRA_THREAD)
            removeLink.value = intent?.getStringExtra(Notifications.EXTRA_REMOVE_DEVICE)
        }
        // Live while on screen; push and the periodic pull cover the rest.
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { PullWorker.live(this@MainActivity) } }
        setContent {
            FerryTheme {
                FerryRoot(
                    deepLink = deepLink.value,
                    onDeepLinkUsed = { deepLink.value = null },
                    threadLink = threadLink.value,
                    onThreadLinkUsed = { threadLink.value = null },
                    removeLink = removeLink.value,
                    onRemoveLinkUsed = { removeLink.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pairingLink(intent)?.let { deepLink.value = it }
        intent.getStringExtra(Notifications.EXTRA_THREAD)?.let { threadLink.value = it }
        intent.getStringExtra(Notifications.EXTRA_REMOVE_DEVICE)?.let { removeLink.value = it }
    }

    override fun onResume() {
        super.onResume()
        // Restarts the relay service if the OS killed it while the app was closed.
        if (graph.store.isPaired && !graph.store.awaitingKey) Roles.apply(this)
    }

    private fun pairingLink(intent: Intent?): String? =
        intent?.data?.takeIf { it.scheme == "ferry" && it.host in setOf("pair", "node", "intro") }?.toString()
}
