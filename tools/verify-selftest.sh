#!/usr/bin/env bash
# Checks that tools/verify.py FAILS on a broken report.
#
# A verifier is only worth running if it can fail. This repo has already shipped one CI guard
# that passed vacuously -- a grep whose pattern was invalid, swallowed by `|| true`, so it
# approved everything for weeks. So every check in verify.py is exercised here against a report
# mutated to break exactly that check, and the run fails if the verifier still says yes.
#
# Usage: tools/verify-selftest.sh <a directory holding a good sample report>
set -uo pipefail

good="${1:-qualflare-espresso/build/sample-report}"
verify="$(dirname "$0")/verify.py"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fails=0

# A mutation is a python snippet that edits report.json in place, or touches the directory.
mutate() {
    local name="$1" snippet="$2"
    local dir="$work/$name"
    rm -rf "$dir"
    cp -R "$good" "$dir" || return 1
    python3 - "$dir" <<PY
import json, os, sys, glob
d = sys.argv[1]
path = glob.glob(os.path.join(d, "qualflare-espresso-*.json"))[0]
with open(path) as f:
    r = json.load(f)
cases = {c["id"]: c for s in r["suites"] for c in s["cases"]}
$snippet
with open(path, "w") as f:
    json.dump(r, f)
PY
    if python3 "$verify" "$dir" > "$dir/out.txt" 2>&1; then
        echo "  NOT CAUGHT  $name -- verify.py approved a report that is wrong"
        sed -n '1,8p' "$dir/out.txt"
        fails=$((fails + 1))
    else
        echo "  caught      $name"
    fi
}

echo "verify.py must accept the good report:"
if python3 "$verify" "$good" > "$work/good.txt" 2>&1; then
    echo "  ok          the unmodified sample report passes"
else
    echo "  BROKEN      verify.py rejects a good report:"
    cat "$work/good.txt"
    fails=$((fails + 1))
fi

echo "verify.py must reject each of these:"

mutate a-missing-case \
    'r["suites"][0]["cases"] = [c for c in r["suites"][0]["cases"] if "passes" not in c["id"]]'

mutate a-wrong-status \
    'cases[[k for k in cases if k.endswith("#timesOut")][0]]["status"] = "error"'

mutate an-ignored-test-that-vanished \
    'r["suites"][0]["cases"] = [c for c in r["suites"][0]["cases"] if "isIgnored" not in c["id"]]'

mutate a-lost-main-looper-step \
    'c = [v for k, v in cases.items() if k.endswith("#recordsMetadataFromTheMainLooper")][0];
c["steps"] = [s for s in c["steps"] if "main looper" not in s["name"]]'

mutate a-retry-flattened-into-one-attempt \
    'c = [v for k, v in cases.items() if k.endswith("#flakesOnceThenPasses")][0];
c.pop("attempts", None); c.pop("isFlaky", None); c.pop("retryCount", None)'

mutate a-class-failure-that-vanished \
    'r["suites"] = [s for s in r["suites"] if "BrokenSetup" not in s["name"]];
[s["cases"].remove(c) for s in r["suites"] for c in list(s["cases"]) if "qf-class-failure" in c["id"]]'

mutate an-attachment-inlined-instead-of-written \
    'c = [v for k, v in cases.items() if k.endswith("#takesAScreenshotWhenItFails")][0];
a = [x for x in c["attachments"] if x["name"] == "failure screenshot"][0];
a.pop("localImagePath"); a["content"] = "aGVsbG8="'

mutate base64-that-wrapped \
    'c = [v for k, v in cases.items() if k.endswith("#attachesBytesAndAnImage")][0];
a = [x for x in c["attachments"] if x["name"] == "notes.txt"][0];
a["content"] = a["content"][:4] + "\n" + a["content"][4:]'

mutate the-generic-os-name 'r["os"] = "Linux"'

mutate the-unset-version 'r["metadata"]["version"] = "0.0.0-dev"'

mutate a-unit-category 'r["suites"][0]["category"] = "unit"'

mutate a-localimagepath-that-is-a-path \
    'c = [v for k, v in cases.items() if k.endswith("#takesAScreenshotWhenItFails")][0];
a = [x for x in c["attachments"] if x["name"] == "failure screenshot"][0];
a["localImagePath"] = "../../etc/passwd"'

# Two that need the filesystem rather than the JSON.
dangling="$work/a-dangling-image"
rm -rf "$dangling"; cp -R "$good" "$dangling"
rm -f "$dangling"/qf-attach-*failure_screenshot*.png
if python3 "$verify" "$dangling" > "$dangling/out.txt" 2>&1; then
    echo "  NOT CAUGHT  a-dangling-image -- the report referenced an image that is not there"
    fails=$((fails + 1))
else
    echo "  caught      a-dangling-image"
fi

empty="$work/no-report-at-all"
mkdir -p "$empty"
if python3 "$verify" "$empty" > "$empty/out.txt" 2>&1; then
    echo "  NOT CAUGHT  no-report-at-all -- nothing reached the host and verify.py said fine"
    fails=$((fails + 1))
else
    echo "  caught      no-report-at-all"
fi

if [ "$fails" -ne 0 ]; then
    echo
    echo "$fails self-test(s) failed: verify.py cannot be trusted to catch a broken report."
    exit 1
fi
echo
echo "verify.py catches every mutation."
