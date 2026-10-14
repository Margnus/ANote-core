#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$task_root"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17}"
: "${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK with platform 36 and build-tools 35+}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$task_root/.gradle-user-home}"
task_python="${PYTHON_BIN:-python3}"
./gradlew --no-daemon --console=plain -PenableAndroidTargets=true verifyPrototype :android-app:verifyPreviewAndroid
"$task_python" tools/collect_verification.py
"$task_python" tools/package_android_preview.py
