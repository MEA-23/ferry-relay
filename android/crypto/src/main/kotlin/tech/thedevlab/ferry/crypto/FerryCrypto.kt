package tech.thedevlab.ferry.crypto

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Ferry crypto (docs/PROTOCOL.md §3), byte-compatible with libsodium:
 * BLAKE2b for the key id and `crypto_kdf_derive_from_key`, XChaCha20-Poly1305-IETF for AEAD,
 * ISO/IEC 7816-4 padding into fixed buckets, X25519 for node keys (§3.7).
 * Verified against docs/test-vectors/v1.json and v2.json.
 */
object FerryCrypto {
    const val UMK_BYTES = 32
    const val NONCE_BYTES = 24
    const val TAG_BYTES = 16
    const val MAX_PADDED_BYTES = 256 * 1024
    const val KEY_BYTES = 32
    const val MAC_BYTES = 32

    private val BUCKETS = intArrayOf(256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536)
    private const val LARGE_BLOCK = 65536
    private val random = SecureRandom()

    enum class Subkey(val context: String, val id: Long) {
        EVENT("FR_EVENT", 1),
        PROFILE("FR_PROFL", 1),
        RECOVERY("FR_RECOV", 1),
        ACCOUNT("FR_ACCTK", 1),
        COMMAND("FR_CMMND", 1),
    }

    class Sealed(val keyId: String, val nonce: String, val ct: String)

    class KeyPair(val secretKey: ByteArray, val publicKey: ByteArray)

    class DecryptionException(message: String, cause: Throwable? = null) : Exception(message, cause)

    // ── encoding ─────────────────────────────────────────────────────────────

    // By hand rather than java.util.Base64, which Android only has from 8.0 on.
    private const val B64U = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val B64U_VALUES = IntArray(128) { -1 }.also { t -> B64U.forEachIndexed { i, c -> t[c.code] = i } }

    /** base64url without padding (RFC 4648 §5). */
    fun toB64u(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        for (i in bytes.indices step 3) {
            val n = (bytes[i].toInt() and 0xff shl 16) or
                ((bytes.getOrNull(i + 1)?.toInt() ?: 0) and 0xff shl 8) or
                ((bytes.getOrNull(i + 2)?.toInt() ?: 0) and 0xff)
            val chars = minOf(4, (bytes.size - i) * 4 / 3 + 1)
            for (j in 0 until chars) out.append(B64U[(n shr (18 - 6 * j)) and 0x3f])
        }
        return out.toString()
    }

