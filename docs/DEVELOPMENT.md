# Development setup

The authoritative rebuild specification is [the Home/Away plan](superpowers/plans/2026-11-02-home-away-rebuild-plan.md).

## Verified checkout

- Windows directory: `C:\programming\ClockYou-location`.
- Git root: `C:/programming/ClockYou-location`.
- Branch: `master`, created from verified `upstream/main`.
- Starting commit: `a1970f1a79db5a59a6dc1a8a210cc5fe232da798`.
- Upstream: `https://github.com/you-apps/ClockYou.git`.
- User-owned `origin`: `https://github.com/janmerhar/ClockYou.git`; upstream is never the push destination.
- No `AGENTS.md` was found in the checkout or its ancestor directories.

## Build

The unmodified upstream declares Gradle 9.6.1, Android Gradle Plugin 9.4.1, Java source/target 17, compile/target SDK 37, minimum SDK 23, Room 2.8.5 and database version 15. The debug application ID is `com.bnyro.clock.debug`.

PowerShell setup used for the baseline:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

This bundled runtime reports OpenJDK 25.0.3. Source/target compatibility remains 17. The ignored `local.properties` points to `C:/Users/win10-5950x/AppData/Local/Android/Sdk`. The wrapper installed the required Android SDK Platform 37.0 (revision 2) using an already accepted SDK license. No dependency versions were downgraded.

The baseline command's original output is retained in `.superpowers/sdd/2026-11-02-home-away-rebuild-plan/baseline-build.log` during execution. See [implementation progress](IMPLEMENTATION_PROGRESS.md) for its actual result.

## Device checks

ADB: `C:\Users\win10-5950x\AppData\Local\Android\Sdk\platform-tools\adb.exe`.

Configured AVDs include `Demo_device` (Android 34, Google APIs, x86_64), `Pixel_10`, and `Pixel_10_Edited`. An emulator starting or a service launching does not establish successful alarm delivery or audible output. Record observed firing, snooze, second firing and dismissal before accepting Task 01.

## Existing integration points

Alarm definitions are in `domain/model/Alarm.kt`; Room initialization and migrations are in `data/database/AppDatabase.kt`. The model already contains nullable `snoozedUntil`, absent from exported schema 15. Preserve that fixture and test both layouts in Task 02.

`CreateUpdateDeleteAlarmUseCase` calls `AlarmHelper` after repository writes. `AlarmReceiver`, `BootReceiver`, `TimeZoneReceiver`, `AlarmService`, `App`, and `MainActivity` participate in delivery, recovery and rescheduling. `ClockBackupUseCase` currently exports preferences except saved timers and must exclude new runtime keys. Compose entry points are `AlarmSettingsSheet`, `SettingsScreen`, `NavRoutes`, and `NavHost`.

Each numbered task requires its own passing checks, commit directly on `master`, push to the supplied user repository, and local/remote SHA comparison before starting the next task. No force pushes or unrelated cleanup.
