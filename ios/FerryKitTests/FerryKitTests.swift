import Foundation
import Testing
@testable import FerryKit

private let vectors: [String: Any] = {
    let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../docs/test-vectors/v1.json")
    return try! JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
}()

private func hex(_ s: String) -> Data {
    Data(stride(from: 0, to: s.count, by: 2).map { UInt8(s.dropFirst($0).prefix(2), radix: 16)! })
}

private let umk = hex(vectors["umk_hex"] as! String)

private let v2: [String: Any] = {
    let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../docs/test-vectors/v2.json")
    return try! JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
}()

/// Node keys and pairing MACs (§3.7, §5.6).
struct NodeKeyVectorTests {
    let node = v2["node"] as! [String: Any]
    let account = v2["account"] as! [String: String]

    @Test func accountKeyPair() {
        let pair = FerryCrypto.accountKeyPair(umk)
        #expect(pair.secretKey == hex(account["secretKey_hex"]!))
        #expect(pair.publicKey.base64URL == account["publicKey"]!)
    }

    @Test func rootOnBothSides() throws {
        let pair = FerryCrypto.nodeKeyPair(hex(node["secretKey_hex"] as! String))
        #expect(pair.publicKey.base64URL == node["publicKey"] as! String)
        let root = try FerryCrypto.nodeRootForNode(nodeSk: pair.secretKey, acctPk: Data(base64URL: account["publicKey"]!)!)
        #expect(root == hex(node["root_hex"] as! String))
        #expect(try FerryCrypto.nodeRootForReceiver(umk: umk, nodePk: pair.publicKey) == root)
        #expect(FerryCrypto.nodeKeyId(root) == node["keyId"] as! String)
        let sk = node["subkeys_hex"] as! [String: String]
        #expect(FerryCrypto.deriveSubkey(root, .event) == hex(sk["event"]!))
        #expect(FerryCrypto.deriveSubkey(root, .command) == hex(sk["command"]!))
    }

    @Test func events() throws {
        let root = hex(node["root_hex"] as! String)
        for e in v2["events"] as! [[String: Any]] {
            let (u, d, id) = (e["userId"] as! String, e["sourceDeviceId"] as! String, e["eventId"] as! String)
            let ad = FerryCrypto.eventAd(userId: u, sourceDeviceId: d, eventId: id)
            let plaintext = Data((e["plaintext"] as! String).utf8)
            let (_, ct) = FerryCrypto.seal(key: FerryCrypto.deriveSubkey(root, .event), plain: plaintext, ad: ad, nonce: Data(base64URL: e["nonce"] as! String)!)
            #expect(ct.base64URL == e["ct"] as! String, "\(e["name"]!)")
            let sealed = FerryCrypto.Sealed(keyId: node["keyId"] as! String, nonce: e["nonce"] as! String, ct: e["ct"] as! String)
            #expect(try FerryCrypto.decryptNodeEvent(root: root, userId: u, sourceDeviceId: d, eventId: id, sealed: sealed) == plaintext)
        }
    }

    @Test func commands() {
        let root = hex(node["root_hex"] as! String)
        for c in v2["commands"] as! [[String: String]] {
            let ad = FerryCrypto.commandAd(userId: c["userId"]!, nodeId: c["nodeId"]!, commandId: c["commandId"]!)
            #expect(String(decoding: ad, as: UTF8.self) == c["ad"]!)
            let (_, ct) = FerryCrypto.seal(key: FerryCrypto.deriveSubkey(root, .command), plain: Data(c["plaintext"]!.utf8), ad: ad, nonce: Data(base64URL: c["nonce"]!)!)
            #expect(ct.base64URL == c["ct"]!, "\(c["name"]!)")
        }
    }

