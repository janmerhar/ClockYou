# Independent whole-change review

Reviewed range: `a1970f1a79db5a59a6dc1a8a210cc5fe232da798..85645ba5139d4e216aac0c0a2cb0c859753f9b12`.

This is one read-only review pass against Sections 1-8 of the rebuild specification and the execution rulings in `progress.md`. I inspected the production changes and connected recurrence, persistence, ringing UI, repository, and test code. No source, index, or branch changes were made. I did not run Gradle or emulator commands. The 47 unit / 2 native passing results are the implementer's documented evidence, not independently rerun results. Concurrent Task 06 additions are not included in this verdict.

## Strengths

- The coordinator moves the entire mutex-protected operation onto IO, refetches rows inside CRUD/delivery operations, and avoids the original Main-thread blocking pattern. The real Room gate regression tests the significant deadlock boundary.
- Due location commitments are retained before delivery, with consumption recorded before the next base occurrence is scheduled. Queue-extras and duplicate-delivery tests exercise more than the pure policy.
- Frozen choices retain wall-time identity across timezone changes, and bounded consumed/skipped dates are applied to every candidate. The additional original cutoff and frozen flag have a clear purpose supported by targeted regressions.
- Storage migration handles both schema-15 snooze layouts; sample writes share a compare-and-write lock. Home capture remains a draft, has bounded listeners, and does not hold the scheduling lock while waiting for GPS.
- Native monitoring remains sparse and optional. Translation suppression is limited to the new fallback resource file, and documentation distinguishes emulator evidence from unverified phone behavior.

## Issues

### Critical

None found.

### Important

1. **Old asynchronous service work can stop a newer delivered alarm.**

   References: `app/src/main/java/com/bnyro/clock/util/services/AlarmService.kt:125`, `:76`, and `:191`; the new-start boundary is `:120`.

   The missing-row continuation calls unkeyed `stopSelf()` before checking `startId != latestStartId`. Start A can suspend in Room, A can be deleted, and a valid alarm B can start before A's null result resumes. A then stops B's service, including its scope and player. B has already been consumed by the coordinator, so ordinary reconciliation will not deliver it again. This confirms the author's concern.

   The same ownership gap exists in the new asynchronous notification Snooze path: while A's snooze suspends, B can enter `onStartCommand` and suspend loading its row. `currentAlarm` and `currentRingTime` still describe A until B's load completes, so A's `finally` check succeeds and its unkeyed stop cancels B. The existing timeout callback can also run during that newly introduced pending-load window.

   Minimum fix: bind delayed stop work to its originating service start. Use `stopSelf(startId)` in the missing-row branch, retain a start identifier for the active ringing occurrence/action, and prevent its delayed snooze/timeout completion from stopping a newer start. Do not identify ownership solely through `currentAlarm`, which is intentionally updated after the suspension. Add gated regressions for a late missing-row result after valid B starts, and for a pending A snooze completing while B is loading; B must remain live.

2. **Async alarm loading can replace the configured snooze duration with the placeholder's 10 minutes.**

   References: `app/src/main/java/com/bnyro/clock/presentation/screens/alarm/AlarmActivity.kt:77`, with the placeholder rendered at `:22` / `:33`; connected state initializer: `app/src/main/java/com/bnyro/clock/presentation/screens/alarm/AlarmAlertScreen.kt:98`.

   `handleIntent` now suspends instead of completing before the initial Activity lifecycle returns. If Room is slow enough for the first Compose frame, the controls render `Alarm(0, 0)`, whose snooze duration is 10. `AlarmControls` remembers that initial duration without a key. Loading a real alarm configured for 1 or 30 minutes updates the parameter but leaves the button's remembered value at 10. Tapping it schedules ten minutes, despite the user's saved preference. The passing native one-minute test does not cover a delayed first row load.

   Minimum fix: do not expose operational alarm controls until the matching alarm has loaded, and initialize/reset the snooze draft for that loaded ringing occurrence. Keep user adjustments stable during the same occurrence. Guard any pending load against a newer `onNewIntent` so older results cannot become the current controls. A regression should hold the Room read across the first frame, release a non-default snooze duration, then assert both the displayed value and the scheduled snooze.

### Minor

3. **The missed-alarm notification reports Home time for an Away occurrence.**

   References: `app/src/main/java/com/bnyro/clock/util/services/AlarmService.kt:172` and `:176`.

   If an 08:00 Home / 10:00 Away alarm actually rings at 10:00 and times out, the missed notification formats `alarm.time` and says 08:00. The ringing notification and screen now correctly use the committed trigger, so the subsequent missed message contradicts them.

   Minimum fix: format the triggering occurrence captured by this service start (`trigger`) in both missed-title branches, rather than the base definition or a mutable later service trigger.

## Recommendations

Fix the two Important findings with deterministic delayed-IO regressions, then run the intended final suites once. The changes fit the existing coordinator/service/UI structure; they do not require another scheduling layer or new dependencies. Keep the documented physical-device limitations in the handoff.

## Declined to judge

- Physical-phone background delivery, screen-off behavior, actual Home/Away travel, speaker audibility, and overnight battery/reliability: no physical-device or overnight evidence is available; these remain explicitly unverified.
- Final Task 06 lint comparison, new native/reboot checks, APK hash/source correspondence, and final push equality: these are concurrently in progress and outside the fixed reviewed head, so this report does not certify them.
- Existing exported, unpermissioned generic alarm-action broadcast handling: the exposure predates this range; broader upstream security hardening was not assessed as part of the requested Home/Away rebuild. This is a scope decision, not a statement that the existing exposure is safe.
- Unrelated upstream recurrence/editor behavior and inherited lint findings: inspected where connected to this change, but no general upstream cleanup or full legacy security audit was performed.

## Assessment

**Ready to merge / hand off? With fixes.** The core location policy, migration, queue reconciliation, and IO serialization are well aligned with the specification and have useful regression coverage. The two remaining asynchronous lifecycle defects can suppress a newly delivered alarm or snooze for the wrong duration, so they should be corrected before the final POC artifact is called ready.

Skipped: no build/device rerun or independent replay of archived evidence; this was the requested single static review pass.
Risk: phone and overnight reliability remain unverified even after these code fixes.
