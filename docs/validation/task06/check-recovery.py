from verify_recovery import *
import json, xml.etree.ElementTree as ET

PREFS = '/data/user_de/0/' + PACKAGE + '/shared_prefs/clock_you.xml'
def prefs():
    return adb('shell', 'run-as', PACKAGE, 'cat', PREFS)
saved = prefs()
root = ET.fromstring(saved)
expected = int(root.find("long[@name='location_runtime_reboot_expected']").get('value'))
alarm_id = int(root.find("long[@name='location_runtime_reboot_id']").get('value'))
record = json.loads(root.find("string[@name='location_runtime_occurrence_%d']" % alarm_id).text)
assert record['frozen']
(OUT / 'recovery-before-prefs.xml').write_text(saved, encoding='utf-8')
results = {'expected_trigger': expected, 'alarm_id': alarm_id}

def queue_matches(raw):
    return re.search(r'RTC_WAKEUP #\d+: Alarm\{[^\n]*origWhen ' + str(expected) + r'[^\n]*' + re.escape(PACKAGE), raw) is not None

def queue(stage, matches=True, wait=30):
    end = time.monotonic() + wait
    while True:
        raw = adb('shell', 'dumpsys', 'alarm')
        if queue_matches(raw) == matches:
            (OUT / ('recovery-' + stage + '-alarm.txt')).write_text(raw, encoding='utf-8')
            results[stage] = True
            print(stage + ': PASS', flush=True)
            return
        if time.monotonic() >= end:
            (OUT / ('recovery-' + stage + '-failed.txt')).write_text(raw, encoding='utf-8')
            raise AssertionError(stage)
        time.sleep(.5)

queue('before')
before_pid = adb('shell', 'pidof', PACKAGE).strip()
assert before_pid
adb('shell', 'input', 'keyevent', 'HOME')
adb('shell', 'am', 'kill', PACKAGE)
end = time.monotonic() + 10
while subprocess.run([ADB, 'shell', 'pidof', PACKAGE], capture_output=True, text=True).stdout.strip():
    assert time.monotonic() < end, 'background process remained alive'
    time.sleep(.1)
results['killed_process_pid'] = before_pid
queue('background-process-killed')
adb('shell', 'appops', 'set', PACKAGE, 'SCHEDULE_EXACT_ALARM', 'deny')
queue('exact-denied', matches=False)
adb('shell', 'appops', 'set', PACKAGE, 'SCHEDULE_EXACT_ALARM', 'allow')
queue('exact-granted-without-app-open')
adb('shell', 'pm', 'revoke', PACKAGE, 'android.permission.ACCESS_BACKGROUND_LOCATION')
adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/com.bnyro.clock.ui.MainActivity')
queue('foreground-after-background-denial')
denied = ET.fromstring(prefs())
assert json.loads(denied.find("string[@name='location_runtime_occurrence_%d']" % alarm_id).text) == record
adb('shell', 'pm', 'grant', PACKAGE, 'android.permission.ACCESS_BACKGROUND_LOCATION')
adb('shell', 'input', 'keyevent', 'HOME')
# Shell app-op writes are buffered; flush before an immediate adb reboot.
adb('shell', 'cmd', 'appops', 'write-settings')
adb('shell', 'cmd', 'appops', 'read-settings')
assert 'allow' in adb('shell', 'appops', 'get', PACKAGE, 'SCHEDULE_EXACT_ALARM')
print('Rebooting emulator; no app/instrumentation will be opened before queue capture.', flush=True)
adb('reboot')
adb('wait-for-device', timeout=60)
end = time.monotonic() + 90
while adb('shell', 'getprop', 'sys.boot_completed').strip() != '1':
    assert time.monotonic() < end, 'boot timed out'
    time.sleep(.5)
queue('after-reboot-before-app-open', wait=45)
(OUT / 'recovery-after-appops.txt').write_text(adb('shell', 'appops', 'get', PACKAGE, 'SCHEDULE_EXACT_ALARM'), encoding='utf-8')
after = prefs()
(OUT / 'recovery-after-prefs.xml').write_text(after, encoding='utf-8')
assert json.loads(ET.fromstring(after).find("string[@name='location_runtime_occurrence_%d']" % alarm_id).text) == record
print(instrument('verifyAfterReboot', 'verify'), flush=True)
results['frozen_record_unchanged'] = True
results['explicit_prepare_and_verify_phases_passed'] = True
(OUT / 'recovery-results.json').write_text(json.dumps(results, indent=2), encoding='utf-8')
