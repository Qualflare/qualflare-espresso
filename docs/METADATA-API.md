# The metadata API

Everything a test can add to its own report. One import:

```java
import com.qualflare.espresso.Qualflare;
```

Every method is safe to call from anywhere. Nothing throws, nothing returns an error, and a call
made outside a running test is dropped with a single warning. A reporting API that can break a
test suite is worse than no API.

## Labels, tags, links

```java
Qualflare.label("team", "identity");
Qualflare.label("owner", "ada");

Qualflare.tag("smoke", "login");

Qualflare.link("https://issues.example/QF-1", Qualflare.ISSUE, "QF-1");
Qualflare.link("https://wiki.example/login-flow");   // typed as CUSTOM
```

Link types are `Qualflare.ISSUE`, `Qualflare.TMS` and `Qualflare.CUSTOM`.

## Priority and description

```java
Qualflare.priority(Qualflare.HIGH);     // HIGH, MEDIUM, LOW — anything else passes through
Qualflare.description("Covers the reset-password path, not the happy path.");
```

## Steps

Named and timed, nested as deeply as you nest the calls:

```java
Qualflare.step("sign in", () -> {
    Qualflare.step("enter credentials", () -> {
        onView(withId(R.id.email)).perform(typeText("ada@example.com"));
        Qualflare.parameter("account", "ada@example.com");
    });
    Qualflare.step("submit", () -> onView(withId(R.id.submit)).perform(click()));
});
```

A step records its own status and duration. **Exceptions propagate**: a step that swallowed them
would turn a failing test green, which is a far worse bug than a missing step. A failing step is
recorded as failed, and the throwable is rethrown exactly as thrown — not wrapped — so the test
sees what it threw and the status mapping classifies the original type.

Steps are capped at 300 per attempt. Past that they are dropped and the report carries a warning
saying so, which is also printed at the end of the run: a truncated step tree that says nothing is
how you end up trusting a report that quietly lost half the run.

### Steps from the main looper

This works, and it is the reason the current case is a `static volatile` rather than a
`ThreadLocal`:

```java
scenario.onActivity(activity -> {
    Qualflare.step("read the state directly", () -> assertTrue(activity.isReady()));
});
```

`onActivity` and `ViewAction.perform` run on the **main looper**, while the test body runs on the
instrumentation thread. A `ThreadLocal` would have silently dropped exactly the calls test authors
are most likely to write. One slot is safe because `AndroidJUnitRunner` runs tests serially.

## Parameters

A parameter lands on the innermost open step, or on the case when no step is open:

```java
Qualflare.parameter("environment", "staging");
Qualflare.maskedParameter("password");
```

There is deliberately no `maskedParameter(name, value)` overload. A signature that cannot accept a
secret cannot leak one — the masked parameter records only that a value existed, and the value
never enters the report at all.

## Attachments

```java
Qualflare.attachment("response.json", bytes, "application/json");
Qualflare.attachment("logcat.txt", new File(dir, "logcat.txt"), "text/plain");
Qualflare.screenshot("after login", bitmap);
```

`File`, not `Path`: `java.nio.file.Path` is API 26+ and this library targets 24.

Images (`png`, `jpeg`, `gif`) are written beside the report and referenced by filename, so a
screenshot never competes with the run's inline budget. Everything else is base64-inlined.

The limits, and what happens at each:

| Limit | Value | Past it |
|---|---|---|
| Attachments per case | 50 | further attachments are dropped |
| Inline bytes per run | 8 MiB *encoded* | recorded with its size, without its content |
| Image files per run | 100 | recorded without its file |

Past a limit the attachment is still *recorded* — the report shows that a screenshot was taken and
how big it was — because "nothing here" and "too many to keep" are different facts.

A `Bitmap` you pass is never recycled: it belongs to you, and recycling someone else's bitmap
turns a reporting call into a crash on the next draw.

## Screenshot on failure

`QualflareRule` captures the screen when a test fails, **while the app is still on it**:

```java
private final ActivityScenarioRule<LoginActivity> activity =
        new ActivityScenarioRule<>(LoginActivity.class);

@Rule
public final RuleChain rules = RuleChain.outerRule(activity).around(new QualflareRule());
```

The ordering is load-bearing — see the [README](../README.md#screenshot-on-failure). To keep the
rule without the capture (a suite whose screens show real customer data, or one where the capture
is too slow to afford):

```java
QualflareRule.withoutScreenshotOnFailure()
```

The capture is `UiAutomation.takeScreenshot()`, which photographs the screen rather than a view,
so it catches dialogs, permission prompts and IMEs that Espresso's `captureToBitmap()` would miss.
On a window with `FLAG_SECURE` it returns nothing and the run carries on: a test that fails on a
password screen must not fail twice.

## Calls made outside a test

Dropped, with one warning per process rather than one per call. There is no case to attach them
to — this happens in `@BeforeClass`, in a `@ClassRule`, or on a helper thread that outlived the
test. The warning names the situation so it is fixable:

```
[qualflare-espresso] a Qualflare.* call was made outside a running test and was ignored.
```
