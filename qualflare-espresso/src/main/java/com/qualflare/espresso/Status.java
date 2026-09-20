package com.qualflare.espresso;

import java.util.concurrent.TimeoutException;

/**
 * The six statuses the wire accepts, and how a JUnit 4 / Espresso failure maps onto them.
 *
 * <p>This is the class that differs most from the JUnit 5 sibling. There, a
 * {@code TestExecutionResult} arrived with an explicit SUCCESSFUL / ABORTED / FAILED verdict.
 * JUnit 4 has no verdict object: a pass is the *absence* of a failure callback, and everything
 * else has to be read off the throwable. Espresso then adds its own exception family, none of
 * which extends {@link AssertionError}.
 *
 * <p>Classification is by type name rather than by {@code instanceof}, because the library is
 * compiled against Espresso but must not require it at runtime: a project using only
 * UiAutomator or plain instrumentation tests has no {@code androidx.test.espresso} classes on
 * the classpath, and an {@code instanceof} against a missing class throws
 * {@link NoClassDefFoundError} inside the reporter — turning a test failure into a reporter
 * crash.
 */
final class Status {
    static final String PASSED = "passed";
    static final String FAILED = "failed";
    static final String SKIPPED = "skipped";
    static final String ERROR = "error";
    static final String TIMEOUT = "timeout";
    static final String ABORTED = "aborted";

    /** Deep enough for any real wrapping, short enough that a cycle cannot hang a build. */
    private static final int MAX_CAUSE_DEPTH = 32;

    private Status() {}

    /**
     * The status for a failure reported by JUnit 4.
     *
     * <p>Walks the cause chain, because the interesting exception is routinely wrapped: Espresso
     * puts a failed assertion inside a {@code PerformException}, and JUnit's own rules wrap
     * freely.
     *
     * <p>The deliberate call is that a view that did not match is {@code failed}, not
     * {@code error}: {@code NoMatchingViewException} is the single commonest way an Espresso test
     * fails, and it means the app was not in the expected state — an assertion that did not hold.
     * Filing it as an infrastructure error would mislabel most real failures.
     */
    static String of(Throwable t) {
        if (t == null) {
            // A failure with no throwable: JUnit 4 allows it, and "failed" is the honest reading.
            return FAILED;
        }
        int depth = 0;
        for (Throwable c = t; c != null && depth < MAX_CAUSE_DEPTH; c = c.getCause(), depth++) {
            String kind = classify(c);
            if (kind != null) {
                return kind;
            }
        }
        return ERROR;
    }

    /** null when this throwable says nothing, so the caller keeps walking the chain. */
    private static String classify(Throwable c) {
        // An assertion that did not hold, from JUnit, Truth, Hamcrest or Kotlin's assert.
        if (c instanceof AssertionError) {
            return FAILED;
        }
        if (c instanceof TimeoutException) {
            return TIMEOUT;
        }
        String name = c.getClass().getName();
        switch (name) {
            // JUnit 4's own timeout, from @Test(timeout = …) and the Timeout rule.
            case "org.junit.runners.model.TestTimedOutException":
                return TIMEOUT;
            // The app never went idle, or an IdlingResource never settled: a timeout in
            // substance, whatever the class hierarchy says.
            case "androidx.test.espresso.AppNotIdleException":
            case "androidx.test.espresso.IdlingResourceTimeoutException":
                return TIMEOUT;
            // The view the test asked for was not there, was ambiguous, or was in no matching
            // root. The app was not in the expected state.
            case "androidx.test.espresso.NoMatchingViewException":
            case "androidx.test.espresso.AmbiguousViewMatcherException":
            case "androidx.test.espresso.NoMatchingRootException":
                return FAILED;
            // The harness could not drive the app at all: no resumed activity, a window without
            // focus, an injection the system refused. Not the app's verdict.
            case "androidx.test.espresso.NoActivityResumedException":
            case "androidx.test.espresso.RootViewWithoutFocusException":
            case "androidx.test.espresso.InjectEventSecurityException":
                return ERROR;
            default:
                break;
        }
        // PerformException wraps the real reason; keep walking rather than deciding here. Its
        // cause is usually a NoMatchingView (failed) but can be an injection problem (error).
        if ("androidx.test.espresso.PerformException".equals(name)) {
            return null;
        }
        return null;
    }
}
