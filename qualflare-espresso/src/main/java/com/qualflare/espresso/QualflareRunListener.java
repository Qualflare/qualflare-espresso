package com.qualflare.espresso;

import android.content.Context;

import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

import java.io.IOException;

/**
 * The reporter. Registered by the consumer with one line:
 *
 * <pre>
 * testInstrumentationRunnerArguments["listener"] = "com.qualflare.espresso.QualflareRunListener"
 * </pre>
 *
 * <p>JUnit 4 has no ServiceLoader hook, so that argument is unavoidable — and it is also the better
 * choice: it composes with whatever runner the consumer already has, where shipping an
 * {@code AndroidJUnitRunner} subclass would fight Hilt's. The spike confirmed the argument invokes
 * us (docs/SPIKE-2026-09-20.md).
 *
 * <p>Three parts of JUnit 4's callback shape are load-bearing and easy to get wrong:
 *
 * <ul>
 *   <li><b>There is no "passed" callback.</b> A pass is {@code testFinished} with neither a failure
 *       nor an assumption failure before it, so the verdict is inferred rather than read.
 *   <li><b>{@code testFailure} can fire more than once for one test.</b> An {@code ErrorCollector},
 *       a failing {@code @After} after a failing body, and a {@code RuleChain} all do it. Keyed
 *       naively, the second failure would look like a second attempt and fabricate a retry — so the
 *       first failure decides the status and later ones append to its message.
 *   <li><b>A {@code @BeforeClass} failure has no test to attach to.</b> It arrives as a failure
 *       whose {@link Description} is a suite, and the tests it guarded emit nothing at all, so the
 *       run would read green with cases simply missing. A synthetic case carries it instead.
 * </ul>
 *
 * <p>AndroidJUnitRunner constructs this reflectively and requires a public no-arg constructor;
 * {@code consumer-rules.pro} keeps it for minified consumers, because nothing in the code
 * references the class by name.
 */
public final class QualflareRunListener extends RunListener {

    private static final String TAG = "[qualflare-espresso]";

    /**
     * How often the report is rewritten while the run is still going.
     *
     * <p>A device run is the one place where losing the report is likely rather than exotic: the
     * emulator is killed, the app is force-stopped, the test process is low-memory-killed, or a
     * native crash takes the process down between tests. Writing only at {@code testRunFinished}
     * means any of those costs the whole run's results, and a flaky suite is exactly the suite
     * that dies that way.
     *
     * <p>Three seconds rather than every test, because the flush rewrites the WHOLE report: at one
     * write per test a thousand-test suite would rewrite a growing document a thousand times. At
     * this interval the loss window is a few seconds of tests instead of the entire run.
     */
    private static final long FLUSH_INTERVAL_MS = 3_000L;

    private final Accumulator accumulator = new Accumulator();

    /**
     * Resolved once at run start; null means "report nothing", decided there. Package-private
     * because off a device {@link ReportSink#resolve} finds nothing, so tests install their own.
     */
    ReportSink sink;
    private boolean enabled = true;

    /** Visible for tests, which drive the throttle rather than sleeping through it. */
    long flushIntervalMs = FLUSH_INTERVAL_MS;

    /** When the last incremental flush happened; 0 means none yet, so the first one is due. */
    private long lastFlushAt;

    /** Set after a flush fails, so a broken sink cannot print once per test for the rest of a run. */
    private boolean flushBroken;

    /** The failure seen for the test in flight, or null. First one wins. */
    private String pendingStatus;
    private String pendingMessage;
    private String pendingTrace;
    private boolean assumptionFailed;

    /** Required by AndroidJUnitRunner's reflective instantiation. */
    public QualflareRunListener() {}

