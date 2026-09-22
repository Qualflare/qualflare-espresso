# qualflare-espresso

[![CI](https://github.com/Qualflare/qualflare-espresso/actions/workflows/ci.yml/badge.svg)](https://github.com/Qualflare/qualflare-espresso/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.qualflare/qualflare-espresso.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/com.qualflare/qualflare-espresso)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)

A native Espresso reporter for [Qualflare](https://qualflare.com). It runs **inside the
instrumented app process**, on the device, so it reports what the run actually did rather than
what the XML managed to preserve.

Espresso results otherwise reach Qualflare as the JUnit XML Gradle writes: one `<testcase>` per
test with a status, a duration and a class name. This reporter adds the things a device run
produces and that file has nowhere to put — screenshots, steps, per-attempt retry history, and
metadata the test wrote about itself.

The delivery is the unusual part. `androidx.test:monitor` exposes `PlatformTestStorage`, which
writes into `additionalTestOutputDir`, and Gradle pulls that directory to the host by itself. So
the install is one Gradle line and one `qf collect`, with **no `adb pull`**.

## Install

```kotlin
// app/build.gradle.kts
dependencies {
    androidTestImplementation("com.qualflare:qualflare-espresso:0.1.0")
}

android {
    defaultConfig {
        testInstrumentationRunnerArguments["listener"] =
            "com.qualflare.espresso.QualflareRunListener"
    }
}
```

```bash
./gradlew connectedAndroidTest
qf my-project collect app/build/outputs/connected_android_test_additional_output
```

JUnit 4 has no ServiceLoader hook, so that argument line is unavoidable. It is also the better
shape: a `listener` argument composes with whatever runner you already have — Hilt's, for
instance — where shipping our own `AndroidJUnitRunner` subclass would fight it.

The reporter declares every androidx.test dependency `compileOnly`, so it adds nothing to your
test classpath and takes no position on which Espresso version you use.

## What ends up in the report

| | |
|---|---|
| Status | passed, failed, skipped, timeout, error — [how each is decided](docs/CONFIGURATION.md#status-mapping) |
| Retries | one case with every attempt, marked flaky when a rerun turned it green |
| Steps | named, timed, nestable, with parameters |
| Screenshots | on demand, or automatically on failure via `QualflareRule` |
| Attachments | bytes, a `File`, or a `Bitmap` |
| Metadata | labels, tags, links, priority, description — [the API](docs/METADATA-API.md) |

A test writes its own metadata:

```java
@Test
public void signsIn() {
    Qualflare.label("team", "identity");
    Qualflare.tag("smoke");
    Qualflare.step("enter credentials", () -> {
        onView(withId(R.id.email)).perform(typeText("ada@example.com"));
    });
}
```

## Screenshot on failure

Optional, and the rule ordering matters more than it looks:

```java
private final ActivityScenarioRule<LoginActivity> activity =
        new ActivityScenarioRule<>(LoginActivity.class);

@Rule
public final RuleChain rules = RuleChain.outerRule(activity).around(new QualflareRule());
```

JUnit runs the outermost rule first and closes it last, so the rule that wants to photograph the
app has to be **inside** the rule that launches it. Reversed, the activity is already closed and
every screenshot is a picture of the launcher.

This cannot be automatic, for the same reason: `RunAfters` runs the `@After` methods and *then*
rethrows, so by the time a `RunListener` hears about a failure every rule has unwound. A listener
can report the failure; only a rule can see the screen.

## Documentation

- [CONFIGURATION.md](docs/CONFIGURATION.md) — every option, where it can be set, and the status mapping
- [METADATA-API.md](docs/METADATA-API.md) — the `Qualflare` API in full
- [LIMITATIONS.md](docs/LIMITATIONS.md) — what does not work, each one measured
- [RELEASING.md](docs/RELEASING.md) — cutting a release

## Requirements

minSdk **24**, `androidx.test:monitor` **1.4.0** or newer (the first release carrying
`PlatformTestStorage`). CI compiles against that floor as well as the current version.

**One measured caveat on the no-adb story:** Gradle passes `additionalTestOutputDir` only from
**API 29**. Below that the reporter writes into the app's own files directory — which
`connectedAndroidTest` deletes when it uninstalls the app — so collecting an API 24-28 run takes
one Gradle flag and one `adb pull`. The reporter prints both, filled in for your app;
[LIMITATIONS.md](docs/LIMITATIONS.md#api-2428-the-report-needs-one-flag-and-one-adb-pull) has the
measurement behind it.

## Development

```bash
./gradlew :qualflare-espresso:assembleRelease     # the AAR
./gradlew :qualflare-espresso:lint                # NewApi is an error: the minSdk 24 guard rail
./gradlew :qualflare-espresso:testDebugUnitTest   # JVM + Robolectric
python3 tools/check-banned-apis.py                # no API 26+ types in src/main
python3 tools/verify.py qualflare-espresso/build/sample-report
bash tools/verify-selftest.sh                     # the verifier must still be able to fail
```

The device suite runs in CI on demand: **Actions → Emulator → Run workflow**. It runs the fixture
app across API levels, plain and under Android Test Orchestrator, and `tools/verify.py` decides
whether the leg passed by reading the report that reached the host.

`adb logcat -s QualflareEspresso` shows what the reporter did on the device, including which
delivery route it chose.

## Related reporters

[jest](https://github.com/Qualflare/qualflare-jest) ·
[vitest](https://github.com/Qualflare/qualflare-vitest) ·
[mocha](https://github.com/Qualflare/qualflare-mocha) ·
[cypress](https://github.com/Qualflare/qualflare-cypress) ·
[playwright](https://github.com/Qualflare/qualflare-playwright) ·
[cucumberjs](https://github.com/Qualflare/qualflare-cucumberjs) ·
[pytest](https://github.com/Qualflare/qualflare-pytest) ·
[testng](https://github.com/Qualflare/qualflare-testng) ·
[junit5](https://github.com/Qualflare/qualflare-junit5) ·
[go](https://github.com/Qualflare/qualflare-go) ·
[maestro](https://github.com/Qualflare/qualflare-maestro) ·
[cli](https://github.com/Qualflare/qualflare-cli)

## License

Apache-2.0