    @Test func lowOrderKeysAreRejected() {
        let pair = FerryCrypto.generateNodeKeyPair()
        for pk in (v2["lowOrderPublicKeys"] as! [String]).map({ Data(base64URL: $0)! }) {
            #expect(throws: FerryCrypto.CryptoError.weakPublicKey) {
                try FerryCrypto.nodeRoot(secretKey: pair.secretKey, peerPublicKey: pk, acctPk: pk, nodePk: pair.publicKey)
            }
        }
    }

    @Test func pairingMacs() {
        for m in v2["pairingMacs"] as! [[String: String]] {
            let secret = hex(m["secret_hex"]!)
            let pk = Data(base64URL: m["publicKey"]!)!
            let mac = FerryCrypto.pairingMac(secret: secret, userId: m["userId"]!, deviceId: m["deviceId"]!, publicKey: pk)
            #expect(mac.base64URL == m["mac"]!, "\(m["name"]!)")
            #expect(FerryCrypto.verifyPairingMac(secret: secret, userId: m["userId"]!, deviceId: m["deviceId"]!, publicKey: pk, mac: mac))
            #expect(!FerryCrypto.verifyPairingMac(secret: secret, userId: m["userId"]!, deviceId: "other", publicKey: pk, mac: mac))
        }
    }
}

struct CryptoVectorTests {
    @Test func keyId() {
        #expect(FerryCrypto.keyId(umk) == vectors["keyId"] as! String)
    }

    @Test func subkeys() {
        let sk = vectors["subkeys_hex"] as! [String: String]
        #expect(FerryCrypto.deriveSubkey(umk, .event) == hex(sk["event"]!))
        #expect(FerryCrypto.deriveSubkey(umk, .profile) == hex(sk["profile"]!))
        #expect(FerryCrypto.deriveSubkey(umk, .recovery) == hex(sk["recovery"]!))
    }

    @Test func padding() {
        for p in vectors["padding"] as! [[String: Int]] {
            #expect(FerryCrypto.paddedLength(p["plaintextBytes"]!) == p["paddedLength"]!)
        }
    }

    @Test func events() throws {
        for e in vectors["events"] as! [[String: Any]] {
            let (u, d, id) = (e["userId"] as! String, e["sourceDeviceId"] as! String, e["eventId"] as! String)
            let ad = FerryCrypto.eventAd(userId: u, sourceDeviceId: d, eventId: id)
            #expect(String(decoding: ad, as: UTF8.self) == e["ad"] as! String)

            let plaintext = Data((e["plaintext"] as! String).utf8)
            let (_, ct) = FerryCrypto.seal(key: FerryCrypto.deriveSubkey(umk, .event), plain: plaintext, ad: ad, nonce: Data(base64URL: e["nonce"] as! String)!)
            #expect(ct.base64URL == e["ct"] as! String, "\(e["name"]!)")

            let sealed = FerryCrypto.Sealed(keyId: FerryCrypto.keyId(umk), nonce: e["nonce"] as! String, ct: e["ct"] as! String)
            let opened = try FerryCrypto.decryptEvent(umks: [sealed.keyId: umk], userId: u, sourceDeviceId: d, eventId: id, sealed: sealed)
            #expect(opened == plaintext)
        }
    }

    @Test func profile() throws {
        let p = vectors["profile"] as! [String: Any]
        let sealed = FerryCrypto.Sealed(keyId: FerryCrypto.keyId(umk), nonce: p["nonce"] as! String, ct: p["ct"] as! String)
        let plain = try FerryCrypto.decryptProfile(umks: [sealed.keyId: umk], userId: p["userId"] as! String, deviceId: p["deviceId"] as! String, version: Int64(p["version"] as! Int), sealed: sealed)
        #expect(String(decoding: plain, as: UTF8.self) == p["plaintext"] as! String)
        #expect(try JSONDecoder().decode(DeviceProfile.self, from: plain).sims["1"] == "Travel SIM")
    }

    @Test func wrongAdFails() {
        let e = (vectors["events"] as! [[String: Any]])[0]
        let sealed = FerryCrypto.Sealed(keyId: FerryCrypto.keyId(umk), nonce: e["nonce"] as! String, ct: e["ct"] as! String)
        #expect(throws: FerryCrypto.CryptoError.authenticationFailed) {
            try FerryCrypto.decryptEvent(umks: [sealed.keyId: umk], userId: e["userId"] as! String, sourceDeviceId: "other", eventId: e["eventId"] as! String, sealed: sealed)
        }
    }
}

