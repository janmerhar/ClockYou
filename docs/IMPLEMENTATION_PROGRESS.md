# Home/Away rebuild progress

Plan: [2026-11-02-home-away-rebuild-plan.md](superpowers/plans/2026-11-02-home-away-rebuild-plan.md).

## Task 01 — Baseline verified

Implemented setup:

- Preserved the original root-level supplied plan and copied it to the plan's required documentation path.
- Initialized the real local folder as a Git repository, fetched Clock You as `upstream`, and checked out `master` at `a1970f1a79db5a59a6dc1a8a210cc5fe232da798`.
- Confirmed upstream's advertised HEAD matches that commit. Authenticated the existing Git credential as `janmerhar`, created the authorized fork, configured `origin` as `https://github.com/janmerhar/ClockYou.git`, fetched it, pushed the baseline to `origin/master`, and pulled with `--ff-only`.
- Inspected the baseline model, migrations, CRUD, delivery/recovery receivers, ringing service, backup, UI/navigation and build configuration.
- Created ignored local SDK configuration and invoked the baseline build with the bundled Android Studio JDK. Platform 37 installation completed.
- Launched installed Android Studio for this checkout and the existing `Demo_device` AVD. These actions alone do not verify IDE sync or a usable device.

Checks:

| Command/check | Result |
| --- | --- |
| `Get-Location`; `git rev-parse --show-toplevel` | Verified real Windows workspace and repository root. |
| `git status --short`; `git branch -avv`; `git remote -v` | `master` at the verified baseline; `origin` is the user's fork, `upstream` is the original project. |
| `git ls-remote --symref https://github.com/you-apps/ClockYou.git HEAD` | `refs/heads/main`, SHA `a1970f1a79db5a59a6dc1a8a210cc5fe232da798`. |
| `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` | Exit 1 after 4m 31s: assemble succeeded, 4 unit tests passed (0 failures/errors), lint failed with 325 errors, 139 warnings and 10 hints. |
| `adb devices -l` before emulator launch | No connected device. |
| Ordinary alarm firing → snooze → firing → dismiss | Observed on API 34: first firing at 01:05, one-minute snooze queued 01:06, second ringing screen/media playback, then dismissal stopped player/service. Speaker audibility unverified. |
| Initial `git push -u origin master`; `git pull --ff-only origin master`; SHA comparison | Passed: local and remote baseline both `a1970f1a79db5a59a6dc1a8a210cc5fe232da798`. Task documentation commit follows these recorded checks. |

Tasks 02–06 have not started at this checkpoint. Production source files remain unchanged. A fresh baseline command recheck completed in 3 seconds: assemble and tests remained successful/up-to-date, lint retained the same inherited failure. See `baseline-recheck.log`.

Baseline evidence is retained in `docs/validation/baseline/`: the complete build log, lint XML/text and both JUnit XML reports. Lint errors include 304 `MissingTranslation`, 11 `ImpliedQuantity`, 4 `MissingQuantity`, and the `AlarmService.kt:143` `MissingPermission` finding. These are inherited from the untouched upstream source. No lint suppression or lint-baseline configuration has been added.

The baseline build regenerated schema 15 with the existing `snoozedUntil` column and a changed identity hash. Its generated variant was retained in the ignored execution workspace; the tracked original fixture was restored from the verified starting commit, as the plan requires. Kotlin session/build outputs are locally ignored.

Native smoke test: installed the baseline debug APK on `emulator-5554`, API 34, model `sdk_gphone64_x86_64`. Granted exact-alarm and notification access through the standard Android screens; skipped optional battery/manufacturer prompts. Created an ordinary daily 01:05 alarm with one-minute snooze in the editor. Android's queue reported the exact `RTC_WAKEUP` trigger at `2026-11-02 01:05:00`, timezone Europe/Budapest. The ringing screen appeared and `dumpsys media.player` showed this package playing looping alarm audio with volume 1.0. Snooze stopped the player and queued 01:06. A second ringing screen and a new active player appeared after 01:06. Dismiss returned to the alarm list; the player and AlarmService were absent afterward. The next base occurrence was tomorrow. Disabled the test alarm afterward. Evidence includes UI XML, media/audio dumps and Android alarm queues in the baseline evidence directory. This is emulator/software evidence; actual speaker audibility and phone behavior were not verified.

The feature is **not implemented** at this checkpoint. Task 01's documentation is committed as `docs(task-01): record rebuild plan and verified baseline`; its actual local/remote SHA is recorded after committing in the execution ledger and the next progress entry. Android Studio was launched but its project/sync UI could not be verified. No physical-device or overnight trial has run.

## Task 02 — Storage and migration

Task 01 checkpoint: local and GitHub `master` matched `dc3eef0f18aaed3716fecbc3a43b6a7755137661` after its push.

