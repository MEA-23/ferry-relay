package tech.thedevlab.ferry.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import tech.thedevlab.ferry.Notifications
import tech.thedevlab.ferry.graph
import tech.thedevlab.ferry.service.Roles

/** FragmentActivity (not ComponentActivity) because BiometricPrompt needs one. */
class MainActivity : FragmentActivity() {
    private val deepLink = mutableStateOf<String?>(null)
    private val threadLink = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLink.value = pairingLink(intent)
        // Only on a fresh start: after a rotation the thread was already opened.
        if (savedInstanceState == null) threadLink.value = intent?.getStringExtra(Notifications.EXTRA_THREAD)
        setContent {
            FerryTheme {
                FerryRoot(
                    deepLink = deepLink.value,
                    onDeepLinkUsed = { deepLink.value = null },
                    threadLink = threadLink.value,
                    onThreadLinkUsed = { threadLink.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pairingLink(intent)?.let { deepLink.value = it }
        intent.getStringExtra(Notifications.EXTRA_THREAD)?.let { threadLink.value = it }
    }

    override fun onResume() {
        super.onResume()
        // Restarts the relay service if the OS killed it while the app was closed.
        if (graph.store.isPaired) Roles.apply(this)
    }

    private fun pairingLink(intent: Intent?): String? =
        intent?.data?.takeIf { it.scheme == "ferry" && it.host == "pair" }?.toString()
}
