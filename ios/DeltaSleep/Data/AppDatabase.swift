import Combine
import Foundation
import SQLite3

/// On-device SQLite store. Same tables and columns as the Android Room DB
/// (v5, docs/schema.md) so exports and schema docs cover both platforms.
///
/// All access is serialised on one private queue; calls are synchronous and
/// cheap (a night is ~1,000 epoch rows). Writers publish [didChange] on the
/// main queue so screens can reload.
///
/// `secure_delete` is on for the life of the connection: SQLite overwrites
/// deleted content with zeros, so "Delete all data" and retention purges
/// overwrite rows before the file shrinks (PRD: nuke must overwrite first).
final class AppDatabase: @unchecked Sendable {
    static let shared = AppDatabase()

    /// Fires on the main queue after any write.
    let didChange = PassthroughSubject<Void, Never>()

    private var db: OpaquePointer?
    private let queue = DispatchQueue(label: "deltasleep.db")
    private static let schemaVersion: Int32 = 5

    init(path: String? = nil) {
        let url = path.map { URL(fileURLWithPath: $0) } ?? Self.defaultURL()
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        if sqlite3_open_v2(url.path, &db, flags, nil) != SQLITE_OK {
            fatalError("Cannot open database at \(url.path)")
        }
        // Readable after first unlock: tracking writes epochs all night with
        // the phone locked.
        try? FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
            ofItemAtPath: url.path
        )
        queue.sync {
            exec("PRAGMA foreign_keys = ON")
            exec("PRAGMA secure_delete = ON")
            exec("PRAGMA journal_mode = WAL")
            migrate()
        }
    }

    private static func defaultURL() -> URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("deltasleep.db")
    }

    // MARK: - Schema

    private func migrate() {
        var version: Int32 = 0
        query("PRAGMA user_version") { version = sqlite3_column_int($0, 0) }
        guard version < Self.schemaVersion else { return }
        // Fresh installs only: the iOS app starts at v5, so there is no
        // older on-device schema to migrate from. Future bumps add steps here.
        exec("""
            CREATE TABLE IF NOT EXISTS sleep_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                startTime INTEGER NOT NULL,
                endTime INTEGER,
                feelRating INTEGER
            )
            """)
        exec("""
            CREATE TABLE IF NOT EXISTS sleep_epochs (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                sessionId INTEGER NOT NULL,
                timestamp INTEGER NOT NULL,
                phase INTEGER NOT NULL,
                hasSnore INTEGER NOT NULL,
                rmsEnergy REAL NOT NULL,
                breathingMarginDb REAL NOT NULL DEFAULT 0,
                breathingPresentFraction REAL NOT NULL DEFAULT 0,
                breathPeriodS REAL,
                externalAudioFraction REAL NOT NULL DEFAULT 0,
                playbackActive INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(sessionId) REFERENCES sleep_sessions(id) ON DELETE CASCADE
            )
            """)
        exec("CREATE INDEX IF NOT EXISTS index_sleep_epochs_sessionId ON sleep_epochs (sessionId)")
        exec("""
            CREATE TABLE IF NOT EXISTS acoustic_event (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                sessionId INTEGER NOT NULL,
                type INTEGER NOT NULL,
                startUtc INTEGER NOT NULL,
                durationMs INTEGER NOT NULL,
                confidence REAL NOT NULL,
                peakDbOverFloor REAL NOT NULL,
                envelopeReductionPct REAL NOT NULL,
                terminatedByGasp INTEGER NOT NULL,
                meanDbOverFloor REAL NOT NULL,
                FOREIGN KEY(sessionId) REFERENCES sleep_sessions(id) ON DELETE CASCADE
            )
            """)
        exec("CREATE INDEX IF NOT EXISTS index_acoustic_event_sessionId ON acoustic_event (sessionId)")
        exec("""
            CREATE TABLE IF NOT EXISTS night_summary (
                sessionId INTEGER PRIMARY KEY NOT NULL,
                totalSleepTimeMin INTEGER NOT NULL,
                reiA REAL NOT NULL,
                apneaLikeCount INTEGER NOT NULL,
                hypopneaLikeCount INTEGER NOT NULL,
                longestEventS REAL NOT NULL,
                snorePctOfSleep REAL NOT NULL,
                meanSnoreDbOverFloor REAL NOT NULL,
                signalQuality INTEGER NOT NULL,
                acousticBand INTEGER NOT NULL,
                FOREIGN KEY(sessionId) REFERENCES sleep_sessions(id) ON DELETE CASCADE
            )
            """)
        exec("""
            CREATE TABLE IF NOT EXISTS questionnaire_result (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                dateUtc INTEGER NOT NULL,
                snoring INTEGER NOT NULL,
                tiredness INTEGER NOT NULL,
                observedApnea INTEGER NOT NULL,
                highPressure INTEGER NOT NULL,
                bmiOver35 INTEGER NOT NULL,
                ageOver50 INTEGER NOT NULL,
                neckOver40cm INTEGER NOT NULL,
                maleGender INTEGER NOT NULL,
                score INTEGER NOT NULL
            )
            """)
        exec("PRAGMA user_version = \(Self.schemaVersion)")
    }

    // MARK: - Sessions

    func insertSession(startTime: Int64) -> Int64 {
        write {
            run("INSERT INTO sleep_sessions (startTime) VALUES (?)", [.int(startTime)])
            return sqlite3_last_insert_rowid(db)
        }
    }

    func updateSession(_ s: SleepSession) {
        write {
            run("UPDATE sleep_sessions SET startTime = ?, endTime = ?, feelRating = ? WHERE id = ?",
                [.int(s.startTime), .optInt(s.endTime), .optInt(s.feelRating.map(Int64.init)), .int(s.id)])
        }
    }

    func session(id: Int64) -> SleepSession? {
        read { sessions("SELECT * FROM sleep_sessions WHERE id = ?", [.int(id)]).first }
    }

    /// All sessions, newest first.
    func allSessions() -> [SleepSession] {
        read { sessions("SELECT * FROM sleep_sessions ORDER BY startTime DESC", []) }
    }

    /// Completed sessions starting at/after [sinceMs], oldest first (D-1).
    func completedSessions(since sinceMs: Int64) -> [SleepSession] {
        read {
            sessions("SELECT * FROM sleep_sessions WHERE endTime IS NOT NULL AND startTime >= ? ORDER BY startTime ASC",
                     [.int(sinceMs)])
        }
    }

    /// Sessions left open by a crash or a force-quit (endTime NULL).
    func openSessions() -> [SleepSession] {
        read { sessions("SELECT * FROM sleep_sessions WHERE endTime IS NULL", []) }
    }

    func deleteSessionsEnded(before cutoffMs: Int64) -> Int {
        write {
            run("DELETE FROM sleep_sessions WHERE endTime IS NOT NULL AND endTime < ?", [.int(cutoffMs)])
            return Int(sqlite3_changes(db))
        }
    }

    func countSessionsEnded(before cutoffMs: Int64) -> Int {
        read {
            var n = 0
            query("SELECT COUNT(*) FROM sleep_sessions WHERE endTime IS NOT NULL AND endTime < ?", [.int(cutoffMs)]) {
                n = Int(sqlite3_column_int64($0, 0))
            }
            return n
        }
    }

    // MARK: - Epochs

    func insertEpoch(_ e: SleepEpoch) {
        write {
            run("""
                INSERT INTO sleep_epochs (sessionId, timestamp, phase, hasSnore, rmsEnergy,
                    breathingMarginDb, breathingPresentFraction, breathPeriodS,
                    externalAudioFraction, playbackActive)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                [.int(e.sessionId), .int(e.timestamp), .int(Int64(e.phase.rawValue)), .bool(e.hasSnore),
                 .real(e.rmsEnergy), .real(e.breathingMarginDb), .real(e.breathingPresentFraction),
                 .optReal(e.breathPeriodS), .real(e.externalAudioFraction), .bool(e.playbackActive)])
        }
    }

    func epochs(sessionId: Int64) -> [SleepEpoch] {
        read {
            var out: [SleepEpoch] = []
            query("SELECT * FROM sleep_epochs WHERE sessionId = ? ORDER BY timestamp ASC", [.int(sessionId)]) { st in
                out.append(SleepEpoch(
                    id: sqlite3_column_int64(st, 0),
                    sessionId: sqlite3_column_int64(st, 1),
                    timestamp: sqlite3_column_int64(st, 2),
                    phase: SleepPhase(ordinal: Int(sqlite3_column_int(st, 3))),
                    hasSnore: sqlite3_column_int(st, 4) != 0,
                    rmsEnergy: Float(sqlite3_column_double(st, 5)),
                    breathingMarginDb: Float(sqlite3_column_double(st, 6)),
                    breathingPresentFraction: Float(sqlite3_column_double(st, 7)),
                    breathPeriodS: sqlite3_column_type(st, 8) == SQLITE_NULL ? nil : Float(sqlite3_column_double(st, 8)),
                    externalAudioFraction: Float(sqlite3_column_double(st, 9)),
                    playbackActive: sqlite3_column_int(st, 10) != 0
                ))
            }
            return out
        }
    }

    /// Per-session (epochCount, deepCount, snoreCount) for trends (D-1).
    func epochAggregates(sessionIds: [Int64]) -> [Int64: (epochs: Int, deep: Int, snore: Int)] {
        guard !sessionIds.isEmpty else { return [:] }
        return read {
            var out: [Int64: (Int, Int, Int)] = [:]
            let list = sessionIds.map(String.init).joined(separator: ",")
            query("""
                SELECT sessionId, COUNT(*),
                    SUM(CASE WHEN phase = ? THEN 1 ELSE 0 END),
                    SUM(CASE WHEN hasSnore THEN 1 ELSE 0 END)
                FROM sleep_epochs WHERE sessionId IN (\(list)) GROUP BY sessionId
                """, [.int(Int64(SleepPhase.deep.rawValue))]) { st in
                out[sqlite3_column_int64(st, 0)] = (Int(sqlite3_column_int(st, 1)),
                                                    Int(sqlite3_column_int(st, 2)),
                                                    Int(sqlite3_column_int(st, 3)))
            }
            return out
        }
    }

    /// Measured breath periods per session (A-7 trend), NULLs excluded.
    func breathPeriods(sessionIds: [Int64]) -> [Int64: [Float]] {
        guard !sessionIds.isEmpty else { return [:] }
        return read {
            var out: [Int64: [Float]] = [:]
            let list = sessionIds.map(String.init).joined(separator: ",")
            query("SELECT sessionId, breathPeriodS FROM sleep_epochs WHERE sessionId IN (\(list)) AND breathPeriodS IS NOT NULL", []) { st in
                out[sqlite3_column_int64(st, 0), default: []].append(Float(sqlite3_column_double(st, 1)))
            }
            return out
        }
    }

    // MARK: - Acoustic events

    func insertEvents(_ events: [AcousticEvent]) {
        guard !events.isEmpty else { return }
        write {
            for e in events {
                run("""
                    INSERT INTO acoustic_event (sessionId, type, startUtc, durationMs, confidence,
                        peakDbOverFloor, envelopeReductionPct, terminatedByGasp, meanDbOverFloor)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    [.int(e.sessionId), .int(Int64(e.type.rawValue)), .int(e.startUtc), .int(e.durationMs),
                     .real(e.confidence), .real(e.peakDbOverFloor), .real(e.envelopeReductionPct),
                     .bool(e.terminatedByGasp), .real(e.meanDbOverFloor)])
            }
        }
    }

    func events(sessionId: Int64, type: AcousticEventType? = nil) -> [AcousticEvent] {
        read {
            var out: [AcousticEvent] = []
            var sql = "SELECT * FROM acoustic_event WHERE sessionId = ?"
            var args: [SQLValue] = [.int(sessionId)]
            if let type { sql += " AND type = ?"; args.append(.int(Int64(type.rawValue))) }
            query(sql + " ORDER BY startUtc ASC", args) { st in
                out.append(AcousticEvent(
                    id: sqlite3_column_int64(st, 0),
                    sessionId: sqlite3_column_int64(st, 1),
                    type: AcousticEventType(ordinal: Int(sqlite3_column_int(st, 2))),
                    startUtc: sqlite3_column_int64(st, 3),
                    durationMs: sqlite3_column_int64(st, 4),
                    confidence: Float(sqlite3_column_double(st, 5)),
                    peakDbOverFloor: Float(sqlite3_column_double(st, 6)),
                    envelopeReductionPct: Float(sqlite3_column_double(st, 7)),
                    terminatedByGasp: sqlite3_column_int(st, 8) != 0,
                    meanDbOverFloor: Float(sqlite3_column_double(st, 9))
                ))
            }
            return out
        }
    }

    func deleteEvents(ids: [Int64]) {
        guard !ids.isEmpty else { return }
        write { run("DELETE FROM acoustic_event WHERE id IN (\(ids.map(String.init).joined(separator: ",")))", []) }
    }

    // MARK: - Night summaries

    func upsertSummary(_ s: NightSummary) {
        write {
            run("""
                INSERT OR REPLACE INTO night_summary (sessionId, totalSleepTimeMin, reiA, apneaLikeCount,
                    hypopneaLikeCount, longestEventS, snorePctOfSleep, meanSnoreDbOverFloor,
                    signalQuality, acousticBand)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                [.int(s.sessionId), .int(Int64(s.totalSleepTimeMin)), .real(s.reiA), .int(Int64(s.apneaLikeCount)),
                 .int(Int64(s.hypopneaLikeCount)), .real(s.longestEventS), .real(s.snorePctOfSleep),
                 .real(s.meanSnoreDbOverFloor), .int(Int64(s.signalQuality.rawValue)), .int(Int64(s.acousticBand.rawValue))])
        }
    }

    func summary(sessionId: Int64) -> NightSummary? {
        read { summaries("SELECT * FROM night_summary WHERE sessionId = ?", [.int(sessionId)]).first }
    }

    /// Most recent [limit] summaries, newest first; optionally excluding LOW quality.
    func recentSummaries(limit: Int, excludeLowQuality: Bool = false) -> [NightSummary] {
        read {
            let filter = excludeLowQuality ? "WHERE signalQuality != \(SignalQuality.low.rawValue)" : ""
            return summaries("SELECT * FROM night_summary \(filter) ORDER BY sessionId DESC LIMIT ?", [.int(Int64(limit))])
        }
    }

    // MARK: - Questionnaire

    func insertQuestionnaire(_ q: QuestionnaireResult) {
        write {
            run("""
                INSERT INTO questionnaire_result (dateUtc, snoring, tiredness, observedApnea, highPressure,
                    bmiOver35, ageOver50, neckOver40cm, maleGender, score)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                [.int(q.dateUtc), .bool(q.snoring), .bool(q.tiredness), .bool(q.observedApnea), .bool(q.highPressure),
                 .bool(q.bmiOver35), .bool(q.ageOver50), .bool(q.neckOver40cm), .bool(q.maleGender), .int(Int64(q.score))])
        }
    }

    func latestQuestionnaire() -> QuestionnaireResult? {
        read {
            var out: QuestionnaireResult?
            query("SELECT * FROM questionnaire_result ORDER BY dateUtc DESC LIMIT 1", []) { st in
                func b(_ i: Int32) -> Bool { sqlite3_column_int(st, i) != 0 }
                out = QuestionnaireResult(
                    id: sqlite3_column_int64(st, 0), dateUtc: sqlite3_column_int64(st, 1),
                    snoring: b(2), tiredness: b(3), observedApnea: b(4), highPressure: b(5),
                    bmiOver35: b(6), ageOver50: b(7), neckOver40cm: b(8), maleGender: b(9),
                    score: Int(sqlite3_column_int(st, 10)))
            }
            return out
        }
    }

    func deleteQuestionnaires(olderThan cutoffMs: Int64) {
        write { run("DELETE FROM questionnaire_result WHERE dateUtc < ?", [.int(cutoffMs)]) }
    }

    // MARK: - Maintenance

    /// Delete every row (children first), then VACUUM so freed pages leave
    /// the file. With secure_delete on, deleted content is zeroed first.
    func deleteAll() {
        write {
            for table in ["acoustic_event", "night_summary", "questionnaire_result", "sleep_epochs", "sleep_sessions"] {
                exec("DELETE FROM \(table)")
            }
            exec("DELETE FROM sqlite_sequence")
            exec("PRAGMA wal_checkpoint(TRUNCATE)")
            exec("VACUUM")
        }
    }

    func vacuum() {
        queue.sync {
            exec("PRAGMA wal_checkpoint(TRUNCATE)")
            exec("VACUUM")
        }
    }

    // MARK: - SQLite plumbing

    enum SQLValue {
        case int(Int64), optInt(Int64?), real(Float), optReal(Float?), bool(Bool)
    }

    private func read<T>(_ body: () -> T) -> T { queue.sync(execute: body) }

    private func write<T>(_ body: () -> T) -> T {
        let result = queue.sync(execute: body)
        DispatchQueue.main.async { self.didChange.send() }
        return result
    }

    private func exec(_ sql: String) {
        if sqlite3_exec(db, sql, nil, nil, nil) != SQLITE_OK {
            assertionFailure("SQL failed: \(sql) — \(String(cString: sqlite3_errmsg(db)))")
        }
    }

    private func prepare(_ sql: String, _ args: [SQLValue]) -> OpaquePointer? {
        var st: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &st, nil) == SQLITE_OK else {
            assertionFailure("Prepare failed: \(sql) — \(String(cString: sqlite3_errmsg(db)))")
            return nil
        }
        for (i, arg) in args.enumerated() {
            let idx = Int32(i + 1)
            switch arg {
            case .int(let v): sqlite3_bind_int64(st, idx, v)
            case .optInt(let v): if let v { sqlite3_bind_int64(st, idx, v) } else { sqlite3_bind_null(st, idx) }
            case .real(let v): sqlite3_bind_double(st, idx, Double(v))
            case .optReal(let v): if let v { sqlite3_bind_double(st, idx, Double(v)) } else { sqlite3_bind_null(st, idx) }
            case .bool(let v): sqlite3_bind_int(st, idx, v ? 1 : 0)
            }
        }
        return st
    }

    private func run(_ sql: String, _ args: [SQLValue]) {
        guard let st = prepare(sql, args) else { return }
        defer { sqlite3_finalize(st) }
        if sqlite3_step(st) != SQLITE_DONE {
            assertionFailure("Step failed: \(sql) — \(String(cString: sqlite3_errmsg(db)))")
        }
    }

    private func query(_ sql: String, _ args: [SQLValue] = [], row: (OpaquePointer) -> Void) {
        guard let st = prepare(sql, args) else { return }
        defer { sqlite3_finalize(st) }
        while sqlite3_step(st) == SQLITE_ROW { row(st) }
    }

    private func sessions(_ sql: String, _ args: [SQLValue]) -> [SleepSession] {
        var out: [SleepSession] = []
        query(sql, args) { st in
            out.append(SleepSession(
                id: sqlite3_column_int64(st, 0),
                startTime: sqlite3_column_int64(st, 1),
                endTime: sqlite3_column_type(st, 2) == SQLITE_NULL ? nil : sqlite3_column_int64(st, 2),
                feelRating: sqlite3_column_type(st, 3) == SQLITE_NULL ? nil : Int(sqlite3_column_int(st, 3))
            ))
        }
        return out
    }

    private func summaries(_ sql: String, _ args: [SQLValue]) -> [NightSummary] {
        var out: [NightSummary] = []
        query(sql, args) { st in
            out.append(NightSummary(
                sessionId: sqlite3_column_int64(st, 0),
                totalSleepTimeMin: Int(sqlite3_column_int(st, 1)),
                reiA: Float(sqlite3_column_double(st, 2)),
                apneaLikeCount: Int(sqlite3_column_int(st, 3)),
                hypopneaLikeCount: Int(sqlite3_column_int(st, 4)),
                longestEventS: Float(sqlite3_column_double(st, 5)),
                snorePctOfSleep: Float(sqlite3_column_double(st, 6)),
                meanSnoreDbOverFloor: Float(sqlite3_column_double(st, 7)),
                signalQuality: SignalQuality(rawValue: Int(sqlite3_column_int(st, 8))) ?? .low,
                acousticBand: AcousticBand(rawValue: Int(sqlite3_column_int(st, 9))) ?? .none
            ))
        }
        return out
    }
}
