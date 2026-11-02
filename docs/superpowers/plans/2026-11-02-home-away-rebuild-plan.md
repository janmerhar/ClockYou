# Home / Away Alarm — Fresh Implementation Plan

> **For the implementer:** Use the native, sequential `superpowers:executing-plans` workflow. Follow the user's explicit override: work directly on `master`, without a feature branch or a separate worktree. Implement one numbered task, test it, commit it on `master`, push it to the user's GitHub repository, verify the remote commit, then continue. This document is a plan, not evidence of an implementation.

**Goal:** Extend Clock You with one saved Home and an optional different alarm time when away, keeping the Android proof of concept small and usable.

**Architecture:** Reuse the existing alarm CRUD, recurrence calculation, ringing service, Compose theme, Room database and preferences. Add native location detection and a small coordinator that commits the chosen next occurrence before Android schedules it. Location delivery never becomes a prerequisite for ringing an already scheduled alarm.

**Tech stack:** Existing Kotlin, Jetpack Compose/Material 3, Room/SQLite, SharedPreferences, Android LocationManager and AlarmManager. No Google Play services, server, map SDK or new UI framework.

**Spec:** The scope, views, defaults, scheduling invariants and execution rules in this document are the complete rebuild specification. It supersedes the previous plan, including its feature-branch instructions. All tasks begin unchecked; previous implementation, commits, APKs and test totals must not be assumed to exist.

## 1. Starting point and working location

- Intended Windows folder: `C:\programming\ClockYou-location`.
- Equivalent Git Bash path: `/c/programming/ClockYou-location`.
- The implementation session must actually have access to this folder. Print its working directory and Git repository root before making changes. A cloud checkout is not the Windows folder; do not create a look-alike `/c` directory and claim otherwise.
- Upstream source: <https://github.com/you-apps/ClockYou>.
- Previously inspected upstream commit: `a1970f1a79db5a59a6dc1a8a210cc5fe232da798`. It contains no location feature. Pulling newer source may change the baseline; record the actual starting SHA and recheck relevant files before implementation.
- Missing execution input: the user's writable GitHub repository URL, represented below as `<USER_GITHUB_REPO_URL>`. Obtain it once before execution; do not guess an account or push to the original project's repository.
- Preserve existing files. Starting from zero means implementing the feature afresh, not deleting unrelated local work.

## 2. Global constraints

- Android POC first. iOS, rewrites, monetization and store publishing are outside this rebuild.
- Keep Clock You's existing screens, tabs, ordinary alarms, recurrence, sound, vibration, snooze and deletion.
- One Home only. No saved-place list, movement history, accounts, backend or configurable rule engine.
- Normal alarm time remains the existing `Alarm.time`; `awayTime: Long? = null` means the location rule is off.
- Use standard controls and the existing theme. Add separate views where needed instead of filling the existing alarm editor with location settings.
- Preserve upstream licensing, copyright notices and attribution. Branding changes are a later task.
- New controls use English fallback resources for this POC. Translation work is deferred; any MissingTranslation lint exception must be scoped to the new resource file and documented, never applied to the whole project.
- Make every new task/fix commit directly on `master`. Existing upstream branch names may remain; branch count is not evidence of completed features.
- Build and test the actual checked-out files. Do not reuse earlier claimed test counts or report a task complete from memory.

## 3. GitHub and commit workflow

A **pull** downloads GitHub changes; a **push** uploads local commits. Both are required in this plan. Each completed task must be backed up on GitHub before the next task starts.

### One-time setup

1. Inspect `git status --short`, `git branch -avv`, `git remote -v` and `git rev-parse --show-toplevel` in the real local folder.
2. Use an existing writable user repository/fork if available. If a fresh checkout is needed, clone into the intended folder only when that folder does not already contain the project.
3. Configure `origin` as `<USER_GITHUB_REPO_URL>` and keep `upstream` as the original Clock You repository. Inspect existing remote URLs before changing them; never use `upstream` as the push destination.
4. Fetch `origin`. Switch to an existing `master`, or create `master` from the verified starting ref. Do not reset an existing branch or discard local work.
5. If `origin/master` already exists, pull with `git pull --ff-only origin master`. If the destination is empty or only has `main`, first establish `origin/master` from the verified baseline with `git push -u origin master`; then use the normal pull workflow. Do not pull a nonexistent branch.
6. Verify authentication and permission to push directly to `master`. If protection requires a PR, report that conflict rather than bypassing protection or silently changing the requested workflow.

