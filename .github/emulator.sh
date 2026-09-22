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

# An optional argument switches the fixture from the reporter in this repo to a PUBLISHED one
# resolved from Maven Central, and makes the verifier insist the report carries exactly that
# version. That is the whole point of the consumer check: proving the version generated at build
# time survived publication.
published="${1:-}"
use_published=""
expect_version=""
if [ -n "$published" ]; then
    use_published="-Pqualflare.usePublished=$published"
    expect_version="$published"
    echo "=== testing the PUBLISHED artifact com.qualflare:qualflare-espresso:$published"
fi

sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
echo "=== device: API $sdk, $(adb shell getprop ro.product.model | tr -d '\r')"
adb devices -l

echo "=== run the fixture suite"
# Below API 29 the report is written into the app's own external files directory, and
# connectedAndroidTest uninstalls the app when it finishes -- which deletes it. Measured: an
# API 24 leg wrote ten cases and then had nothing to pull, with /Android/data empty and the
# package unknown to run-as. Keeping the APKs installed is the documented way to collect that
# run, and is exactly what the reporter now tells a user to do.
keep=""
if [ "$sdk" -lt 29 ]; then
    keep="-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true"
    echo "API $sdk: keeping the APKs installed so the report survives the run"
fi
./gradlew :fixture-app:connectedDebugAndroidTest $keep $use_published --stacktrace
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
    # The path the REPORTER named in logcat, not a guess about it. The two spellings are
    # usually the same mount, but on API 24 the shell user and the app do not necessarily see
    # external storage the same way, which is the thing this leg is here to settle.
    app_dir=$(sed -n 's/.*so the report goes to \(.*\)$/\1/p' reporter-logcat.txt | tail -1 | tr -d '\r')
    echo "the reporter says it wrote to: ${app_dir:-(nothing in logcat)}"
    pulled=1
    for candidate in \
        "$app_dir" \
        /storage/emulated/0/Android/data/com.qualflare.espresso.fixture/files/qualflare-results \
        /sdcard/Android/data/com.qualflare.espresso.fixture/files/qualflare-results \
        /storage/emulated/legacy/Android/data/com.qualflare.espresso.fixture/files/qualflare-results
    do
        [ -n "$candidate" ] || continue
        echo "--- trying $candidate"
        if adb pull "$candidate" collected/ 2>&1; then
            pulled=0
            echo "pulled from $candidate"
            break
        fi
    done
    if [ "$pulled" -ne 0 ]; then
        # Nothing could be pulled although the reporter says it wrote. Print what the shell user
        # can actually see, which decides whether the adb pull the reporter PRINTS is advice that
        # works for a user on this API level.
        echo "--- who the shell is:"; adb shell id
        echo "--- /storage/emulated/0:"; adb shell 'ls -l /storage/emulated/0 2>&1' | head -20
        echo "--- /storage/emulated/0/Android/data:"; adb shell 'ls -l /storage/emulated/0/Android/data 2>&1' | head -20
        echo "--- the app's own view, via run-as:"
        adb shell 'run-as com.qualflare.espresso.fixture ls -l /storage/emulated/0/Android/data/com.qualflare.espresso.fixture/files/qualflare-results 2>&1' || true
        echo "--- mounts mentioning emulated:"; adb shell 'mount 2>&1 | grep -i emulated' || true
    fi
fi

echo "=== what was collected"
find collected -type f | sort

echo "=== how many report files (one per run, or one per test under the orchestrator)"
find collected -name 'qualflare-espresso-*.json' | wc -l

echo "=== verify"
python3 tools/verify.py collected $expect_version
