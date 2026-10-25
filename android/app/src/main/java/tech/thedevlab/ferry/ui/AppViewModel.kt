package tech.thedevlab.ferry.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.work.WorkManager
import tech.thedevlab.ferry.capture.SmsHistory
import tech.thedevlab.ferry.relay.HistoryImportWorker
import tech.thedevlab.ferry.BuildConfig
import tech.thedevlab.ferry.Notifications
import tech.thedevlab.ferry.data.MessageDao
import tech.thedevlab.ferry.capture.Sims
import tech.thedevlab.ferry.crypto.FerryCrypto
import tech.thedevlab.ferry.data.Role
import tech.thedevlab.ferry.graph
import tech.thedevlab.ferry.model.CommandPayload
import tech.thedevlab.ferry.model.DeviceProfile
import tech.thedevlab.ferry.model.FerryJson
import tech.thedevlab.ferry.model.IntroduceEvent
import tech.thedevlab.ferry.model.ProfileEvent
import tech.thedevlab.ferry.net.Api
import tech.thedevlab.ferry.net.CommandEnvelope
import tech.thedevlab.ferry.net.ApiException
import tech.thedevlab.ferry.net.userMessage
import tech.thedevlab.ferry.net.CreateAccountRequest
import tech.thedevlab.ferry.net.NewDevice
import tech.thedevlab.ferry.net.PairingMessage
import tech.thedevlab.ferry.net.RegisterDeviceRequest
import tech.thedevlab.ferry.net.Sealed
import tech.thedevlab.ferry.push.Push
import tech.thedevlab.ferry.relay.PullWorker
import tech.thedevlab.ferry.relay.Receiver
import tech.thedevlab.ferry.data.Message
import tech.thedevlab.ferry.data.TextTools
import tech.thedevlab.ferry.uuidV7
import tech.thedevlab.ferry.service.RelayService
import tech.thedevlab.ferry.service.Roles
import java.io.IOException

data class DeviceItem(
    val id: String,
    val name: String,
    val roles: List<String>,
    val platform: String,
    val lastSeenAt: Long,
    val isSelf: Boolean,
    /** The device that paired or introduced this node (§5.4), by name. */
    val addedBy: String? = null,
    val addedById: String? = null,
    /** What a relaying phone said it can't relay, by the names people know: "texts", "calls", "notifications". */
    val missing: List<String> = emptyList(),
)

/** A phone and SIM a new text can be sent from. */
data class SendOption(val nodeId: String, val simSlot: Int?, val subscriptionId: Int?, val label: String) {
    val id get() = "$nodeId:${simSlot ?: -1}"
}

/** A pairing code on screen; [paired] once the device that scanned it has answered (§5.6). */
data class PairingState(val link: String, val expiresAt: Long, val paired: Boolean = false)

