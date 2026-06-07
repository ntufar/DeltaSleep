# SQLite Schema v1

Database file: app-private storage (`deltasleep.db`).

## sleep_sessions

| Column       | Type    | Notes                                 |
|--------------|---------|---------------------------------------|
| id           | INTEGER | PK, autoincrement                     |
| startTime    | INTEGER | Unix epoch ms                         |
| endTime      | INTEGER | Unix epoch ms; NULL while in progress |
| feelRating   | INTEGER | 1–5 morning rating; NULL if not set   |

## sleep_epochs

| Column      | Type    | Notes                                              |
|-------------|---------|----------------------------------------------------|
| id          | INTEGER | PK, autoincrement                                  |
| sessionId   | INTEGER | FK → sleep_sessions.id, ON DELETE CASCADE          |
| timestamp   | INTEGER | Unix epoch ms at start of epoch                    |
| phase       | INTEGER | 0=Awake, 1=Light, 2=Deep (matches SleepPhase enum) |
| hasSnore    | INTEGER | 0 or 1                                             |
| rmsEnergy   | REAL    | Mean RMS over epoch (normalised 0–1)               |

Each epoch represents 30 seconds of audio analysis.
