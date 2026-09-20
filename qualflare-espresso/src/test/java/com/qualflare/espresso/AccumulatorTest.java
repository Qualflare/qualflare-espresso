package com.qualflare.espresso;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * The accumulation rules, driven directly rather than through a real run.
 *
 * <p>These are the rules a rerun depends on, and a real Surefire rerun is slow and awkward
 * to assert against, so the integration suite proves the plumbing once and these pin the
 * logic.
 */
public class AccumulatorTest {

    private static final String UID = "[engine:junit-jupiter]/[class:T]/[method:flaky()]";

    private static void attempt(Accumulator acc, String status, long startNs, long endNs, String msg) {
        acc.started(UID, startNs);
        acc.finished(UID, "T", "T", "flaky()", "flaky()", status, endNs, msg, "");
    }

    private static CaseRecord only(Accumulator acc) {
        List<CaseRecord> all = new ArrayList<>(acc.cases());
        assertEquals("the same uniqueId must not create a second case", 1, all.size());
        return all.get(0);
    }

    @Test
    public void reruns_of_the_same_uniqueId_accumulate_onto_one_case() {
        // The measured Surefire shape: separate TestPlans, same JVM, same uniqueId.
        Accumulator acc = new Accumulator();
        attempt(acc, Status.FAILED, 0, 1_000_000, "first");
        attempt(acc, Status.FAILED, 0, 2_000_000, "second");
        attempt(acc, Status.PASSED, 0, 3_000_000, "");

        CaseRecord c = only(acc);
        assertEquals(3, c.attempts.size());
        assertEquals("the final attempt decides the case", Status.PASSED, c.status());
        assertEquals(2, c.retryCount());
    }

    @Test
    public void a_recovered_test_is_flaky() {
        Accumulator acc = new Accumulator();
        attempt(acc, Status.FAILED, 0, 1, "boom");
        attempt(acc, Status.PASSED, 0, 1, "");
        assertTrue(only(acc).isFlaky());
    }

    @Test
    public void a_test_that_never_recovers_is_broken_not_flaky() {
        // Calling this flaky would hide a hard failure behind a softer word, and it is
        // the difference between "retry it" and "fix it".
        Accumulator acc = new Accumulator();
        attempt(acc, Status.FAILED, 0, 1, "boom");
        attempt(acc, Status.FAILED, 0, 1, "boom");
        CaseRecord c = only(acc);
        assertFalse(c.isFlaky());
        assertEquals(Status.FAILED, c.status());
    }

    @Test
    public void a_first_time_pass_is_not_flaky_and_sends_no_attempts() {
        Accumulator acc = new Accumulator();
        attempt(acc, Status.PASSED, 0, 5, "");
        CaseRecord c = only(acc);
        assertFalse(c.isFlaky());
        assertEquals(0, c.retryCount());
        // The wire-level half of this rule -- that a lone attempt is not serialised at all --
        // belongs to ReportWriter's test (task 7). What the accumulator owes is the input to
        // that decision: exactly one attempt, so nothing to send.
        assertEquals("a single attempt is the wire's floor", 1, c.attempts.size());
    }

    @Test
    public void duration_comes_from_the_final_attempt() {
        Accumulator acc = new Accumulator();
        attempt(acc, Status.FAILED, 0, 9_000_000, "slow failure");
        attempt(acc, Status.PASSED, 0, 2_000_000, "");
        assertEquals(2_000_000L, only(acc).durationNanos());
    }

    @Test
    public void a_finish_with_no_start_degrades_to_zero_rather_than_a_negative() {
        // Defensive: a listener can miss a start if a plan is filtered mid-run, and a
        // negative duration is worse than a missing one -- it corrupts suite totals.
        Accumulator acc = new Accumulator();
        acc.finished(UID, "T", "T", "orphan()", "orphan()", Status.PASSED, 5_000_000, "", "");
        assertEquals(0L, only(acc).durationNanos());
    }

    @Test
    public void a_skip_is_recorded_so_the_case_still_appears() {
        // A skipped test missing from the report entirely reads as a shrinking suite,
        // which is exactly the signal a deleted test should give -- so it must not.
        Accumulator acc = new Accumulator();
        acc.skipped(UID, "T", "T", "skipped()", "skipped()", "not ready");
        CaseRecord c = only(acc);
        assertEquals(Status.SKIPPED, c.status());
        assertEquals("not ready", c.message());
    }

    @Test
    public void concurrent_appends_do_not_lose_attempts() {
        // Parallel execution calls the listener from ForkJoinPool workers; appending an
        // attempt is a read-modify-write that must not interleave.
        Accumulator acc = new Accumulator();
        int threads = 8, each = 200;
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            Thread th = new Thread(() -> {
                for (int i = 0; i < each; i++) {
                    String uid = "[class:T]/[method:m" + id + "()]";
                    acc.started(uid, 0);
                    acc.finished(uid, "T", "T", "m" + id, "m" + id, Status.PASSED, 1, "", "");
                }
            });
            ts.add(th);
            th.start();
        }
        ts.forEach(th -> {
            try { th.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        assertEquals(threads, acc.cases().size());
        acc.cases().forEach(c -> assertEquals(c.uniqueId, each, c.attempts.size()));
    }
}