/** How long a node may wait before sending a reply; it refuses anything older (§6.6). */
private const val COMMAND_TTL_MS = 5 * 60_000L

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph
    val store = g.store

    val outboxCount = g.db.outbox().count().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val activity = g.db.activity().recent(200).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Texts this node sent, or refused, for receivers (§6.6). */
    val recentlySent = g.db.sent().recent(5).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── inbox (receiver) ─────────────────────────────────────────────────────

    val threads = g.db.messages().threads().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val unread = g.db.messages().unreadCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    private val query = MutableStateFlow("")

    /** Messages whose text matches the inbox search, newest first. */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val searchResults = query.debounce(150).flatMapLatest { q ->
        if (q.isBlank()) flowOf(emptyList()) else g.db.messages().search(MessageDao.likePattern(q), 300)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun search(q: String) {
        query.value = q
    }

    /** Pulls now and returns once that pull has finished, or after 15 seconds (e.g. while offline). */
    suspend fun refresh() = PullWorker.runAndWait(getApplication())

    fun thread(threadKey: String) = g.db.messages().thread(threadKey)

    fun markRead(threadKey: String) {
        viewModelScope.launch { g.db.messages().markRead(threadKey) }
        Notifications.clearThread(getApplication(), threadKey)
    }

    /** Whether replies to [m]'s conversation can go out through the node it came from (§6.6). */
    fun canReply(m: Message?): Boolean =
        m != null && m.type == "sms" && m.number != null && store.currentUmk != null && m.sourceDeviceId in store.trustedNodes

    /** Where a new text can go out from: each phone that sends for this one, once per active SIM. */
    fun sendOptions(): List<SendOption> {
        if (store.currentUmk == null) return emptyList()
        val statuses = store.remoteStatuses
        val names = store.deviceNames
        return store.trustedNodes.keys.flatMap { id ->
            val name = names[id] ?: "Android phone"
            val sims = statuses[id]?.sims.orEmpty().filter { it.active }
            if (sims.isEmpty()) {
                listOf(SendOption(id, null, null, name))
            } else {
                sims.map { SendOption(id, it.slot, it.subscriptionId, "${store.remoteSimLabel(id, it.slot)} · $name") }
            }
        }
    }

    /**
     * §6.6: asks the node a conversation came from to text [body] to [number], on the SIM of the
     * conversation's last text. It shows here right away, and its status follows the node's reports.
     */
    fun sendSms(threadKey: String, sourceDeviceId: String, number: String, body: String) {
        viewModelScope.launch {
            val last = g.db.messages().lastText(threadKey)
            send(threadKey, sourceDeviceId, number, body, last?.title ?: number, last?.simSlot, last?.subscriptionId)
        }
    }

    /** §6.6: a new text to [number], added to its conversation through [from]. Returns the conversation. */
    fun sendNew(number: String, body: String, from: SendOption): String {
        val threadKey = "p:${from.nodeId}:${Receiver.normalizeNumber(number)}"
        store.lastSendOption = from.id
        viewModelScope.launch {
            // Keeps the contact name if this number already has a conversation.
            val title = g.db.messages().lastText(threadKey)?.title ?: number
            send(threadKey, from.nodeId, number, body, title, from.simSlot, from.subscriptionId)
        }
        return threadKey
    }

    private suspend fun send(threadKey: String, nodeId: String, number: String, body: String, title: String, simSlot: Int?, subscriptionId: Int?) {
        val now = System.currentTimeMillis()
        val id = uuidV7(now)
        val row = Receiver.commandRow(id)
        g.db.messages().saveSent(
            Message(
                row, id, nodeId, "sms", threadKey, title, body, number, simSlot, now, now,
                outgoing = true, unread = false, searchText = TextTools.normalize(body), status = "sending", subscriptionId = subscriptionId,
            ),
        )
        try {
            val nodePk = store.trustedNodes[nodeId] ?: throw IOException("not a trusted node")
            val root = FerryCrypto.nodeRootForReceiver(store.currentUmk!!, FerryCrypto.fromB64u(nodePk))
            val payload = CommandPayload("send_sms", number, body, subscriptionId, now, now + COMMAND_TTL_MS)
            val plain = FerryJson.encodeToString(CommandPayload.serializer(), payload).toByteArray()
            val sealed = FerryCrypto.encryptCommand(root, store.userId!!, nodeId, id, plain)
            g.api().sendCommand(nodeId, CommandEnvelope(id, sealed.keyId, sealed.nonce, sealed.ct))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            g.db.messages().updateStatus(row, "failed:not_queued")
            message.value = (e as? IOException)?.userMessage() ?: "Couldn't send that text."
        }
    }

    fun deleteThread(threadKey: String) {
        viewModelScope.launch { g.db.messages().deleteThread(threadKey) }
        Notifications.clearThread(getApplication(), threadKey)
    }

    /** "Work SIM · Pixel 8" for a received message, from the source's encrypted profile. */
    fun origin(sourceDeviceId: String, simSlot: Int?): String {
        val device = store.deviceNames[sourceDeviceId] ?: "Android phone"
        return simSlot?.let { "${store.remoteSimLabel(sourceDeviceId, it)} · $device" } ?: device
    }

    init {
        // Names and SIM labels of the phones messages come from; quietly, as the inbox works without them.
        if (store.has(Role.RECEIVER)) viewModelScope.launch { runCatching { loadDevices() } }
    }

    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val devices = MutableStateFlow<List<DeviceItem>>(emptyList())
    val pairing = MutableStateFlow<PairingState?>(null)

    /** A device to ask about removing, from an introduction notification. */
    val removeRequest = MutableStateFlow<String?>(null)

    val historyImport = WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(HistoryImportWorker.NAME)
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun historyCount(since: Long): Int = withContext(Dispatchers.IO) { SmsHistory.count(getApplication(), since) }

    fun startHistoryImport(since: Long) = HistoryImportWorker.start(getApplication(), since)

    fun cancelHistoryImport() = HistoryImportWorker.cancel(getApplication())

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                message.value = e.userMessage()
            } catch (e: IllegalArgumentException) {
                message.value = e.message
            } catch (_: FerryCrypto.DecryptionException) {
                message.value = "That pairing code isn't valid. Show a new one and scan it again."
            } finally {
                busy.value = false
            }
        }
    }

    private fun newDevice(roles: Set<Role>) = NewDevice(roles.map { it.wire }, appVersion = BuildConfig.VERSION_NAME)

    fun createAccount(server: String, invite: String, roles: Set<Role>) = run {
        val url = normalizeServer(server)
        val inviteCode = invite.trim().ifEmpty { null }
        if (roles == setOf(Role.SOURCE)) {
            // A node never holds the UMK. The account gets its key from the first phone that reads
            // messages, when it scans this node's code (§5.6 flow A).
            val keys = FerryCrypto.generateNodeKeyPair()
            val created = Api(url) { null }.createAccount(CreateAccountRequest(newDevice(roles), invite = inviteCode))
            store.saveNodeIdentity(url, created.userId, created.deviceId, created.token, keys.secretKey)
            showNodeCode()
            return@run
        }
        val umk = FerryCrypto.generateUmk()
        val created = Api(url) { null }.createAccount(CreateAccountRequest(newDevice(roles), FerryCrypto.keyId(umk), inviteCode))
        store.saveIdentity(url, created.userId, created.deviceId, created.token, umk, roles)
        afterSetup()
    }

    fun join(linkText: String, roles: Set<Role>) = run {
        when (val link = parsePairingCode(linkText)) {
            is PairingLink -> {
                require(Role.RECEIVER in roles) {
                    "This code is for a phone that reads messages. To relay this phone's SIMs, choose “Relay its own SIMs” on your other phone."
                }
                val registered = Api(link.server) { null }.registerDevice(
                    RegisterDeviceRequest(link.userId, link.code, roles.map { it.wire }, appVersion = BuildConfig.VERSION_NAME),
                )
                // The QR carries the key; the server vouches for which key the account uses (§5.3 step 3).
                require(registered.keyId == FerryCrypto.keyId(link.umk)) { "The pairing code's key doesn't match this account. Nothing was saved." }
                store.saveIdentity(link.server, link.userId, registered.deviceId, registered.token, link.umk, roles)
                afterSetup()
            }
            is NodeLink -> {
                require(roles == setOf(Role.RECEIVER)) { "That's the code of a phone that relays its SIMs. Scan it with the phone you read messages on." }
                becomeFirstReceiver(link)
            }
            is IntroLink -> {
                require(roles == setOf(Role.SOURCE)) { "That code adds a phone that relays its SIMs. On this phone, choose “It has the SIM cards”." }
                joinAsNode(link)
            }
            else -> throw IllegalArgumentException("That isn't a Ferry pairing code.")
        }
    }

    /** §5.6 flow A, on the receiver: make the account's key, trust the node, and send it the account public key. */
    private suspend fun becomeFirstReceiver(link: NodeLink) {
        val registered = Api(link.server) { null }.registerDevice(
            RegisterDeviceRequest(link.userId, link.code, listOf(Role.RECEIVER.wire), appVersion = BuildConfig.VERSION_NAME),
        )
        val umk = FerryCrypto.generateUmk()
        // Saved before the server records its key ID, so the account never has a key no phone holds.
        store.saveIdentity(link.server, link.userId, registered.deviceId, registered.token, umk, setOf(Role.RECEIVER))
        val api = g.api()
        try {
            api.setInitialKey(FerryCrypto.keyId(umk))
        } catch (e: IOException) {
            runCatching { api.revoke(registered.deviceId) }
            store.reset()
            throw e
        }
        trustNode(api, link.deviceId, link.nodePk)
        val acctPk = FerryCrypto.accountKeyPair(umk).publicKey
        val mac = FerryCrypto.pairingMac(link.secret, link.userId, link.deviceId, acctPk)
        api.sendPairingReply(link.code, PairingMessage(link.deviceId, FerryCrypto.toB64u(acctPk), FerryCrypto.toB64u(mac)))
        afterSetup()
    }

    /** §5.6 flow B, on the new node: join, derive the root from the code's account public key, and answer with ours. */
    private suspend fun joinAsNode(link: IntroLink) {
        val keys = FerryCrypto.generateNodeKeyPair()
        val root = FerryCrypto.nodeRootForNode(keys.secretKey, link.acctPk)
        val registered = Api(link.server) { null }.registerDevice(
            RegisterDeviceRequest(link.userId, link.code, listOf(Role.SOURCE.wire), appVersion = BuildConfig.VERSION_NAME),
        )
        store.saveNodeIdentity(link.server, link.userId, registered.deviceId, registered.token, keys.secretKey)
        store.saveNodeRoot(root, link.acctPk)
        val api = g.api()
        api.putNodeKey(registered.deviceId, FerryCrypto.nodeKeyId(root))
        val mac = FerryCrypto.pairingMac(link.secret, link.userId, registered.deviceId, keys.publicKey)
        api.sendPairingReply(link.code, PairingMessage(registered.deviceId, FerryCrypto.toB64u(keys.publicKey), FerryCrypto.toB64u(mac)))
        afterSetup()
    }

    /** Writes [nodePk] into the node's sealed profile, which is what makes receivers trust it (§3.7). */
    private suspend fun trustNode(api: Api, deviceId: String, nodePk: ByteArray) {
        g.devices.updateProfile(api, deviceId) { current ->
            (current ?: DeviceProfile("")).copy(nodePk = FerryCrypto.toB64u(nodePk), addedBy = store.deviceId)
        }
    }

    /**
     * §5.6 flow A, on the node: shows its code until a receiver answers with the account public key,
     * then registers its own key ID and sends what it captured meanwhile.
     */
    fun showNodeCode() = run {
        val api = g.api()
        val code = api.requestPairing(listOf(Role.RECEIVER.wire))
        val secret = FerryCrypto.randomBytes(FerryCrypto.KEY_BYTES)
        val userId = store.userId!!
        val deviceId = store.deviceId!!
        val nodeSk = store.nodeSecretKey!!
        val link = NodeLink(store.serverUrl!!, userId, deviceId, code.code, FerryCrypto.nodeKeyPair(nodeSk).publicKey, secret)
        pairing.value = PairingState(link.toUri(), code.expiresAt)
        watchMailbox(code.code, code.expiresAt) { msg ->
            val acctPk = FerryCrypto.fromB64u(msg.publicKey)
            // Only the phone that scanned this code knows the secret, so the server can't swap the key.
            require(msg.deviceId == deviceId && FerryCrypto.verifyPairingMac(secret, userId, deviceId, acctPk, FerryCrypto.fromB64u(msg.mac))) {
                "The answer to this code didn't check out. Show a new code and scan it again."
            }
            val root = FerryCrypto.nodeRootForNode(nodeSk, acctPk)
            api.putNodeKey(deviceId, FerryCrypto.nodeKeyId(root))
            store.saveNodeRoot(root, acctPk)
            g.relay.drainPending()
            afterSetup()
        }
    }

    private var watch: Job? = null

    /** Polls a code's mailbox (§5.6) until the other side answers, or a minute after the code expires. */
    private fun watchMailbox(code: String, expiresAt: Long, onMessage: suspend (PairingMessage) -> Unit) {
        watch?.cancel()
        watch = viewModelScope.launch {
            while (System.currentTimeMillis() < expiresAt + 60_000) {
                delay(2_000)
                val msg = try {
                    g.api().pairingReply(code)
                } catch (_: IOException) {
                    null
                } ?: continue
                try {
                    onMessage(msg)
                    pairing.update { it?.copy(paired = true) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    message.value = e.userMessage()
                } catch (e: IllegalArgumentException) {
                    message.value = e.message
                } catch (_: FerryCrypto.DecryptionException) {
                    message.value = "That phone's key isn't valid. Show a new code and scan it again."
                }
                return@launch
            }
        }
    }

    private suspend fun afterSetup() {
        Roles.apply(getApplication())
        if (store.has(Role.SOURCE)) publishProfile()
        // Names and SIM labels of the other phones, so the inbox has them from the first message.
        if (store.has(Role.RECEIVER)) runCatching { loadDevices() }
    }

    fun requestPairing(roles: Set<Role>) = run {
        if (Role.SOURCE in roles) return@run showIntroCode()
        val code = g.api().requestPairing(roles.map { it.wire })
        val link = PairingLink(store.serverUrl!!, store.userId!!, code.code, store.currentUmk!!)
        pairing.value = PairingState(link.toUri(), code.expiresAt)
    }

    /**
     * A code that adds the phone that scans it as a node (§5.6). Once its answer checks out, a
     * receiver trusts it right away (flow B); a node tells the receivers with an introduction (flow C).
     */
    private suspend fun showIntroCode() {
        val api = g.api()
        val umk = store.currentUmk
        val acctPk = umk?.let { FerryCrypto.accountKeyPair(it).publicKey } ?: store.accountPublicKey!!
        val code = api.requestPairing(listOf(Role.SOURCE.wire))
        val secret = FerryCrypto.randomBytes(FerryCrypto.KEY_BYTES)
        val userId = store.userId!!
        pairing.value = PairingState(IntroLink(store.serverUrl!!, userId, code.code, acctPk, secret).toUri(), code.expiresAt)
        watchMailbox(code.code, code.expiresAt) { msg ->
            val nodePk = FerryCrypto.fromB64u(msg.publicKey)
            require(FerryCrypto.verifyPairingMac(secret, userId, msg.deviceId, nodePk, FerryCrypto.fromB64u(msg.mac))) {
                "The new phone's answer didn't check out. Show a new code and scan it again."
            }
            if (umk == null) {
                g.relay.submit(IntroduceEvent(System.currentTimeMillis(), msg.deviceId, msg.publicKey))
                return@watchMailbox
            }
            trustNode(api, msg.deviceId, nodePk)
            loadDevices()
            PullWorker.runNow(getApplication())
        }
    }

    fun clearPairing() {
        watch?.cancel()
        pairing.value = null
    }

    fun refreshDevices() = run { loadDevices() }

    private suspend fun loadDevices() {
        val entries = g.devices.refresh(g.api())
        val names = entries.associate { it.device.id to it.name }
        val statuses = store.remoteStatuses
        // Relaying phones report permissions by internal key; name them the way people know them.
        val labels = mapOf("sms" to "texts", "phone" to "calls", "notifications" to "notifications")
        devices.value = entries.map {
            val d = it.device
            val by = it.profile?.addedBy
            val missing = statuses[d.id]?.permissions.orEmpty().filterValues { ok -> !ok }.keys.map { k -> labels[k] ?: k }.sorted()
            DeviceItem(d.id, it.name, d.roles, d.platform, d.lastSeenAt, d.id == store.deviceId, by?.let(names::get), by, missing)
        }
    }

    /** Removes [ids] in order; for a node, also those it added if the person chose so. */
    fun revoke(vararg ids: String) = run {
        for (id in ids) {
            // Stop trusting a node's key here first, whatever the server does next (§3.7).
            g.devices.forget(id)
            g.api().revoke(id)
        }
        loadDevices()
    }

    fun saveNames(deviceName: String, simLabels: Map<Int, String>) = run {
        store.deviceName = deviceName.trim().ifEmpty { android.os.Build.MODEL }
        simLabels.forEach { (slot, label) -> store.setSimLabel(slot, label.trim().ifEmpty { "SIM ${slot + 1}" }) }
        publishProfile()
        RelayService.start(getApplication())
    }

    /**
     * This phone's name and SIM labels, for receivers to display (§5.4). A phone with the UMK seals
     * its own profile; a node can't, so it sends them as an event for receivers to write (§4.6).
     */
    private suspend fun publishProfile() {
        val sims = Sims.active(getApplication()).associate { it.simSlotIndex.toString() to store.simLabel(it.simSlotIndex) }
        val umk = store.currentUmk
        if (umk == null) {
            g.relay.submit(ProfileEvent(System.currentTimeMillis(), store.deviceName, sims))
            return
        }
        val version = maxOf(store.profileVersion + 1, System.currentTimeMillis())
        val plain = FerryJson.encodeToString(DeviceProfile.serializer(), DeviceProfile(store.deviceName, sims)).toByteArray()
        val sealed = FerryCrypto.encryptProfile(umk, store.userId!!, store.deviceId!!, version, plain)
        g.api().putProfile(store.deviceId!!, Sealed(version, sealed.keyId, sealed.nonce, sealed.ct))
        store.profileVersion = version
    }

    fun reset() = run { wipe() }

    /** Revokes this phone on the server, then wipes it. Offline, it still wipes; another device can remove it later. */
    fun leave() = run {
        try {
            g.api().revoke(store.deviceId!!)
        } catch (_: IOException) {
        } catch (_: ApiException) {
        }
        wipe()
    }

    private suspend fun wipe() {
        clearPairing()
        RelayService.stop(getApplication())
        Push.unregister(getApplication())
        withContext(Dispatchers.IO) { g.db.clearAllTables() }
        store.reset()
    }

    private fun normalizeServer(input: String): String {
        var s = input.trim().trimEnd('/')
        if ("://" !in s) s = (if (s.startsWith("localhost") || s.startsWith("10.0.2.2")) "http://" else "https://") + s
        require(s.startsWith("https://") || s.startsWith("http://localhost") || s.startsWith("http://10.0.2.2")) {
            "The server address must start with https://"
        }
        return s
    }
}
