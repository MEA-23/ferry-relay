package tech.thedevlab.ferry.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Plaintext event payloads (docs/PROTOCOL.md §4). Only ever sent encrypted. */

val FerryJson = Json {
    classDiscriminator = "t"
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class Sim(
    val slot: Int,
    val subscriptionId: Int,
    val carrier: String? = null,
    val number: String? = null,
)

@Serializable
sealed interface FerryEvent {
    val occurredAt: Long
}

@Serializable
@SerialName("sms")
data class SmsEvent(
    override val occurredAt: Long,
    val sim: Sim?,
    /** The other party: the sender of an incoming SMS, the recipient of an outgoing one. */
    val from: String,
    val body: String,
    val contactName: String? = null,
    val truncated: Boolean = false,
    /** "in" or "out". */
    val direction: String = "in",
    /** Copied from the phone's SMS history rather than captured live: file it, don't notify. */
    val imported: Boolean = false,
    /** Sent for this `send_sms` command (§6.6). */
    val commandId: String? = null,
) : FerryEvent

@Serializable
@SerialName("call")
data class CallEvent(
    override val occurredAt: Long,
    val sim: Sim?,
    val from: String?,
    /** ringing · missed · rejected · answered · ended */
    val state: String,
    val callKey: String,
    val durationSec: Int = 0,
    val contactName: String? = null,
) : FerryEvent

@Serializable
@SerialName("notification")
data class NotificationEvent(
    override val occurredAt: Long,
    @SerialName("package") val packageName: String,
    val appName: String,
    val title: String?,
    val text: String?,
    val category: String?,
    val key: String,
    val truncated: Boolean = false,
) : FerryEvent

@Serializable
@SerialName("status")
data class StatusEvent(
    override val occurredAt: Long,
    val battery: Int? = null,
    val charging: Boolean? = null,
    val sims: List<SimStatus>,
    val permissions: Map<String, Boolean>,
    val appVersion: String,
) : FerryEvent

@Serializable
data class SimStatus(val slot: Int, val subscriptionId: Int, val carrier: String?, val active: Boolean)

/** A node's name and SIM labels (§4.6). It can't seal its own profile, so receivers write it from this. */
@Serializable
@SerialName("profile")
data class ProfileEvent(
    override val occurredAt: Long,
    val name: String,
    val sims: Map<String, String> = emptyMap(),
) : FerryEvent

/** A node added another node (§4.7, §5.6 flow C). Receivers trust it once they've checked the introducer. */
@Serializable
@SerialName("introduce")
data class IntroduceEvent(
    override val occurredAt: Long,
    val deviceId: String,
    val nodePk: String,
) : FerryEvent

/** What became of a `send_sms` command (§4.8): sent · delivered · failed, with a [reason] when it failed. */
@Serializable
@SerialName("sms_status")
data class SmsStatusEvent(
    override val occurredAt: Long,
    val commandId: String,
    val status: String,
    val reason: String? = null,
) : FerryEvent

/** A command's plaintext (§6.6). Not an event: it goes from a receiver to one node. */
@Serializable
data class CommandPayload(
    val t: String,
    val to: String,
    val body: String,
    val subscriptionId: Int? = null,
    val createdAt: Long,
    val expiresAt: Long,
)

/** Device profile plaintext (§5.4). [nodePk] makes a node trusted (§3.7); keep it and [addedBy] on every rewrite. */
@Serializable
data class DeviceProfile(
    val name: String,
    val sims: Map<String, String> = emptyMap(),
    val nodePk: String? = null,
    val addedBy: String? = null,
    val namedAt: Long? = null,
)
