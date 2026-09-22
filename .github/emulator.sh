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

echo "=== what the reporter itself said"
# Its stdout does not reliably reach Gradle's console -- under the orchestrator each test is a
# separate process whose output Gradle never relays -- so the reporter logs to logcat too. This
# is the only place a device run explains which delivery route it chose.
adb logcat -d -s QualflareEspresso > reporter-logcat.txt 2>/dev/null || true
if [ -s reporter-logcat.txt ]; then
    cat reporter-logcat.txt
else
    echo "(nothing under the QualflareEspresso tag: the listener never ran)"
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
    pulled=$?
    echo "adb pull exit=$pulled"
    if [ "$pulled" -ne 0 ]; then
        # The pull path is a guess about where the reporter wrote; the logcat above is the fact.
        # List the candidates so a failing leg says where the report IS, not only where it is not.
        echo "--- the app's files directory:"
        adb shell 'ls -lR /sdcard/Android/data/com.qualflare.espresso.fixture/files 2>&1' || true
        echo "--- androidx.test storage's own output directory:"
        adb shell 'ls -lR /sdcard/googletest 2>&1' || true
        adb shell 'ls -lR /storage/emulated/0/googletest 2>&1' || true
    fi
fi

echo "=== what was collected"
find collected -type f | sort

echo "=== how many report files (one per run, or one per test under the orchestrator)"
find collected -name 'qualflare-espresso-*.json' | wc -l

echo "=== verify"
python3 tools/verify.py collected
