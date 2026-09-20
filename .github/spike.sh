#!/usr/bin/env bash
# Runs inside the emulator action for .github/workflows/android-spike.yml.
#
# A file, not inline YAML: the action feeds `script:` to `sh -c` one line at a time,
# so a backslash continuation reaches the tool literally and a `cd` does not survive
# to the next line.
#
# Deliberately NOT `set -e`: one fixture test fails on purpose, so a non-zero
# connectedAndroidTest is the expected outcome. What matters is what the run left
# behind, which this script then reports on.
set -uo pipefail

echo "=== device"
adb devices -l
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.model

echo "=== question 2+3: run the instrumented tests (one test fails on purpose)"
./gradlew :fixture-app:connectedDebugAndroidTest --stacktrace
echo "connectedDebugAndroidTest exit=$? (1 expected: failsAfterTeardown fails by design)"

echo "=== question 1: did anything reach the host, with no adb pull?"
out=fixture-app/build/outputs/connected_android_test_additional_output
if [ -d "$out" ]; then
  find "$out" -type f | sort
  echo "--- contents of each file:"
  find "$out" -type f -name '*.json' -o -type f -name '*.txt' | while IFS= read -r f; do
    echo "----- $f"
    head -c 400 "$f"
    echo
  done
else
  echo "NOTHING at $out -- additionalTestOutputDir was not pulled on this leg"
  echo "--- is the file on the device instead?"
  adb shell 'ls -la /sdcard/googletest/test_outputfiles 2>/dev/null || true'
  adb shell 'ls -la /storage/emulated/0/Android/media/com.qualflare.espresso.fixture 2>/dev/null || true'
fi

echo "=== questions 2+3: the listener's own log, in order"
# The listener logs under QualflareEspresso; the fixture prints SPIKE lines. Read the
# interleaving to settle whether testFailure lands before or after @After teardown.
adb logcat -d > spike-logcat.txt 2>/dev/null || true
grep -E "QualflareEspresso|SPIKE" spike-logcat.txt | tail -40 || echo "(no marker lines in logcat)"

echo "=== test result XML, for the record"
find fixture-app/build/outputs/androidTest-results -name '*.xml' -exec sh -c 'echo "--- $1"; head -20 "$1"' _ {} \; 2>/dev/null | head -60
