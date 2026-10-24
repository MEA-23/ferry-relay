package tech.thedevlab.ferry.crypto

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.SecureRandom
import java.util.Base64

/**
 * Ferry crypto (docs/PROTOCOL.md §3), byte-compatible with libsodium:
 * BLAKE2b for the key id and `crypto_kdf_derive_from_key`, XChaCha20-Poly1305-IETF for AEAD,
 * ISO/IEC 7816-4 padding into fixed buckets. Verified against docs/test-vectors/v1.json.
 */
object FerryCrypto {
    const val UMK_BYTES = 32
    const val NONCE_BYTES = 24
    const val TAG_BYTES = 16
    const val MAX_PADDED_BYTES = 256 * 1024

    private val BUCKETS = intArrayOf(256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536)
    private const val LARGE_BLOCK = 65536
    private val random = SecureRandom()

    enum class Subkey(val context: String, val id: Long) {
        EVENT("FR_EVENT", 1),
        PROFILE("FR_PROFL", 1),
        RECOVERY("FR_RECOV", 1),
    }

    class Sealed(val keyId: String, val nonce: String, val ct: String)

    class DecryptionException(message: String, cause: Throwable? = null) : Exception(message, cause)

    // ── encoding ─────────────────────────────────────────────────────────────

    fun toB64u(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun fromB64u(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

    // ── keys ─────────────────────────────────────────────────────────────────

    fun generateUmk(): ByteArray = ByteArray(UMK_BYTES).also(random::nextBytes)

    fun keyId(umk: ByteArray): String {
        requireUmk(umk)
        return toB64u(blake2b(key = umk, outLen = 16, message = "ferry-keyid-v1".toByteArray()))
    }

    /** libsodium `crypto_kdf_derive_from_key`: keyed BLAKE2b with the subkey id as salt and the context as personalization. */
    fun deriveSubkey(umk: ByteArray, purpose: Subkey): ByteArray {
        requireUmk(umk)
        val salt = ByteArray(16)
        for (i in 0 until 8) salt[i] = (purpose.id ushr (8 * i)).toByte()
        val personal = ByteArray(16)
        purpose.context.toByteArray(Charsets.US_ASCII).copyInto(personal)
        return blake2b(key = umk, outLen = 32, salt = salt, personal = personal)
    }

    private fun requireUmk(umk: ByteArray) = require(umk.size == UMK_BYTES) { "UMK must be $UMK_BYTES bytes" }

    private fun blake2b(
        key: ByteArray,
        outLen: Int,
        message: ByteArray = ByteArray(0),
        salt: ByteArray? = null,
        personal: ByteArray? = null,
    ): ByteArray {
        val digest = Blake2bDigest(key, outLen, salt, personal)
        digest.update(message, 0, message.size)
        return ByteArray(outLen).also { digest.doFinal(it, 0) }
    }

    // ── associated data ──────────────────────────────────────────────────────

    fun eventAd(userId: String, sourceDeviceId: String, eventId: String): ByteArray =
        "ferry:v1:event:$userId:$sourceDeviceId:$eventId".toByteArray()

    fun profileAd(userId: String, deviceId: String, version: Long): ByteArray =
        "ferry:v1:profile:$userId:$deviceId:$version".toByteArray()

    // ── padding ──────────────────────────────────────────────────────────────

    fun paddedLength(n: Int): Int =
        BUCKETS.firstOrNull { it >= n + 1 } ?: ((n + 1 + LARGE_BLOCK - 1) / LARGE_BLOCK * LARGE_BLOCK)

    fun pad(plain: ByteArray): ByteArray {
        val target = paddedLength(plain.size)
        require(target <= MAX_PADDED_BYTES) { "plaintext too large; truncate before encrypting" }
        return plain.copyOf(target).also { it[plain.size] = 0x80.toByte() }
    }

    fun unpad(padded: ByteArray): ByteArray {
        var i = padded.size - 1
        while (i >= 0 && padded[i] == 0.toByte()) i--
        if (i < 0 || padded[i] != 0x80.toByte()) throw DecryptionException("invalid padding")
        return padded.copyOf(i)
    }

    // ── AEAD ─────────────────────────────────────────────────────────────────

    /** `nonce` is only for test vectors; production callers must omit it. */
    fun seal(key: ByteArray, plain: ByteArray, ad: ByteArray, nonce: ByteArray? = null): Pair<ByteArray, ByteArray> {
        val n = nonce ?: ByteArray(NONCE_BYTES).also(random::nextBytes)
        require(n.size == NONCE_BYTES) { "nonce must be $NONCE_BYTES bytes" }
        return n to xchacha(encrypt = true, key, n, pad(plain), ad)
    }

    /** Throws [DecryptionException] on a wrong key, tampered ciphertext or mismatched AD. */
    fun open(key: ByteArray, nonce: ByteArray, ct: ByteArray, ad: ByteArray): ByteArray {
        if (nonce.size != NONCE_BYTES || ct.size < TAG_BYTES) throw DecryptionException("malformed ciphertext")
        val padded = try {
            xchacha(encrypt = false, key, nonce, ct, ad)
        } catch (e: Exception) {
            throw DecryptionException("authentication failed", e)
        }
        return unpad(padded)
    }

    /** XChaCha20-Poly1305-IETF = HChaCha20 subkey + ChaCha20-Poly1305 (RFC 8439) with nonce 0x00000000 ‖ nonce[16..24]. */
    private fun xchacha(encrypt: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray, ad: ByteArray): ByteArray {
        val subkey = hchacha20(key, nonce.copyOfRange(0, 16))
        val ietfNonce = ByteArray(12).also { nonce.copyInto(it, destinationOffset = 4, startIndex = 16, endIndex = 24) }
        val cipher = ChaCha20Poly1305()
        cipher.init(encrypt, AEADParameters(KeyParameter(subkey), TAG_BYTES * 8, ietfNonce, ad))
        val out = ByteArray(cipher.getOutputSize(input.size))
        var len = cipher.processBytes(input, 0, input.size, out, 0)
        len += cipher.doFinal(out, len)
        return if (len == out.size) out else out.copyOf(len)
    }

    private fun hchacha20(key: ByteArray, nonce16: ByteArray): ByteArray {
        val s = IntArray(16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = le32(key, i * 4)
        for (i in 0 until 4) s[12 + i] = le32(nonce16, i * 4)
        repeat(10) {
            quarter(s, 0, 4, 8, 12); quarter(s, 1, 5, 9, 13); quarter(s, 2, 6, 10, 14); quarter(s, 3, 7, 11, 15)
            quarter(s, 0, 5, 10, 15); quarter(s, 1, 6, 11, 12); quarter(s, 2, 7, 8, 13); quarter(s, 3, 4, 9, 14)
        }
        val out = ByteArray(32)
        intArrayOf(0, 1, 2, 3, 12, 13, 14, 15).forEachIndexed { i, w -> putLe32(out, i * 4, s[w]) }
        return out
    }

    private fun quarter(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = (s[d] xor s[a]).rotateLeft(16)
        s[c] += s[d]; s[b] = (s[b] xor s[c]).rotateLeft(12)
        s[a] += s[b]; s[d] = (s[d] xor s[a]).rotateLeft(8)
        s[c] += s[d]; s[b] = (s[b] xor s[c]).rotateLeft(7)
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun putLe32(b: ByteArray, o: Int, v: Int) {
        for (i in 0 until 4) b[o + i] = (v ushr (8 * i)).toByte()
    }

    // ── events & profiles ────────────────────────────────────────────────────

    fun encryptEvent(umk: ByteArray, userId: String, sourceDeviceId: String, eventId: String, plaintext: ByteArray): Sealed {
        val (nonce, ct) = seal(deriveSubkey(umk, Subkey.EVENT), plaintext, eventAd(userId, sourceDeviceId, eventId))
        return Sealed(keyId(umk), toB64u(nonce), toB64u(ct))
    }

    /** [umks] maps keyId → UMK so events from before a key rotation stay readable. */
    fun decryptEvent(umks: Map<String, ByteArray>, userId: String, sourceDeviceId: String, eventId: String, env: Sealed): ByteArray {
        val umk = umks[env.keyId] ?: throw DecryptionException("unknown keyId ${env.keyId}")
        val ad = eventAd(userId, sourceDeviceId, eventId)
        return open(deriveSubkey(umk, Subkey.EVENT), fromB64u(env.nonce), fromB64u(env.ct), ad)
    }

    fun encryptProfile(umk: ByteArray, userId: String, deviceId: String, version: Long, plaintext: ByteArray): Sealed {
        val (nonce, ct) = seal(deriveSubkey(umk, Subkey.PROFILE), plaintext, profileAd(userId, deviceId, version))
        return Sealed(keyId(umk), toB64u(nonce), toB64u(ct))
    }

    fun decryptProfile(umks: Map<String, ByteArray>, userId: String, deviceId: String, version: Long, env: Sealed): ByteArray {
        val umk = umks[env.keyId] ?: throw DecryptionException("unknown keyId ${env.keyId}")
        val ad = profileAd(userId, deviceId, version)
        return open(deriveSubkey(umk, Subkey.PROFILE), fromB64u(env.nonce), fromB64u(env.ct), ad)
    }
}
