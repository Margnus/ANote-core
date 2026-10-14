#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$task_root"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 before running verification}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$task_root/.gradle-user-home}"
task_python="${PYTHON_BIN:-python3}"
"$task_python" tools/check_architecture.py
./gradlew --no-daemon --console=plain verifyPrototype :canvas-core:runPrototype
"$task_python" tools/collect_verification.py
