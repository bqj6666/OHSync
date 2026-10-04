# OHSync

Sync data from **OPPO Health** (`com.heytap.health`, the China build) into Android's
native **Health Connect**.

[中文说明](README.zh-CN.md)

## Why

OPPO Health on Chinese firmware ships **without any Health Connect integration**.
The international `OHealth` build supports it, but the China build has the whole
feature removed — not hidden behind a flag:

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
  + DexKit: discover Room tables           + normalise rows
  + read tables (SELECT only)              + HealthConnectClient (holds HC perms)
  + push on write
```

Two things are worth calling out:

**The read happens inside the target process.** OHSync does not copy and decrypt the
138 MB database. It hooks the moment OPPO Health opens its own `SupportSQLiteDatabase`
and reuses that instance. No extra SQLCipher dependency, no key handling, and no risk
of colliding with the `libsqlcipher.so` already loaded in that process.

**Writing happens in OHSync's own process.** Health Connect checks permissions by
calling UID. Code injected into `com.heytap.health` has no `WRITE_HEALTH_DATA`, so it
cannot write directly. Data is read in the injected process and handed to the main
process, which holds the permissions.

## Setup

Requirements:

- Android 14 (API 34) or newer
- Health Connect available (built in on Android 14+, or the Google Health Connect app)
- Root (KernelSU / Magisk) — OHSync needs to read another app's private database
- LSPosed, or a compatible Xposed framework

Steps:

1. Install the APK and open OHSync once.
2. Grant Health Connect write permission when prompted.
3. Copy the pairing token shown in the app.
4. Enable OHSync in LSPosed, with scope set to **OPPO Health**.
5. Open OPPO Health once so it creates its database.
6. Watch `adb logcat -s OHSyncHook` — the hook prints the tables it found.
7. Tap **Sync historical data** in OHSync.

## What gets synced

Mapping is discovered at runtime from OPPO Health's Room entities rather than
hardcoded, so it survives app updates that only rename internals.

| OPPO Health | Health Connect |
|---|---|
| Steps, distance, active calories | `StepsRecord`, `DistanceRecord`, `ActiveCaloriesBurnedRecord` |
| Heart rate | `HeartRateRecord`, `RestingHeartRateRecord` |
| Sleep, with per-stage segments | `SleepSessionRecord` with `stages` |
| Workout sessions | `ExerciseSessionRecord` |
| Blood pressure, blood oxygen, blood glucose | corresponding records |
| Weight, body fat | `WeightRecord`, `BodyFatRecord` |
| Relaxation / breathing | `MindfulnessSessionRecord` |

Records that Health Connect has no type for are **dropped by design** — stress,
hearing health, snoring / OSA, fitness scores, and the various warning tables. The full
list is shown in the app so nothing disappears silently.

## Privacy

Everything stays on the device. OHSync has no network code. Data flows between exactly
two processes on the same phone, guarded by a random token generated at install.

The module runs in `PROTECTIVE` exception mode and every hook wraps its work in
try/catch, so a failure in OHSync cannot crash OPPO Health. All database access is
`SELECT` only.

## Build

```bash
./gradlew :app:assembleDebug
```

Requires JDK 21. On ARM64 Linux hosts where the bundled `aapt2` cannot run natively,
point `OHSYNC_AAPT2` at a wrapper:

```bash
OHSYNC_AAPT2=/path/to/wrapper/aapt2 ./scripts/build.sh :app:assembleDebug
```

## Disclaimer

Built for personal use, by reading a database you already own, on your own device.
OPPO Health is closed-source software; if it changes, this may stop working. Not
affiliated with OPPO or Google.