### Repeat for every task

```bash
# Begin only from the intended repository, with unrelated work preserved.
git switch master
git pull --ff-only origin master

# Implement only the current task; run its specified checks.
# Stage the task's named files, not an indiscriminate git add .
git add <files-for-this-task>
git commit -m "<numbered-task-message>"
git push origin master

# Local and remote SHAs must match before the next task.
git rev-parse HEAD
git ls-remote origin refs/heads/master
git status --short
```

Record the matching SHA and test results in the task report. If a pull, test or push fails, fix/report that blocker before advancing. Do not force-push, rewrite previous commits, or create feature branches. Necessary corrective commits use `fix(task-XX): ...`, are also tested, and are also pushed to `master`. No repeated approval is needed between already authorized tasks.

## 4. Minimal product behaviour

These fixed POC defaults carry forward the previous design; they are not new claims about completed work.

| Setting | Behaviour |
| --- | --- |
| Home area | Fixed 300 m radius around the saved coordinates. |
| Background requests | Native network location, requested minimum interval 30 minutes. No continuous background GPS. |
| Periodic recheck | One inexact 30-minute recheck reads cached fixes and reconciles scheduling. It also checks provider availability when automatic monitoring is otherwise eligible. |
| Home capture | Foreground GPS/network capture, maximum 30 seconds. Accept a fix no older than 2 minutes with accuracy at most 100 m. Stop listeners on success, timeout, cancellation or leaving the visible screen. |
| Sample retention | Store only the newest fix, at most 12 hours old for classification. Reject future timestamps, invalid coordinates/accuracy and invalid ages. Concurrent writers cannot replace a newer fix with an older one. |
| Classification | Home only if the accuracy circle fits inside 300 m; Away only if wholly outside; overlapping/absent/stale/unavailable means Unknown. |
| Time selection | Home and Unknown use the ordinary time during evaluation; Away uses the optional away time. A disabled rule uses the unchanged ordinary-alarm path. |
| Cutoff | Freeze an occurrence's choice 30 actual minutes before the earlier Home/Away trigger on that occurrence's local date. Account for midnight and daylight-saving transitions. |
| Frozen choice | Later automatic location/permission uncertainty does not replace an already frozen commitment. |
| Explicit edits | Alarm/Home changes may recalculate. If the new selected time has already passed, use the next eligible occurrence. Do not revive a date already fired/skipped through a location-rule edit. Explicit enable/disable retains upstream's intentional re-arm behaviour. |
| Snooze | Preserve `snoozedUntil`; never replace a snooze with a Home/Away base alarm. A snooze across midnight must not consume a second base occurrence accidentally. |
| Timezone | Both times are local wall-clock times. A timezone-only change must preserve the selected wall time of a frozen, still-future occurrence; the rule identity must not depend on the old zone. |

**Example:** Home 08:00 and Away 10:00. Before 07:30, a reliable location update may change the choice. At/after 07:30, retain the committed choice. After that date rings or is skipped, location changes must not create another wake-up for that date.

Android can delay both background location and inexact rechecks. Thirty minutes is a requested interval, not a guaranteed location freshness deadline. Keep the exact ringing alarm scheduled independently. This POC does not promise reacting to last-minute travel or overnight reliability without phone testing.

Monitoring starts only with Home, an enabled location-aware alarm and sufficient permission. Removing Home, disabling the last rule, or losing background permission stops location monitoring/rechecks. When only the network provider is unavailable, stop network requests, show that state, and keep the low-frequency availability recheck while otherwise eligible. Repeated registration must not restart the recheck timer or cause a callback loop.

## 5. Data storage

| Data | Storage and change |
| --- | --- |
| Alarm definitions | Existing Room/SQLite `alarms` table. Add nullable `awayTime` at the end of `Alarm` with a null default. |
| Home | Existing internal preferences: one latitude/longitude pair, written together and read as a consistent snapshot. |
| Latest fix | One atomic preference record containing latitude, longitude, accuracy and timestamp; one shared lock protects compare-and-write across storage instances. |
| Next choice | One bounded record per alarm: occurrence date, selected wall time and alarm-configuration identity. Remove it on deletion/rule removal; reuse existing dismissal state for consumed/skipped dates where possible. |
| Backups | Preserve old JSON decoding with the new field absent. Extend the existing JSON backup for Home and alarm definitions; exclude sample/runtime scheduling keys and clear/recalculate runtime state on restore. |

