// ios/EvaApp/EdgeAI/Routing/GeohashManifestStore.swift
import Foundation
import SQLite3

/// Redis'teki `route:edge-fallback:{geohash5}` anahtarının, senkronizasyon
/// sırasında cihaza indirilmiş, SQLite tabanlı yerel kopyası. Tamamen
/// offline çalışır; ağ bağlantısı gerektirmez. Dosya sistemi seviyesinde
/// şifreleme, iOS Data Protection (NSFileProtectionComplete) ile sağlanır —
/// bu dosyanın oluşturulduğu dizin Info.plist / entitlements üzerinden
/// yapılandırılmalıdır.
final class GeohashManifestStore {
    private var db: OpaquePointer?
    private let dbPath: String

    init(dbPath: String) throws {
        self.dbPath = dbPath

        if sqlite3_open(dbPath, &db) != SQLITE_OK {
            let message = String(cString: sqlite3_errmsg(db))
            sqlite3_close(db)
            throw ManifestStoreError.openFailed(message)
        }

        try createTableIfNeeded()
    }

    deinit {
        sqlite3_close(db)
    }

    enum ManifestStoreError: LocalizedError {
        case openFailed(String)
        case queryFailed(String)
        case encodingFailed

        var errorDescription: String? {
            switch self {
            case .openFailed(let msg): return "Yerel veritabanı açılamadı: \(msg)"
            case .queryFailed(let msg): return "Sorgu başarısız: \(msg)"
            case .encodingFailed: return "Manifest verisi kodlanamadı."
            }
        }
    }

    private func createTableIfNeeded() throws {
        let sql = """
        CREATE TABLE IF NOT EXISTS geohash_manifest (
            geohash5 TEXT PRIMARY KEY,
            stations_json TEXT NOT NULL,
            synced_at INTEGER NOT NULL
        );
        """
        var statement: OpaquePointer?
        defer { sqlite3_finalize(statement) }

        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK else {
            throw ManifestStoreError.queryFailed(String(cString: sqlite3_errmsg(db)))
        }
        guard sqlite3_step(statement) == SQLITE_DONE else {
            throw ManifestStoreError.queryFailed(String(cString: sqlite3_errmsg(db)))
        }
    }

    func getStations(forRegion geohash5: String) throws -> [ChargeStopManifestEntry] {
        let sql = "SELECT stations_json FROM geohash_manifest WHERE geohash5 = ? LIMIT 1;"
        var statement: OpaquePointer?
        defer { sqlite3_finalize(statement) }

        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK else {
            throw ManifestStoreError.queryFailed(String(cString: sqlite3_errmsg(db)))
        }

        sqlite3_bind_text(statement, 1, geohash5, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self))

        guard sqlite3_step(statement) == SQLITE_ROW else {
            throw ManifestUnavailableException(geohash5: geohash5)
        }

        guard let cString = sqlite3_column_text(statement, 0) else {
            throw ManifestUnavailableException(geohash5: geohash5)
        }

        let jsonString = String(cString: cString)
        guard let jsonData = jsonString.data(using: .utf8) else {
            throw ManifestStoreError.encodingFailed
        }

        let decoder = JSONDecoder()
        return try decoder.decode([ChargeStopManifestEntry].self, from: jsonData)
    }

    func upsertRegion(_ geohash5: String, stations: [ChargeStopManifestEntry]) throws {
        let encoder = JSONEncoder()
        let jsonData = try encoder.encode(stations)
        guard let jsonString = String(data: jsonData, encoding: .utf8) else {
            throw ManifestStoreError.encodingFailed
        }

        let sql = """
        INSERT INTO geohash_manifest (geohash5, stations_json, synced_at)
        VALUES (?, ?, ?)
        ON CONFLICT(geohash5) DO UPDATE SET
            stations_json = excluded.stations_json,
            synced_at = excluded.synced_at;
        """
        var statement: OpaquePointer?
        defer { sqlite3_finalize(statement) }

        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK else {
            throw ManifestStoreError.queryFailed(String(cString: sqlite3_errmsg(db)))
        }

        let destructor = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        sqlite3_bind_text(statement, 1, geohash5, -1, destructor)
        sqlite3_bind_text(statement, 2, jsonString, -1, destructor)
        sqlite3_bind_int64(statement, 3, Int64(Date().timeIntervalSince1970 * 1000))

        guard sqlite3_step(statement) == SQLITE_DONE else {
            throw ManifestStoreError.queryFailed(String(cString: sqlite3_errmsg(db)))
        }
    }

    func close() {
        sqlite3_close(db)
        db = nil
    }
}
