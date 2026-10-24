package tech.thedevlab.ferry

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import tech.thedevlab.ferry.data.Message
import tech.thedevlab.ferry.ui.MainActivity

object Notifications {
    const val CHANNEL_RELAY = "relay"
    const val CHANNEL_INCOMING = "incoming"
    const val CHANNEL_ALERTS = "alerts"
    const val ID_RELAY = 1
    private const val ID_ALERT = 2
    const val ID_SYNC = 3

    /** Intent extra: the thread a tapped message notification belongs to. */
    const val EXTRA_THREAD = "thread"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_RELAY, "Relay status", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Ferry relays this phone's messages"
                },
                NotificationChannel(CHANNEL_INCOMING, "Relayed messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Messages received from your other phones"
                },
                NotificationChannel(CHANNEL_ALERTS, "Problems", NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    private fun openApp(context: Context, thread: String? = null): PendingIntent = PendingIntent.getActivity(
        context,
        thread?.hashCode() ?: 0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { thread?.let { putExtra(EXTRA_THREAD, it) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun relay(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_RELAY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Ferry is relaying")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    /** A received message. Tagged with its thread so opening the thread clears it; tapping opens the thread. */
    fun message(context: Context, m: Message, sourceName: String?) = post(
        context,
        m.eventId.hashCode(),
        NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(m.title)
            .setContentText(m.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(m.body))
            .setSubText(sourceName)
            .setWhen(m.occurredAt)
            .setShowWhen(true)
            .setGroup(m.threadKey)
            .setCategory(if (m.type == "call") NotificationCompat.CATEGORY_MISSED_CALL else NotificationCompat.CATEGORY_MESSAGE)
            // Relayed app notifications arrive quietly, as on iOS.
            .setSilent(m.type == "notification")
            .setAutoCancel(true)
            .setContentIntent(openApp(context, m.threadKey))
            .build(),
        tag = m.threadKey,
    )

    /** Removes a thread's delivered notifications once it has been read. */
    fun clearThread(context: Context, threadKey: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.activeNotifications.filter { it.tag == threadKey }.forEach { nm.cancel(it.tag, it.id) }
    }

    /** Shown briefly while an urgent pull runs on Android 11 and older. */
    fun syncing(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_RELAY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Checking for new messages")
            .setSilent(true)
            .build()

    fun alert(context: Context, title: String, body: String) = post(
        context,
        ID_ALERT,
        NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openApp(context))
            .build(),
    )

    private fun post(context: Context, id: Int, notification: Notification, tag: String? = null) {
        if (context.granted(Manifest.permission.POST_NOTIFICATIONS) || android.os.Build.VERSION.SDK_INT < 33) {
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(context).notify(tag, id, notification)
        }
    }
}