For the inspected baseline, add Room migration **15 → 16** and a new exported schema. The inspected model contains `snoozedUntil`, while the published schema-15 JSON omits it: test both schema-15 variants and add that nullable column only if missing, alongside `awayTime`. Preserve old fixture files. If pulling changes the database version/layout, adapt the migration to the actual baseline rather than forcing version 16.

## 6. Views and appearance

All new content uses the existing Material 3 colours, typography, light/dark theme, approximately 16 dp spacing, standard buttons, switches and time pickers. No maps, illustrations or custom animations.

| View | Minimum change |
| --- | --- |
| Alarm list — existing | Show the committed next scheduled time. Add one small secondary line: `Home 08:00 · Away 10:00` when enabled. |
| Add/Edit Alarm — existing | Keep the ordinary time picker. Add one `Location rule: Off` / `Away: 10:00` row opening the rule sheet. Existing Save persists everything. |
| Location Rule — new bottom sheet | Title, enable switch, Away time picker, short Home/fallback/cutoff explanation, Done and Cancel. Enabling initially suggests 10:00. |
| Settings — existing | One `Home location` navigation row. |
| Home Location — new screen | Toolbar/Back; saved/not-set state; 300 m area; current Home/Away/Unknown status and last fix; Use current location, preview, Save/Update, Refresh and confirmed Remove. Plain secondary coordinates are enough. |
| System dialogs | Standard foreground permission request, separate background permission explanation/settings, time picker and removal confirmation. |

The rule sheet edits only a draft. Done updates the parent draft, while Cancel, Back and outside dismissal discard sheet changes. Cancelling the parent saves nothing. Preserve new draft fields through rotation/state restoration. A failed capture never replaces Home; saving/removing Home must finish its short persistence/rescheduling operation even if the user immediately navigates back. Permissions remain optional for ordinary alarms.

## 7. Files and interfaces

Base for Kotlin paths: `app/src/main/java/com/bnyro/clock/`.

| Files | Responsibility |
| --- | --- |
| `domain/model/Alarm.kt`, `data/database/AppDatabase.kt`, `app/schemas/com.bnyro.clock.data.database.AppDatabase/16.json` (if the baseline remains v15) | Nullable away time and explicit compatible migration. |
| New `domain/model/HomeLocation.kt`, `LocationSample.kt` | Small data records. |
| New `util/location/LocationPreferences.kt` | Home CRUD, latest sample and bounded committed choices. |
| New `util/location/LocationAlarmPolicy.kt` | Pure classification, time selection and zoned cutoff calculation. |
| New `util/location/HomeLocationTracker.kt`, `HomeLocationReceiver.kt` | Bounded foreground capture, sparse native updates, cached rechecks and idempotent registration. |
| New `util/location/LocationAlarmScheduler.kt`, `LocationAlarmCoordinator.kt` | Next eligible occurrence, persistence and serialized scheduling operations. |
| New `presentation/screens/settings/HomeLocationScreen.kt` | Dedicated Home CRUD screen. |
| New `presentation/screens/alarmpicker/components/LocationRuleSheet.kt` | Separate rule draft editor. |
| Existing `SettingsScreen.kt`, `AlarmSettingsSheet.kt`, `navigation/NavRoutes.kt`, `navigation/NavHost.kt` | The two small UI entry points and route. |
| Existing `util/AlarmHelper.kt`, `domain/usecase/CreateUpdateDeleteAlarmUseCase.kt`, `ClockBackupUseCase.kt` | Integrate selection, CRUD and backup lifecycle without replacing recurrence. |
| Existing `util/receivers/AlarmReceiver.kt`, `BootReceiver.kt`, `TimeZoneReceiver.kt`, `util/services/AlarmService.kt`, `App.kt`, `ui/MainActivity.kt` | Delivery, snooze/dismiss, startup, reboot, clock/timezone and permission recovery. |
| Existing `presentation/screens/alarm/components/AlarmCard.kt` | Display the same committed trigger that was queued with Android. |
| `app/src/main/AndroidManifest.xml`, new `app/src/main/res/values/location_alarm_strings.xml` | Optional location permissions, internal receiver declarations and strings. |

Use the following interfaces consistently across tasks:

