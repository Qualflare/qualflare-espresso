package com.qualflare.espresso;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.Statement;
import org.robolectric.RobolectricTestRunner;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The rule, driven the way JUnit drives it: its {@link org.junit.rules.TestRule#apply} statement
 * wrapped around a body that throws, inside a run the listener is watching.
 *
 * <p>The capture itself is faked — {@code UiAutomation} does not exist off a device, and the
 * emulator leg (task 13) is what proves a real screenshot is of the app rather than the launcher.
 * What these tests hold down is everything around the capture: that a failure still propagates
 * unchanged, that the image reaches the report through the same sink, and that every reason not to
 * capture is checked <i>before</i> spending a full-screen bitmap.
 */
@RunWith(RobolectricTestRunner.class)
public class QualflareRuleTest {

    private static final byte[] PNG = new byte[] {(byte) 0x89, 'P', 'N', 'G', 7, 7};

    private QualflareRunListener listener;
    private RunNotifier notifier;
    private FakeSink sink;
    private AtomicInteger captures;

    @Before
    public void setUp() {
        Attachments.resetBudgets();
        listener = new QualflareRunListener();
        notifier = new RunNotifier();
        notifier.addListener(listener);
        listener.testRunStarted(Description.createSuiteDescription("suite"));
        // Off-device the listener resolves no sink, so give it the one the assertions can read.
        sink = new FakeSink();
        Qualflare.sink(sink);
        captures = new AtomicInteger();
    }

    private QualflareRule ruleReturning(byte[] png) {
        return new QualflareRule(true, () -> {
            captures.incrementAndGet();
            return png;
        });
    }

    /** Runs a body through the rule exactly as JUnit would, returning what came out of it. */
    private Throwable runThrough(QualflareRule rule, Description d, Statement body) {
        try {
            rule.apply(body, d).evaluate();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static Statement throwing(Throwable t) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                throw t;
            }
        };
    }

    @Test
    public void a_failing_test_gets_a_screenshot_attached_to_its_own_case() {
        Description d = Description.createTestDescription("com.example.LoginTest", "signsIn");
        notifier.fireTestStarted(d);

        AssertionError thrown = new AssertionError("no view matching withId(email)");
        Throwable out = runThrough(ruleReturning(PNG), d, throwing(thrown));

        // The listener's own notifications, in the order JUnit sends them.
        notifier.fireTestFailure(new Failure(d, thrown));
        notifier.fireTestFinished(d);

        assertEquals("the rule must not swallow or wrap the failure", thrown, out);
        assertEquals(1, captures.get());
        String json = ReportWriter.render(listener.casesForTest());
        assertTrue(json.contains("failure screenshot"));
        assertTrue("an image goes beside the report, not inline", json.contains("localImagePath"));
        assertEquals("and through the report's own sink", 1, sink.written.size());
        assertEquals(PNG.length, sink.written.values().iterator().next().size());
    }

    @Test
    public void a_passing_test_captures_nothing() {
        Description d = Description.createTestDescription("com.example.LoginTest", "signsIn");
        notifier.fireTestStarted(d);

        Throwable out = runThrough(ruleReturning(PNG), d, new Statement() {
            @Override
            public void evaluate() {}
        });
        notifier.fireTestFinished(d);

        assertEquals(null, out);
        assertEquals("a screenshot of a green test is a screenshot nobody opens", 0, captures.get());
        assertTrue(sink.written.isEmpty());
    }

    /**
     * A secure window, or no UiAutomation at all. The capture returns null and the run carries on —
     * the test that fails on a password screen must not fail twice.
     */
    @Test
    public void a_capture_that_returns_nothing_is_not_an_error() {
        Description d = Description.createTestDescription("com.example.PayTest", "hidesTheCard");
        notifier.fireTestStarted(d);

        AssertionError thrown = new AssertionError("wrong total");
        Throwable out = runThrough(ruleReturning(null), d, throwing(thrown));
        notifier.fireTestFailure(new Failure(d, thrown));
        notifier.fireTestFinished(d);

        assertEquals(thrown, out);
        assertEquals(1, captures.get());
        String json = ReportWriter.render(listener.casesForTest());
        assertTrue("the case is still reported", json.contains("\"status\":\"failed\""));
        assertFalse(json.contains("failure screenshot"));
    }

    @Test
    public void the_opt_out_does_not_even_attempt_a_capture() {
        Description d = Description.createTestDescription("com.example.LoginTest", "signsIn");
        notifier.fireTestStarted(d);

        AssertionError thrown = new AssertionError("boom");
        QualflareRule rule = new QualflareRule(false, () -> {
            captures.incrementAndGet();
            return PNG;
        });
        Throwable out = runThrough(rule, d, throwing(thrown));
        notifier.fireTestFinished(d);

        assertEquals(thrown, out);
        assertEquals("withoutScreenshotOnFailure means the bitmap is never taken", 0,
                captures.get());
    }

    /**
     * Reporting off, or the rule used outside a run the listener watches. Capturing would cost a
     * full-screen bitmap and then be dropped, so the case is checked first.
     */
    @Test
    public void with_no_case_in_flight_the_screen_is_not_captured_at_all() {
        Description d = Description.createTestDescription("com.example.LoginTest", "signsIn");
        // No fireTestStarted: nothing has opened a case.
        AssertionError thrown = new AssertionError("boom");

        Throwable out = runThrough(ruleReturning(PNG), d, throwing(thrown));

        assertEquals(thrown, out);
        assertEquals(0, captures.get());
        assertTrue(sink.written.isEmpty());
    }

    /** Off a device there is no instrumentation to ask, and asking must not throw. */
    @Test
    public void the_real_capture_returns_null_off_a_device_rather_than_throwing() {
        assertEquals(null, Screenshots.capturePng());
    }
}
