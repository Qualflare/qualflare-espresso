# Releasing

Maven Central is **immutable**: a published version can never be replaced, only superseded by a
higher one. Everything below is arranged around that single fact.

## Before you tag

```bash
./gradlew :qualflare-espresso:assembleRelease :qualflare-espresso:lint \
    :qualflare-espresso:testDebugUnitTest :fixture-app:assembleDebugAndroidTest
python3 tools/check-banned-apis.py
python3 tools/verify.py qualflare-espresso/build/sample-report
bash tools/verify-selftest.sh qualflare-espresso/build/sample-report
```

Then run the device gate — **Actions → Emulator → Run workflow** — and read the legs rather than
the checkmark. The report reaching the host is the product; a green Gradle run is not, and cannot
be: several fixture tests fail on purpose.

For a first release of anything, cut `-rc.1` first. The Maestro reporter's rc caught a `go install`
broken by a filename, after thirteen task reviews had missed it. An rc costs an hour; a wrong
version on Central is permanent.

## Cut it

1. Set the version in `qualflare-espresso/build.gradle.kts` (`version = "0.1.0"`), with no
   `-SNAPSHOT`. Update `CHANGELOG.md`.
2. Commit, push, and tag:
   ```bash
   git tag v0.1.0 && git push origin v0.1.0
   ```
3. The `Release` workflow runs on the tag. Before uploading anything it:
   - asserts the tag matches the project version and refuses a `SNAPSHOT`;
   - runs the whole gate again at the exact bytes about to be published;
   - asserts the **built AAR** carries that version in its generated `Version` class, and that
     `consumer-rules.pro` is in it. (The JUnit 5 sibling once shipped a release whose reports all
     claimed `0.0.0-dev`; on Android that failure mode is the default, not an accident, because
     `Package.getImplementationVersion()` returns null in dex.)
4. The upload validates and then **stops**. Go to
   <https://central.sonatype.com/publishing/deployments> and publish it by hand.

`automaticRelease = false` is a deliberate human gate. Flip it once this pipeline has earned it.

### Secrets

Four, in the `maven-central` environment:

| Secret | What it is |
|---|---|
| `CENTRAL_TOKEN_USERNAME` | Central Portal user token |
| `CENTRAL_TOKEN_PASSWORD` | Central Portal token password |
| `GPG_PRIVATE_KEY` | ASCII-armoured private key |
| `GPG_PASSPHRASE` | its passphrase |

Gradle's signing plugin reads the key from project properties in memory, so there is no keyring,
no gpg agent and no `--pinentry-mode loopback` to arrange — the part of the Maven siblings'
release that does not carry over.

## Verify at the source

Not at a green checkmark:

```bash
curl -s https://repo1.maven.org/maven2/com/qualflare/qualflare-espresso/maven-metadata.xml
```

Then resolve it from a scratch project — a minSdk 24 app that depends on the published
coordinates, not on `:qualflare-espresso` — run one instrumented test, and check the report's
`metadata.version` is the version you just released.

## After the release

The reporter existing changes claims that are currently true. Work through these in one pass, or
they propagate:

**Listings**

- [ ] Maven Central artifact page renders the POM, licence and javadoc.
- [ ] GitHub release notes from `CHANGELOG.md`; repository topics include `espresso`,
      `android-testing`, `test-reporting`.
- [ ] Add to the Android/Espresso awesome lists that take a reporter. The bar is usage, not
      persistence: do not refile a rejected PR.

**The website and docs** (`landing-fe`, `qf-docs`)

- [ ] `espresso-test-reporting.astro` — rewrite reporter-first, the way
      `maestro-test-reporting.astro` was. Remove the "there's nothing to configure" hero, the FAQ
      at line 129, and the "no bespoke Espresso parser" answer, all of which stop being true.
- [ ] `compare/qualflare-vs-reportportal.astro` — the row recording a straight loss on
      "dedicated mobile-framework parsers" flips; fix the prose below it too.
- [ ] `qf-docs` native-reporter table and its count (eleven → twelve), and `framework-grid.tsx`.
- [ ] `blog/CONTENT_STRATEGY.md` — the positioning note instructing every future mobile post to
      frame Espresso as JUnit-XML-native. Until that changes, the framing keeps propagating.
- [ ] Three posts argue a mobile-specific reporter is unnecessary:
      `best-mobile-test-management-tools.md`, `mobile-testing-complete-guide.md`,
      `what-is-mobile-test-observability.md`.
- [ ] Launch post, in the shape of `maestro-test-reports-junit-limits.md`.
      `espresso-flaky-tests.md` already notes that sharded Espresso runs have no merge step —
      that is the reporter-shaped payoff to lead with.

Each of those is a claim that was accurate the day it was written. Leaving one in place is not a
stale-docs problem; it is the site telling a visitor the product does not do the thing it now
does.
