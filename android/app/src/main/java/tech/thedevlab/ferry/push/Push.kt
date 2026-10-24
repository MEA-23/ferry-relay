package tech.thedevlab.ferry.push

import android.app.Activity
import android.content.Context
import android.util.Log
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.UnifiedPush
import tech.thedevlab.ferry.data.Role
import tech.thedevlab.ferry.graph
import tech.thedevlab.ferry.net.PushRegistration
import tech.thedevlab.ferry.relay.PullWorker

/**
 * Instant delivery for receivers (§7). A push only wakes the app: it then pulls, decrypts and
 * notifies like the periodic check, so both paths dedupe and ack the same way.
 *
 * UnifiedPush is used when the user has a distributor app (ntfy, …) set up; otherwise Firebase
 * Cloud Messaging, when this build has a Firebase project ([Fcm.configured]). Without either,
 * receivers still check every 15 minutes and whenever Ferry opens. With push, the periodic check
 * drops to a slow safety net ([PullWorker.schedule]).
 */
object Push {
    const val UNIFIED_PUSH = "unifiedpush"
    const val FCM = "fcm"

    /** Re-registers with the server on app open, so a changed token or endpoint is never missed. */
    fun setUp(context: Context) {
        if (!context.graph.store.has(Role.RECEIVER)) return
        if (UnifiedPush.getAckDistributor(context) != null) {
            UnifiedPush.register(context)
        } else {
            Fcm.requestToken(context)
        }
    }

    /** Distributor apps installed on this phone. */
    fun distributors(context: Context): List<String> = UnifiedPush.getDistributors(context)

    /** Lets the user pick a distributor (or uses the only one) and registers with it. */
    fun useUnifiedPush(activity: Activity, onResult: (Boolean) -> Unit) {
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
            if (ok) UnifiedPush.register(activity)
            onResult(ok)
        }
    }

    /** Stops using UnifiedPush and falls back to FCM, if this build has it. */
    fun stopUnifiedPush(context: Context) {
        UnifiedPush.unregister(context)
        UnifiedPush.removeDistributor(context)
        context.graph.store.pushProvider = null
        PullWorker.schedule(context)
        Fcm.requestToken(context)
    }

    /** Forgets every registration. Used when the device is revoked or reset. */
    fun unregister(context: Context) {
        runCatching { UnifiedPush.unregister(context) }
        runCatching { UnifiedPush.removeDistributor(context) }
        Fcm.deleteToken()
        context.graph.store.pushProvider = null
    }

    /** A push arrived: fetch whatever is new right away. */
    fun onPush(context: Context) {
        if (context.graph.store.has(Role.RECEIVER)) PullWorker.runNow(context, expedited = true)
    }

    /** Tells the server where to push (§5.4). A failure is retried on the next app open. */
    fun register(context: Context, provider: String, token: String) {
        val g = context.graph
        val store = g.store
        if (!store.has(Role.RECEIVER) || (store.pushProvider == provider && store.pushToken == token)) return
        g.scope.launch {
            try {
                g.api().putPush(store.deviceId!!, PushRegistration(provider, token, context.packageName, "production"))
                store.pushProvider = provider
                store.pushToken = token
                PullWorker.schedule(context)
            } catch (e: Exception) {
                Log.w("Ferry", "push registration failed", e)
            }
        }
    }
}
