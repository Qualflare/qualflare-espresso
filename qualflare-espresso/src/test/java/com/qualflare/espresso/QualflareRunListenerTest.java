package com.qualflare.espresso;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.AssumptionViolatedException;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunNotifier;
import org.junit.runner.RunWith;
import org.junit.runners.model.TestTimedOutException;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The listener, driven through a real {@link RunNotifier} rather than by calling its methods
 * directly — so the sequences under test are the ones JUnit actually produces.
 *
 * <p>Statuses are read back off the accumulator through the rendered report, because that is the
 * only surface that matters: what {@code qf collect} receives.
 */
@RunWith(RobolectricTestRunner.class)
public class QualflareRunListenerTest {

    private static Description test(String cls, String method) {
        return Description.createTestDescription(cls, method);
    }

    /** A listener wired to a notifier, with the report readable afterwards. */
    private static final class Harness {
        final QualflareRunListener listener = new QualflareRunListener();
        final RunNotifier notifier = new RunNotifier();

        Harness() {
            notifier.addListener(listener);
            listener.testRunStarted(Description.createSuiteDescription("suite"));
        }

        String report() {
            return ReportWriter.render(listener.casesForTest());
        }

        int caseCount() {
            return listener.casesForTest().size();
        }
    }

    @Test
    public void a_pass_is_inferred_from_a_finish_with_no_failure() {
        Harness h = new Harness();
        Description d = test("com.example.LoginTest", "signsIn");
        h.notifier.fireTestStarted(d);
        h.notifier.fireTestFinished(d);

        assertEquals(1, h.caseCount());
        assertTrue("JUnit 4 has no passed callback; a pass is the absence of a failure",
                h.report().contains("\"status\":\"passed\""));
    }

    @Test
    public void a_failure_makes_the_case_failed_and_keeps_its_message() {
        Harness h = new Harness();
        Description d = test("com.example.LoginTest", "signsIn");
        h.notifier.fireTestStarted(d);
        h.notifier.fireTestFailure(new Failure(d, new AssertionError("expected a to equal b")));
        h.notifier.fireTestFinished(d);

        String json = h.report();
        assertTrue(json.contains("\"status\":\"failed\""));
        assertTrue(json.contains("expected a to equal b"));
    }

    /**
     * The trap. An ErrorCollector, or an {@code @After} that throws after a failing body, fires
     * testFailure twice for ONE test. Recorded as two attempts it would look like a retry that
     * never happened, and the case would claim to be flaky.
     */
    @Test
    public void two_failures_for_one_test_are_one_case_not_a_fabricated_retry() {
        Harness h = new Harness();
        Description d = test("com.example.LoginTest", "signsIn");
        h.notifier.fireTestStarted(d);
        h.notifier.fireTestFailure(new Failure(d, new AssertionError("first problem")));
        h.notifier.fireTestFailure(new Failure(d, new IllegalStateException("teardown also blew up")));
        h.notifier.fireTestFinished(d);

        String json = h.report();
        assertEquals("one test, one case", 1, h.caseCount());
        assertFalse("two failures must not read as two attempts", json.contains("\"attempts\""));
        assertFalse(json.contains("\"isFlaky\":true"));
        assertTrue("the first failure decides the status", json.contains("\"status\":\"failed\""));
        assertTrue("the first message is kept", json.contains("first problem"));
        assertTrue("and the second is appended rather than lost",
                json.contains("teardown also blew up"));
    }

    @Test
    public void an_assumption_failure_is_skipped_and_still_finishes() {
        Harness h = new Harness();
        Description d = test("com.example.LoginTest", "onlyOnApi30");
        h.notifier.fireTestStarted(d);
        h.notifier.fireTestAssumptionFailed(
                new Failure(d, new AssumptionViolatedException("needs API 30")));
        h.notifier.fireTestFinished(d);

        assertEquals(1, h.caseCount());
        assertTrue(h.report().contains("\"status\":\"skipped\""));
    }

