import Foundation

/// BLAKE2b (RFC 7693) with key, salt and personalization — the subset libsodium uses for
/// `crypto_generichash` and `crypto_kdf_derive_from_key`. Single-shot; inputs here are tiny.
enum Blake2b {
    private static let iv: [UInt64] = [
        0x6a09e667f3bcc908, 0xbb67ae8584caa73b, 0x3c6ef372fe94f82b, 0xa54ff53a5f1d36f1,
        0x510e527fade682d1, 0x9b05688c2b3e6c1f, 0x1f83d9abfb41bd6b, 0x5be0cd19137e2179,
    ]

    private static let sigma: [[Int]] = [
        [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15],
        [14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3],
        [11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4],
        [7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8],
        [9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13],
        [2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9],
        [12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11],
        [13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10],
        [6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5],
        [10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0],
    ]

    static func hash(
        _ message: [UInt8],
        key: [UInt8] = [],
        outLen: Int,
        salt: [UInt8]? = nil,
        personal: [UInt8]? = nil
    ) -> [UInt8] {
        precondition((1...64).contains(outLen) && key.count <= 64)
        precondition(salt.map { $0.count == 16 } ?? true && personal.map { $0.count == 16 } ?? true)

        var h = iv
        h[0] ^= 0x0101_0000 ^ (UInt64(key.count) << 8) ^ UInt64(outLen)
        if let salt {
            h[4] ^= le64(salt, 0)
            h[5] ^= le64(salt, 8)
        }
        if let personal {
            h[6] ^= le64(personal, 0)
            h[7] ^= le64(personal, 8)
        }

        var data = [UInt8]()
        if !key.isEmpty {
            data = key + [UInt8](repeating: 0, count: 128 - key.count)
        }
        data += message

        let blocks = max(1, (data.count + 127) / 128)
        data += [UInt8](repeating: 0, count: blocks * 128 - data.count)
        let total = UInt64(key.isEmpty ? message.count : 128 + message.count)

        for b in 0..<blocks {
            let last = b == blocks - 1
            let counter = last ? total : UInt64((b + 1) * 128)
            compress(&h, data, offset: b * 128, counter: counter, last: last)
        }

        var out = [UInt8]()
        out.reserveCapacity(64)
        for word in h {
            for i in 0..<8 { out.append(UInt8(truncatingIfNeeded: word >> (8 * UInt64(i)))) }
        }
        return Array(out.prefix(outLen))
    }

    private static func compress(_ h: inout [UInt64], _ block: [UInt8], offset: Int, counter: UInt64, last: Bool) {
        var m = [UInt64](repeating: 0, count: 16)
        for i in 0..<16 { m[i] = le64(block, offset + i * 8) }
        var v = h + iv
        v[12] ^= counter
        if last { v[14] = ~v[14] }

        func g(_ a: Int, _ b: Int, _ c: Int, _ d: Int, _ x: UInt64, _ y: UInt64) {
            v[a] = v[a] &+ v[b] &+ x
            v[d] = (v[d] ^ v[a]).rotatedRight(32)
            v[c] = v[c] &+ v[d]
            v[b] = (v[b] ^ v[c]).rotatedRight(24)
            v[a] = v[a] &+ v[b] &+ y
            v[d] = (v[d] ^ v[a]).rotatedRight(16)
            v[c] = v[c] &+ v[d]
            v[b] = (v[b] ^ v[c]).rotatedRight(63)
        }

        for r in 0..<12 {
            let s = sigma[r % 10]
            g(0, 4, 8, 12, m[s[0]], m[s[1]])
            g(1, 5, 9, 13, m[s[2]], m[s[3]])
            g(2, 6, 10, 14, m[s[4]], m[s[5]])
            g(3, 7, 11, 15, m[s[6]], m[s[7]])
            g(0, 5, 10, 15, m[s[8]], m[s[9]])
            g(1, 6, 11, 12, m[s[10]], m[s[11]])
            g(2, 7, 8, 13, m[s[12]], m[s[13]])
            g(3, 4, 9, 14, m[s[14]], m[s[15]])
        }
        for i in 0..<8 { h[i] ^= v[i] ^ v[i + 8] }
    }

    private static func le64(_ b: [UInt8], _ o: Int) -> UInt64 {
        var x: UInt64 = 0
        for i in 0..<8 { x |= UInt64(b[o + i]) << (8 * UInt64(i)) }
        return x
    }
}

private extension UInt64 {
    func rotatedRight(_ n: UInt64) -> UInt64 { (self >> n) | (self << (64 - n)) }
}
