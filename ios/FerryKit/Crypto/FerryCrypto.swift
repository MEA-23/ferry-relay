import CryptoKit
import Foundation
import Security

/// Ferry crypto (docs/PROTOCOL.md §3), byte-compatible with libsodium:
/// BLAKE2b for the key id and `crypto_kdf_derive_from_key`, XChaCha20-Poly1305-IETF for AEAD,
/// ISO/IEC 7816-4 padding into fixed buckets, X25519 for node keys (§3.7).
/// Verified against docs/test-vectors/v1.json and v2.json.
public enum FerryCrypto {
    public static let umkBytes = 32
    public static let nonceBytes = 24
    public static let tagBytes = 16
    public static let maxPaddedBytes = 256 * 1024
    public static let keyBytes = 32
    public static let macBytes = 32

    private static let buckets = [256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536]
    private static let largeBlock = 65536

    public enum Subkey {
        case event, profile, recovery, account, command

        var context: String {
            switch self {
            case .event: "FR_EVENT"
            case .profile: "FR_PROFL"
            case .recovery: "FR_RECOV"
            case .account: "FR_ACCTK"
            case .command: "FR_CMMND"
            }
        }

        var id: UInt64 { 1 }
    }

    public struct Sealed: Sendable, Equatable {
        public let keyId: String
        public let nonce: String
        public let ct: String

        public init(keyId: String, nonce: String, ct: String) {
            self.keyId = keyId
            self.nonce = nonce
            self.ct = ct
        }
    }

    public enum CryptoError: Error, Equatable {
        case unknownKeyId(String)
        case malformed
        case authenticationFailed
        case invalidPadding
        case weakPublicKey
    }

    public struct KeyPair: Sendable {
        public let secretKey: Data
        public let publicKey: Data
    }

    // ── keys ─────────────────────────────────────────────────────────────────

    public static func generateUmk() -> Data { randomBytes(umkBytes) }

    public static func keyId(_ umk: Data) -> String {
        precondition(umk.count == umkBytes, "UMK must be \(umkBytes) bytes")
        return Data(Blake2b.hash(Array("ferry-keyid-v1".utf8), key: Array(umk), outLen: 16)).base64URL
    }

    /// libsodium `crypto_kdf_derive_from_key`: keyed BLAKE2b with the subkey id as salt and the context as personalization.
    /// `umk` is a UMK, or a node root for the `.event` and `.command` subkeys (§3.7).
    public static func deriveSubkey(_ umk: Data, _ purpose: Subkey) -> Data {
        precondition(umk.count == umkBytes, "UMK must be \(umkBytes) bytes")
        var salt = [UInt8](repeating: 0, count: 16)
        for i in 0..<8 { salt[i] = UInt8(truncatingIfNeeded: purpose.id >> (8 * UInt64(i))) }
        var personal = [UInt8](repeating: 0, count: 16)
        personal.replaceSubrange(0..<8, with: Array(purpose.context.utf8))
        return Data(Blake2b.hash([], key: Array(umk), outLen: 32, salt: salt, personal: personal))
    }

    // ── node keys (§3.7) ─────────────────────────────────────────────────────

    /// The account keypair, derived from the UMK, so every receiver holds the same one.
    public static func accountKeyPair(_ umk: Data) -> KeyPair { nodeKeyPair(deriveSubkey(umk, .account)) }

    public static func generateNodeKeyPair() -> KeyPair { nodeKeyPair(randomBytes(keyBytes)) }

    public static func nodeKeyPair(_ secretKey: Data) -> KeyPair {
        let key = try! Curve25519.KeyAgreement.PrivateKey(rawRepresentation: secretKey)
        return KeyPair(secretKey: secretKey, publicKey: key.publicKey.rawRepresentation)
    }