struct PairingLinkTests {
    @Test func roundTrip() throws {
        let link = PairingLink(server: "https://ferry.example.com:8443/base", userId: "u1", code: "abc_-123", umk: umk)
        let parsed = try #require(PairingLink.parse(link.uri))
        #expect(parsed.server == link.server)
        #expect(parsed.userId == "u1")
        #expect(parsed.code == "abc_-123")
        #expect(parsed.umk == umk)
        #expect(link.uri.contains("s=https%3A%2F%2F"))
    }

    @Test func plainHttpOnlyOnLocalNetwork() {
        for ok in ["https://ferry.example.com", "http://localhost:3999", "http://ferry-dev.local:3999", "http://192.168.1.2:3999", "http://10.0.0.5", "http://172.20.1.1"] {
            #expect(Ferry.isAllowedServer(URL(string: ok)!), "\(ok)")
        }
        for bad in ["http://ferry.example.com", "http://8.8.8.8", "http://172.32.0.1", "ftp://ferry-dev.local", "http://192.168.1.2.evil.com"] {
            #expect(!Ferry.isAllowedServer(URL(string: bad)!), "\(bad)")
        }
    }

    @Test func normalizesTypedServers() {
        #expect(Ferry.normalizeServer("ferry.example.com")?.absoluteString == "https://ferry.example.com")
        #expect(Ferry.normalizeServer("  https://ferry.example.com/ ")?.absoluteString == "https://ferry.example.com")
        #expect(Ferry.normalizeServer("ferry-dev.local:3999")?.absoluteString == "http://ferry-dev.local:3999")
        #expect(Ferry.normalizeServer("192.168.1.2:3999")?.absoluteString == "http://192.168.1.2:3999")
        #expect(Ferry.normalizeServer("http://ferry.example.com") == nil)
        #expect(Ferry.normalizeServer("not a server") == nil)
        #expect(Ferry.normalizeServer("") == nil)
    }

    @Test func rejectsPlainHttpAndBadKeys() {
        #expect(PairingLink.parse("ferry://pair?v=1&s=http%3A%2F%2Fevil.com&u=u&c=c&k=\(umk.base64URL)") == nil)
        #expect(PairingLink.parse("ferry://pair?v=1&s=https%3A%2F%2Fa.com&u=u&c=c&k=AAAA") == nil)
        #expect(PairingLink.parse("ferry://pair?v=2&s=https%3A%2F%2Fa.com&u=u&c=c&k=\(umk.base64URL)") == nil)
    }
}

struct ScannedCodeTests {
    @Test func introRoundTrip() throws {
        let link = IntroLink(server: "https://ferry.example.com", userId: "u1", code: "c_-1", acctPk: Data(repeating: 7, count: 32), secret: Data(repeating: 9, count: 32))
        guard case .intro(let parsed) = try #require(ScannedCode.parse(link.uri)) else { Issue.record("expected intro"); return }
        #expect(parsed.acctPk == link.acctPk)
        #expect(parsed.secret == link.secret)
        #expect(parsed.server == "https://ferry.example.com")
    }

    @Test func nodeCodeFromAndroid() throws {
        let pk = Data(repeating: 1, count: 32).base64URL, x = Data(repeating: 2, count: 32).base64URL
        let uri = "ferry://node?v=2&s=https%3A%2F%2Fa.com&u=u1&c=c1&d=d1&p=\(pk)&x=\(x)"
        guard case .node(let link) = try #require(ScannedCode.parse(uri)) else { Issue.record("expected node"); return }
        #expect(link.deviceId == "d1")
        #expect(link.nodePk == Data(repeating: 1, count: 32))
        // Short keys, a missing device id or plain http are not codes.
        #expect(ScannedCode.parse("ferry://node?v=2&s=https%3A%2F%2Fa.com&u=u1&c=c1&d=d1&p=AAAA&x=\(x)") == nil)
        #expect(ScannedCode.parse("ferry://node?v=2&s=https%3A%2F%2Fa.com&u=u1&c=c1&p=\(pk)&x=\(x)") == nil)
        #expect(ScannedCode.parse("ferry://node?v=2&s=http%3A%2F%2Fa.com&u=u1&c=c1&d=d1&p=\(pk)&x=\(x)") == nil)
    }

