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

## Remaining tasks

2. Storage, migration, JSON compatibility and backup lifecycle.
3. Sparse native detection, bounded capture and classification.
4. Home CRUD screen, location rule sheet and draft/permission behavior.
5. Serialized scheduling, commitments, delivery and recovery.
6. Device verification, independent review, APK and handoff.
