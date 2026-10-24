package tech.thedevlab.ferry.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import tech.thedevlab.ferry.BuildConfig
import tech.thedevlab.ferry.graph
import tech.thedevlab.ferry.relay.PullWorker

/** Receives UnifiedPush events from the user's distributor app. */
class UnifiedPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) = Push.register(this, Push.UNIFIED_PUSH, endpoint.url)

    // The server posts the §7.2 `sr` object as plain JSON (it's already end-to-end encrypted), so
    // the connector can't Web-Push-decrypt it and passes it through. Either way it's just a wake-up.
    override fun onMessage(message: PushMessage, instance: String) = Push.onPush(this)

    override fun onRegistrationFailed(reason: FailedReason, instance: String) = fallBack()

    override fun onUnregistered(instance: String) = fallBack()

    /** The distributor is gone or refused: use FCM instead, if this build has it. */
    private fun fallBack() {
        UnifiedPush.removeDistributor(this)
        if (Fcm.configured) {
            Fcm.requestToken(this)
        } else {
            graph.store.pushProvider = null
            PullWorker.schedule(this)
        }
    }
}

/** Receives Firebase Cloud Messaging events. Only active when the build has a Firebase project. */
class FcmService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        // UnifiedPush, when set up, takes precedence.
        if (UnifiedPush.getAckDistributor(this) == null) Push.register(this, Push.FCM, token)
    }

    override fun onMessageReceived(message: RemoteMessage) = Push.onPush(this)
}

/**
 * Firebase, initialised from build-time values (see app/build.gradle.kts) instead of the
 * google-services plugin, so builds without a Firebase project work too.
 */
object Fcm {
    val configured: Boolean get() = BuildConfig.FCM_APP_ID.isNotEmpty()

    fun init(context: Context) {
        if (!configured || FirebaseApp.getApps(context).isNotEmpty()) return
        FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FCM_APP_ID)
                .setApiKey(BuildConfig.FCM_API_KEY)
                .setProjectId(BuildConfig.FCM_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
                .build(),
        )
    }

    fun requestToken(context: Context) {
        if (!configured) return
        val app = context.applicationContext
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            if (UnifiedPush.getAckDistributor(app) == null) Push.register(app, Push.FCM, token)
        }
    }

    fun deleteToken() {
        if (configured) FirebaseMessaging.getInstance().deleteToken()
    }
}
