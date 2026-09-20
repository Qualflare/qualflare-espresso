package com.qualflare.espresso;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The step stack, which is the one piece of the JUnit 5 sibling's {@code Replay} that survived
 * the port — the string-entry transport around it did not, because JUnit 4 has no
 * {@code publishReportEntry} and the listener shares a process with the test.
 *
 * <p>The sibling had no test for this logic at all: it was exercised only through a Surefire
 * integration run. Pinning it here is cheap and the sentinel behaviour below is subtle enough
 * that it was originally found as a bug in the pytest reporter, not by reading the code.
 */
public class StepsTest {

    @Test
    public void nesting_follows_order_not_ids() {
        CaseMeta meta = new CaseMeta();
        Steps.start(meta, "outer");
        Steps.start(meta, "inner");
        Steps.stop(meta, Status.PASSED, 5L, "");
        Steps.stop(meta, Status.PASSED, 10L, "");

        assertEquals(2, meta.steps.size());
        assertNull("the outer step has no parent", meta.steps.get(0).parentIndex);
        assertEquals("the inner step's parent is the outer one",
                Integer.valueOf(0), meta.steps.get(1).parentIndex);
        assertEquals(10L, meta.steps.get(0).durationNanos);
        assertEquals(5L, meta.steps.get(1).durationNanos);
        assertTrue("every step closed", meta.open.isEmpty());
    }

    @Test
    public void a_stop_with_no_start_is_ignored_rather_than_corrupting_the_tree() {
        CaseMeta meta = new CaseMeta();
        Steps.stop(meta, Status.FAILED, 1L, "boom");
        assertTrue(meta.steps.isEmpty());
        assertTrue(meta.open.isEmpty());
    }

    /**
     * The sentinel. Past the cap a step is not created, but its matching stop still arrives —
     * and with nothing to pop it would close whichever step is legitimately open, overwriting
     * that step's status and duration and then discarding its real stop. Measured in the pytest
     * reporter as an outer step around a 50 ms sleep reporting 0.000 ms.
     */
    @Test
    public void a_dropped_step_does_not_close_the_step_that_is_really_open() {
        CaseMeta meta = new CaseMeta();
        int outer = Steps.start(meta, "outer");
        assertEquals(0, outer);

        // Fill to the cap from inside the outer step.
        for (int i = 0; i < Steps.MAX_STEPS_PER_ATTEMPT - 1; i++) {
            Steps.start(meta, "filler-" + i);
            Steps.stop(meta, Status.PASSED, 1L, "");
        }
        assertEquals(Steps.MAX_STEPS_PER_ATTEMPT, meta.steps.size());

        // One past the cap: dropped, and its stop must close nothing real.
        assertEquals(Steps.DROPPED, Steps.start(meta, "over-the-cap"));
        Steps.stop(meta, Status.FAILED, 999L, "should not land anywhere");

        // The outer step is still open and still has its own timing to come.
        Steps.stop(meta, Status.PASSED, 50_000_000L, "");
        Steps.Step outerStep = meta.steps.get(0);
        assertEquals("the outer step kept its measured duration", 50_000_000L, outerStep.durationNanos);
        assertEquals(Status.PASSED, outerStep.status);
        assertTrue("the cap produced a warning", meta.warnings.size() >= 1);
        assertTrue(meta.warnings.get(0).contains("more than " + Steps.MAX_STEPS_PER_ATTEMPT));
    }

    @Test
    public void the_cap_warns_once_not_once_per_dropped_step() {
        CaseMeta meta = new CaseMeta();
        for (int i = 0; i < Steps.MAX_STEPS_PER_ATTEMPT + 10; i++) {
            Steps.start(meta, "s" + i);
            Steps.stop(meta, Status.PASSED, 1L, "");
        }
        assertEquals("one warning, however many steps were dropped", 1, meta.warnings.size());
    }

    @Test
    public void a_parameter_attaches_to_the_open_step_and_otherwise_to_the_case() {
        CaseMeta meta = new CaseMeta();
        Steps.parameter(meta, "caseLevel", "1", false);
        Steps.start(meta, "step");
        Steps.parameter(meta, "stepLevel", "2", false);
        Steps.parameter(meta, "secret", "hunter2", true);
        Steps.stop(meta, Status.PASSED, 1L, "");

        assertEquals(1, meta.parameters.size());
        assertEquals("caseLevel", meta.parameters.get(0)[0]);

        Steps.Step s = meta.steps.get(0);
        assertEquals(2, s.parameters.size());
        assertEquals("stepLevel", s.parameters.get(0)[0]);
        assertEquals("2", s.parameters.get(0)[1]);
        assertEquals("secret", s.parameters.get(1)[0]);
        assertNull("a masked parameter carries no value, ever", s.parameters.get(1)[1]);
        assertEquals("1", s.parameters.get(1)[2]);
    }

    @Test
    public void a_dropped_step_is_not_treated_as_a_parent() {
        CaseMeta meta = new CaseMeta();
        for (int i = 0; i < Steps.MAX_STEPS_PER_ATTEMPT; i++) {
            Steps.start(meta, "s" + i);
            Steps.stop(meta, Status.PASSED, 1L, "");
        }
        // Open a dropped step, then a parameter: it must land on the CASE, not on a step that
        // does not exist.
        Steps.start(meta, "dropped");
        Steps.parameter(meta, "orphan", "x", false);
        assertEquals(1, meta.parameters.size());
        assertEquals("orphan", meta.parameters.get(0)[0]);
    }

    @Test
    public void isEmpty_distinguishes_a_test_that_recorded_nothing() {
        CaseMeta meta = new CaseMeta();
        assertTrue("a fresh meta exists for every started test and must read as empty",
                meta.isEmpty());
        Steps.start(meta, "one");
        assertFalse(meta.isEmpty());
    }
}