```kotlin
data class HomeLocation(val latitude: Double, val longitude: Double)
data class LocationSample(val latitude: Double, val longitude: Double,
    val accuracyMeters: Float, val timestampMillis: Long)
enum class LocationState { HOME, AWAY, UNKNOWN }
enum class ReconcileReason { AUTOMATIC, USER_EDIT, RECOVERY }

// LocationPreferences — Home/sample methods in Task 02
fun getHome(): HomeLocation?
fun saveHome(home: HomeLocation)
fun clearHome()
fun getSample(): LocationSample?
fun saveSample(sample: LocationSample)

// LocationPreferences — add with the scheduling record in Task 05
fun getOccurrence(alarmId: Long): LocationOccurrence?
fun saveOccurrence(alarmId: Long, occurrence: LocationOccurrence)
fun clearOccurrence(alarmId: Long)
fun clearRuntimeState()

// LocationAlarmPolicy
fun classify(home: HomeLocation?, sample: LocationSample?, nowMillis: Long): LocationState
fun chooseTime(normalTime: Long, awayTime: Long?, state: LocationState): Long
fun cutoff(date: LocalDate, normalTime: Long, awayTime: Long, zone: ZoneId): Instant

// HomeLocationTracker
suspend fun captureHome(): LocationSample?
fun refresh()
suspend fun updateRegistration()

// Scheduling: deterministic time is an input, not a production test-mode switch.
data class LocationOccurrence(val date: LocalDate, val timeOfDay: Long, val configKey: String)
fun LocationOccurrence.triggerMillis(zone: ZoneId): Long
fun getCommittedTrigger(alarm: Alarm): Long?
fun resolve(alarm: Alarm, state: LocationState, saved: LocationOccurrence?,
    now: ZonedDateTime, reason: ReconcileReason): LocationOccurrence?
suspend fun reconcile(context: Context, reason: ReconcileReason = ReconcileReason.AUTOMATIC)
suspend fun <T> serialized(block: suspend () -> T): T
```

The coordinator owns a private mutex; the complete critical operation runs on IO. Do not expose a mutex that a Main coroutine can hold while suspending in repository IO. Do not block Main waiting for such a lock. Receivers use bounded asynchronous work and finish their pending result; service actions use their coroutine scope. Acquire serialization once per operation, never recursively, and never hold it while waiting for GPS or user permission.

**Delivery invariant:** automatic reconciliation must not cancel, roll to tomorrow, or invalidate a due-but-unconsumed commitment before its broadcast is handled. Mark the occurrence consumed before scheduling the next base occurrence, reject duplicates, and exclude consumed/skipped dates on every candidate search, including fallback candidates. Explicit recovery after reboot or an exact-alarm permission grant may advance a missed past occurrence. A future frozen choice survives recovery.

## 8. Review focus

1. Alarm delivery races with app startup/location reconciliation: retain and deliver the due commitment once.
2. CRUD suspended in repository IO while a receiver or snooze/dismiss action runs: no Main-thread deadlock, no stale replacement.
3. Midnight, daylight-saving and timezone changes after cutoff: preserve a valid frozen wall time and exclude every skipped date.
4. Permission/provider changes, stale fixes and simultaneous writers: use the correct fallback without continuous GPS or reverting to an older fix.
5. Migration, cancellation, snooze and deletion: preserve existing alarms/drafts and remove all pending work/state when appropriate.

## 9. Numbered implementation tasks

Every task follows Section 3: **pull → failing tests → minimal implementation → passing checks → commit on master → push → verify matching remote SHA**. Complete the task and its checkpoint before moving on. Investigate failures; do not mark a timeout, missing device or skipped check as a pass.

### Task 01 — Local repository, GitHub checkpoint and clean baseline

**Files:** this plan at `docs/superpowers/plans/2026-11-02-home-away-rebuild-plan.md`; new `docs/DEVELOPMENT.md`; new `docs/IMPLEMENTATION_PROGRESS.md`.

**Produces:** verified local path, writable `origin/master`, recorded baseline SHA, working Gradle/device setup and baseline evidence.

