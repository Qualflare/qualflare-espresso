# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Repository skeleton: Gradle 8.14.3 + AGP 8.13, an Android library module at minSdk 24 with
  `NewApi` as a lint error, consumer ProGuard rules keeping the string-named listener, and a
  `fixture-app` module that exists only to be instrumented.
- A device spike (`.github/workflows/android-spike.yml`, dispatch + weekly) measuring the three
  unknowns that decide the reporter's shape: whether `PlatformTestStorage` output reaches the host
  with no `adb pull`, whether the `listener` instrumentation argument invokes us, and where
  `testFailure` falls relative to `@After` teardown.
