# Home/Away alarm proof of concept

Source: [janmerhar/ClockYou, master](https://github.com/janmerhar/ClockYou/tree/master). Specification: [rebuild plan](superpowers/plans/2026-11-02-home-away-rebuild-plan.md). This is an emulator-validated proof of concept; physical-phone and overnight acceptance remain open.

## Install and use

Install the delivered `ClockYou-home-away-debug.apk` (application ID `com.bnyro.clock.debug`). It is a debug build and can coexist with the regular application. Follow the normal alarm onboarding for exact alarms and notifications. Android 14+ can also require full-screen alarm access in system settings.

1. Open **Settings → Home location → Use current location**, grant foreground location access, review the coordinates, and choose **Save Home**. Capturing coordinates alone does not save Home. Update and confirmed removal are on this screen.
2. To enable automatic detection, use **Allow background detection** and follow the separate system background-location instructions. Keep device location enabled. Declining location access leaves ordinary alarms usable.
3. Create or edit an alarm. Its normal time is the Home time. Open **Location rule**, enable it, choose Away time, select **Done**, then save the parent alarm. Cancel, Back, or outside dismissal discards the sheet draft. Switching the rule off and saving clears Away time.
4. The alarm card shows the committed next time and the Home/Away rule. Home or Unknown uses the normal time; confidently Away uses Away time. The choice freezes 30 minutes before the earlier time. Later automatic location changes do not change that occurrence. Explicit alarm edits intentionally recalculate it.

Home is one saved coordinate with a 300 m radius. Classification accounts for the reported accuracy circle; ambiguous, missing, future or older-than-12-hour fixes are Unknown. Home capture requires a fix no older than two minutes with accuracy at most 100 m and stops after 30 seconds. Background monitoring uses sparse native network location and an inexact 30-minute recheck when eligible. Android and provider delays can exceed those intervals; the app does not continuously run GPS or promise immediate travel detection.

Skip applies to the selected local date; snooze uses the delivered occurrence and remains independent across midnight. Deleting an alarm removes its queued work and runtime records. Reboot, clock/timezone changes and exact-alarm permission recovery reconcile saved choices. Home is included in backups; cached location, next choice and delivery/skip runtime records are excluded and cleared on restore.

## Validation environment

Windows checkout `C:\programming\ClockYou-location`, branch `master`; upstream baseline `a1970f1a79db5a59a6dc1a8a210cc5fe232da798`. Android Studio's JBR 25.0.3, Java source/target 17, Gradle 9.6.1, AGP 9.4.1, compile/target API 37 and minimum API 23. Native tests use `Demo_device`, Android 14 / API 34 Google APIs x86_64 (`emulator-5554`, `sdk_gphone64_x86_64`).

Foreground capture and native transitions use Android shell test providers only in test setup. No production location override or mock-location UI was added. Test providers are removed afterward. Native player checks observe a looping media client and actual UI snooze/dismiss actions; they do not establish physical speaker audibility.

## Final check results

- `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`: all **51 unit tests pass** and both APKs build. The combined command exits 1 solely at inherited lint (1m 25s). Lint has **325 errors / 144 warnings / 10 hints**, against baseline **325 / 139 / 10**. Comparing severity, issue ID, message and file shows **zero new errors**. New warnings are seven `UseKtx` suggestions and a protected-broadcast warning for the non-exported Home receiver; three obsolete-SDK warnings disappeared. Synchronous preference commits intentionally remain synchronous. Reports and exact comparison are in [validation/task06](validation/task06/).
- `gradlew.bat :app:connectedDebugAndroidTest`: exits 0 (2m 45s). Five native feature tests pass: debug package identity; ordinary actual ringing, one-minute snooze, second ringing and UI dismissal; Unknown-location ringing; real native provider Home/Away changes before cutoff and frozen choice afterward; migration from both schema-15 layouts. The two reboot-phase methods intentionally raise assumptions without phase arguments. This UTP XML serializes those assumptions as two `failure` elements despite Gradle success; do not count them as exercised reboot checks or hide the raw report.
- Explicit `am instrument -w -e class com.bnyro.clock.LocationRebootDeviceTest#prepareForReboot -e rebootPhase prepare ...` and the corresponding `#verifyAfterReboot` / `verify` phase each report **OK (1 test)**. APKs were installed directly so Gradle's post-test uninstall could not remove the fixture. Expected alarm ID 1 / trigger **1793620620000** was saved first. The background app process (PID 2044) was confirmed gone; Android retained the alarm. Exact-alarm denial removed it and granting restored the identical queue before any app launch. Background-location denial plus foreground reconciliation preserved the frozen Away choice.
- After actual `adb reboot`, the system alarm queue was captured **before any app or instrumentation launch** and contained the same exact trigger. Only then were device-protected preferences read and the verification instrumentation started; the frozen record was unchanged. The fixture was deleted afterward. Raw queues, preferences, phase output and [recovery-results.json](validation/task06/recovery-results.json) preserve the evidence.
- The first immediate reboot reset a buffered shell app-op to its default, correctly preventing exact scheduling. Repeating after `cmd appops write-settings` and `read-settings` verified the grant was on disk and passed. This was test setup; no application behavior was weakened or changed to bypass permission checks.

The final debug APK is `ClockYou-home-away-debug.apk`, SHA-256 `19ed5c48201afdd345524379dad55d8634fb1d1fd10085f90ad079e241448222`. The delivered adjacent manifest records the final source commit and local/remote equality. Build outputs and APKs are not committed to the repository.

## Review and limits

The [single independent review](validation/task06/independent-review.md) inspected the complete baseline-to-Task-05 change. Both Important findings were reproduced before fixes: late asynchronous service work could stop a newer alarm, and the first ringing frame could remember the placeholder's ten-minute snooze. Delayed work now stops only its originating service start; operational controls appear only after the matching row loads, with per-occurrence snooze state and cancellation of superseded loads. Regression tests cover both service races, delayed configured snooze/display and scheduled duration, and older loads versus new intents. The reviewer did not rerun final tests; implementer-run evidence supplies the final verification.

Deferred minor: a missed Away alarm's timeout notification still formats the Home definition time; the actual committed delivery, ringing screen and ringing notification use the selected occurrence. Translation of new controls is deferred; English fallback is scoped to the new resource file.

Physical-phone background permissions on current Android/OEM builds, screen-off delivery, real travel, speaker audibility and overnight battery/reliability are unverified. Do not rely on this POC as the sole wake-up alarm until those checks pass on the target phone. Existing exported generic alarm-action broadcasts and unrelated upstream behavior/lint were not subjected to a general security/legacy cleanup. Home coordinates remain in the app's local preferences and user-created backup; sparse provider use still depends on Android permissions and services.
