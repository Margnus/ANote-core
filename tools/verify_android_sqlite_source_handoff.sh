#!/bin/bash
# Exercise the original App Store/View in an isolated API 26 AVD.
set -euo pipefail
cd "$(dirname "$0")/.."
sdk="${ANDROID_HOME:?set ANDROID_HOME}"
image="${1:?pass local API 26 ARM64 image directory}"
artifacts="${2:?pass isolated Gradle artifacts directory}"
test -f "$image/system.img"
image=$(cd "$image" && pwd)
artifacts=$(cd "$artifacts" && pwd)
app="$artifacts/android-app/outputs/apk/debug/android-app-debug.apk"
tests="$artifacts/android-app/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk"
test -f "$app"
test -f "$tests"
run=$(mktemp -d "$PWD/build/sqlite-source-handoff.XXXXXX")
name="NoteCoreSource26-${run##*.}"
adb="$sdk/platform-tools/adb"
serial=emulator-5582
"$adb" devices | awk -v serial="$serial" '$1 == serial {found=1} END{exit found?1:0}'
export ANDROID_AVD_HOME="$run/avd" ANDROID_EMULATOR_HOME="$run" ANDROID_USER_HOME="$run"
avd="$ANDROID_AVD_HOME/$name.avd"
mkdir -p "$avd"
printf 'avd.ini.encoding=UTF-8\npath=%s\ntarget=android-26\n' "$avd" > "$ANDROID_AVD_HOME/$name.ini"
sed -e "s/^AvdId = .*/AvdId = $name/" -e "s/^avd.ini.displayname = .*/avd.ini.displayname = $name/" \
    -e "s@^image.sysdir.1 = .*@image.sysdir.1 = $image/@" -e 's/^tag.id = .*/tag.id = default/' \
    -e 's/^tag.display = .*/tag.display = Default Android System Image/' \
    -e 's/^disk.dataPartition.size = .*/disk.dataPartition.size = 2147483648/' \
    .android-avd/NoteCorePreview.avd/config.ini > "$avd/config.ini"
echo "$run"
"$sdk/emulator/emulator" -avd "$name" -port 5582 -no-window -no-audio -no-snapshot \
    -gpu swiftshader_indirect > "$run/emulator.log" 2>&1 &
emulator=$!
cleanup() {
    kill "$emulator" 2>/dev/null || true
    wait "$emulator" 2>/dev/null || true
    rm -rf -- "$avd" "$ANDROID_AVD_HOME/$name.ini"
}
trap cleanup EXIT
ready=false
for attempt in $(seq 1 100); do
    kill -0 "$emulator"
    if test "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1; then
        ready=true
        break
    fi
    sleep 2
done
test "$ready" = true
test "$("$adb" -s "$serial" shell getprop ro.kernel.qemu.avd_name | tr -d '\r')" = "$name"
test "$("$adb" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')" = 26
"$adb" -s "$serial" shell getprop ro.build.fingerprint > "$run/fingerprint.txt"
"$adb" -s "$serial" shell getenforce > "$run/selinux.txt"
"$adb" -s "$serial" shell pm list packages dev.notecore.preview > "$run/before-packages.txt"
test ! -s "$run/before-packages.txt"
"$adb" -s "$serial" install "$app" > "$run/install-app.log"
"$adb" -s "$serial" install "$tests" > "$run/install-tests.log"
"$adb" -s "$serial" shell am instrument -w -r -e notecore_case sqlite_source_handoff \
    dev.notecore.preview.test/dev.notecore.app.ViewportInstrumentation > "$run/instrument.log" 2>&1
python3 - "$run" "$app" "$tests" <<'PY'
from pathlib import Path
import hashlib
import json
import sys

run, app, tests = map(Path, sys.argv[1:])
raw = (run / 'instrument.log').read_text()
prefix = 'INSTRUMENTATION_RESULT: sqlite_source_handoff='
observation, = [json.loads(line[len(prefix):]) for line in raw.splitlines() if line.startswith(prefix)]
assert 'INSTRUMENTATION_RESULT: sqlite_source_handoff_status=passed' in raw, raw
assert 'INSTRUMENTATION_CODE: -1' in raw, raw
for key in ('retirement_before_source_receipt_refused', 'retirement_after_source_receipt',
            'sqlite_rollback_preserves_source', 'new_recovery_after_rollback_same_canonical',
            'original_source_unchanged'):
    assert observation[key] is True
report = {
    'status': 'passed', 'milestone': 'DEV-041',
    'scope': 'Original Android View, Store, ledger and file worker to isolated SQLite; synthesized MotionEvents on API26 emulator',
    'fingerprint': (run / 'fingerprint.txt').read_text().strip(),
    'selinux': (run / 'selinux.txt').read_text().strip(),
    'observations': observation,
    'apk_sha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in (app, tests)},
    'production_sqlite_connected': False, 'physical_verified': False, 'process_kill_in_this_case': False,
}
(run / 'verification.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
print(run / 'verification.json')
PY
