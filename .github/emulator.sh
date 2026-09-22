#!/usr/bin/env bash
# Runs inside the emulator action for .github/workflows/emulator.yml.
#
# A file, not inline YAML: the action feeds `script:` to `sh -c` one line at a time, so a
# backslash continuation reaches the shell literally and a `cd` does not survive to the next line.
#
# Deliberately NOT `set -e`. Several fixture tests fail on purpose, so a non-zero
# connectedDebugAndroidTest is the expected outcome and the thing that matters is what the run
# left behind. The leg's verdict is tools/verify.py's exit code, which is this script's last
# command.
set -uo pipefail

sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
echo "=== device: API $sdk, $(adb shell getprop ro.product.model | tr -d '\r')"
adb devices -l

echo "=== run the fixture suite"
./gradlew :fixture-app:connectedDebugAndroidTest --stacktrace
status=$?
echo "connectedDebugAndroidTest exit=$status"
if [ "$status" -eq 0 ]; then
    # Six fixture tests fail on purpose. A green run means they stopped failing, which means
    # the suite stopped testing the statuses it exists to produce.
    echo "::error::connectedDebugAndroidTest passed, but the fixture suite is supposed to fail."
    exit 1
fi

echo "=== did anything reach the host with no adb command?"
out=fixture-app/build/outputs/connected_android_test_additional_output
mkdir -p collected
if find "$out" -name 'qualflare-espresso-*.json' 2>/dev/null | grep -q .; then
    echo "yes: AGP pulled additionalTestOutputDir. This is the zero-adb install story working."
    cp -R "$out"/. collected/
else
    # Expected below API 29: Gradle passes no additionalTestOutputDir there, so the reporter
    # wrote to the app's external files directory and printed the pull command itself. The
    # workflow runs that command so the leg can still check the report.
    echo "no additional output on API $sdk -- falling back to the app's files directory"
    adb pull /sdcard/Android/data/com.qualflare.espresso.fixture/files/qualflare-results collected/
    echo "adb pull exit=$?"
fi

echo "=== what was collected"
find collected -type f | sort

echo "=== how many report files (one per run, or one per test under the orchestrator)"
find collected -name 'qualflare-espresso-*.json' | wc -l

echo "=== verify"
python3 tools/verify.py collected
