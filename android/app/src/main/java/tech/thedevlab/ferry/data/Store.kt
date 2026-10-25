package tech.thedevlab.ferry.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import tech.thedevlab.ferry.crypto.FerryCrypto
import tech.thedevlab.ferry.model.FerryJson
import tech.thedevlab.ferry.model.Sim
import tech.thedevlab.ferry.model.StatusEvent
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class Role(val wire: String) { SOURCE("source"), RECEIVER("receiver") }

/** An incoming call between RINGING and the final call-log entry (§4.3). */
@Serializable
data class CurrentCall(val callKey: String, val number: String?, val sim: Sim?, val startedAt: Long, val answered: Boolean = false)

/**
 * App settings. Secrets (the UMKs or a node's own key, the device token and the database key) are
 * encrypted with an Android Keystore key that never leaves the device; the rest is plain SharedPreferences.
 */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("ferry", Context.MODE_PRIVATE)
    private val dbPrefs = context.getSharedPreferences("ferry-db", Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0L)

    /** Bumped on every write so Compose can re-read. */
    val changes: StateFlow<Long> = _changes

    private fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit(commit = true, action = block)
        _changes.value++
    }

    // ── identity ─────────────────────────────────────────────────────────────

    val serverUrl: String? get() = prefs.getString("serverUrl", null)
    val userId: String? get() = prefs.getString("userId", null)
    val deviceId: String? get() = prefs.getString("deviceId", null)
    val deviceToken: String? get() = secret("deviceToken")
    val roles: Set<Role>
        get() = prefs.getStringSet("roles", emptySet())!!.mapNotNull { r -> Role.entries.find { it.wire == r } }.toSet()
    val revoked: Boolean get() = prefs.getBoolean("revoked", false)

    val isPaired: Boolean get() = deviceToken != null && (currentUmk != null || nodeSecretKey != null) && !revoked
    fun has(role: Role) = isPaired && role in roles

    // ── node keys (§3.7) ─────────────────────────────────────────────────────

    /** A source-only phone: it has its own keypair and never the UMK. */
    val isNode: Boolean get() = prefs.contains("nodeSecretKey")

    val nodeSecretKey: ByteArray? get() = secret("nodeSecretKey")?.let(FerryCrypto::fromB64u)

    /** The key this node shares with the receivers; null until its first receiver answers (§5.6 flow A). */
    val nodeRoot: ByteArray? get() = secret("nodeRoot")?.let(FerryCrypto::fromB64u)

    /** A node that's waiting for a receiver to scan its code before it can encrypt anything. */
    val awaitingKey: Boolean get() = isPaired && isNode && !prefs.contains("nodeRoot")

    fun saveNodeIdentity(serverUrl: String, userId: String, deviceId: String, token: String, secretKey: ByteArray) = edit {
        putString("serverUrl", serverUrl.trimEnd('/'))
        putString("userId", userId)
        putString("deviceId", deviceId)
        putStringSet("roles", setOf(Role.SOURCE.wire))
        putString("deviceToken", seal(token))
        putString("nodeSecretKey", seal(FerryCrypto.toB64u(secretKey)))
        putBoolean("revoked", false)
        putLong("liveSince", System.currentTimeMillis())
    }

    /** The account public key, which a node puts in the codes it shows to introduce other nodes. */
    val accountPublicKey: ByteArray? get() = prefs.getString("accountPublicKey", null)?.let(FerryCrypto::fromB64u)

    fun saveNodeRoot(root: ByteArray, acctPk: ByteArray) = edit {
        putString("nodeRoot", seal(FerryCrypto.toB64u(root)))
        putString("accountPublicKey", FerryCrypto.toB64u(acctPk))
    }

    /** keyId → UMK. Receivers keep old keys so events from before a rotation stay readable (§5.5). */
    val umks: Map<String, ByteArray>
        get() = secret("umks")?.let { json ->
            FerryJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), json)
                .mapValues { FerryCrypto.fromB64u(it.value) }
        } ?: emptyMap()

    val currentUmk: ByteArray? get() = prefs.getString("currentKeyId", null)?.let { umks[it] }

    fun saveIdentity(serverUrl: String, userId: String, deviceId: String, token: String, umk: ByteArray, roles: Set<Role>) {
        val keyId = FerryCrypto.keyId(umk)
        val umkJson = FerryJson.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            mapOf(keyId to FerryCrypto.toB64u(umk)),
        )
        edit {
            putString("serverUrl", serverUrl.trimEnd('/'))
            putString("userId", userId)
            putString("deviceId", deviceId)
            putString("currentKeyId", keyId)
            putStringSet("roles", roles.map { it.wire }.toSet())
            putString("deviceToken", seal(token))
            putString("umks", seal(umkJson))
            putBoolean("revoked", false)
            putLong("liveSince", System.currentTimeMillis())
        }
    }

    /**
     * The server says this device was removed. Forgets the keys and token, which must not outlive
     * that (§9.1), but remembers why, so the app can say so instead of starting over silently.
     */
    fun markRevoked() = edit {
        remove("deviceToken")
        remove("umks")
        remove("currentKeyId")
        remove("nodeSecretKey")
        remove("nodeRoot")
        remove("pullCursor")
        putBoolean("revoked", true)
    }

    fun reset() = edit { clear() }

    // ── source settings ──────────────────────────────────────────────────────

    var allowedPackages: Set<String>
        get() = prefs.getStringSet("allowedPackages", emptySet())!!
        set(value) = edit { putStringSet("allowedPackages", value) }

    var deviceName: String
        get() = prefs.getString("deviceName", null) ?: android.os.Build.MODEL
        set(value) = edit { putString("deviceName", value) }

    fun simLabel(slot: Int): String = prefs.getString("simLabel.$slot", null) ?: "SIM ${slot + 1}"
    fun setSimLabel(slot: Int, label: String) = edit { putString("simLabel.$slot", label) }

    var profileVersion: Long
        get() = prefs.getLong("profileVersion", 0)
        set(value) = edit { putLong("profileVersion", value) }

    /** Lets receivers text numbers this phone has never exchanged texts with (§6.6). Off by default. */
    var allowNewNumbers: Boolean
        get() = prefs.getBoolean("allowNewNumbers", false)
        set(value) = edit { putBoolean("allowNewNumbers", value) }

    /** Texts that look like marketing get a "Mute?" suggestion in the inbox (see [Filters.SUGGEST]). Off by default. */
    var suggestMuting: Boolean
        get() = prefs.getBoolean("suggestMuting", false)
        set(value) = edit { putBoolean("suggestMuting", value) }

    /** Texts that score as marketing arrive muted, without asking (see [Filters.AUTO]). Off by default. */
    var autoMuteMarketing: Boolean
        get() = prefs.getBoolean("autoMuteMarketing", false)
        set(value) = edit { putBoolean("autoMuteMarketing", value) }

    /** The phone and SIM the last new message went out from, as [tech.thedevlab.ferry.ui.SendOption.id]. */
    var lastSendOption: String?
        get() = prefs.getString("lastSendOption", null)
        set(value) = edit { putString("lastSendOption", value) }

    var currentCall: CurrentCall?
        get() = prefs.getString("currentCall", null)?.let { FerryJson.decodeFromString(CurrentCall.serializer(), it) }
        set(value) = edit {
            if (value == null) remove("currentCall") else putString("currentCall", FerryJson.encodeToString(CurrentCall.serializer(), value))
        }

    // ── SMS history (§4.2) ───────────────────────────────────────────────────

    /** When live capture started; incoming SMS from before this are history. */
    var liveSince: Long
        get() = prefs.getLong("liveSince", 0).takeIf { it > 0 } ?: System.currentTimeMillis().also { liveSince = it }
        set(value) = edit { putLong("liveSince", value) }

    /** Highest sent-SMS row id seen by the outgoing observer; older sent SMS are history. */
    var outgoingSeenId: Long
        get() = prefs.getLong("outgoingSeenId", -1)
        set(value) = edit { putLong("outgoingSeenId", value) }

    /** Oldest date already imported (0 = nothing imported). Everything from here to [liveSince] is done. */
    var historyImportedSince: Long
        get() = prefs.getLong("historyImportedSince", 0)
        set(value) = edit { putLong("historyImportedSince", value) }

    var historyImportedCount: Int
        get() = prefs.getInt("historyImportedCount", 0)
        set(value) = edit { putInt("historyImportedCount", value) }

    // ── sync status ──────────────────────────────────────────────────────────

    var lastUploadAt: Long
        get() = prefs.getLong("lastUploadAt", 0)
        set(value) = edit { putLong("lastUploadAt", value) }

    var lastError: String?
        get() = prefs.getString("lastError", null)
        set(value) = edit { putString("lastError", value) }

    /** The push service the server was last told to use: "unifiedpush", "fcm", or null for polling only. */
    var pushProvider: String?
        get() = prefs.getString("pushProvider", null)
        set(value) = edit { putString("pushProvider", value) }

    /** The token or endpoint last registered, so unchanged ones aren't re-sent on every app open. */
    var pushToken: String?
        get() = prefs.getString("pushToken", null)
        set(value) = edit { putString("pushToken", value) }

    var pullCursor: String?
        get() = prefs.getString("pullCursor", null)
        set(value) = edit { putString("pullCursor", value) }

    /** deviceId → decrypted device name, for showing where received events came from. */
    var deviceNames: Map<String, String>
        get() = prefs.getString("deviceNames", null)
            ?.let { FerryJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it) } ?: emptyMap()
        set(value) = edit { putString("deviceNames", FerryJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), value)) }

    /** "deviceId:slot" → SIM label, from the other phones' encrypted profiles (§5.4). */
    var remoteSimLabels: Map<String, String>
        get() = prefs.getString("remoteSimLabels", null)
            ?.let { FerryJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it) } ?: emptyMap()
        set(value) = edit { putString("remoteSimLabels", FerryJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), value)) }

    /** deviceId → b64u public key of each trusted node, from the profiles receivers sealed (§3.7). */
    var trustedNodes: Map<String, String>
        get() = prefs.getString("trustedNodes", null)
            ?.let { FerryJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it) } ?: emptyMap()
        set(value) = edit { putString("trustedNodes", FerryJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), value)) }

    /** Public keys of nodes that were removed. Never trusted again, whatever the server says (§3.7). */
    var removedNodeKeys: Set<String>
        get() = prefs.getString("removedNodeKeys", null)?.let { FerryJson.decodeFromString(SetSerializer(String.serializer()), it) } ?: emptySet()
        set(value) = edit { putString("removedNodeKeys", FerryJson.encodeToString(SetSerializer(String.serializer()), value)) }

    /** deviceId → the newest status each relaying phone sent (§4.5), for its battery and missing permissions. */
    var remoteStatuses: Map<String, StatusEvent>
        get() = prefs.getString("remoteStatuses", null)
            ?.let { FerryJson.decodeFromString(MapSerializer(String.serializer(), StatusEvent.serializer()), it) } ?: emptyMap()
        set(value) = edit { putString("remoteStatuses", FerryJson.encodeToString(MapSerializer(String.serializer(), StatusEvent.serializer()), value)) }

    /** The permissions this phone last reported, so a change can be reported straight away. */
    var reportedPermissions: Map<String, Boolean>?
        get() = prefs.getString("reportedPermissions", null)
            ?.let { FerryJson.decodeFromString(MapSerializer(String.serializer(), Boolean.serializer()), it) }
        set(value) = edit { putString("reportedPermissions", value?.let { FerryJson.encodeToString(MapSerializer(String.serializer(), Boolean.serializer()), it) }) }

    fun remoteSimLabel(deviceId: String, slot: Int): String = remoteSimLabels["$deviceId:$slot"]?.takeIf(String::isNotBlank) ?: "SIM ${slot + 1}"

    // ── database key (§9.1) ──────────────────────────────────────────────────

    /**
     * The SQLCipher key for the local database, or null if there is none yet or it can no longer be
     * unsealed. It lives in its own file so [reset] leaves it in step with the database file.
     */
    val databaseKey: ByteArray?
        get() = runCatching { dbPrefs.getString("key", null)?.let(::open) }.getOrNull()?.let(FerryCrypto::fromB64u)

    fun newDatabaseKey(): ByteArray {
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        dbPrefs.edit(commit = true) { putString("key", seal(FerryCrypto.toB64u(key))) }
        return key
    }

    // ── keystore ─────────────────────────────────────────────────────────────

    private fun secret(name: String): String? = prefs.getString(name, null)?.let(::open)

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun seal(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
        return FerryCrypto.toB64u(cipher.iv + cipher.doFinal(plain.toByteArray()))
    }

    private fun open(sealed: String): String {
        val bytes = FerryCrypto.fromB64u(sealed)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, bytes, 0, 12))
        return String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }

    private companion object {
        const val KEY_ALIAS = "ferry-secrets"
    }
}
