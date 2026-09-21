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

    private final Accumulator accumulator = new Accumulator();

    /** Resolved once at run start; null means "report nothing", decided there. */
    private ReportSink sink;
    private boolean enabled = true;

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
        warnAboutAnUnrecognisedEnabledValue();
        sink = ReportSink.resolve(targetContext());
        // Attachments must travel the SAME route as the report, or every localImagePath dangles.
        Qualflare.sink(sink);
        if (sink == null) {
            System.err.println(TAG + " no way to write a report on this device: neither"
                    + " androidx.test storage nor an app context was available. Nothing will be"
                    + " reported.");
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
            System.err.println(TAG + " could not write the report: " + e);
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
            System.err.println(TAG + " qualflare.enabled=" + raw + " is not a value I recognise;"
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