Added nullable `Alarm.awayTime`, schema 16, explicit migration 15→16 supporting both old snooze layouts, Home/sample records, atomic preferences, newest-valid-fix retention and JSON backup runtime filtering/clearing. Home and sample writes persist synchronously; one shared lock protects writers across storage instances. Existing schema fixtures are unchanged. Android test assets use the actual exported schemas.

RED: storage tests ran against placeholder storage and failed 4/5 checks for Home persistence, newest-sample retention, invalid sample handling and backup contents. Native migration with an empty migration failed Room's actual table validation on API 34. GREEN: `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bnyro.clock.LocationMigrationTest` exited 0 (11 seconds): all 9 unit tests passed; 1 native test passed, checking both schema-15 layouts and preserved label/time/snooze. Old JSON decoding and away-time round-trip passed. Backup tests confirm Home survives and sample/occurrence keys are excluded/cleared, including restore without preferences.

Commit: `feat(task-02): store home and optional away alarm time`. Actual SHA/remote verification follows the commit in the execution ledger/next task entry. Scheduling integration remains Task 05.

## Remaining tasks

3. Sparse native detection, bounded capture and classification.
4. Home CRUD screen, location rule sheet and draft/permission behavior.
5. Serialized scheduling, commitments, delivery and recovery.
6. Device verification, independent review, APK and handoff.

## Task 03 — Native detection

Task 02 checkpoint: local/GitHub `master` matched `99ac7114d141e18ec36e0eb5682c0e2535065c7a`. The execution helper initially received an unquoted PowerShell `-P` argument; rerunning it with the complete quoted property passed. This was command parsing, not a feature failure.

Added pure accuracy-circle classification and zoned cutoff policy, a tracker owned by the Application, bounded foreground GPS/network capture, cached refresh, optional sparse background network requests and one inexact 30-minute recheck. Registration is serialized on IO, preserves its deadline on repeated callbacks, stops without Home/rules/background permission, and retains the availability recheck when the network provider is disabled. Runtime callbacks finish bounded asynchronous receiver work. The new location permissions are optional and ordinary alarm permission onboarding is unchanged.

RED: 8 of 9 initial policy/monitoring tests failed against placeholder behavior. A further permission regression showed cached Home surviving background permission denial; changing it to Unknown made that test pass. GREEN: `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug` exited 0 (38 seconds): 20 unit tests passed, including the actual 30-second capture timeout, live success cleanup, cancellation cleanup, capture age/accuracy boundaries, provider recovery, no continuous GPS registration, and an unchanged recheck deadline. Task 05 attaches lifecycle/CRUD scheduling reconciliation; no location-triggered ringing behavior is claimed yet.

Commit: `feat(task-03): add sparse native home and away detection`.

## Task 04 — Home screen and rule sheet

Task 03 checkpoint: local/GitHub `master` matched `b1d7b340eb0d2a4a5b44f725a019edf74f786b38`.

Added a separate draft-only Material 3 rule sheet, optional Away time in the parent editor, Home settings navigation, and a Home screen with bounded capture preview, save/update, refresh and confirmed removal. New draft fields use saved state. Capture stops when the screen stops/leaves; Home writes use Application IO scope so navigation does not abandon them. Foreground and background permission flows are separate; Android 11+ background access has an explanation, localized system option label, settings navigation and decline. English-only translation suppression is confined to `location_alarm_strings.xml`.

RED: all 9 UI tests failed against placeholder views/missing entry points. GREEN: `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug` exited 0 (45 seconds): all 29 unit tests passed. Nine Compose checks cover suggested 10:00, draft-only Done, Cancel/outside dismissal, restoration, parent cancellation, Off clearing Away, Home preview/save/update, failed capture, confirmed removal and denied permission. Two initial harness issues were corrected: wait for the Save operation to re-enable capture before clicking again, and target the sheet root rather than Material's separate tooltip root for outside dismissal.

Native API 34 checks: denied location shows Unknown; captured test GPS coordinates remain a preview until Save; Save/Update persist the shown coordinates; Cancel on removal keeps Home and confirmed Remove clears it. Native Back (after dismissing the input keyboard) discards sheet edits. Done→parent Save→reopen retains Away 10:00; switching Off and saving clears it. Test alarm was disabled afterward. Light and dark Home layouts and dark Home at 1.5× system font scale were inspected. Native emulator GNSS delivered no fixes, so capture tests used Android's shell test GPS provider; this is test-only fixture setup and was removed afterward. No production mock-location control exists. Native evidence is in `docs/validation/task04/`.

The IDE removed obsolete upstream Linux run-target selections during opening/sync; its generated variant was backed up in the ignored execution workspace before restoring that unrelated tracked configuration. Feature files alone enter this checkpoint. Scheduling remains Task 05.

Commit: `feat(task-04): add home settings and location rule editor`.
