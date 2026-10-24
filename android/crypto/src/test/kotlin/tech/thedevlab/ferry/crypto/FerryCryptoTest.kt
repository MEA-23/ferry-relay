package tech.thedevlab.ferry.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class FerryCryptoTest {
    private val v: JsonObject = Json.parseToJsonElement(
        File(System.getProperty("ferry.testVectors"), "v1.json").readText(),
    ).jsonObject

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private val umk = hex(v.str("umk_hex"))

    @Test
    fun keyIdMatchesVector() {
        assertEquals(v.str("keyId"), FerryCrypto.keyId(umk))
    }

    @Test
    fun subkeysMatchVector() {
        val expected = v.getValue("subkeys_hex").jsonObject
        assertEquals(expected.str("event"), FerryCrypto.deriveSubkey(umk, FerryCrypto.Subkey.EVENT).hex())
        assertEquals(expected.str("profile"), FerryCrypto.deriveSubkey(umk, FerryCrypto.Subkey.PROFILE).hex())
        assertEquals(expected.str("recovery"), FerryCrypto.deriveSubkey(umk, FerryCrypto.Subkey.RECOVERY).hex())
    }

    @Test
    fun paddingMatchesVector() {
        for (p in v.getValue("padding").jsonArray) {
            val n = p.jsonObject.getValue("plaintextBytes").jsonPrimitive.int
            val expected = p.jsonObject.getValue("paddedLength").jsonPrimitive.int
            assertEquals("n=$n", expected, FerryCrypto.paddedLength(n))
            val padded = FerryCrypto.pad(ByteArray(n) { 7 })
            assertEquals(expected, padded.size)
            assertEquals(n, FerryCrypto.unpad(padded).size)
        }
        assertThrows(IllegalArgumentException::class.java) { FerryCrypto.pad(ByteArray(262144)) }
    }

    @Test
    fun eventsEncryptToExactVectorBytesAndDecryptBack() {
        val key = FerryCrypto.deriveSubkey(umk, FerryCrypto.Subkey.EVENT)
        for (e in v.getValue("events").jsonArray.map { it.jsonObject }) {
            val name = e.str("name")
            val ad = FerryCrypto.eventAd(e.str("userId"), e.str("sourceDeviceId"), e.str("eventId"))
            assertEquals(name, e.str("ad"), String(ad))
            val nonce = FerryCrypto.fromB64u(e.str("nonce"))
            val (_, ct) = FerryCrypto.seal(key, e.str("plaintext").toByteArray(), ad, nonce)
            assertEquals(name, e.str("ct"), FerryCrypto.toB64u(ct))
            assertEquals(name, e.str("plaintext"), String(FerryCrypto.open(key, nonce, ct, ad)))
        }
    }

    @Test
    fun profileMatchesVector() {
        val p = v.getValue("profile").jsonObject
        val version = p.getValue("version").jsonPrimitive.long
        val sealed = FerryCrypto.Sealed(FerryCrypto.keyId(umk), p.str("nonce"), p.str("ct"))
        val plain = FerryCrypto.decryptProfile(mapOf(sealed.keyId to umk), p.str("userId"), p.str("deviceId"), version, sealed)
        assertEquals(p.str("plaintext"), String(plain))
    }

    @Test
    fun roundTripAndRejections() {
        val k = FerryCrypto.generateUmk()
        val umks = mapOf(FerryCrypto.keyId(k) to k)
        val plain = "hello 🚢".toByteArray()
        val env = FerryCrypto.encryptEvent(k, "u1", "d1", "e1", plain)
        assertArrayEquals(plain, FerryCrypto.decryptEvent(umks, "u1", "d1", "e1", env))

        assertThrows(FerryCrypto.DecryptionException::class.java) { FerryCrypto.decryptEvent(umks, "u1", "d1", "e2", env) }
        assertThrows(FerryCrypto.DecryptionException::class.java) { FerryCrypto.decryptEvent(umks, "u1", "d2", "e1", env) }
        assertThrows(FerryCrypto.DecryptionException::class.java) { FerryCrypto.decryptEvent(emptyMap(), "u1", "d1", "e1", env) }

        val other = FerryCrypto.generateUmk()
        assertThrows(FerryCrypto.DecryptionException::class.java) {
            FerryCrypto.decryptEvent(mapOf(env.keyId to other), "u1", "d1", "e1", env)
        }

        val tampered = FerryCrypto.fromB64u(env.ct).also { it[0] = (it[0].toInt() xor 1).toByte() }
        val bad = FerryCrypto.Sealed(env.keyId, env.nonce, FerryCrypto.toB64u(tampered))
        assertThrows(FerryCrypto.DecryptionException::class.java) { FerryCrypto.decryptEvent(umks, "u1", "d1", "e1", bad) }
    }
}
