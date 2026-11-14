#!/usr/bin/env bash
set -euo pipefail
test_exit=0
filter=()
if [[ -n "$TEST_CLASSES" ]]; then filter+=("-Pandroid.testInstrumentationRunnerArguments.class=$TEST_CLASSES"); fi
./gradlew --no-daemon "$TEST_TASK" "${filter[@]}" || test_exit=$?
mkdir -p .ci-results/fixtures
adb logcat -d -v brief '*:E' > .ci-results/emulator-errors.log || true
for name in wear-refresh-ready.png wear-refresh-arrival.png wear-refresh-running.png; do
  if adb shell run-as "$TEST_PACKAGE" test -f "cache/$name"; then
    adb exec-out run-as "$TEST_PACKAGE" cat "cache/$name" > ".ci-results/fixtures/$name"
  fi
done
exit "$test_exit"