    @Override
    public void testRunStarted(Description description) {
        enabled = Config.enabled();
        if (!enabled) {
            return;
        }
        if (Config.isDryRun()) {
            // The runner is listing the suite, not running it: every test is about to be reported
            // as started and finished without executing. Reporting that would produce a complete,
            // convincing, entirely green run.
            enabled = false;
            Notes.say("the runner is listing tests rather than running them, so nothing will be"
                    + " reported for this pass.");
            return;
        }
        warnAboutAnUnrecognisedEnabledValue();
        sink = ReportSink.resolve(targetContext());
        // Attachments must travel the SAME route as the report, or every localImagePath dangles.
        Qualflare.sink(sink);
        if (sink == null) {
            Notes.warn("no way to write a report on this device: neither androidx.test"
                    + " storage nor an app context was available. Nothing will be reported.");
        }
    }

    @Override
    public void testStarted(Description description) {
        if (!enabled) {
            return;
        }
        String key = keyOf(description);
        clearPending();
        accumulator.started(key, System.nanoTime());
        // The current case is a single static volatile, NOT a ThreadLocal: Qualflare.step() is
        // routinely called from ActivityScenario.onActivity{} and ViewAction.perform(), which run
        // on the main looper while the test runs on the instrumentation thread -- measured, see
        // docs/SPIKE-2026-09-20.md. A ThreadLocal would silently drop exactly those calls.
        // AndroidJUnitRunner runs tests serially, so one slot is enough.
        Qualflare.begin(accumulator, key);
    }

    @Override
    public void testFailure(Failure failure) {
        if (!enabled) {
            return;
        }
        Description d = failure.getDescription();
        if (isClassLevel(d)) {
            reportClassFailure(d, failure);
            return;
        }
        String status = Status.of(failure.getException());
        if (pendingStatus == null) {
            pendingStatus = status;
            pendingMessage = message(failure);
            pendingTrace = failure.getTrace() == null ? "" : failure.getTrace();
            return;
        }
        // A second failure for the same test: an ErrorCollector, or an @After that also threw.
        // Appended rather than dropped, because the later one is often the more informative, and
        // rather than recorded as an attempt, because that would invent a retry that never happened.
        pendingMessage = pendingMessage + "\n\n[also] " + message(failure);
    }

    @Override
    public void testAssumptionFailure(Failure failure) {
        if (!enabled) {
            return;
        }
        // Unlike testIgnored, this does NOT replace testFinished: the case is completed below.
        assumptionFailed = true;
        if (pendingMessage == null) {
            pendingMessage = message(failure);
        }
    }

    @Override
    public void testIgnored(Description description) {
        if (!enabled) {
            return;
        }
        // No testStarted and no testFinished arrive for an ignored test, so the case has to be
        // created here or the suite would appear to shrink. @Ignore's own reason string is not in
        // the notification, so it cannot be reported.
        accumulator.skipped(keyOf(description), suiteOf(description), description.getClassName(),
                methodOf(description), methodOf(description), "@Ignore");
        flushIfDue();
    }

    @Override
    public void testFinished(Description description) {
        if (!enabled) {
            return;
        }
        Qualflare.end();
        String key = keyOf(description);
        String status = pendingStatus != null ? pendingStatus
                : assumptionFailed ? Status.SKIPPED
                        : Status.PASSED;
        accumulator.finished(key, suiteOf(description), description.getClassName(),
                methodOf(description), methodOf(description), status, System.nanoTime(),
                pendingMessage == null ? "" : pendingMessage,
                pendingTrace == null ? "" : pendingTrace);
        clearPending();
        flushIfDue();
    }

    @Override
    public void testRunFinished(Result result) {
        if (!enabled || sink == null || accumulator.isEmpty()) {
            return;
        }
        try {
            ReportWriter.write(sink, accumulator.cases());
        } catch (IOException e) {
            // A reporting failure must never fail a run that passed.
            Notes.warn("could not write the report: " + e);
        }
    }

