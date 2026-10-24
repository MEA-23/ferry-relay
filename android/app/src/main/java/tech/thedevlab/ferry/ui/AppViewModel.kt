package tech.thedevlab.ferry.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import androidx.work.WorkManager
import androidx.work.await
import tech.thedevlab.ferry.capture.SmsHistory
import tech.thedevlab.ferry.relay.HistoryImportWorker
import tech.thedevlab.ferry.BuildConfig
import tech.thedevlab.ferry.Notifications
import tech.thedevlab.ferry.data.MessageDao
import tech.thedevlab.ferry.capture.Sims
import tech.thedevlab.ferry.crypto.FerryCrypto
import tech.thedevlab.ferry.data.Role
import tech.thedevlab.ferry.graph
import tech.thedevlab.ferry.model.DeviceProfile
import tech.thedevlab.ferry.model.FerryJson
import tech.thedevlab.ferry.net.Api
import tech.thedevlab.ferry.net.ApiException
import tech.thedevlab.ferry.net.userMessage
import tech.thedevlab.ferry.net.CreateAccountRequest
import tech.thedevlab.ferry.net.DeviceDto
import tech.thedevlab.ferry.net.NewDevice
import tech.thedevlab.ferry.net.RegisterDeviceRequest
import tech.thedevlab.ferry.net.Sealed
import tech.thedevlab.ferry.push.Push
import tech.thedevlab.ferry.relay.PullWorker
import tech.thedevlab.ferry.service.RelayService
import tech.thedevlab.ferry.service.Roles
import java.io.IOException

data class DeviceItem(val id: String, val name: String, val roles: List<String>, val platform: String, val lastSeenAt: Long, val isSelf: Boolean)

data class PairingState(val link: String, val expiresAt: Long)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph
    val store = g.store

    val outboxCount = g.db.outbox().count().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val activity = g.db.activity().recent(200).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
    suspend fun refresh() {
        val app = getApplication<Application>()
        PullWorker.runNow(app).await()
        withTimeoutOrNull(15_000) {
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(PullWorker.NOW).first { infos -> infos.all { it.state.isFinished } }
        }
    }

    fun thread(threadKey: String) = g.db.messages().thread(threadKey)

    fun markRead(threadKey: String) {
        viewModelScope.launch { g.db.messages().markRead(threadKey) }
        Notifications.clearThread(getApplication(), threadKey)
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
            } finally {
                busy.value = false
            }
        }
    }

    private fun newDevice(roles: Set<Role>) = NewDevice(roles.map { it.wire }, appVersion = BuildConfig.VERSION_NAME)

    fun createAccount(server: String, invite: String, roles: Set<Role>) = run {
        val url = normalizeServer(server)
        val umk = FerryCrypto.generateUmk()
        val created = Api(url) { null }.createAccount(
            CreateAccountRequest(newDevice(roles), FerryCrypto.keyId(umk), invite.trim().ifEmpty { null }),
        )
        store.saveIdentity(url, created.userId, created.deviceId, created.token, umk, roles)
        afterSetup()
    }

    fun join(linkText: String, roles: Set<Role>) = run {
        val link = PairingLink.parse(linkText) ?: throw IllegalArgumentException("That isn't a Ferry pairing code.")
        val registered = Api(link.server) { null }.registerDevice(
            RegisterDeviceRequest(link.userId, link.code, roles.map { it.wire }, appVersion = BuildConfig.VERSION_NAME),
        )
        // The QR carries the key; the server vouches for which key the account uses (§5.3 step 3).
        require(registered.keyId == FerryCrypto.keyId(link.umk)) { "The pairing code's key doesn't match this account. Nothing was saved." }
        store.saveIdentity(link.server, link.userId, registered.deviceId, registered.token, link.umk, roles)
        afterSetup()
    }

    private suspend fun afterSetup() {
        Roles.apply(getApplication())
        if (store.has(Role.SOURCE)) uploadProfile()
        // Names and SIM labels of the other phones, so the inbox has them from the first message.
        if (store.has(Role.RECEIVER)) runCatching { loadDevices() }
    }

    fun requestPairing(roles: Set<Role>) = run {
        val code = g.api().requestPairing(roles.map { it.wire })
        val link = PairingLink(store.serverUrl!!, store.userId!!, code.code, store.currentUmk!!)
        pairing.value = PairingState(link.toUri(), code.expiresAt)
    }

    fun clearPairing() {
        pairing.value = null
    }

    fun refreshDevices() = run { loadDevices() }

    private suspend fun loadDevices() {
        val list = g.api().devices()
        val profiles = list.associate { it.id to profile(it) }
        val names = list.associate { it.id to (profiles[it.id]?.name?.takeIf(String::isNotBlank) ?: defaultName(it)) }
        store.deviceNames = names
        store.remoteSimLabels = profiles.flatMap { (id, p) -> p?.sims.orEmpty().map { (slot, label) -> "$id:$slot" to label } }.toMap()
        devices.value = list.map {
            DeviceItem(it.id, names.getValue(it.id), it.roles, it.platform, it.lastSeenAt, it.id == store.deviceId)
        }
    }

    private fun profile(d: DeviceDto): DeviceProfile? = d.profile?.let { p ->
        runCatching {
            val plain = FerryCrypto.decryptProfile(store.umks, store.userId!!, d.id, p.version ?: 0, FerryCrypto.Sealed(p.keyId, p.nonce, p.ct))
            FerryJson.decodeFromString(DeviceProfile.serializer(), String(plain))
        }.getOrNull()
    }

    private fun defaultName(d: DeviceDto) = when {
        d.platform == "ios" -> "iPhone"
        "source" in d.roles -> "Android phone"
        else -> "Android reader"
    }

    fun revoke(id: String) = run {
        g.api().revoke(id)
        refreshDevices()
    }

    fun saveNames(deviceName: String, simLabels: Map<Int, String>) = run {
        store.deviceName = deviceName.trim().ifEmpty { android.os.Build.MODEL }
        simLabels.forEach { (slot, label) -> store.setSimLabel(slot, label.trim().ifEmpty { "SIM ${slot + 1}" }) }
        uploadProfile()
        RelayService.start(getApplication())
    }

    /** Encrypted device profile (§5.4): this phone's name and SIM labels, for receivers to display. */
    private suspend fun uploadProfile() {
        val profile = DeviceProfile(
            store.deviceName,
            Sims.active(getApplication()).associate { it.simSlotIndex.toString() to store.simLabel(it.simSlotIndex) },
        )
        val version = maxOf(store.profileVersion + 1, System.currentTimeMillis())
        val plain = FerryJson.encodeToString(DeviceProfile.serializer(), profile).toByteArray()
        val sealed = FerryCrypto.encryptProfile(store.currentUmk!!, store.userId!!, store.deviceId!!, version, plain)
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