    /** testIgnored replaces start AND finish, so the case has to be created from it alone. */
    @Test
    public void an_ignored_test_still_appears_in_the_report() {
        Harness h = new Harness();
        h.notifier.fireTestIgnored(test("com.example.LoginTest", "notYetWritten"));

        assertEquals("a suite that silently shrinks is worse than a skip", 1, h.caseCount());
        String json = h.report();
        assertTrue(json.contains("\"status\":\"skipped\""));
        assertTrue(json.contains("notYetWritten"));
    }

    /**
     * A @BeforeClass failure arrives with a SUITE description and the tests it guarded emit
     * nothing at all — so without a synthetic case the run reads green with cases missing.
     */
    @Test
    public void a_class_level_failure_is_reported_rather_than_vanishing() {
        Harness h = new Harness();
        Description suite = Description.createSuiteDescription("com.example.BrokenSetupTest");
        h.notifier.fireTestFailure(new Failure(suite, new IllegalStateException("@BeforeClass threw")));

        assertEquals(1, h.caseCount());
        String json = h.report();
        assertTrue(json.contains("[class failure]"));
        assertTrue("a class failure is infrastructure, not an assertion",
                json.contains("\"status\":\"error\""));
        assertTrue(json.contains("@BeforeClass threw"));
    }

    @Test
    public void a_timeout_is_reported_as_a_timeout_not_an_error() {
        Harness h = new Harness();
        Description d = test("com.example.SlowTest", "waitsTooLong");
        h.notifier.fireTestStarted(d);
        h.notifier.fireTestFailure(new Failure(d, new TestTimedOutException(500, TimeUnit.MILLISECONDS)));
        h.notifier.fireTestFinished(d);

        assertTrue(h.report().contains("\"status\":\"timeout\""));
    }

    @Test
    public void metadata_written_by_the_test_lands_on_the_right_case() {
        Harness h = new Harness();
        Description first = test("com.example.A", "one");
        Description second = test("com.example.A", "two");

        h.notifier.fireTestStarted(first);
        Qualflare.label("team", "identity");
        Qualflare.tag("smoke");
        Qualflare.step("open the screen", () -> {});
        h.notifier.fireTestFinished(first);

        h.notifier.fireTestStarted(second);
        Qualflare.label("team", "billing");
        h.notifier.fireTestFinished(second);

        String json = h.report();
        assertTrue(json.contains("identity"));
        assertTrue(json.contains("billing"));
        assertTrue(json.contains("open the screen"));
        assertEquals(2, h.caseCount());
    }

    /** Between tests there is no case, and a stray call must be dropped, not attached. */
    @Test
    public void metadata_written_between_tests_is_dropped() {
        Harness h = new Harness();
        Description d = test("com.example.A", "one");
        h.notifier.fireTestStarted(d);
        h.notifier.fireTestFinished(d);

        Qualflare.label("team", "nobody"); // after the test finished
        assertFalse("a label with no case must not attach to the previous one",
                h.report().contains("nobody"));
    }

    @Test
    public void a_step_that_throws_records_the_failure_and_rethrows() {
        Harness h = new Harness();
        Description d = test("com.example.A", "one");
        h.notifier.fireTestStarted(d);

        List<String> ran = new ArrayList<>();
        try {
            Qualflare.step("tap submit", () -> {
                ran.add("body ran");
                throw new AssertionError("no such view");
            });
            throw new AssertionError("the step must not swallow the failure");
        } catch (AssertionError expected) {
            assertEquals("no such view", expected.getMessage());
        }
        h.notifier.fireTestFinished(d);

        assertEquals(1, ran.size());
        String json = h.report();
        assertTrue(json.contains("tap submit"));
        assertTrue("the step itself is marked failed", json.contains("\"status\":\"failed\""));
        assertNotNull(json);
    }
}
