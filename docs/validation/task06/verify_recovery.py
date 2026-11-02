import pathlib, re, subprocess, time

ROOT = pathlib.Path(__file__).resolve().parents[3]
OUT = pathlib.Path(__file__).resolve().parent
ADB = r'C:\Users\win10-5950x\AppData\Local\Android\Sdk\platform-tools\adb.exe'
PACKAGE = 'com.bnyro.clock.debug'
RUNNER = PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'

def adb(*args, timeout=60):
    result = subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout

def instrument(method, phase):
    result = adb('shell', 'am', 'instrument', '-w', '-e', 'class',
                 'com.bnyro.clock.LocationRebootDeviceTest#' + method,
                 '-e', 'rebootPhase', phase, RUNNER, timeout=90)
    (OUT / ('recovery-' + phase + '.log')).write_text(result, encoding='utf-8')
    if 'OK (1 test)' not in result:
        raise RuntimeError(result)
    return result

if __name__ == '__main__':
    print(adb('install', '-r', str(ROOT / 'app/build/outputs/apk/debug/app-debug.apk')), flush=True)
    print(adb('install', '-r', str(ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')), flush=True)
    print(instrument('prepareForReboot', 'prepare'), flush=True)
