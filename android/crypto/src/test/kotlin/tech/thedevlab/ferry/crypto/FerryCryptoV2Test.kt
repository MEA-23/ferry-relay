package tech.thedevlab.ferry.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Node keys and pairing MACs against docs/test-vectors/v2.json (§3.7, §5.6). */
class FerryCryptoV2Test {
    private val v: JsonObject = Json.parseToJsonElement(
        File(System.getProperty("ferry.testVectors"), "v2.json").readText(),
    ).jsonObject

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.obj(key: String) = getValue(key).jsonObject
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private val umk = hex(v.str("umk_hex"))
    private val node = v.obj("node")

    @Test
    fun accountKeyPairMatchesVector() {
        val account = FerryCrypto.accountKeyPair(umk)
        assertEquals(v.obj("account").str("secretKey_hex"), account.secretKey.hex())
        assertEquals(v.obj("account").str("publicKey"), FerryCrypto.toB64u(account.publicKey))
    }

    @Test
    fun nodeRootMatchesVectorOnBothSides() {
        val pair = FerryCrypto.nodeKeyPair(hex(node.str("secretKey_hex")))
        assertEquals(node.str("publicKey"), FerryCrypto.toB64u(pair.publicKey))
        val acctPk = FerryCrypto.fromB64u(v.obj("account").str("publicKey"))
        val root = FerryCrypto.nodeRootForNode(pair.secretKey, acctPk)
        assertEquals(node.str("root_hex"), root.hex())
        assertArrayEquals(root, FerryCrypto.nodeRootForReceiver(umk, pair.publicKey))
        assertEquals(node.str("keyId"), FerryCrypto.nodeKeyId(root))
        assertEquals(node.obj("subkeys_hex").str("event"), FerryCrypto.deriveSubkey(root, FerryCrypto.Subkey.EVENT).hex())
        assertEquals(node.obj("subkeys_hex").str("command"), FerryCrypto.deriveSubkey(root, FerryCrypto.Subkey.COMMAND).hex())
    }

    @Test
    fun nodeEventsEncryptToExactVectorBytes() {
        val root = hex(node.str("root_hex"))
        val key = FerryCrypto.deriveSubkey(root, FerryCrypto.Subkey.EVENT)
        for (e in v.getValue("events").jsonArray.map { it.jsonObject }) {
            val ad = FerryCrypto.eventAd(e.str("userId"), e.str("sourceDeviceId"), e.str("eventId"))
            val (_, ct) = FerryCrypto.seal(key, e.str("plaintext").toByteArray(), ad, FerryCrypto.fromB64u(e.str("nonce")))
            assertEquals(e.str("name"), e.str("ct"), FerryCrypto.toB64u(ct))
            val sealed = FerryCrypto.Sealed(node.str("keyId"), e.str("nonce"), e.str("ct"))
            val plain = FerryCrypto.decryptNodeEvent(root, e.str("userId"), e.str("sourceDeviceId"), e.str("eventId"), sealed)
            assertEquals(e.str("plaintext"), String(plain))
        }
    }

    @Test
    fun commandsMatchVectors() {
        val root = hex(node.str("root_hex"))
        val key = FerryCrypto.deriveSubkey(root, FerryCrypto.Subkey.COMMAND)
        for (c in v.getValue("commands").jsonArray.map { it.jsonObject }) {
            val ad = FerryCrypto.commandAd(c.str("userId"), c.str("nodeId"), c.str("commandId"))
            assertEquals(c.str("ad"), String(ad))
            val (_, ct) = FerryCrypto.seal(key, c.str("plaintext").toByteArray(), ad, FerryCrypto.fromB64u(c.str("nonce")))
            assertEquals(c.str("name"), c.str("ct"), FerryCrypto.toB64u(ct))
            val sealed = FerryCrypto.Sealed(node.str("keyId"), c.str("nonce"), c.str("ct"))
            assertEquals(c.str("plaintext"), String(FerryCrypto.decryptCommand(root, c.str("userId"), c.str("nodeId"), c.str("commandId"), sealed)))
            assertThrows(FerryCrypto.DecryptionException::class.java) {
                FerryCrypto.decryptCommand(root, c.str("userId"), c.str("nodeId"), "other", sealed)
            }
        }
    }

    @Test
    fun lowOrderKeysAreRejected() {
        val pair = FerryCrypto.generateNodeKeyPair()
        for (pk in v.getValue("lowOrderPublicKeys").jsonArray.map { FerryCrypto.fromB64u(it.jsonPrimitive.content) }) {
            assertThrows(FerryCrypto.DecryptionException::class.java) { FerryCrypto.nodeRoot(pair.secretKey, pk, pk, pair.publicKey) }
        }
    }

    @Test
    fun pairingMacsMatchVectors() {
        for (m in v.getValue("pairingMacs").jsonArray.map { it.jsonObject }) {
            val secret = hex(m.str("secret_hex"))
            val pk = FerryCrypto.fromB64u(m.str("publicKey"))
            val mac = FerryCrypto.pairingMac(secret, m.str("userId"), m.str("deviceId"), pk)
            assertEquals(m.str("name"), m.str("mac"), FerryCrypto.toB64u(mac))
            assertTrue(FerryCrypto.verifyPairingMac(secret, m.str("userId"), m.str("deviceId"), pk, mac))
            assertFalse(FerryCrypto.verifyPairingMac(secret, m.str("userId"), "other", pk, mac))
        }
    }

    @Test
    fun anotherNodeCannotRead() {
        val k = FerryCrypto.generateUmk()
        val a = FerryCrypto.nodeRootForReceiver(k, FerryCrypto.generateNodeKeyPair().publicKey)
        val b = FerryCrypto.nodeRootForReceiver(k, FerryCrypto.generateNodeKeyPair().publicKey)
        val env = FerryCrypto.encryptNodeEvent(a, "u1", "d1", "e1", "hi".toByteArray())
        assertEquals("hi", String(FerryCrypto.decryptNodeEvent(a, "u1", "d1", "e1", env)))
        assertThrows(FerryCrypto.DecryptionException::class.java) { FerryCrypto.decryptNodeEvent(b, "u1", "d1", "e1", env) }
        val relabelled = FerryCrypto.Sealed(FerryCrypto.nodeKeyId(b), env.nonce, env.ct)
        assertThrows(FerryCrypto.DecryptionException::class.java) { FerryCrypto.decryptNodeEvent(b, "u1", "d1", "e1", relabelled) }
    }
}
