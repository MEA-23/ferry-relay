package tech.thedevlab.ferry.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.decodeCertificatePem
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.util.concurrent.TimeUnit

/** Server error (§8). [code] is the protocol's snake_case error code. */
class ApiException(val status: Int, val code: String, message: String) : IOException("$status $code: $message") {
    /** The server's own message, which is written for developers; see [userMessage]. */
    val serverMessage = message
}

/**
 * Plain-language text for an error, for showing to people. Server and network messages are
 * written for developers ("403 forbidden: …", "failed to connect to /10.0.2.2"), so they never
 * reach the screen as they are.
 */
fun Throwable.userMessage(): String = when (this) {
    is ApiException -> when {
        code == "pairing_expired" -> "That pairing code expired or was already used. Show a new one."
        code == "unauthorized" -> "This phone was removed from your Ferry account."
        code == "key_rotated" -> "Your account's encryption key changed. Pair this phone again."
        code == "profile_stale" -> "These names were just changed on another device. Try again."
        code == "key_set" -> "That account already has a phone that reads messages. Pair from that phone instead."
        code == "forbidden" && "receivers once" in serverMessage -> "Add phones that read messages from a phone that already reads them."
        code == "forbidden" && "Nodes cannot remove" in serverMessage -> "Remove phones that read messages from one of them."
        code == "not_found" -> "That's no longer on your account. Refresh and try again."
        code == "forbidden" && "invite" in serverMessage -> "The invite code is wrong."
        code == "forbidden" && "Pairing code" in serverMessage -> "That code was made for a different kind of device. Show a new one and choose what this phone will do."
        code == "forbidden" -> "This phone isn't set up to do that."
        code == "invalid_request" && "sources" in serverMessage -> "Only Android phones can relay messages."
        else -> "Something went wrong on the server. Try again in a moment."
    }
    is UnknownHostException -> "Couldn't find the server. Check your internet connection, or the address for typos."
    is SSLException -> "The server's secure connection isn't set up correctly. Servers on the internet need a valid https:// certificate."
    else -> "Couldn't reach the server. Check your internet connection and try again."
}

@Serializable data class NewDevice(val roles: List<String>, val platform: String = "android", val appVersion: String? = null)
@Serializable data class CreateAccountRequest(val device: NewDevice, val keyId: String? = null, val invite: String? = null)
@Serializable data class CreatedAccount(val userId: String, val deviceId: String, val token: String)
@Serializable data class PairingRequest(val roles: List<String>)
@Serializable data class PairingCode(val code: String, val expiresAt: Long)
@Serializable data class RegisterDeviceRequest(
    val userId: String,
    val code: String,
    val roles: List<String>,
    val platform: String = "android",
    val appVersion: String? = null,
)
@Serializable data class RegisteredDevice(val deviceId: String, val token: String, val keyId: String? = null)
@Serializable data class KeyIdBody(val keyId: String)
/** A §5.6 mailbox message: a public key, and a MAC proving it came from whoever has the QR code's secret. */
@Serializable data class PairingMessage(val deviceId: String, val publicKey: String, val mac: String)
@Serializable data class PairingReply(val message: PairingMessage? = null)
/** A §6.6 command envelope, sealed to one node. */
@Serializable data class CommandEnvelope(val id: String, val keyId: String, val nonce: String, val ct: String)
@Serializable data class StoredCommand(val id: String, val keyId: String, val nonce: String, val ct: String, val senderId: String, val createdAt: Long)
@Serializable data class CommandList(val commands: List<StoredCommand>)
@Serializable data class CommandAck(val ids: List<String>)
@Serializable data class Sealed(val version: Long? = null, val keyId: String, val nonce: String, val ct: String)
@Serializable data class DeviceDto(
    val id: String,
    val roles: List<String>,
    val platform: String,
    val appVersion: String? = null,
    val createdAt: Long,
    val lastSeenAt: Long,
    val profile: Sealed? = null,
)
@Serializable data class DeviceList(val devices: List<DeviceDto>)
@Serializable data class Envelope(val id: String, val keyId: String, val nonce: String, val ct: String, val kind: String)
@Serializable data class UploadRequest(val events: List<Envelope>)
@Serializable data class UploadResult(val accepted: List<String>, val duplicate: List<String>)
@Serializable data class StoredEvent(
    val id: String,
    val keyId: String,
    val nonce: String,
    val ct: String,
    val kind: String,
    val seq: String,
    val sourceDeviceId: String,
    val receivedAt: Long,
)
@Serializable data class EventPage(val events: List<StoredEvent>, val next: String, val hasMore: Boolean)
@Serializable data class AckRequest(val upTo: String)
@Serializable data class PushRegistration(val provider: String, val token: String, val appId: String, val environment: String)
@Serializable private data class ErrorBody(val error: ErrorDetail)
@Serializable private data class ErrorDetail(val code: String, val message: String = "")

class Api(private val baseUrl: String, private val token: () -> String?) {
    suspend fun createAccount(req: CreateAccountRequest): CreatedAccount =
        decode(CreatedAccount.serializer(), send("POST", "/v1/accounts", encode(CreateAccountRequest.serializer(), req), auth = false))

