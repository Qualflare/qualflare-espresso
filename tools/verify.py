#!/usr/bin/env python3
"""Checks the report an emulator run actually produced against what it must contain.

    python3 tools/verify.py <directory> [expected-version]

Given an expected version, the report's metadata.version must equal it exactly. That is the
check for a run against a PUBLISHED artifact: it is the only way to prove the version generated
into the AAR survived publication, and the JUnit 5 sibling once shipped a release whose reports
all claimed 0.0.0-dev.

The directory is wherever the reports landed: on API 29+ that is
`fixture-app/build/outputs/connected_android_test_additional_output/...`, pulled by AGP with no adb
command; on API 24-28 it is whatever the workflow pulled off the device, because Gradle passes no
additionalTestOutputDir there (measured, docs/SPIKE-2026-09-20.md).

Every expectation below corresponds to a fixture test that exists to produce it. The point is that
a green emulator leg means the REPORT is right, not merely that Gradle exited zero -- which it
cannot, since several fixture tests fail on purpose.

Under Android Test Orchestrator each test runs in its own process and writes its own report, so
reports are merged by case id before anything is checked. That merge is also the assertion that
per-process reports do not collide: the filename carries the pid for exactly this reason.
"""

import json
import os
import sys

FIXTURE = "com.qualflare.espresso.fixture"
REPORT_PREFIX = "qualflare-espresso-"


class Failure(Exception):
    pass


def find_reports(root):
    found = []
    for dirpath, _, filenames in os.walk(root):
        for name in sorted(filenames):
            if name.startswith(REPORT_PREFIX) and name.endswith(".json"):
                found.append(os.path.join(dirpath, name))
    return found


def load(paths):
    """Merges every report into one case map, keeping the directory each case came from.

    The directory matters: an attachment's localImagePath is a BARE filename that qf collect
    resolves next to the report it was found in, so checking the file exists means checking it
    exists there and not merely somewhere.
    """
    cases = {}
    tops = []
    for path in paths:
        with open(path, "r", encoding="utf-8") as handle:
            try:
                report = json.load(handle)
            except ValueError as exc:
                raise Failure("%s is not valid JSON: %s" % (path, exc))
        tops.append((path, report))
        for suite in report.get("suites", []):
            for case in suite.get("cases", []):
                case["_dir"] = os.path.dirname(path)
                case["_suite"] = suite
                cases.setdefault(case.get("id"), case)
    return tops, cases


def check_top_level(tops, expected_version=None):
    for path, report in tops:
        where = os.path.basename(path)
        expect(report.get("framework") == "espresso", where, "framework", report.get("framework"))
        expect(report.get("platform") == "android", where, "platform", report.get("platform"))
        os_name = report.get("os") or ""
        expect(os_name.startswith("Android "), where,
               "os should name the device's Android release, not 'Linux'", os_name)
        version = (report.get("metadata") or {}).get("version") or ""
        expect(version and not version.startswith("0.0.0"), where,
               "metadata.version should be the built version, not the unset default", version)
        if expected_version is not None:
            expect(version == expected_version, where,
                   "metadata.version should be exactly the released version %r" % expected_version,
                   version)
        for suite in report.get("suites", []):
            expect(suite.get("category") == "e2e", where,
                   "suite category for an instrumented run", suite.get("category"))


def expect(condition, where, what, got):
    if not condition:
        raise Failure("%s: %s -- got %r" % (where, what, got))


def case(cases, cls, method):
    key = "%s.%s#%s" % (FIXTURE, cls, method)
    if key not in cases:
        raise Failure("no case %s in the report; the suite had %d case(s): %s"
                      % (key, len(cases), ", ".join(sorted(cases)) or "(none)"))
    return cases[key]


def status_of(c, expected):
    if c.get("status") != expected:
        raise Failure("%s: expected status %s, got %s (error=%r)"
                      % (c.get("id"), expected, c.get("status"), c.get("error")))


def attachment_named(c, name):
    for a in c.get("attachments", []):
        if a.get("name") == name:
            return a
    raise Failure("%s: no attachment named %r; has %r"
                  % (c.get("id"), name, [a.get("name") for a in c.get("attachments", [])]))


def check_local_image(c, attachment):
    path = attachment.get("localImagePath")
    if not path:
        raise Failure("%s: attachment %r has no localImagePath, so the image was not written"
                      % (c.get("id"), attachment.get("name")))
    if "/" in path or "\\" in path:
        raise Failure("%s: localImagePath %r must be a bare filename -- qf collect resolves it "
                      "next to the report" % (c.get("id"), path))
    beside = os.path.join(c["_dir"], path)
    if not os.path.isfile(beside):
        raise Failure("%s: localImagePath %r does not exist next to its report in %s -- a "
                      "dangling image is worse than none" % (c.get("id"), path, c["_dir"]))
    if os.path.getsize(beside) == 0:
        raise Failure("%s: %s is empty" % (c.get("id"), beside))


def step_names(c):
    """Every step's name.

    Steps ride FLAT on the wire with a parentIndex pointing at the enclosing step, rather than
    nested, so a nesting walk here would be dead code that hides a missing child step.
    """
    return [s.get("name") for s in c.get("steps", [])]


