# OHSync

Sync data from **OPPO Health** (`com.heytap.health`, the China build) into Android's
native **Health Connect**.

[中文说明](README.md)

**Download**: [latest release](https://github.com/bqj6666/OHSync/releases/latest) ·
[all releases](https://github.com/bqj6666/OHSync/releases)

## Why

OPPO Health on Chinese firmware ships **without any Health Connect integration**.
The international `OHealth` build supports it, but the China build has the whole feature
removed — not hidden behind a flag (measured):

- no `android.permission.health.*` in its manifest
- zero references to `androidx.health` / `healthconnect` across all dex files
- it exposes its own API instead: `com.oplus.health.apiprovider`, `protectionLevel=signature`

So OHSync reads OPPO Health's own database and writes into Health Connect on your behalf.

## How it works

One APK, two roles.

```
com.heytap.health process                  io.github.ohsync process
----------------------------------        ----------------------------------
OHSyncHook (Xposed injected)
  + capture the already-open db  --IPC-->  SyncReceiverProvider (token checked)
  + read tables (SELECT only)              + normalise rows
  + push on interval                       + HealthConnectClient (holds HC perms)
```

**The read happens inside the target process.** OHSync does not copy or decrypt the
138 MB database. It captures the `SupportSQLiteDatabase` that OPPO Health opens itself
and reuses that instance. No extra SQLCipher dependency, no key handling, and no risk of
colliding with the `libsqlcipher.so` already loaded in that process.

**Writing must happen in OHSync's own process.** Health Connect validates permissions by
calling UID, and code injected into `com.heytap.health` has no write permission. So the
injected process reads and the main process writes.

## Setup

Requirements:

- Android 14 (API 34) or newer
- Health Connect available (built in on Android 14+, or the Google Health Connect app)
- Root (KernelSU / Magisk) — OHSync needs to read another app's private database
- LSPosed, or a compatible Xposed framework

Steps:

1. Install the APK and **open OHSync once** (the system will not start a data interface
   for an app that has never been launched)
2. Tap **Grant permission** in the app and tick the health data types you want
3. Enable OHSync in LSPosed, with scope set to **OPPO Health** only
4. Open OPPO Health once so it prepares its database
5. Back in OHSync, tap **Sync now**

## Sync settings

| Setting | Options |
|---|---|
| Auto sync interval | Manual only / 15m / 30m / 1h (default) / 3h / 6h / 12h / daily |
| Backfill range | Only data inside this window is read; default 3 months |

- **Auto sync** is incremental: only data newer than the last sync is pushed.
- **Sync now** uses the full window: for backfilling history or repairing data.
- Both are idempotent via `clientRecordId`, so repeated pushes do not duplicate records.

## What gets synced

The mapping is derived from the **live database schema** (`sqlite_master` +
`PRAGMA table_info`) rather than hardcoded table names, so it survives app updates that
only rename internals.

| OPPO Health | Health Connect |
|---|---|
| Steps, distance, active calories | `StepsRecord`, `DistanceRecord`, `ActiveCaloriesBurnedRecord` |
| Heart rate | `HeartRateRecord` |
| Sleep, with per-stage segments | `SleepSessionRecord` with `stages` |
| Blood pressure, blood oxygen | `BloodPressureRecord`, `OxygenSaturationRecord` |
| Weight | `WeightRecord` |

Data that Health Connect has no record type for is **dropped by design** (18 types):
stress, hearing health, snoring / OSA, fitness scores, various warnings, ECG, sedentary
reminders, and so on. The app lists them all so nothing disappears silently.

### Sleep stages

OPPO's `DBSleepPiece.type` is a private enum with no semantic definition anywhere in its
code. This project's mapping was derived by **reconciling against the OPPO Health UI**,
not guessed:

| type | Meaning | Evidence (one measured night) |
|---|---|---|
| 0 | Awake | 1 segment, 2 min = UI "Awake 1x / 2 min" |
| 2 | Deep | 7 segments, 68 min = UI "Deep 1h 8m" |
| 3 | REM | 10 segments, 119 min, 24.9% = UI 25% |
| 4 | Light | 18 segments, 288 min, 60.4% = UI 59% |

## Background operation

The app runs a **lightweight foreground service** (a silent notification). This is
required, not defensive:

The reader runs inside OPPO Health's process and hands data over through this app's
content provider. On this device the system **will not start an app that is not already
running just because something accessed its provider** (verified against a second app
with the same behaviour). So once the process is reclaimed, every write fails — which is
exactly what "sync stops after I swipe the card away" looks like.

A foreground service keeps the process alive after the recents card is swiped, so the
provider stays reachable.

- Measured CPU cost is negligible (about 0.36 s per hour)
- The notification uses the lowest importance level: no sound, no vibration, no badge
- You can turn "keep running in background" off in settings; automatic sync then stops
  working and you can only sync manually while the app is open

Note: if the process is killed by the system or by a force-stop from app info, the app
currently **will not come back on its own** — open it once. Allowing the app in the
vendor auto-start list and disabling battery optimisation for it makes this far more
reliable.

## Privacy

Everything stays on the device. OHSync has no network code; data flows only between two
processes on the same phone.

The module runs in `PROTECTIVE` exception mode and every hook wraps its work in try/catch,
so a failure in OHSync cannot crash OPPO Health. All database access is `SELECT` only.

## Troubleshooting

**Tapping "Grant permission" does nothing / the app shows as an "inactive app" in Health Connect**

Health Connect requires the app to declare a rationale page (`VIEW_PERMISSION_USAGE` with
the `HEALTH_PERMISSIONS` category). Without it, HC's permission screen exits immediately.
This project declares it; you would only hit this if you modified the manifest yourself.

Also note: **every app update causes Health Connect to revoke all health permissions**
(platform behaviour). Just grant again after updating.

**The "Permissions -> Health, fitness and wellbeing" row disappeared from app info**

ColorOS only shows that row when at least one health permission is granted. If you turn
them all off, there is no entry point left in system settings — use the in-app
**Grant permission** button instead.

**Sync seems to do nothing**

Make sure the module is enabled in LSPosed with OPPO Health in scope, and open OPPO Health
once. The app's status card shows whether the reader and write permission are ready.

## Build

```bash
./gradlew :app:assembleDebug
```

Requires JDK 21. On ARM64 Linux hosts where the bundled `aapt2` cannot run natively, point
`OHSYNC_AAPT2` at a wrapper:

```bash
OHSYNC_AAPT2=/path/to/wrapper/aapt2 ./scripts/build.sh :app:assembleDebug
```

## License

MIT. OPPO Health is closed-source software; this project only reads data you already own
on your own device. Not affiliated with OPPO or Google.
