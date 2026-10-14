#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$task_root"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$task_root/.gradle-user-home}"
export KONAN_DATA_DIR="${KONAN_DATA_DIR:-$task_root/.konan}"
task_python="${PYTHON_BIN:-python3}"
task_native_args=()
if [[ -n "${KOTLIN_NATIVE_HOME:-}" ]]; then task_native_args+=("-Pkotlin.native.home=$KOTLIN_NATIVE_HOME"); fi
./gradlew --no-daemon --console=plain -PenableAppleTargets=true "${task_native_args[@]}" :canvas-core:linkDebugFrameworkIosSimulatorArm64
mkdir -p ios-app/build
if ! xcodebuild -quiet -project ios-app/NoteCorePreview.xcodeproj -scheme NoteCorePreview \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath ios-app/build CODE_SIGNING_ALLOWED=NO build > ios-app/build/preview-build.log 2>&1; then
  cat ios-app/build/preview-build.log
  exit 1
fi
"$task_python" tools/package_ios_preview.py --record-success