def checks(cases):
    yield "a passing test is reported as passed", lambda: status_of(
        case(cases, "ReporterTest", "passes"), "passed")

    yield "@Ignore arrives with no start and no finish, and must still appear", lambda: status_of(
        case(cases, "ReporterTest", "isIgnored"), "skipped")

    yield "an assumption failure is a skip, not a failure", lambda: status_of(
        case(cases, "ReporterTest", "skipsOnAnAssumption"), "skipped")

    yield "@Test(timeout) is a timeout, not a generic error", lambda: status_of(
        case(cases, "ReporterTest", "timesOut"), "timeout")

    def missing_view():
        c = case(cases, "ReporterTest", "failsOnAMissingView")
        status_of(c, "failed")
        if "NoMatchingViewException" not in (c.get("error") or "") \
                and "No views in hierarchy" not in (c.get("error") or ""):
            raise Failure("%s: the error should name the Espresso failure, got %r"
                          % (c.get("id"), c.get("error")))

    yield "NoMatchingViewException is failed, the commonest Espresso failure", missing_view

    def metadata():
        c = case(cases, "ReporterTest", "recordsMetadataFromTheMainLooper")
        status_of(c, "passed")
        names = step_names(c)
        if "a step recorded on the main looper" not in names:
            raise Failure("%s: the main-looper step is missing -- a ThreadLocal current-case "
                          "would lose exactly this. Steps present: %r" % (c.get("id"), names))
        if "a step recorded on the instrumentation thread" not in names:
            raise Failure("%s: steps present: %r" % (c.get("id"), names))
        labels = {l.get("name"): l.get("value") for l in c.get("labels", [])}
        if labels.get("team") != "identity":
            raise Failure("%s: labels were %r" % (c.get("id"), labels))
        if sorted(c.get("tags", [])) != ["login", "smoke"]:
            raise Failure("%s: tags were %r" % (c.get("id"), c.get("tags")))
        if c.get("priority") != "high":
            raise Failure("%s: priority was %r" % (c.get("id"), c.get("priority")))

    yield "metadata written from the main looper lands on the right case", metadata

    def attachments():
        c = case(cases, "ReporterTest", "attachesBytesAndAnImage")
        status_of(c, "passed")
        inline = attachment_named(c, "notes.txt")
        if not inline.get("content"):
            raise Failure("%s: a non-image should be inlined as base64" % c.get("id"))
        if "\n" in inline.get("content"):
            raise Failure("%s: base64 must be NO_WRAP" % c.get("id"))
        from_file = attachment_named(c, "from-a-file.txt")
        if not from_file.get("content"):
            raise Failure("%s: the File overload produced no content" % c.get("id"))
        check_local_image(c, attachment_named(c, "the app, while it is up"))

    yield "bytes inline, images beside the report", attachments

    def screenshot():
        c = case(cases, "ReporterTest", "takesAScreenshotWhenItFails")
        status_of(c, "failed")
        check_local_image(c, attachment_named(c, "failure screenshot"))

    yield "a failing test is photographed while the app is still up", screenshot

    def class_failure():
        c = case(cases, "BrokenSetupTest", "[qf-class-failure]")
        status_of(c, "error")
        if "@BeforeClass threw on purpose" not in (c.get("error") or ""):
            raise Failure("%s: error was %r" % (c.get("id"), c.get("error")))

    yield "a @BeforeClass failure does not vanish", class_failure

    def flaky():
        c = case(cases, "FlakyRetryTest", "flakesOnceThenPasses")
        status_of(c, "passed")
        if not c.get("isFlaky"):
            raise Failure("%s: a fail-then-pass must be flaky, not a plain pass" % c.get("id"))
        attempts = c.get("attempts") or []
        if [a.get("status") for a in attempts] != ["failed", "passed"]:
            raise Failure("%s: attempts were %r" % (c.get("id"), attempts))
        if c.get("retryCount") != 1:
            raise Failure("%s: retryCount was %r" % (c.get("id"), c.get("retryCount")))

    yield "a retried test is one case with its history intact", flaky


def main(argv):
    if len(argv) not in (2, 3):
        print(__doc__)
        return 2
    root = argv[1]
    expected_version = argv[2] if len(argv) == 3 else None
    paths = find_reports(root)
    print("reports under %s:" % root)
    for p in paths:
        print("  %s (%d bytes)" % (p, os.path.getsize(p)))
    if not paths:
        print("FAIL: no %s*.json anywhere under %s. Nothing reached the host."
              % (REPORT_PREFIX, root))
        return 1

    try:
        tops, cases = load(paths)
        check_top_level(tops, expected_version)
    except Failure as exc:
        print("FAIL: %s" % exc)
        return 1

    print("\n%d report file(s), %d distinct case(s)\n" % (len(paths), len(cases)))
    failures = []
    for description, check in checks(cases):
        try:
            check()
            print("  ok    %s" % description)
        except Failure as exc:
            failures.append((description, exc))
            print("  FAIL  %s\n          %s" % (description, exc))

    if failures:
        print("\n%d of the report's guarantees did not hold." % len(failures))
        return 1
    print("\nEvery guarantee held.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
