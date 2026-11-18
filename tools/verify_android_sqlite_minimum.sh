#!/bin/bash
# Run the pre-OpenParams SQLite candidate on an isolated API 26 ARM64 emulator.
set -euo pipefail
cd "$(dirname "$0")/.."
root=$PWD
sdk="${ANDROID_HOME:?set ANDROID_HOME to a local Android SDK}"
jdk="${JAVA_HOME:?set JAVA_HOME to JDK 17}"
image="${1:?pass the local API 26 ARM64 system-image directory}"
test -f "$image/system.img"
image=$(cd "$image" && pwd)
adb="$sdk/platform-tools/adb"
port=5582
serial="emulator-$port"
"$adb" devices | awk -v serial="$serial" '$1 == serial {found=1} END{exit found?1:0}'
mkdir -p build
run=$(mktemp -d "$root/build/sqlite-minimum.XXXXXX")
echo "$run"
name="NoteCoreSqlite26-${run##*.}"
export ANDROID_AVD_HOME="$run/avd" ANDROID_EMULATOR_HOME="$run" ANDROID_USER_HOME="$run"
avd="$ANDROID_AVD_HOME/$name.avd"
mkdir -p "$avd" "$run/classes"
printf 'avd.ini.encoding=UTF-8\npath=%s\ntarget=android-26\n' "$avd" > "$ANDROID_AVD_HOME/$name.ini"
sed -e "s/^AvdId = .*/AvdId = $name/" -e "s/^avd.ini.displayname = .*/avd.ini.displayname = $name/" \
    -e "s@^image.sysdir.1 = .*@image.sysdir.1 = $image/@" -e 's/^tag.id = .*/tag.id = default/' \
    -e 's/^tag.display = .*/tag.display = Default Android System Image/' \
    -e 's/^disk.dataPartition.size = .*/disk.dataPartition.size = 2147483648/' \
    .android-avd/NoteCorePreview.avd/config.ini > "$avd/config.ini"
android_jar="$sdk/platforms/android-36/android.jar"
"$jdk/bin/javac" --release 8 -classpath "$android_jar" -d "$run/classes" tools/probes/SqliteMinimumProbe.java
"$jdk/bin/java" -cp "$sdk/build-tools/36.0.0/lib/d8.jar" com.android.tools.r8.D8 \
    --min-api 26 --lib "$android_jar" --output "$run/probe.jar" "$run/classes/SqliteMinimumProbe.class"
"$sdk/emulator/emulator" -avd "$name" -port "$port" -no-window -no-audio -no-snapshot \
    -gpu swiftshader_indirect > "$run/emulator.log" 2>&1 &
emulator=$!
cleanup() {
    kill "$emulator" 2>/dev/null || true
    wait "$emulator" 2>/dev/null || true
    # Only the AVD created by mktemp above is removed.
    rm -rf -- "$avd" "$ANDROID_AVD_HOME/$name.ini"
}
trap cleanup EXIT
connected=false
for attempt in $(seq 1 100); do
    kill -0 "$emulator"
    if test "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1; then
        connected=true
        break
    fi
    sleep 2
done
test "$connected" = true
test "$("$adb" -s "$serial" shell getprop ro.kernel.qemu.avd_name | tr -d '\r')" = "$name"
test "$("$adb" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')" = 26
"$adb" -s "$serial" shell getprop ro.build.fingerprint > "$run/fingerprint.txt"
"$adb" -s "$serial" shell getprop ro.product.cpu.abi > "$run/abi.txt"
"$adb" -s "$serial" shell getenforce > "$run/selinux.txt"
prepare_fixture() {
    remote="/data/local/tmp/notecore-sqlite-minimum-$(uuidgen | tr -d '-' | tr 'A-F' 'a-f')"
    "$adb" -s "$serial" shell mkdir "$remote"
    "$adb" -s "$serial" push "$run/probe.jar" "$remote/probe.jar"
    actual=$("$adb" -s "$serial" shell sha256sum "$remote/probe.jar" | awk '{print $1}')
    test "$actual" = "$(shasum -a 256 "$run/probe.jar" | awk '{print $1}')"
}

index=0
for stage in init before-commit verify after-commit verify replay conflict verify init reopened-writer replay; do
    if test "$stage" = init; then
        prepare_fixture
    fi
    index=$((index+1))
    status=0
    "$adb" -s "$serial" shell "CLASSPATH=$remote/probe.jar /system/bin/app_process /system/bin SqliteMinimumProbe $remote $stage" \
        > "$run/$index-$stage.log" 2>&1 || status=$?
    printf '%s\n' "$status" > "$run/$index.exit"
    if test "$stage" = before-commit || test "$stage" = after-commit; then
        test "$status" = 137
    else
        test "$status" = 0
    fi
done
python3 - "$run" <<'PY'
import json
from pathlib import Path
import sys

root = Path(sys.argv[1])
prefix = 'NOTECORE_SQLITE_MINIMUM '
expected = [('init', '1', '0', 'last-confirmed'), ('before-commit',),
            ('verify', '1', '0', 'last-confirmed'), ('after-commit',),
            ('verify', '2', '1', 'new-checkpoint'), ('replay', '2', '1', 'new-checkpoint'),
            ('conflict', '2', '1', 'new-checkpoint'), ('verify', '2', '1', 'new-checkpoint'),
            ('init', '1', '0', 'last-confirmed'), ('reopened-writer', '2', '1', 'new-checkpoint'),
            ('replay', '2', '1', 'new-checkpoint')]
observations = []
for index, (stage, *state) in enumerate(expected, 1):
    text = (root / f'{index}-{stage}.log').read_text()
    outcome, = (json.loads(line[len(prefix):]) for line in text.splitlines() if line.startswith(prefix))
    if not state:
        assert outcome['cut'] == stage
    else:
        assert [outcome[key] for key in ('revision', 'operation_count', 'payload')] == state
        assert outcome['journal_mode'] == 'wal' and outcome['writer_synchronous'] == '2'
        assert outcome['quick_check'] == 'ok'
        if stage == 'init':
            assert outcome['normal_control_synchronous'] == '1'
        if stage == 'reopened-writer':
            assert outcome['reader_revision_while_writer_held_lock'] == '1'
            assert outcome['contender_refused_busy'] and outcome['normal_replacement_refused']
        expected_status = {'replay': 'REPLAYED', 'conflict': 'REFUSED', 'reopened-writer': 'COMMITTED'}
        assert outcome['status'] == expected_status.get(stage, stage)
    assert outcome['pid'] not in {item['pid'] for item in observations}
    outcome['exit'] = int((root / f'{index}.exit').read_text())
    observations.append(outcome)
(root / 'observations.json').write_text(json.dumps(observations, indent=2) + '\n', encoding='utf-8')
print('Confirmed API 26 WAL/FULL on the writer across pool reopen, lock contention and both process recovery boundaries')
PY
