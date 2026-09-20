# qualflare-espresso

[![CI](https://github.com/Qualflare/qualflare-espresso/actions/workflows/ci.yml/badge.svg)](https://github.com/Qualflare/qualflare-espresso/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)

A native Espresso reporter for [Qualflare](https://qualflare.com). **Under construction** —
the repository currently holds the skeleton and a device spike, not a working reporter.

Espresso results reach Qualflare today as the JUnit XML Gradle writes: one `<testcase>` per
test, with a status, a duration and a class name. This reporter runs *inside the instrumented
app process* instead, so it can record what the run actually did — steps, screenshots,
per-attempt retry history and metadata written by the test itself.

The thing that makes it worth building: `androidx.test:monitor` exposes
`PlatformTestStorage`, which writes into `additionalTestOutputDir`, and Gradle pulls that
directory to `build/outputs/connected_android_test_additional_output/…` on its own. So the
install is one line of Gradle and one `qf collect`, with **no `adb pull`** — unlike every
existing Android reporting adapter.

## Planned usage

```kotlin
// app/build.gradle.kts
androidTestImplementation("com.qualflare:qualflare-espresso:x.y.z")

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

JUnit 4 has no ServiceLoader hook, so that one argument line is unavoidable. It is also
deliberate: a `listener` argument composes with a custom runner (Hilt's, for instance),
where shipping our own `AndroidJUnitRunner` subclass would not.

## Status

| | |
|---|---|
| Skeleton, lint gate, consumer ProGuard rules | done |
| Device spike (storage → host, listener registration, failure/teardown ordering) | done — [findings](docs/SPIKE-2026-09-20.md) |
| The reporter itself | not started |

Requires **minSdk 24** and `androidx.test:monitor` 1.4.0 or newer.

**One measured caveat on the no-adb story:** Gradle only passes `additionalTestOutputDir` from
**API 29**. On API 24–28 the report lands in the app's files directory and the reporter prints the
`adb pull` command to run; from API 29 it arrives on the host by itself. Measured on real emulators
across API 24, 29 and 34 — see the findings.

## Development

```bash
./gradlew :qualflare-espresso:assembleRelease   # the AAR
./gradlew :qualflare-espresso:lint              # NewApi is an error: minSdk 24 guard rail
./gradlew :qualflare-espresso:testDebugUnitTest # JVM + Robolectric
./gradlew :fixture-app:connectedDebugAndroidTest  # needs a device or emulator
```

The emulator work runs in CI on demand: **Actions → Android spike → Run workflow**.

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