    /// The secret a node shares with the receivers: X25519(nodeSk, acctPk) = X25519(acctSk, nodePk),
    /// hashed with both public keys. Throws on a low-order public key (all-zero shared secret).
    public static func nodeRoot(secretKey: Data, peerPublicKey: Data, acctPk: Data, nodePk: Data) throws -> Data {
        guard [peerPublicKey, acctPk, nodePk].allSatisfy({ $0.count == keyBytes }) else { throw CryptoError.malformed }
        let shared: Data
        do {
            let key = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: secretKey)
            let secret = try key.sharedSecretFromKeyAgreement(with: .init(rawRepresentation: peerPublicKey))
            shared = secret.withUnsafeBytes { Data($0) }
        } catch {
            throw CryptoError.weakPublicKey
        }
        guard shared.contains(where: { $0 != 0 }) else { throw CryptoError.weakPublicKey }
        return Data(Blake2b.hash(Array("ferry-node-v2".utf8) + Array(acctPk) + Array(nodePk), key: Array(shared), outLen: 32))
    }

    /// On the node: its own secret key and the account public key from the pairing.
    public static func nodeRootForNode(nodeSk: Data, acctPk: Data) throws -> Data {
        try nodeRoot(secretKey: nodeSk, peerPublicKey: acctPk, acctPk: acctPk, nodePk: nodeKeyPair(nodeSk).publicKey)
    }

    /// On a receiver: the UMK and the node public key from its profile.
    public static func nodeRootForReceiver(umk: Data, nodePk: Data) throws -> Data {
        let account = accountKeyPair(umk)
        return try nodeRoot(secretKey: account.secretKey, peerPublicKey: nodePk, acctPk: account.publicKey, nodePk: nodePk)
    }

    public static func nodeKeyId(_ root: Data) -> String {
        precondition(root.count == keyBytes, "root must be \(keyBytes) bytes")
        return Data(Blake2b.hash(Array("ferry-keyid-v2".utf8), key: Array(root), outLen: 16)).base64URL
    }

    // ── pairing MAC (§5.6) ───────────────────────────────────────────────────

    /// Proves a public key came from whoever scanned (or showed) the QR code holding `secret`.
    public static func pairingMac(secret: Data, userId: String, deviceId: String, publicKey: Data) -> Data {
        precondition(secret.count == keyBytes, "pairing secret must be \(keyBytes) bytes")
        return Data(Blake2b.hash(Array("ferry-pair-v2\(userId)\(deviceId)".utf8) + Array(publicKey), key: Array(secret), outLen: macBytes))
    }

    public static func verifyPairingMac(secret: Data, userId: String, deviceId: String, publicKey: Data, mac: Data) -> Bool {
        let expected = pairingMac(secret: secret, userId: userId, deviceId: deviceId, publicKey: publicKey)
        guard mac.count == expected.count else { return false }
        return zip(expected, mac).reduce(0) { $0 | ($1.0 ^ $1.1) } == 0
    }

    // ── associated data ──────────────────────────────────────────────────────

    public static func eventAd(userId: String, sourceDeviceId: String, eventId: String) -> Data {
        Data("ferry:v1:event:\(userId):\(sourceDeviceId):\(eventId)".utf8)
    }

    public static func commandAd(userId: String, nodeId: String, commandId: String) -> Data {
        Data("ferry:v2:command:\(userId):\(nodeId):\(commandId)".utf8)
    }

    public static func profileAd(userId: String, deviceId: String, version: Int64) -> Data {
        Data("ferry:v1:profile:\(userId):\(deviceId):\(version)".utf8)
    }

    // ── padding ──────────────────────────────────────────────────────────────

    public static func paddedLength(_ n: Int) -> Int {
        buckets.first { $0 >= n + 1 } ?? ((n + 1 + largeBlock - 1) / largeBlock * largeBlock)
    }

    static func pad(_ plain: Data) -> Data {
        let target = paddedLength(plain.count)
        precondition(target <= maxPaddedBytes, "plaintext too large; truncate before encrypting")
        var out = plain
        out.append(0x80)
        out.append(Data(count: target - out.count))
        return out
    }

    static func unpad(_ padded: Data) throws -> Data {
        let bytes = [UInt8](padded)
        var i = bytes.count - 1
        while i >= 0 && bytes[i] == 0 { i -= 1 }
        guard i >= 0, bytes[i] == 0x80 else { throw CryptoError.invalidPadding }
        return Data(bytes[0..<i])
    }

    // ── AEAD ─────────────────────────────────────────────────────────────────

    /// `nonce` is only for test vectors; production callers must omit it.
    public static func seal(key: Data, plain: Data, ad: Data, nonce: Data? = nil) -> (nonce: Data, ct: Data) {
        let n = nonce ?? randomBytes(nonceBytes)
        precondition(n.count == nonceBytes, "nonce must be \(nonceBytes) bytes")
        let box = try! ChaChaPoly.seal(pad(plain), using: hchachaKey(key, n), nonce: ietfNonce(n), authenticating: ad)
        return (n, box.ciphertext + box.tag)
    }

    /// Throws `CryptoError` on a wrong key, tampered ciphertext or mismatched AD.
    public static func open(key: Data, nonce: Data, ct: Data, ad: Data) throws -> Data {
        guard nonce.count == nonceBytes, ct.count >= tagBytes else { throw CryptoError.malformed }
        let padded: Data
        do {
            let box = try ChaChaPoly.SealedBox(nonce: ietfNonce(nonce), ciphertext: ct.dropLast(tagBytes), tag: ct.suffix(tagBytes))
            padded = try ChaChaPoly.open(box, using: hchachaKey(key, nonce), authenticating: ad)
        } catch {
            throw CryptoError.authenticationFailed
        }
        return try unpad(padded)
    }

    /// XChaCha20-Poly1305-IETF = HChaCha20 subkey + ChaCha20-Poly1305 (RFC 8439) with nonce 0x00000000 ‖ nonce[16..24].
    private static func hchachaKey(_ key: Data, _ nonce: Data) -> SymmetricKey {
        SymmetricKey(data: hchacha20(Array(key), Array(nonce.prefix(16))))
    }

    private static func ietfNonce(_ nonce: Data) -> ChaChaPoly.Nonce {
        try! ChaChaPoly.Nonce(data: Data(count: 4) + nonce.suffix(8))
    }

    private static func hchacha20(_ key: [UInt8], _ nonce16: [UInt8]) -> [UInt8] {
        var s = [UInt32](repeating: 0, count: 16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for i in 0..<8 { s[4 + i] = le32(key, i * 4) }
        for i in 0..<4 { s[12 + i] = le32(nonce16, i * 4) }

        func quarter(_ a: Int, _ b: Int, _ c: Int, _ d: Int) {
            s[a] = s[a] &+ s[b]; s[d] = (s[d] ^ s[a]).rotatedLeft(16)
            s[c] = s[c] &+ s[d]; s[b] = (s[b] ^ s[c]).rotatedLeft(12)
            s[a] = s[a] &+ s[b]; s[d] = (s[d] ^ s[a]).rotatedLeft(8)
            s[c] = s[c] &+ s[d]; s[b] = (s[b] ^ s[c]).rotatedLeft(7)
        }
        for _ in 0..<10 {
            quarter(0, 4, 8, 12); quarter(1, 5, 9, 13); quarter(2, 6, 10, 14); quarter(3, 7, 11, 15)
            quarter(0, 5, 10, 15); quarter(1, 6, 11, 12); quarter(2, 7, 8, 13); quarter(3, 4, 9, 14)
        }
        var out = [UInt8]()
        out.reserveCapacity(32)
        for w in [0, 1, 2, 3, 12, 13, 14, 15] {
            for i in 0..<4 { out.append(UInt8(truncatingIfNeeded: s[w] >> (8 * UInt32(i)))) }
        }
        return out
    }

    private static func le32(_ b: [UInt8], _ o: Int) -> UInt32 {
        UInt32(b[o]) | UInt32(b[o + 1]) << 8 | UInt32(b[o + 2]) << 16 | UInt32(b[o + 3]) << 24
    }

    public static func randomBytes(_ count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        precondition(SecRandomCopyBytes(kSecRandomDefault, count, &bytes) == errSecSuccess)
        return Data(bytes)
    }

    // ── events & profiles ────────────────────────────────────────────────────

    public static func encryptEvent(umk: Data, userId: String, sourceDeviceId: String, eventId: String, plaintext: Data) -> Sealed {
        let (nonce, ct) = seal(key: deriveSubkey(umk, .event), plain: plaintext, ad: eventAd(userId: userId, sourceDeviceId: sourceDeviceId, eventId: eventId))
        return Sealed(keyId: keyId(umk), nonce: nonce.base64URL, ct: ct.base64URL)
    }

    /// `umks` maps keyId → UMK so events from before a key rotation stay readable.
    public static func decryptEvent(umks: [String: Data], userId: String, sourceDeviceId: String, eventId: String, sealed: Sealed) throws -> Data {
        guard let umk = umks[sealed.keyId] else { throw CryptoError.unknownKeyId(sealed.keyId) }
        guard let nonce = Data(base64URL: sealed.nonce), let ct = Data(base64URL: sealed.ct) else { throw CryptoError.malformed }
        return try open(key: deriveSubkey(umk, .event), nonce: nonce, ct: ct, ad: eventAd(userId: userId, sourceDeviceId: sourceDeviceId, eventId: eventId))
    }

    /// A source-only node encrypts with its own root (§3.7); the AD is the same as for UMK events.
    public static func encryptNodeEvent(root: Data, userId: String, sourceDeviceId: String, eventId: String, plaintext: Data) -> Sealed {
        let (nonce, ct) = seal(key: deriveSubkey(root, .event), plain: plaintext, ad: eventAd(userId: userId, sourceDeviceId: sourceDeviceId, eventId: eventId))
        return Sealed(keyId: nodeKeyId(root), nonce: nonce.base64URL, ct: ct.base64URL)
    }

    public static func decryptNodeEvent(root: Data, userId: String, sourceDeviceId: String, eventId: String, sealed: Sealed) throws -> Data {
        guard sealed.keyId == nodeKeyId(root) else { throw CryptoError.unknownKeyId(sealed.keyId) }
        guard let nonce = Data(base64URL: sealed.nonce), let ct = Data(base64URL: sealed.ct) else { throw CryptoError.malformed }
        return try open(key: deriveSubkey(root, .event), nonce: nonce, ct: ct, ad: eventAd(userId: userId, sourceDeviceId: sourceDeviceId, eventId: eventId))
    }

    /// §6.6: a receiver encrypts a command to one node with that node's command key.
    public static func encryptCommand(root: Data, userId: String, nodeId: String, commandId: String, plaintext: Data) -> Sealed {
        let (nonce, ct) = seal(key: deriveSubkey(root, .command), plain: plaintext, ad: commandAd(userId: userId, nodeId: nodeId, commandId: commandId))
        return Sealed(keyId: nodeKeyId(root), nonce: nonce.base64URL, ct: ct.base64URL)
    }

    public static func encryptProfile(umk: Data, userId: String, deviceId: String, version: Int64, plaintext: Data) -> Sealed {
        let (nonce, ct) = seal(key: deriveSubkey(umk, .profile), plain: plaintext, ad: profileAd(userId: userId, deviceId: deviceId, version: version))
        return Sealed(keyId: keyId(umk), nonce: nonce.base64URL, ct: ct.base64URL)
    }

    public static func decryptProfile(umks: [String: Data], userId: String, deviceId: String, version: Int64, sealed: Sealed) throws -> Data {
        guard let umk = umks[sealed.keyId] else { throw CryptoError.unknownKeyId(sealed.keyId) }
        guard let nonce = Data(base64URL: sealed.nonce), let ct = Data(base64URL: sealed.ct) else { throw CryptoError.malformed }
        return try open(key: deriveSubkey(umk, .profile), nonce: nonce, ct: ct, ad: profileAd(userId: userId, deviceId: deviceId, version: version))
    }
}

private extension UInt32 {
    func rotatedLeft(_ n: UInt32) -> UInt32 { (self << n) | (self >> (32 - n)) }
}

public extension Data {
    /// base64url without padding (RFC 4648 §5).
    var base64URL: String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    init?(base64URL s: String) {
        var b64 = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        guard !b64.contains("=") else { return nil }
        b64 += String(repeating: "=", count: (4 - b64.count % 4) % 4)
        self.init(base64Encoded: b64)
    }
}