    suspend fun requestPairing(roles: List<String>): PairingCode =
        decode(PairingCode.serializer(), send("POST", "/v1/pairing", encode(PairingRequest.serializer(), PairingRequest(roles))))

    suspend fun registerDevice(req: RegisterDeviceRequest): RegisteredDevice =
        decode(RegisteredDevice.serializer(), send("POST", "/v1/devices", encode(RegisterDeviceRequest.serializer(), req), auth = false))

    suspend fun devices(): List<DeviceDto> = decode(DeviceList.serializer(), send("GET", "/v1/devices")).devices

    suspend fun revoke(id: String) {
        send("DELETE", "/v1/devices/$id")
    }

    suspend fun putPush(id: String, registration: PushRegistration) {
        send("PUT", "/v1/devices/$id/push", encode(PushRegistration.serializer(), registration))
    }

    suspend fun putProfile(id: String, profile: Sealed) {
        send("PUT", "/v1/devices/$id/profile", encode(Sealed.serializer(), profile))
    }

    /** §5.6: the first receiver sets the account key ID, once. */
    suspend fun setInitialKey(keyId: String) {
        send("POST", "/v1/keys/initial", encode(KeyIdBody.serializer(), KeyIdBody(keyId)))
    }

    /** §5.6: a node registers its own key ID. */
    suspend fun putNodeKey(id: String, keyId: String) {
        send("PUT", "/v1/devices/$id/key", encode(KeyIdBody.serializer(), KeyIdBody(keyId)))
    }

    suspend fun sendPairingReply(code: String, message: PairingMessage) {
        send("POST", "/v1/pairing/$code/reply", encode(PairingMessage.serializer(), message))
    }

    /** §6.6: a receiver queues a command for one node. */
    suspend fun sendCommand(nodeId: String, command: CommandEnvelope) {
        send("POST", "/v1/devices/$nodeId/commands", encode(CommandEnvelope.serializer(), command))
    }

    /** §6.6: a node's long poll. Answers at once if commands are waiting, otherwise within [wait] seconds. */
    suspend fun commands(wait: Int): List<StoredCommand> =
        decode(CommandList.serializer(), send("GET", "/v1/commands?wait=$wait", timeoutSeconds = wait + 15L)).commands

    suspend fun ackCommands(ids: List<String>) {
        send("POST", "/v1/commands/ack", encode(CommandAck.serializer(), CommandAck(ids)))
    }

    suspend fun pairingReply(code: String): PairingMessage? =
        decode(PairingReply.serializer(), send("GET", "/v1/pairing/$code/reply")).message

    suspend fun upload(events: List<Envelope>): UploadResult =
        decode(UploadResult.serializer(), send("POST", "/v1/events", encode(UploadRequest.serializer(), UploadRequest(events))))

    /** With [wait], the server holds the request up to that many seconds until an event arrives (§6.5). */
    suspend fun pull(after: String?, limit: Int = 100, wait: Int = 0): EventPage = decode(
        EventPage.serializer(),
        send(
            "GET",
            "/v1/events?limit=$limit" + (after?.let { "&after=$it" } ?: "") + (if (wait > 0) "&wait=$wait" else ""),
            timeoutSeconds = if (wait > 0) wait + 15L else null,
        ),
    )

    suspend fun ack(upTo: String) {
        send("POST", "/v1/events/ack", encode(AckRequest.serializer(), AckRequest(upTo)))
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T) = json.encodeToString(serializer, value)

    private fun <T> decode(serializer: KSerializer<T>, text: String) = json.decodeFromString(serializer, text)

    /** Performs the request and returns the response body; throws [ApiException] for non-2xx. */
    private suspend fun send(method: String, path: String, body: String? = null, auth: Boolean = true, timeoutSeconds: Long? = null): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + path)
                .method(method, body?.toRequestBody(JSON))
                .apply { if (auth) token()?.let { header("Authorization", "Bearer $it") } }
                .build()

            val http = timeoutSeconds?.let { client.newBuilder().readTimeout(it, TimeUnit.SECONDS).build() } ?: client
            http.newCall(request).execute().use { res ->
                val text = res.body.string()
                if (!res.isSuccessful) {
                    val err = runCatching { json.decodeFromString(ErrorBody.serializer(), text).error }.getOrNull()
                    throw ApiException(res.code, err?.code ?: "http_${res.code}", err?.message ?: res.message)
                }
                text
            }
        }

    private companion object {
        val JSON = "application/json".toMediaType()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .apply {
                // Android 6 and 7.0 don't know Let's Encrypt's roots; trust them as well as the system's.
                if (android.os.Build.VERSION.SDK_INT < 25) {
                    val certificates = HandshakeCertificates.Builder()
                        .addPlatformTrustedCertificates()
                        .addTrustedCertificate(IsrgRoots.X1.decodeCertificatePem())
                        .addTrustedCertificate(IsrgRoots.X2.decodeCertificatePem())
                        .build()
                    sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager)
                }
            }
            .build()
    }
}