- [ ] Verify the real Windows/Git Bash path and carry out one-time GitHub/master setup. Preserve unrelated work and supplied plan files.
- [ ] Inspect the pulled source: alarm model/database schema, existing CRUD/receiver/service, UI/navigation, build configuration and any `AGENTS.md` instructions. Record baseline versions rather than assuming the previous environment exists.
- [ ] Open the local project in Android Studio; verify a compatible Gradle JDK, SDK and wrapper. The currently inspected source targets Java 17, compile/target SDK 37 and Gradle 9.6.1; recheck after pulling. Do not silently downgrade upstream dependencies.
- [ ] Run `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`. Expected: build/tests pass; record any inherited lint failures separately and preserve the clean-baseline report. Restore only identified generated schema drift, not user edits.
- [ ] On an actually connected emulator/phone, verify one ordinary near-future alarm fires, snoozes, rings again and dismisses. Record device/API and what was observed. A service starting is not proof of audible volume.
- [ ] Commit/push: `docs(task-01): record rebuild plan and verified baseline`. Record local/remote SHA equality. Baseline build or mandatory smoke-test blockers stop the feature work.

### Task 02 — Minimal local storage and migration

**Files:** Alarm/AppDatabase/new models/LocationPreferences and the new Room schema from Section 7; `app/src/test/java/com/bnyro/clock/LocationStorageTest.kt`; `app/src/androidTest/java/com/bnyro/clock/LocationMigrationTest.kt`; backup use case.

**Consumes:** baseline schema and preferences initialization. **Produces:** nullable `awayTime`, Home CRUD and atomic newest-sample storage.

- [ ] Write failing tests named `oldBackupDefaultsToRuleOff`, `bothSchema15VariantsRetainAlarmsAndSnooze`, `homeCreateUpdateRemove`, and `concurrentWritersKeepNewestSample`.
- [ ] Assertions: old JSON has `awayTime == null`; both legitimate old database layouts preserve label/time/snooze; Home reads across storage instances; racing timestamps 2000 and 3000 leave 3000 stored.
- [ ] Run focused unit/device tests and observe failure before implementation. Implement the migration, compatible serialization and shared atomic sample write; do not add a places/history table.
- [ ] Verify user-requested JSON backup/restore excludes runtime keys and clears them on restore; final location rescheduling is attached in Task 05. Re-run focused tests plus `:app:testDebugUnitTest :app:assembleDebug`; run the real native migration test. Expected: zero feature-test failures and successful build.
- [ ] Commit/push: `feat(task-02): store home and optional away alarm time`.

### Task 03 — Sparse native Home/Away detection

**Files:** policy, tracker, location receiver, App-level tracker lifetime, manifest and `app/src/test/java/com/bnyro/clock/LocationAlarmPolicyTest.kt` / `LocationMonitoringTest.kt`.

**Consumes:** Home/sample storage. **Produces:** classification, bounded capture, refresh and registration interfaces from Section 7.

- [ ] Add failing tests for Home `(0,0)` with precise fix `(0,0)` → HOME; fix `(0.01,0)` → AWAY; overlapping accuracy circle → UNKNOWN; no Home, invalid/future fix or age `43_200_001 ms` → UNKNOWN.
- [ ] Test the capture boundary: age at most 120,000 ms and accuracy at most 100 m. Test cancellation/timeout cleanup.
- [ ] Test registration with missing permission, no Home/no enabled rule, provider unavailable/re-enabled, and repeated callbacks. Repeating registration must leave the existing recheck deadline unchanged.
- [ ] Observe failures, then implement the fixed defaults with platform APIs and explicit pending intents. Stop active network requests when unavailable; never fall back to continuous GPS. Keep one low-frequency availability recheck only while otherwise eligible.
- [ ] Run focused tests and the full unit/build checks. Expected: all pass; ordinary alarms remain usable without location permission.
- [ ] Commit/push: `feat(task-03): add sparse native home and away detection`.

### Task 04 — Simple Home screen and rule sheet

**Files:** new screen/sheet/strings and existing editor/settings/navigation from Section 7; `app/src/test/java/com/bnyro/clock/LocationRuleUiTest.kt` and `HomeLocationUiTest.kt` using the existing Compose test tooling.

**Consumes:** Home CRUD, capture/refresh and tracker status. **Produces:** `HomeLocationScreen(onBack: () -> Unit)` and `LocationRuleSheet(awayTime: Long?, onDone: (Long?) -> Unit, onCancel: () -> Unit)`.

