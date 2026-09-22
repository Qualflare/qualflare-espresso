# Configuration

Every option resolves in the same order:

**instrumentation argument → system property → environment variable → default**

That order exists because of what an instrumented test actually is. The test JVM *is* the app
process, started by the runner, so there is no Surefire-style `systemPropertyVariables` to set a
property per run, and an app inherits no useful environment from your shell. The channel that a CI
run can genuinely set is `testInstrumentationRunnerArguments`, which arrive as a `Bundle` through
`InstrumentationRegistry.getArguments()`. The other two tiers cost nothing, keep the mental model
the same as the other Qualflare reporters, and are how a JVM or Robolectric test sets an option.

An empty or whitespace-only value falls through to the next tier rather than winning, so a
declared-but-unset CI variable cannot silently blank an option.

## Setting an option

In Gradle, for every run:

```kotlin
android {
    defaultConfig {
        testInstrumentationRunnerArguments["listener"] =
            "com.qualflare.espresso.QualflareRunListener"
        testInstrumentationRunnerArguments["qualflare.environment"] = "staging"
    }
}
```

On one run, from the command line:

```bash
./gradlew connectedAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.qualflare.environment=staging
```

## Options

| Option | Argument / property | Environment | Default |
|---|---|---|---|
| Reporting on or off | `qualflare.enabled` | `QUALFLARE_ENABLED` | on |
| Output directory name | `qualflare.outputDir` | `QUALFLARE_OUTPUT_DIR` | `qualflare-results` |
| Environment label | `qualflare.environment` | `QUALFLARE_ENVIRONMENT` | `development` |
| Language | `qualflare.language` | `QUALFLARE_LANGUAGE` | `en-US` |
| Platform | `qualflare.platform` | `QUALFLARE_PLATFORM` | `android` |
| Branch | `qualflare.branch` | `QUALFLARE_BRANCH` | not sent |
| Commit | `qualflare.commit` | `QUALFLARE_COMMIT` | not sent |

**Turning it off.** The values read as off are exactly `0`, `false`, `no` and `off`, in any case
and with surrounding space. Anything else leaves the reporter **on**, and an unrecognised value
prints a warning naming itself. That asymmetry is deliberate: a typo like `TRU` silently costing a
run its report is a worse failure than one extra warning line.

**`outputDir` is a directory name, not a path.** It only applies on the file route (below), where
it is resolved against the app's own files directory. The process working directory on Android is
`/`, which is not writable, so a bare relative path would have nowhere to go.

**Branch and commit** are absent by default rather than empty: an instrumented app has no git
checkout to read them from, and the wire distinguishes "not detected" from "blank". Pass them from
CI if you want them.

## Where the report goes

The reporter picks one of two routes at the start of the run and uses it for the report *and*
every image it writes — they have to travel together, because `qf collect` resolves an
attachment's `localImagePath` next to the report it was found in.

**Test storage**, when Gradle passes `additionalTestOutputDir`. The report goes through
`PlatformTestStorage`, Gradle pulls the directory to
`app/build/outputs/connected_android_test_additional_output/…` on its own, and nothing is asked of
you. This is the route from API 29 up.

**The app's files directory**, when it does not. Gradle passes no `additionalTestOutputDir` below
API 29, so storage output would sit on the device uncollected. Instead the report goes to
`getExternalFilesDir(null)/qualflare-results` and the reporter prints the exact `adb pull` to run.

Which route was taken, and why, is logged on the device:

```bash
adb logcat -s QualflareEspresso
```

## Status mapping

JUnit 4 has no verdict object — a pass is the *absence* of a failure callback — so every other
status is read off the throwable, walking the cause chain because Espresso wraps freely.

| Throwable | Status |
|---|---|
| nothing (test finished, no failure) | `passed` |
| `AssertionError` — JUnit, Truth, Hamcrest, Kotlin `assert` | `failed` |
| `NoMatchingViewException`, `AmbiguousViewMatcherException`, `NoMatchingRootException` | `failed` |
| `TestTimedOutException` (`@Test(timeout = …)`, `Timeout` rule) | `timeout` |
| `AppNotIdleException`, `IdlingResourceTimeoutException` | `timeout` |
| `NoActivityResumedException`, `RootViewWithoutFocusException`, `InjectEventSecurityException` | `error` |
| `AssumptionViolatedException` | `skipped` |
| `@Ignore` | `skipped` |
| anything else | `error` |

The deliberate call is the second row. `NoMatchingViewException` is the single commonest way an
Espresso test fails, and it means the app was not in the expected state — an assertion that did
not hold. Filing it as an infrastructure error would mislabel most real failures.

`PerformException` is never classified itself; it wraps the real reason, so the walk continues
into its cause, which is usually a missing view (`failed`) but can be an injection problem
(`error`).

Classification is by **type name**, not `instanceof`. The library compiles against Espresso but
must not require it: a project using only UiAutomator or plain instrumentation tests has no
`androidx.test.espresso` classes at runtime, and an `instanceof` against a missing class throws
`NoClassDefFoundError` — turning a test failure into a reporter crash.

## Minification

The listener is named only by a string, in your build file, so R8 sees no reference to it and
deletes it. The symptom is the worst kind: no report, no error, a green build.

The AAR ships `consumer-rules.pro` keeping the listener's public no-arg constructor (the runner
reflects it) and the public `Qualflare` and `QualflareRule` members, so a minified androidTest APK
works with no configuration from you.
