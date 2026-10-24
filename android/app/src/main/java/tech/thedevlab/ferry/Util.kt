package tech.thedevlab.ferry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.security.SecureRandom

private val random = SecureRandom()

/** UUIDv7 (time-ordered), lowercase — the protocol's event id format. */
fun uuidV7(now: Long = System.currentTimeMillis()): String {
    val b = ByteArray(16).also(random::nextBytes)
    for (i in 0 until 6) b[i] = (now ushr (8 * (5 - i))).toByte()
    b[6] = ((b[6].toInt() and 0x0f) or 0x70).toByte()
    b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
    val h = b.joinToString("") { "%02x".format(it) }
    return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
}

fun Context.granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** Runs [block] off the main thread while keeping the broadcast alive until it finishes. */
fun BroadcastReceiver.launchAsync(context: Context, block: suspend () -> Unit) {
    val pending = goAsync()
    context.graph.scope.launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}
