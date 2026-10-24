import Foundation
import SQLCipher

private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// Minimal SQLCipher wrapper. Connections are short-lived on purpose: holding a lock on a file in
/// the App Group container while suspended gets the process killed (0xdead10cc).
final class SQLite {
    enum Value {
        case int(Int64), text(String), blob(Data), null
    }

    struct SQLiteError: Error, CustomStringConvertible {
        let description: String
        var code: Int32 = SQLITE_ERROR

        /// The file isn't a database under this key: unencrypted, or encrypted with another key.
        var isNotADatabase: Bool { code == SQLITE_NOTADB }
    }

    private var db: OpaquePointer?

    /// Opens (or creates) the database at `path`, encrypted with `key` = 32-byte key ‖ 16-byte salt.
    init(path: String, key: Data) throws {
        precondition(key.count == 48, "key must be 32-byte key + 16-byte salt")
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        guard sqlite3_open_v2(path, &db, flags, nil) == SQLITE_OK else {
            defer { sqlite3_close(db) }
            throw SQLiteError(description: String(cString: sqlite3_errmsg(db)))
        }
        sqlite3_busy_timeout(db, 3000)
        // A raw key skips SQLCipher's PBKDF2, which would cost ~100 ms on every short-lived connection.
        // The 32-byte plaintext header keeps the file recognisable as SQLite, so iOS doesn't kill
        // the app or extension for holding its lock in the shared container (0xdead10cc); the salt
        // that normally lives in that header comes from the key instead.
        let hex = key.map { String(format: "%02x", $0) }.joined()
        try exec("PRAGMA key = \"x'\(hex)'\"; PRAGMA cipher_plaintext_header_size = 32;")
        // The key is only checked on first read.
        let rc = sqlite3_exec(db, "SELECT count(*) FROM sqlite_master", nil, nil, nil)
        guard rc == SQLITE_OK else { throw SQLiteError(description: String(cString: sqlite3_errmsg(db)), code: rc) }
    }

    deinit { sqlite3_close(db) }

    func exec(_ sql: String) throws {
        guard sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK else { throw error() }
    }

    /// Runs a statement; returns the number of changed rows.
    @discardableResult
    func run(_ sql: String, _ args: [Value] = []) throws -> Int {
        let stmt = try prepare(sql, args)
        defer { sqlite3_finalize(stmt) }
        let rc = sqlite3_step(stmt)
        guard rc == SQLITE_DONE || rc == SQLITE_ROW else { throw error() }
        return Int(sqlite3_changes(db))
    }

    func query(_ sql: String, _ args: [Value] = []) throws -> [Row] {
        let stmt = try prepare(sql, args)
        defer { sqlite3_finalize(stmt) }
        var rows = [Row]()
        while true {
            let rc = sqlite3_step(stmt)
            if rc == SQLITE_DONE { break }
            guard rc == SQLITE_ROW else { throw error() }
            rows.append(Row(stmt: stmt!))
        }
        return rows
    }

    func transaction<T>(_ body: () throws -> T) throws -> T {
        try exec("BEGIN IMMEDIATE")
        do {
            let result = try body()
            try exec("COMMIT")
            return result
        } catch {
            try? exec("ROLLBACK")
            throw error
        }
    }

    private func prepare(_ sql: String, _ args: [Value]) throws -> OpaquePointer? {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { throw error() }
        for (i, arg) in args.enumerated() {
            let idx = Int32(i + 1)
            switch arg {
            case .int(let v): sqlite3_bind_int64(stmt, idx, v)
            case .text(let v): sqlite3_bind_text(stmt, idx, v, -1, SQLITE_TRANSIENT)
            case .blob(let v): _ = v.withUnsafeBytes { sqlite3_bind_blob(stmt, idx, $0.baseAddress, Int32(v.count), SQLITE_TRANSIENT) }
            case .null: sqlite3_bind_null(stmt, idx)
            }
        }
        return stmt
    }

    private func error() -> SQLiteError { SQLiteError(description: String(cString: sqlite3_errmsg(db))) }

    struct Row {
        private var values: [String: Value] = [:]

        fileprivate init(stmt: OpaquePointer) {
            for i in 0..<sqlite3_column_count(stmt) {
                let name = String(cString: sqlite3_column_name(stmt, i))
                switch sqlite3_column_type(stmt, i) {
                case SQLITE_INTEGER: values[name] = .int(sqlite3_column_int64(stmt, i))
                case SQLITE_TEXT: values[name] = .text(String(cString: sqlite3_column_text(stmt, i)))
                case SQLITE_BLOB:
                    let n = Int(sqlite3_column_bytes(stmt, i))
                    values[name] = .blob(n == 0 ? Data() : Data(bytes: sqlite3_column_blob(stmt, i), count: n))
                default: values[name] = .null
                }
            }
        }

        func int(_ k: String) -> Int64? { if case .int(let v) = values[k] { v } else { nil } }
        func text(_ k: String) -> String? { if case .text(let v) = values[k] { v } else { nil } }
        func blob(_ k: String) -> Data? { if case .blob(let v) = values[k] { v } else { nil } }
    }
}

extension SQLite.Value {
    static func text(_ v: String?) -> Self { v.map { .text($0) } ?? .null }
    static func int(_ v: Int?) -> Self { v.map { .int(Int64($0)) } ?? .null }
}
