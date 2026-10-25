package tech.thedevlab.ferry.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tech.thedevlab.ferry.Notifications
import tech.thedevlab.ferry.capture.OutgoingSmsObserver
import tech.thedevlab.ferry.capture.Sims
import tech.thedevlab.ferry.capture.SmsHistory
import tech.thedevlab.ferry.data.Role
import tech.thedevlab.ferry.graph
import tech.thedevlab.ferry.push.Push
import tech.thedevlab.ferry.relay.Commands
import tech.thedevlab.ferry.relay.FlushWorker
import tech.thedevlab.ferry.relay.PullWorker

/**
 * Keeps a source phone's process alive against OEM battery killers and shows the persistent
 * "Ferry is relaying" notification the protocol requires (§9). Capture itself happens in the
 * manifest receivers and the notification listener.
 */
class RelayService : Service() {
    private var outgoing: OutgoingSmsObserver? = null

    /** The §6.6 command long poll, for a node; it lives exactly as long as relaying does. */
    private var commands: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        outgoing?.unregister()
        commands?.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!graph.store.has(Role.SOURCE)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        ServiceCompat.startForeground(this, Notifications.ID_RELAY, Notifications.relay(this, statusText()), type)
        // Registered once READ_SMS is granted; the app restarts the service when permissions change.
        if (outgoing == null && SmsHistory.canRead(this)) outgoing = OutgoingSmsObserver(this).also { it.register() }
        if (commands?.isActive != true && graph.store.nodeRoot != null) commands = graph.scope.launch { Commands(this@RelayService).run() }
        FlushWorker.enqueue(this)
        return START_STICKY
    }

    private fun statusText(): String {
        val store = graph.store
        val sims = Sims.active(this).map { store.simLabel(it.simSlotIndex) }
        val apps = store.allowedPackages.size
        return buildList {
            add(if (sims.isEmpty()) "SMS & calls" else "SMS & calls from ${sims.joinToString(", ")}")
            if (apps > 0) add("notifications from $apps app${if (apps == 1) "" else "s"}")
        }.joinToString(" · ")
    }

    companion object {
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, RelayService::class.java))
            } catch (e: IllegalStateException) {
                // Background start not allowed right now; boot / app open will start it.
                Log.w("Ferry", "could not start relay service", e)
            }
        }

        fun stop(context: Context) = context.stopService(Intent(context, RelayService::class.java))
    }
}

/** Starts whatever this device's roles need. Called after pairing, on app open and on boot. */
object Roles {
    fun apply(context: Context) {
        val store = context.graph.store
        if (store.has(Role.SOURCE)) {
            RelayService.start(context)
            HeartbeatWorker.schedule(context)
            HeartbeatWorker.reportIfChanged(context)
        } else {
            RelayService.stop(context)
        }
        if (store.has(Role.RECEIVER)) {
            PullWorker.schedule(context)
            PullWorker.runNow(context)
            Push.setUp(context)
        }
    }
}
