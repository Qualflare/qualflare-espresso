# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **The reporter.** `QualflareRunListener`, registered with one line
  (`testInstrumentationRunnerArguments["listener"]`), writes a Qualflare report from an
  instrumented Espresso run: status, per-attempt retry history, steps, attachments and
  author-written metadata.
- **Delivery with no `adb pull`** on API 29+, through `PlatformTestStorage` into the directory
  Gradle already collects. Below that — where Gradle passes no `additionalTestOutputDir` — the
  report goes to the app's files directory and the reporter prints the exact command to run.
  The report and every image it writes always travel the same route, so `localImagePath` resolves.
- **The `Qualflare` API**: labels, tags, links, priority, description, parameters, masked
  parameters, nestable timed steps, and attachments from `byte[]`, a `File` or a `Bitmap`. Safe to
  call from the main looper, which is where `onActivity {}` and `ViewAction.perform` run.
- **`QualflareRule`**, which photographs the screen when a test fails, while the app is still on
  it. Declared inside the activity's own rule; a listener cannot do this, because `@After` has
  already closed the activity by the time JUnit reports the failure.
- **Incremental flushing.** The report is rewritten every few seconds during the run, so an
  emulator that dies mid-suite still leaves every case that finished.
- **A device gate** (`Actions → Emulator`) running the fixture suite across API levels, plain and
  under Android Test Orchestrator, with `tools/verify.py` deciding whether the report that reached
  the host is right — and `tools/verify-selftest.sh` proving the verifier can still fail.

### Fixed

- **A green report from a run where nothing ran.** Android Test Orchestrator enumerates the suite
  before executing it, and in that pass the runner fires `testStarted`/`testFinished` for every
  test without running any. The reporter believed it: the first orchestrator emulator legs produced
  twelve cases, all passed, all empty — written *first*, so merging a run's reports read the
  enumeration instead of the tests. The reporter now recognises both of androidx.test's dry-run
  arguments and reports nothing for that pass.
- **A reporter that could not be diagnosed on a device.** Its messages went only to `System.out`,
  which an instrumented app does not reliably deliver and never delivers under the orchestrator.
  Everything now also goes to logcat under the tag `QualflareEspresso`, the sink announces which
  delivery route it chose and why, and the fallback nothing collects stopped being silent.

### Notes

- The task-0 spike (`android-spike.yml`, `StorageSpikeTest`) is retired: the Emulator workflow
  covers the same matrix and asserts on the result rather than printing it. Its findings stay in
  `docs/SPIKE-2026-09-20.md`.