    @Test func receiverCodesStillParse() {
        let link = PairingLink(server: "https://a.com", userId: "u", code: "c", umk: umk)
        guard case .receiver = ScannedCode.parse(link.uri) else { Issue.record("expected receiver"); return }
    }
}

struct ReceiverTests {
    let user = "0192c2a0-0000-7000-8000-000000000001"
    let source = "0192c2a0-0000-7000-8000-0000000000a1"
    let inbox = Inbox(url: FileManager.default.temporaryDirectory.appendingPathComponent("inbox-\(UUID().uuidString).sqlite"))

    var receiver: Receiver {
        Receiver(account: Account(server: "https://x", userId: user, deviceId: "me", token: "t", umk: umk), inbox: inbox)
    }

    private func stored(_ json: String, id: String = UUID().uuidString.lowercased(), seq: Int = 1) -> StoredEvent {
        let s = FerryCrypto.encryptEvent(umk: umk, userId: user, sourceDeviceId: source, eventId: id, plaintext: Data(json.utf8))
        return StoredEvent(id: id, keyId: s.keyId, nonce: s.nonce, ct: s.ct, kind: "alert", seq: String(seq), sourceDeviceId: source, receivedAt: 1)
    }

    @Test func smsIsFiledOnceAndDeduplicated() throws {
        let e = stored(#"{"t":"sms","occurredAt":10,"sim":{"slot":1,"subscriptionId":2},"from":"+49 151 1234","body":"Your code is 123456","contactName":"Bank"}"#)
        guard case .new(let m, let summary) = try receiver.ingest(e) else { Issue.record("expected new"); return }
        #expect(summary.title == "Bank")
        #expect(summary.subtitle == "SIM 2 · Android phone")
        #expect(m.threadKey == "p:\(source):+491511234")
        guard case .duplicate = try receiver.ingest(e) else { Issue.record("expected duplicate"); return }
        #expect(try inbox.threads().count == 1)
        #expect(try inbox.unreadCount() == 1)
    }

    @Test func callStatesMerge() throws {
        _ = try receiver.ingest(stored(#"{"t":"call","occurredAt":10,"sim":null,"from":"+1","state":"ringing","callKey":"k1"}"#))
        _ = try receiver.ingest(stored(#"{"t":"call","occurredAt":20,"sim":null,"from":"+1","state":"missed","callKey":"k1"}"#))
        let messages = try inbox.messages(thread: "p:\(source):+1")
        #expect(messages.count == 1)
        #expect(messages.first?.body == "Missed call")
    }

    @Test func statusIsQuiet() throws {
        let e = stored(#"{"t":"status","occurredAt":5,"battery":81,"charging":false,"sims":[],"permissions":{"sms":true},"appVersion":"1.0.0"}"#)
        guard case .quiet = try receiver.ingest(e) else { Issue.record("expected quiet"); return }
        #expect(try inbox.statuses()[source]?.battery == 81)
        #expect(try inbox.threads().isEmpty)
    }

    @Test func sentAndImportedTextsAreFiledQuietly() throws {
        let sent = stored(#"{"t":"sms","occurredAt":30,"sim":null,"from":"+1","body":"On my way","direction":"out"}"#)
        guard case .filed(let m) = try receiver.ingest(sent) else { Issue.record("expected filed"); return }
        #expect(m.isOutgoing)
        let old = stored(#"{"t":"sms","occurredAt":5,"sim":null,"from":"+1","body":"Old news","imported":true}"#)
        guard case .filed = try receiver.ingest(old) else { Issue.record("expected filed"); return }
        #expect(try inbox.unreadCount() == 0)
        // Imported history arrives late but sorts by when it happened (§4.2).
        #expect(try inbox.messages(thread: "p:\(source):+1").map(\.body) == ["Old news", "On my way"])
    }

    @Test func inboxIsEncryptedOnDisk() throws {
        _ = try receiver.ingest(stored(#"{"t":"sms","occurredAt":1,"sim":null,"from":"+1","body":"secret-marker-7731"}"#))
        let bytes = try Data(contentsOf: inbox.url)
        #expect(bytes.range(of: Data("secret-marker-7731".utf8)) == nil)
        // The 32-byte plaintext header keeps it recognisable as SQLite to iOS (§9.1).
        #expect(bytes.prefix(15) == Data("SQLite format 3".utf8))
    }

    @Test func unreadableInboxIsReplaced() throws {
        try Data(repeating: 0x42, count: 4096).write(to: inbox.url)
        _ = try receiver.ingest(stored(#"{"t":"sms","occurredAt":1,"sim":null,"from":"+1","body":"hi"}"#))
        #expect(try inbox.threads().count == 1)
    }

    @Test func nodeEventsNeedATrustedProfile() throws {
        let node = FerryCrypto.generateNodeKeyPair()
        let root = try FerryCrypto.nodeRootForReceiver(umk: umk, nodePk: node.publicKey)
        let id = UUID().uuidString.lowercased()
        let s = FerryCrypto.encryptNodeEvent(root: root, userId: user, sourceDeviceId: source, eventId: id, plaintext: Data(#"{"t":"sms","occurredAt":1,"sim":null,"from":"+1","body":"hi"}"#.utf8))
        let e = StoredEvent(id: id, keyId: s.keyId, nonce: s.nonce, ct: s.ct, kind: "alert", seq: "1", sourceDeviceId: source, receivedAt: 1)
        #expect(throws: UnknownNodeError.self) { try receiver.ingest(e) }

        let profile = DeviceProfile(name: "Old phone", nodePk: node.publicKey.base64URL)
        try inbox.saveDevices([DeviceRecord(id: source, roles: ["source"], platform: "android", appVersion: nil, createdAt: 0, lastSeenAt: 0, profileVersion: 1, profile: profile)])
        guard case .new(_, let summary) = try receiver.ingest(e) else { Issue.record("expected new"); return }
        #expect(summary.subtitle == "Old phone")
    }

    @Test func removedNodesStayRemoved() throws {
        let node = FerryCrypto.generateNodeKeyPair()
        let root = try FerryCrypto.nodeRootForReceiver(umk: umk, nodePk: node.publicKey)
        try inbox.saveDevices([DeviceRecord(id: source, roles: ["source"], platform: "android", appVersion: nil, createdAt: 0, lastSeenAt: 0, profileVersion: 1, profile: DeviceProfile(name: "", nodePk: node.publicKey.base64URL))])
        inbox.removedNodeKeys = [node.publicKey.base64URL]
        let id = UUID().uuidString.lowercased()
        let s = FerryCrypto.encryptNodeEvent(root: root, userId: user, sourceDeviceId: source, eventId: id, plaintext: Data(#"{"t":"sms","occurredAt":1,"sim":null,"from":"+1","body":"hi"}"#.utf8))
        let e = StoredEvent(id: id, keyId: s.keyId, nonce: s.nonce, ct: s.ct, kind: "alert", seq: "1", sourceDeviceId: source, receivedAt: 1)
        #expect(throws: FerryCrypto.CryptoError.self) { try receiver.ingest(e) }
    }

    @Test func introductionsFromTrustedNodesWaitForTheApp() throws {
        let node = FerryCrypto.generateNodeKeyPair()
        let root = try FerryCrypto.nodeRootForReceiver(umk: umk, nodePk: node.publicKey)
        try inbox.saveDevices([DeviceRecord(id: source, roles: ["source"], platform: "android", appVersion: nil, createdAt: 0, lastSeenAt: 0, profileVersion: 1, profile: DeviceProfile(name: "Old phone", nodePk: node.publicKey.base64URL))])
        let id = UUID().uuidString.lowercased()
        let json = #"{"t":"introduce","occurredAt":1,"deviceId":"new-node","nodePk":"AAAA"}"#
        let s = FerryCrypto.encryptNodeEvent(root: root, userId: user, sourceDeviceId: source, eventId: id, plaintext: Data(json.utf8))
        let e = StoredEvent(id: id, keyId: s.keyId, nonce: s.nonce, ct: s.ct, kind: "alert", seq: "1", sourceDeviceId: source, receivedAt: 1)
        guard case .introduced(let deviceId, let summary) = try receiver.ingest(e) else { Issue.record("expected introduced"); return }
        #expect(deviceId == "new-node")
        #expect(summary.title == "Old phone added a phone")
        #expect(try inbox.introductions().map(\.by) == [source])
        #expect(try inbox.threads().isEmpty)
    }

    @Test func sentTextsFollowTheNodesReports() throws {
        let node = FerryCrypto.generateNodeKeyPair()
        let root = try FerryCrypto.nodeRootForReceiver(umk: umk, nodePk: node.publicKey)
        try inbox.saveDevices([DeviceRecord(id: source, roles: ["source"], platform: "android", appVersion: nil, createdAt: 0, lastSeenAt: 0, profileVersion: 1, profile: DeviceProfile(name: "", nodePk: node.publicKey.base64URL))])
        func event(_ json: String) -> StoredEvent {
            let id = UUID().uuidString.lowercased()
            let s = FerryCrypto.encryptNodeEvent(root: root, userId: user, sourceDeviceId: source, eventId: id, plaintext: Data(json.utf8))
            return StoredEvent(id: id, keyId: s.keyId, nonce: s.nonce, ct: s.ct, kind: "silent", seq: "1", sourceDeviceId: source, receivedAt: 1)
        }
        let thread = "p:\(source):+1"
        _ = try receiver.ingest(event(#"{"t":"sms","occurredAt":20,"sim":null,"from":"+1","body":"On my way","direction":"out","commandId":"c1"}"#))
        _ = try receiver.ingest(event(#"{"t":"sms_status","occurredAt":22,"commandId":"c1","status":"delivered"}"#))
        _ = try receiver.ingest(event(#"{"t":"sms_status","occurredAt":21,"commandId":"c1","status":"sent"}"#))
        let messages = try inbox.messages(thread: thread)
        #expect(messages.count == 1)
        #expect(messages.first?.id == "cmd:c1")
        #expect(messages.first?.status == "delivered")
        #expect(messages.first?.isOutgoing == true)
    }

    @Test func unknownTypeShowsPlaceholder() throws {
        guard case .new(_, let summary) = try receiver.ingest(stored(#"{"t":"fax","occurredAt":1}"#)) else { Issue.record("expected new"); return }
        #expect(summary.title == "New type of message")
    }
}

/// Same cases as android/app/src/test/.../TextToolsTest.kt, so both platforms search alike.
struct TextToolsTests {
    private func found(_ text: String, _ query: String) -> [String] {
        TextTools.matches(in: text, of: query).map { String(text[$0]) }
    }

    @Test func ignoresCaseAndAccents() {
        #expect(found("Your Code is 123456", "code") == ["Code"])
        #expect(found("Meet at Café Nero", "cafe") == ["Café"])
        #expect(TextTools.normalize("HÉLLO") == "hello")
    }

    @Test func ignoresArabicHarakatAndLetterVariants() {
        #expect(TextTools.normalize("أَهْلاً") == "اهلا")
        #expect(found("مرحبا مُحَمَّد", "محمد") == ["مُحَمَّد"])
        #expect(found("في المدرسة", "مدرسه") == ["مدرسة"])
        #expect(found("ذهبت إلى البنك", "الي") == ["إلى"])
        #expect(found("رسالة من البنك", "البنك") == ["البنك"])
        #expect(found("هذا كتـــاب", "كتاب") == ["كتـــاب"])
    }

    @Test func arabicDigitsMatchWesternDigits() {
        #expect(found("رمزك ١٢٣٤٥٦", "1234") == ["١٢٣٤"])
        #expect(found("کد ۹۸۷", "987") == ["۹۸۷"])
    }

    @Test func findsEveryOccurrenceAcrossEmoji() {
        #expect(found("hi 😀 there, there", "there") == ["there", "there"])
    }

    @Test func blankQueryMatchesNothing() {
        #expect(TextTools.matches(in: "anything", of: "  ").isEmpty)
        #expect(TextTools.matches(in: "anything", of: "\u{064E}").isEmpty)
    }

    @Test func directionFollowsFirstStrongCharacter() {
        #expect(TextTools.isRTL("مرحبا Hello"))
        #expect(TextTools.isRTL("123: مرحبا"))
        #expect(TextTools.isRTL("שלום"))
        #expect(!TextTools.isRTL("Hello مرحبا"))
        #expect(!TextTools.isRTL("123456"))
        #expect(!TextTools.isRTL(""))
    }
}
