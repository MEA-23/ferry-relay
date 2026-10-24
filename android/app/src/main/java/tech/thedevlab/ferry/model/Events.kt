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
    val battery: Int,
    val charging: Boolean,
    val sims: List<SimStatus>,
    val permissions: Map<String, Boolean>,
    val appVersion: String,
) : FerryEvent

@Serializable
data class SimStatus(val slot: Int, val subscriptionId: Int, val carrier: String?, val active: Boolean)

/** Device profile plaintext (§5.4). */
@Serializable
data class DeviceProfile(val name: String, val sims: Map<String, String> = emptyMap())