- [ ] Add tests for enabling the rule suggesting 10:00, Done updating only the parent draft, sheet Cancel/Back/outside dismissal discarding changes, parent Cancel making no save, and restoration of new draft fields after rotation.
- [ ] Add Home tests: capture is a preview until Save; failed capture retains existing Home; update replaces Home; removal requires confirmation; denied permission displays Unknown and leaves ordinary alarms available.
- [ ] Observe relevant failures, implement the views exactly as Section 6, and add separate foreground/background permission handling. At this task, wire Home persistence/registration; attach its scheduling reconciliation in Task 05. Android 11+ background access uses the system settings route with an explanation and a decline option.
- [ ] Verify on a device/emulator: create/edit/off, Home CRUD, permission denial, light/dark theme and larger text. Record which Android versions were actually checked. Re-run unit/build checks; do not add another UI framework.
- [ ] Commit/push: `feat(task-04): add home settings and location rule editor`.

### Task 05 — Safe scheduling, delivery and recovery

**Files:** scheduler/coordinator and existing AlarmHelper, alarm/backup use cases, receivers/service, lifecycle and card from Section 7; `LocationAlarmSchedulingTest.kt`, `LocationAlarmIntegrationTest.kt`, `AlarmConcurrencyTest.kt` under `app/src/test/java/com/bnyro/clock/`.

**Consumes:** stored data, classification and saved editor values. **Produces:** persisted next occurrence, serialized CRUD/delivery and actual committed-time display.

- [ ] Write tests with these contracts; observe failure before adding the corresponding behaviour:

| Test | Required assertion |
| --- | --- |
| `selectHomeAwayUnknownAndOff` | 08:00/10:00 chooses 08:00, 10:00, 08:00 respectively; null rule retains the ordinary path and stored normal time. |
| `freezeAtEarlierTimeCutoff` | Away 10:00 can change at 07:00; stays 10:00 at 07:45. With Away 06:00 the cutoff is 05:30. |
| `cutoffAcrossMidnightAndDst` | For 00:10/02:00 UTC on Nov 2, cutoff is Nov 1 at 23:40Z. For 03:10/04:10 in Europe/Berlin on Mar 28, 2027, cutoff is 00:40Z. |
| `dueCommitSurvivesReconcileBeforeDelivery` | Reconcile at/just after the committed trigger, then deliver its original broadcast: it rings once and the date is consumed. Cover cold-start ordering and the actual pending-work replacement path. |
| `mainCrudIoDoesNotDeadlockDelivery` | Gate a real Room update started by Main, initiate delivery while IO is suspended, release IO without pumping Main: delivery completes; clean up deterministically. |
| `frozenAwaySurvivesEastwardTimezoneChange` | Frozen Away 10:00 at 07:45 UTC remains today's 10:00 local after UTC+1 makes the local time 08:45, even though Home 08:00 has passed. |
| `fallbackNeverRequeuesSkippedDate` | Daily 00:10/23:50 at 23:45, with tomorrow skipped: queue the following eligible date, not tomorrow. Assert Android's queued trigger, not only a policy return value. |
| `consumptionSnoozeDeleteAndEdit` | No second same-date occurrence after firing/skipping, including rule edits and earlier Away time; snooze and midnight snooze remain intact; deletion removes queue, notification and runtime record. |
| `recoverAfterBootClockAndPermissionGrant` | Preserve a valid future frozen choice; explicit recovery handles missed past work. Exact-alarm permission restoration and optional location permission changes use the appropriate distinct path. |

- [ ] Implement the smallest integration while reusing recurrence and end-count rules. Exclude consumed/skipped dates on every candidate, not only the first search. Anchor new alarms correctly when the normal time has passed but today's Away time is future.
- [ ] Serialize complete operations on IO through the coordinator; no Main-held mutex dependency and no nested acquisition. Refetch alarm state inside the operation so a callback cannot overwrite a newer edit/delete.
- [ ] Automatic reconciliation retains due unconsumed work for delivery. User edits and explicit recovery are distinct reasons; do not clear all choices on every startup or location callback. Keep the rule identity independent of timezone.
- [ ] Integrate optional monitoring with CRUD, Home changes, backup restore, app lifecycle, reboot/update, time/timezone changes and exact-alarm permission grants. Display the committed trigger without changing scheduling during UI rendering.
- [ ] Run all unit/integration tests, build, and native near-future ordinary and location-enabled alarm checks. Expected: no failures, no duplicate wake-up, and no snooze regression.
- [ ] Commit/push: `feat(task-05): integrate safe location-aware alarm scheduling`.