    /** Decodes base64url, with or without padding. Throws [IllegalArgumentException] on anything else. */
    fun fromB64u(s: String): ByteArray {
        val text = s.trimEnd('=')
        require(text.length % 4 != 1) { "invalid base64url length" }
        val out = ByteArray(text.length * 3 / 4)
        var acc = 0
        var bits = 0
        var o = 0
        for (c in text) {
            val v = if (c.code < 128) B64U_VALUES[c.code] else -1
            require(v >= 0) { "invalid base64url character" }
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[o++] = (acc shr bits).toByte()
            }
        }
        // Leftover bits must be zero, as in a canonical encoding.
        require(acc and ((1 shl bits) - 1) == 0) { "invalid base64url padding bits" }
        return out
    }

    // ── keys ─────────────────────────────────────────────────────────────────

    fun generateUmk(): ByteArray = ByteArray(UMK_BYTES).also(random::nextBytes)

    fun keyId(umk: ByteArray): String {
        requireUmk(umk)
        return toB64u(blake2b(key = umk, outLen = 16, message = "ferry-keyid-v1".toByteArray()))
    }

    /**
     * libsodium `crypto_kdf_derive_from_key`: keyed BLAKE2b with the subkey id as salt and the context as personalization.
     * [umk] is a UMK, or a node root for the EVENT and COMMAND subkeys (§3.7).
     */
    fun deriveSubkey(umk: ByteArray, purpose: Subkey): ByteArray {
        requireUmk(umk)
        val salt = ByteArray(16)
        for (i in 0 until 8) salt[i] = (purpose.id ushr (8 * i)).toByte()
        val personal = ByteArray(16)
        purpose.context.toByteArray(Charsets.US_ASCII).copyInto(personal)
        return blake2b(key = umk, outLen = 32, salt = salt, personal = personal)
    }

    private fun requireUmk(umk: ByteArray) = require(umk.size == UMK_BYTES) { "UMK must be $UMK_BYTES bytes" }

    // ── node keys (§3.7) ─────────────────────────────────────────────────────

    /** The account keypair, derived from the UMK, so every receiver holds the same one. */
    fun accountKeyPair(umk: ByteArray): KeyPair = nodeKeyPair(deriveSubkey(umk, Subkey.ACCOUNT))

    fun generateNodeKeyPair(): KeyPair = nodeKeyPair(ByteArray(KEY_BYTES).also(random::nextBytes))

    fun nodeKeyPair(secretKey: ByteArray): KeyPair {
        require(secretKey.size == KEY_BYTES) { "secret key must be $KEY_BYTES bytes" }
        return KeyPair(secretKey, ByteArray(KEY_BYTES).also { X25519.scalarMultBase(secretKey, 0, it, 0) })
    }

    /**
     * The secret a node shares with the receivers: X25519(nodeSk, acctPk) = X25519(acctSk, nodePk),
     * hashed with both public keys. Throws on a low-order public key (all-zero shared secret).
     */
    fun nodeRoot(secretKey: ByteArray, peerPublicKey: ByteArray, acctPk: ByteArray, nodePk: ByteArray): ByteArray {
        for (k in listOf(peerPublicKey, acctPk, nodePk)) require(k.size == KEY_BYTES) { "public key must be $KEY_BYTES bytes" }
        val shared = ByteArray(KEY_BYTES)
        if (!X25519.calculateAgreement(secretKey, 0, peerPublicKey, 0, shared, 0)) throw DecryptionException("weak public key")
        return blake2b(key = shared, outLen = 32, message = "ferry-node-v2".toByteArray() + acctPk + nodePk)
    }

    /** On the node: its own secret key and the account public key from the pairing. */
    fun nodeRootForNode(nodeSk: ByteArray, acctPk: ByteArray): ByteArray =
        nodeRoot(nodeSk, acctPk, acctPk, nodeKeyPair(nodeSk).publicKey)

    /** On a receiver: the UMK and the node public key from its profile. */
    fun nodeRootForReceiver(umk: ByteArray, nodePk: ByteArray): ByteArray {
        val account = accountKeyPair(umk)
        return nodeRoot(account.secretKey, nodePk, account.publicKey, nodePk)
    }

    fun nodeKeyId(root: ByteArray): String {
        requireUmk(root)
        return toB64u(blake2b(key = root, outLen = 16, message = "ferry-keyid-v2".toByteArray()))
    }

    // ── pairing MAC (§5.6) ───────────────────────────────────────────────────

    /** Proves a public key came from whoever scanned (or showed) the QR code holding [secret]. */
    fun pairingMac(secret: ByteArray, userId: String, deviceId: String, publicKey: ByteArray): ByteArray {
        require(secret.size == KEY_BYTES) { "pairing secret must be $KEY_BYTES bytes" }
        return blake2b(key = secret, outLen = MAC_BYTES, message = "ferry-pair-v2$userId$deviceId".toByteArray() + publicKey)
    }

    fun verifyPairingMac(secret: ByteArray, userId: String, deviceId: String, publicKey: ByteArray, mac: ByteArray): Boolean =
        MessageDigest.isEqual(pairingMac(secret, userId, deviceId, publicKey), mac)

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)

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

    fun commandAd(userId: String, nodeId: String, commandId: String): ByteArray =
        "ferry:v2:command:$userId:$nodeId:$commandId".toByteArray()

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

    /** A source-only node encrypts with its own root (§3.7); the AD is the same as for UMK events. */
    fun encryptNodeEvent(root: ByteArray, userId: String, sourceDeviceId: String, eventId: String, plaintext: ByteArray): Sealed {
        val (nonce, ct) = seal(deriveSubkey(root, Subkey.EVENT), plaintext, eventAd(userId, sourceDeviceId, eventId))
        return Sealed(nodeKeyId(root), toB64u(nonce), toB64u(ct))
    }

    fun decryptNodeEvent(root: ByteArray, userId: String, sourceDeviceId: String, eventId: String, env: Sealed): ByteArray {
        if (env.keyId != nodeKeyId(root)) throw DecryptionException("unknown keyId ${env.keyId}")
        return open(deriveSubkey(root, Subkey.EVENT), fromB64u(env.nonce), fromB64u(env.ct), eventAd(userId, sourceDeviceId, eventId))
    }

    /** §6.6: a receiver encrypts a command to one node with that node's command key. */
    fun encryptCommand(root: ByteArray, userId: String, nodeId: String, commandId: String, plaintext: ByteArray): Sealed {
        val (nonce, ct) = seal(deriveSubkey(root, Subkey.COMMAND), plaintext, commandAd(userId, nodeId, commandId))
        return Sealed(nodeKeyId(root), toB64u(nonce), toB64u(ct))
    }

    fun decryptCommand(root: ByteArray, userId: String, nodeId: String, commandId: String, env: Sealed): ByteArray {
        if (env.keyId != nodeKeyId(root)) throw DecryptionException("unknown keyId ${env.keyId}")
        return open(deriveSubkey(root, Subkey.COMMAND), fromB64u(env.nonce), fromB64u(env.ct), commandAd(userId, nodeId, commandId))
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