    /**
     * Rewrites the report if enough time has passed, so that a run which never reaches
     * {@code testRunFinished} still leaves every case finished so far.
     *
     * <p>The file is replaced whole under one name, never appended to, so what is on disk is
     * always a complete document rather than a prefix of one. The residual window is the write
     * itself: a process killed mid-write leaves a truncated file, and nothing an app process can
     * do about that is portable across both delivery routes -- test storage has no rename.
     */
    private void flushIfDue() {
        if (sink == null || flushBroken || accumulator.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (lastFlushAt != 0L && now - lastFlushAt < flushIntervalMs) {
            return;
        }
        lastFlushAt = now;
        try {
            ReportWriter.flush(sink, accumulator.cases());
        } catch (IOException | RuntimeException e) {
            // Incremental flushing is an optimisation against losing the run; the final write is
            // the guarantee. Stop trying, say so once, and let testRunFinished have its own go.
            flushBroken = true;
            Notes.warn("could not flush the report while the run was going: " + e
                    + ". The report will still be written when the run finishes.");
        }
    }

    /** The accumulated cases. Visible for tests only, which read the report back through them. */
    java.util.Collection<CaseRecord> casesForTest() {
        return accumulator.cases();
    }

    /**
     * A failure with no test of its own: {@code @BeforeClass}, {@code @AfterClass} or a
     * {@code @ClassRule}. The tests it guarded emit nothing, so without this the run reads green
     * with cases quietly absent — the same backstop the JUnit 5 sibling has for containers.
     */
    private void reportClassFailure(Description d, Failure failure) {
        String cls = d.getClassName() == null ? "unknown" : d.getClassName();
        String key = cls + "#[qf-class-failure]";
        accumulator.started(key, System.nanoTime());
        accumulator.finished(key, cls, cls, cls + " [class failure]", cls + " [class failure]",
                Status.ERROR, System.nanoTime(), message(failure),
                failure.getTrace() == null ? "" : failure.getTrace());
    }

    /**
     * True for a failure that belongs to a class rather than a test method.
     *
     * <p>{@code initializationError} is deliberately NOT treated this way: JUnit's
     * {@code ErrorReportingRunner} fires start, failure and finish for it, so it becomes an ordinary
     * case with no special handling. Verified by test rather than assumed.
     */
    private static boolean isClassLevel(Description d) {
        return d != null && d.getMethodName() == null;
    }

    private static String keyOf(Description d) {
        return d.getClassName() + "#" + methodOf(d);
    }

    /** JUnit 4 puts parameters in the method name, e.g. {@code signsIn[0]}; keep them. */
    private static String methodOf(Description d) {
        String m = d.getMethodName();
        return m == null ? "" : m;
    }

    private static String suiteOf(Description d) {
        String cls = d.getClassName();
        return cls == null ? "" : cls;
    }

    private static String message(Failure failure) {
        String m = failure.getMessage();
        if (m != null && !m.isEmpty()) {
            return m;
        }
        Throwable t = failure.getException();
        return t == null ? "" : t.getClass().getName();
    }

    private void clearPending() {
        pendingStatus = null;
        pendingMessage = null;
        pendingTrace = null;
        assumptionFailed = false;
    }

    /**
     * A value like {@code TRU} leaves the reporter ENABLED and says so, rather than silently
     * costing the run its report. The testng sibling treats any unrecognised value as off, which is
     * the failure this avoids.
     */
    private static void warnAboutAnUnrecognisedEnabledValue() {
        String raw = System.getProperty("qualflare.enabled");
        if (raw == null) {
            return;
        }
        String v = raw.trim();
        if (v.isEmpty() || Config.isDisabledSpelling(v)) {
            return;
        }
        if (!v.equalsIgnoreCase("1") && !v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("yes")
                && !v.equalsIgnoreCase("on")) {
            Notes.warn("qualflare.enabled=" + raw + " is not a value I recognise;"
                    + " reporting anyway. Use 0, false, no or off to turn it off.");
        }
    }

    /** The app under test's context, or null off-device. Reflective: see Config#argument. */
    private static Context targetContext() {
        try {
            Class<?> registry = Class.forName("androidx.test.platform.app.InstrumentationRegistry");
            Object instrumentation = registry.getMethod("getInstrumentation").invoke(null);
            Object context = instrumentation.getClass().getMethod("getTargetContext")
                    .invoke(instrumentation);
            return (Context) context;
        } catch (Throwable t) {
            return null;
        }
    }
}
