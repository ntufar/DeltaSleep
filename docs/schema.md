# SQLite Schema v3 (Room)

Database file: app-private storage (`deltasleep.db`).
Implementation: [Room](../app/src/main/java/io/github/ntufar/deltasleep/data/db/AppDatabase.kt)
(current version `3`; entities in
`data/model/`, DAOs in `data/db/`).

Enum columns are stored as `INTEGER` ordinals via `Converters`
(`SleepPhase`, `AcousticEventType`, `SignalQuality`, `RiskBand`,
`AcousticBand`). Unknown values read back as safe defaults
(`AWAKE`, `APNEA_LIKE`, `LOW`, `LOW`, `NONE` respectively).
Booleans are stored as `INTEGER` 0/1. Times are Unix epoch ms.

## sleep_sessions

| Column     | Type    | Notes                                 |
|------------|---------|---------------------------------------|
| id         | INTEGER | PK, autoincrement                     |
| startTime  | INTEGER | Unix epoch ms, NOT NULL               |
| endTime    | INTEGER | Unix epoch ms; NULL while in progress |
| feelRating | INTEGER | 1–5 morning rating; NULL if not set   |

## sleep_epochs

One row per 30 s of audio analysis.
FK `sessionId → sleep_sessions.id` `ON DELETE CASCADE`.
Index on `sessionId`.

| Column                  | Type    | Notes                                              |
|-------------------------|---------|----------------------------------------------------|
| id                      | INTEGER | PK, autoincrement                                  |
| sessionId               | INTEGER | FK → sleep_sessions.id, NOT NULL                   |
| timestamp               | INTEGER | Unix epoch ms at start of epoch, NOT NULL          |
| phase                   | INTEGER | 0=Awake, 1=Light, 2=Deep (`SleepPhase` ordinal)    |
| hasSnore                | INTEGER | 0 or 1, NOT NULL                                   |
| rmsEnergy               | REAL    | Mean RMS over epoch (normalised 0–1), NOT NULL     |
| breathingMarginDb       | REAL    | Breathing level minus noise floor (dB), NOT NULL, DEFAULT 0 — added in v2 |
| breathingPresentFraction| REAL    | Fraction of frames with breathing present (0–1), NOT NULL, DEFAULT 0 — added in v2 |
| breathPeriodS           | REAL    | Mean autocorrelation breath period (s) over breathing-present frames; NULL when never present — added in v3 (A-7) |

## acoustic_event

Event-level DSP output (apnea-like, hypopnea-like, gasp, snore
episodes). Added in v2.
FK `sessionId → sleep_sessions.id` `ON DELETE CASCADE`.
Index on `sessionId`.

| Column               | Type    | Notes                                                        |
|----------------------|---------|--------------------------------------------------------------|
| id                   | INTEGER | PK, autoincrement                                            |
| sessionId            | INTEGER | FK → sleep_sessions.id, NOT NULL                             |
| type                 | INTEGER | 0=APNEA_LIKE, 1=HYPOPNEA_LIKE, 2=GASP, 3=SNORE_EPISODE        |
| startUtc             | INTEGER | Wall-clock start, Unix epoch ms, NOT NULL                    |
| durationMs           | INTEGER | NOT NULL                                                     |
| confidence           | REAL    | Classifier confidence 0–1, NOT NULL                          |
| peakDbOverFloor      | REAL    | Peak dB above adaptive noise floor, NOT NULL                 |
| envelopeReductionPct | REAL    | Fraction of envelope reduction (0–1), NOT NULL               |
| terminatedByGasp     | INTEGER | 0 or 1 (gasp within 5 s after event end), NOT NULL           |
| meanDbOverFloor      | REAL    | Mean dB over floor across event, NOT NULL                    |

## night_summary

One computed row per session (`sessionId` is both PK and FK).
Added in v2.
FK `sessionId → sleep_sessions.id` `ON DELETE CASCADE`.

| Column              | Type    | Notes                                              |
|---------------------|---------|----------------------------------------------------|
| sessionId           | INTEGER | PK = parent `sleep_sessions.id`, NOT NULL          |
| totalSleepTimeMin   | INTEGER | Non-AWAKE sleep minutes, NOT NULL                  |
| reiA                | REAL    | APNEA_LIKE events per hour of sleep, NOT NULL      |
| apneaLikeCount      | INTEGER | NOT NULL                                           |
| hypopneaLikeCount   | INTEGER | NOT NULL                                           |
| longestEventS       | REAL    | Longest apnea/hypopnea-like event (s), NOT NULL    |
| snorePctOfSleep     | REAL    | % of non-AWAKE epochs with snore (0–100), NOT NULL |
| meanSnoreDbOverFloor| REAL    | Mean dB-over-floor of snore episodes, NOT NULL     |
| signalQuality       | INTEGER | 0=GOOD, 1=FAIR, 2=LOW (`SignalQuality` ordinal)    |
| acousticBand        | INTEGER | 0=NONE, 1=MILD, 2=MODERATE, 3=SEVERE               |

## questionnaire_result

One row per completed STOP-BANG questionnaire (booleans only —
no raw biometrics stored). Added in v2. Each row is individually
erasable (`deleteById`).

| Column        | Type    | Notes                                     |
|---------------|---------|-------------------------------------------|
| id            | INTEGER | PK, autoincrement                         |
| dateUtc       | INTEGER | Submission time, Unix epoch ms, NOT NULL  |
| snoring       | INTEGER | 0 or 1, NOT NULL                          |
| tiredness     | INTEGER | 0 or 1, NOT NULL                          |
| observedApnea | INTEGER | 0 or 1, NOT NULL                          |
| highPressure  | INTEGER | 0 or 1, NOT NULL                          |
| bmiOver35     | INTEGER | 0 or 1, NOT NULL                          |
| ageOver50     | INTEGER | 0 or 1, NOT NULL                          |
| neckOver40cm  | INTEGER | 0 or 1, NOT NULL                          |
| maleGender    | INTEGER | 0 or 1, NOT NULL                          |
| score         | INTEGER | Sum of true answers (0–8), NOT NULL       |

## Migrations

- **1 → 2**: `ALTER TABLE sleep_epochs ADD COLUMN` for
  `breathingMarginDb` / `breathingPresentFraction` (DEFAULT 0);
  `CREATE TABLE` for `acoustic_event`, `night_summary`,
  `questionnaire_result` plus the `acoustic_event(sessionId)` index.
  See `MIGRATION_1_2` in `AppDatabase.kt`.
- **2 → 3**: `ALTER TABLE sleep_epochs ADD COLUMN breathPeriodS REAL`
  (nullable, no default — pre-v3 epochs read back as NULL).
  See `MIGRATION_2_3` in `AppDatabase.kt`.

## Rot-check

`tools/check_schema_doc.sh` verifies every `@Entity(tableName=…)`
in `data/model/` is documented below. CI runs it (see
`ci-android.yml`, `schema-doc-check` job).
