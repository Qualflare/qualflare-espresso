# Limitations

Every entry here was measured on a device or pinned by a test. Where a number appears, it came
from a run, not from a guess about one.

## API 24–28: the report needs one flag and one `adb pull`

From **API 29** Gradle passes `additionalTestOutputDir`, the report goes through test storage, and
Gradle pulls it to `app/build/outputs/connected_android_test_additional_output/…` by itself. That
is the install story, and it works — verified on API 29 and 34, plain and under the orchestrator.

Below API 29 Gradle passes no such argument, so the reporter writes into the app's own external
files directory. The catch is not the directory; it is what happens next:

> `connectedAndroidTest` **uninstalls both APKs when it finishes**, and uninstalling an app deletes
> its external files directory, report included.

Measured on an API 24 emulator: the reporter wrote all ten cases, and by the time anything went
looking, `/storage/emulated/0/Android/data` was empty and `run-as` reported the package unknown.

There is no directory an instrumented app can use that escapes this. `getExternalMediaDirs()`,
`getExternalCacheDir()` and `getCacheDir()` are all app-specific — and they are also precisely what
androidx.test's own `TestDirCalculator` falls back to when `additionalTestOutputDir` is unset, so
the storage route dies the same way. Writing somewhere durable would mean
`WRITE_EXTERNAL_STORAGE`, which this library will not add to your app's manifest.

So on API 24–28, collect the run like this:

```bash
./gradlew connectedAndroidTest \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
adb pull /storage/emulated/0/Android/data/<your.app>/files/qualflare-results ./qualflare-results
qf my-project collect ./qualflare-results
adb uninstall <your.app>
```

The reporter prints those exact commands, filled in for your app, at the end of the run. Verified:
with that flag, the API 24 legs pass every check the API 29 legs do.

## Android Test Orchestrator

Supported, and measured: eleven test processes produced **eleven report files and ten distinct
cases** — one file per process, merged on the way in. The filename carries the pid for exactly
this reason. (Ten rather than eleven because the two tests behind a failing `@BeforeClass` each
report the same synthetic class-failure case, which merges.)

**`clearPackageData = true` is not supported below API 29.** It wipes the app's data between
tests, which is where the report lives on those API levels. Test storage survives it, so from API
29 up it is fine.

## The enumeration pass reports nothing

The orchestrator lists the whole suite before running any of it, and `-e log true` does the same
for a dry run. In that pass the runner fires `testStarted` and `testFinished` for every test
**without executing any of them**. The reporter recognises both arguments and writes nothing.

This is deliberate, and it is not a small thing: before the guard existed, those passes produced a
complete, valid-looking report in which every test had passed — written first, so merging a run's
reports read the enumeration instead of the tests.

## Retries need a Runner, not a Rule

A retry `TestRule` loops inside a single statement, so JUnit sees one `testStarted`/`testFinished`
pair and the report records one attempt: a test that failed twice before passing is
indistinguishable from one that passed first time. Nothing can recover that from outside.

A retry **runner** — one that calls `runChild` again — fires the whole notification sequence per
attempt under the same description, and the report gets an `attempts` array and `isFlaky`. See
`fixture-app`'s `RetryRunner` for the shape; third-party retry runners behave the same way.

## Screenshots

- A window with `FLAG_SECURE` (a payment or password screen) cannot be captured. The capture
  returns nothing and the run carries on — a test that fails *there* must not fail twice.
- `QualflareRule` must be declared **inside** the rule that launches the activity. Outside it, the
  activity is already closed and every screenshot is of the launcher.
- An `@After` that closes the activity itself defeats it too: `@After` runs inside all rules.
- Automatic capture cannot live in the listener at all. `RunAfters` runs the `@After` methods and
  *then* rethrows, so by the time JUnit notifies a listener every rule has unwound.

## Caps

| | Limit | Past it |
|---|---|---|
| Steps per attempt | 300 | dropped, with a warning in the report and on stderr |
| Attachments per case | 50 | dropped |
| Inline attachment bytes per run | 8 MiB encoded | recorded with its size, without its content |
| Image files per run | 100 | recorded without its file |

An attachment past a limit is still recorded, because "nothing here" and "too many to keep" are
different facts about a run.

## A process killed mid-write

The report is rewritten every few seconds during the run, so an emulator that dies mid-suite
leaves every case that finished before the last flush. The window that remains is the write
itself: a process killed *during* a write leaves a truncated file. Making that atomic would need a
rename, and test storage has no rename.

## `@Ignore`'s reason is not reported

`testIgnored` carries no reason string — JUnit 4 does not put `@Ignore("because…")` in the
notification — so the case is reported as skipped with `@Ignore` as its reason and nothing more.

## Not for Robolectric or JVM unit tests

This is a reporter for instrumented runs. Off a device there is no test storage and no app
context, so it says so once and reports nothing. Use `qualflare-junit5` for JVM tests.
