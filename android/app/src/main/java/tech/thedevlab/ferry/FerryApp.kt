package tech.thedevlab.ferry

import android.app.Application
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import tech.thedevlab.ferry.data.FerryDb
import tech.thedevlab.ferry.data.Store
import tech.thedevlab.ferry.data.openFerryDb
import tech.thedevlab.ferry.net.Api
import tech.thedevlab.ferry.push.Fcm
import tech.thedevlab.ferry.push.Push
import tech.thedevlab.ferry.relay.Receiver
import tech.thedevlab.ferry.relay.Relay
import tech.thedevlab.ferry.service.RelayService

class FerryApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        Notifications.createChannels(this)
        Fcm.init(this)
    }
}

/** Hand-wired dependencies, created once per process. */
class Graph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store = Store(context)
    val db: FerryDb = openFerryDb(context, store)
    val relay = Relay(context, store, db)
    val receiver = Receiver(db, relay)

    fun api(serverUrl: String = store.serverUrl ?: error("not set up")) = Api(serverUrl) { store.deviceToken }

    /**
     * The server says this device was revoked: delete everything it decrypted or captured, its keys
     * and its token (§9.1). Blocking; call it off the main thread.
     */
    fun wipeAfterRevocation(context: Context) {
        store.markRevoked()
        db.clearAllTables()
        Push.unregister(context)
        RelayService.stop(context)
        // Delivered notifications hold decrypted text too.
        NotificationManagerCompat.from(context).cancelAll()
    }
}

val Context.graph: Graph get() = (applicationContext as FerryApp).graph
