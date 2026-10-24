package tech.thedevlab.ferry.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.launch
import tech.thedevlab.ferry.data.Role
import tech.thedevlab.ferry.graph
import tech.thedevlab.ferry.model.NotificationEvent

/** Relays notifications from apps the user allowlisted (§4.4). Off for every app by default. */
class NotificationCapture : NotificationListenerService() {

    /** Apps re-post the same notification on every update; remember what was already sent. */
    private val recent = object : LinkedHashMap<String, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 200
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val g = graph
        if (!g.store.has(Role.SOURCE)) return
        if (sbn.packageName == packageName || sbn.packageName !in g.store.allowedPackages) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || sbn.isOngoing) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        val fingerprint = "${sbn.key}|$title|$text"
        synchronized(recent) {
            if (recent.containsKey(fingerprint)) return
            recent[fingerprint] = Unit
        }

        val appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)

        g.scope.launch {
            g.relay.submit(
                NotificationEvent(
                    occurredAt = sbn.postTime,
                    packageName = sbn.packageName,
                    appName = appName,
                    title = title,
                    text = text,
                    category = n.category,
                    key = sbn.key,
                ),
            )
        }
    }
}