### Task 06 — End-to-end verification and handoff

**Files:** `app/src/androidTest/java/com/bnyro/clock/LocationAlarmDeviceTest.kt`, `LocationRebootDeviceTest.kt`; `docs/HOME_AWAY_POC.md`; progress record; generated debug APK as an artifact, not a committed build directory.

**Consumes:** complete feature. **Produces:** verified source on GitHub `master`, debug APK and an honest validation report.

- [ ] Run `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`. Keep actual counts, exit codes and reports. Compare inherited lint errors with the clean baseline; fix new errors, do not hide old failures or call lint green when it failed.
- [ ] Run `./gradlew.bat :app:connectedDebugAndroidTest` on an identified device. Check debug package assertions against the actual build variant. Use test/emulator mock location only in tests, never a production location-override UI.
- [ ] Verify ordinary firing/snooze/dismiss and location-enabled firing; Home↔Away changes before cutoff, frozen choices afterward, Unknown/stale/denied permission, skip, deletion and exact-permission recovery.
- [ ] Test app background/process restart and an actual device/emulator reboot. Save the expected trigger first. After reboot, wait for boot callbacks and inspect Android's alarm queue **before reopening the app or instrumentation**, then compare persisted choice. A boot-completed property alone is insufficient evidence. Account separately for instrumentation restarting the app and asynchronous startup work.
- [ ] On the target phone, check current background permission screens, screen-off behaviour, real Home/Away positions and an overnight battery/delivery trial. Record observed delays and limits. If a phone or overnight trial is unavailable, label those checks unverified and the outcome a partially device-validated POC; do not claim them passed.
- [ ] Perform one independent whole-change review if available. Fix important findings with a failing regression first, then a passing suite; keep corrective commits on `master`. Do not count review approval as a substitute for tests.
- [ ] Write setup/use instructions, device/API details, test results, baseline/new lint comparison and remaining limitations. Verify the APK is the final build; record filename, SHA-256 and the source commit.
- [ ] Commit/push: `test(task-06): verify alarms and document the proof of concept`. Update the existing report/checkpoint with the actual local/remote commit SHA. Provide the verified APK as a durable downloadable artifact. Do not commit generated build directories or publish to an app store.
- [ ] Finish by verifying GitHub `origin/master` matches the local head, the working tree is clean, named feature files are committed, and the delivered APK/plan links actually exist. Hand over the GitHub URL, commit list, APK and validation notes.

## 10. Progress reporting and completion rule

In `docs/IMPLEMENTATION_PROGRESS.md`, record each task's files, exact test commands/results, device where applicable, and blockers. Commit that task record with its code. Reports must distinguish **implemented**, **tested locally**, **committed**, **pushed/remote SHA verified**, and **unverified**. The commit message identifies the task; report its actual SHA after committing/pushing rather than inventing a self-referential SHA inside its own commit.

Do not report a task complete until its code exists in the requested checkout, required checks pass and its commit is verified on `origin/master`. Build/unit/native feature checks must pass. An explicitly recorded unchanged upstream lint failure is an exception, not a green lint run; new lint errors are not acceptable. If Task 06 still lacks phone/overnight evidence, those validation items stay open even if an emulator-validated POC is handed over. Do not promise that work continues after an active execution has stopped. A missing path, failed build, unavailable required device, GitHub access issue or rejected push is a concrete blocker to report, not evidence of progress.

Final product acceptance: ordinary alarms still work; location rules select and display the correct committed occurrence; freeze/delivery/skip/snooze/timezone/recovery invariants pass; Home and alarm drafts support the minimal CRUD flows; source and history are on the user's GitHub `master`; the final APK can be retrieved. Physical-device or overnight claims require their own observed evidence.

## References

- [Clock You source](https://github.com/you-apps/ClockYou)
- [Android background location limits](https://developer.android.com/about/versions/oreo/background-location-limits)
- [Android background location permission](https://developer.android.com/develop/sensors-and-location/location/permissions/background)
- [Android alarm scheduling and exact-alarm permission](https://developer.android.com/develop/background-work/services/alarms)

**Plan status:** prepared from zero on November 2, 2026. No application implementation, GitHub pull/push, branch switch or Windows-folder change was performed while preparing this document. The writable GitHub repository URL and an execution session with access to the real local folder are prerequisites for implementation.
