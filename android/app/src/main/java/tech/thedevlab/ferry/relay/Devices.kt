package tech.thedevlab.ferry.relay

import tech.thedevlab.ferry.crypto.FerryCrypto
import tech.thedevlab.ferry.data.Store
import tech.thedevlab.ferry.model.DeviceProfile
import tech.thedevlab.ferry.model.FerryJson
import tech.thedevlab.ferry.net.Api
import tech.thedevlab.ferry.net.ApiException
import tech.thedevlab.ferry.net.DeviceDto
import tech.thedevlab.ferry.net.Sealed

/**
 * The account's devices (§5.4): names and SIM labels for display and, on a receiver, which nodes
 * are trusted (§3.7). Profiles are sealed with the UMK, so a node only sees generic names.
 */
class Devices(private val store: Store) {
    data class Entry(val device: DeviceDto, val profile: DeviceProfile?, val name: String)

    suspend fun refresh(api: Api): List<Entry> {
        val list = api.devices()
        val entries = list.map { d ->
            val profile = profile(d)
            Entry(d, profile, profile?.name?.takeIf(String::isNotBlank) ?: defaultName(d))
        }
        store.deviceNames = entries.associate { it.device.id to it.name }
        store.remoteSimLabels = entries.flatMap { e -> e.profile?.sims.orEmpty().map { (slot, label) -> "${e.device.id}:$slot" to label } }.toMap()
        if (store.currentUmk != null) {
            // A trusted node that's gone from the list was removed: never trust its key again.
            val gone = store.trustedNodes.filterKeys { id -> list.none { it.id == id } }.values
            if (gone.isNotEmpty()) store.removedNodeKeys = store.removedNodeKeys + gone
            val removed = store.removedNodeKeys
            store.trustedNodes = entries
                .mapNotNull { e -> e.profile?.nodePk?.takeIf { "receiver" !in e.device.roles && it !in removed }?.let { e.device.id to it } }
                .toMap()
        }
        return entries
    }

    /**
     * §4.7: trusts a node that the trusted node [by] introduced. True when it's now trusted with
     * [nodePk], also if another receiver got there first. False when the introducer is no longer
     * trusted, the device isn't a node on the account, or its profile names another key.
     */
    suspend fun acceptIntroduction(api: Api, by: String, deviceId: String, nodePk: String): Boolean {
        val removed = store.removedNodeKeys
        val introducer = store.trustedNodes[by]
        if (introducer == null || introducer in removed || nodePk in removed) return false
        val device = api.devices().find { it.id == deviceId } ?: return false
        if ("receiver" in device.roles) return false
        var trusted = false
        updateProfile(api, deviceId) { current ->
            trusted = current?.nodePk == null || current.nodePk == nodePk
            if (current?.nodePk != null) return@updateProfile null
            (current ?: DeviceProfile("")).copy(nodePk = nodePk, addedBy = by)
        }
        return trusted
    }

    /** Stops trusting [deviceId]'s key on this phone, before the server is even asked to remove it. */
    fun forget(deviceId: String) {
        val pk = store.trustedNodes[deviceId] ?: return
        store.removedNodeKeys = store.removedNodeKeys + pk
        store.trustedNodes = store.trustedNodes - deviceId
    }

    /**
     * Rewrites [deviceId]'s profile with [update] applied to the current one (null: leave it). Keeps
     * the version rising, and retries once if another receiver wrote in between.
     */
    suspend fun updateProfile(api: Api, deviceId: String, update: (DeviceProfile?) -> DeviceProfile?) {
        val umk = store.currentUmk ?: return
        repeat(2) { attempt ->
            val device = api.devices().find { it.id == deviceId } ?: return
            val next = update(profile(device)) ?: return
            val version = (device.profile?.version ?: 0) + 1
            val plain = FerryJson.encodeToString(DeviceProfile.serializer(), next).toByteArray()
            val sealed = FerryCrypto.encryptProfile(umk, store.userId!!, deviceId, version, plain)
            try {
                api.putProfile(deviceId, Sealed(version, sealed.keyId, sealed.nonce, sealed.ct))
                return
            } catch (e: ApiException) {
                if (e.code != "profile_stale" || attempt == 1) throw e
            }
        }
    }

    private fun profile(d: DeviceDto): DeviceProfile? = d.profile?.let { p ->
        runCatching {
            val plain = FerryCrypto.decryptProfile(store.umks, store.userId!!, d.id, p.version ?: 0, FerryCrypto.Sealed(p.keyId, p.nonce, p.ct))
            FerryJson.decodeFromString(DeviceProfile.serializer(), String(plain))
        }.getOrNull()
    }

    companion object {
        fun defaultName(d: DeviceDto) = when {
            d.platform == "ios" -> "iPhone"
            "source" in d.roles -> "Android phone"
            else -> "Android reader"
        }
    }
}
