package com.qualflare.espresso;

import java.util.ArrayList;
import java.util.List;

/**
 * The step tree, built as a test runs.
 *
 * <p>This is the JUnit 5 reporter's {@code Replay} with its transport removed. There, steps
 * travelled as strings through {@code ExtensionContext.publishReportEntry} and were replayed
 * from that flat stream afterwards, because the listener lived outside the test's JVM
 * boundary. JUnit 4 has no {@code publishReportEntry}, and on Android the listener and the
 * test body share one process, so steps are pushed straight in here as they happen. What is
 * kept verbatim is the part that was hard to get right: the open-step stack, the cap, and the
 * sentinel.
 *
 * <p>Nesting is implied by ordering, with no ids — the same shape the pytest and Go reporters
 * produce.
 *
 * <p><b>Threading.</b> Callers reach this through {@link Qualflare}, which may be invoked from
 * the main looper (inside {@code ActivityScenario.onActivity}) as well as from the
 * instrumentation thread — measured, see {@code docs/SPIKE-2026-09-20.md}. Every method here is
 * therefore synchronized on the owning {@link CaseMeta}'s step list by the caller; this class
 * holds no state of its own beyond what it is handed.
 */
final class Steps {

    /**
     * The client stops at 300 steps per attempt. The server's hard cap is 1000; stopping
     * lower keeps one pathological test from crowding out the rest of the report.
     */
    static final int MAX_STEPS_PER_ATTEMPT = 300;

    private Steps() {}

    /**
     * Opens a step and returns the stack token to pass back to {@link #stop}.
     *
     * <p>Returns {@link #DROPPED} once the cap is reached. That token is a <b>sentinel</b>, not
     * a bare skip: a dropped step's matching stop still arrives, and with nothing to pop it
     * would close whichever step is legitimately open — overwriting that step's status and
     * duration, then discarding its real stop because the stack had emptied. Measured in the
     * pytest reporter as an outer step around a 50 ms sleep reporting 0.000 ms.
     */
    static final int DROPPED = -1;

    static int start(CaseMeta meta, String name) {
        if (meta.steps.size() >= MAX_STEPS_PER_ATTEMPT) {
            if (!meta.stepsTruncated) {
                meta.stepsTruncated = true;
                meta.warnings.add("a test produced more than " + MAX_STEPS_PER_ATTEMPT
                        + " steps; the rest were dropped");
            }
            meta.open.add(DROPPED);
            return DROPPED;
        }
        Step s = new Step(name);
        s.parentIndex = parentOf(meta.open);
        meta.steps.add(s);
        int index = meta.steps.size() - 1;
        meta.open.add(index);
        return index;
    }

    /** Closes the innermost open step. A stop with no start is ignored rather than corrupting the tree. */
    static void stop(CaseMeta meta, String status, long durationNanos, String error) {
        if (meta.open.isEmpty()) {
            return;
        }
        int idx = meta.open.remove(meta.open.size() - 1);
        if (idx == DROPPED) {
            return;
        }
        Step s = meta.steps.get(idx);
        s.status = (status == null || status.isEmpty()) ? Status.PASSED : status;
        s.durationNanos = durationNanos;
        s.error = error == null ? "" : error;
    }

    /** Attaches a parameter to the innermost open step, or to the case when none is open. */
    static void parameter(CaseMeta meta, String name, String value, boolean masked) {
        String[] row = new String[] {name, masked ? null : value, masked ? "1" : ""};
        Integer parent = parentOf(meta.open);
        if (parent == null) {
            meta.parameters.add(row);
            return;
        }
        meta.steps.get(parent).parameters.add(row);
    }

    /** The innermost open step that is not a dropped sentinel, or null at case level. */
    private static Integer parentOf(List<Integer> open) {
        for (int i = open.size() - 1; i >= 0; i--) {
            int idx = open.get(i);
            if (idx != DROPPED) {
                return idx;
            }
        }
        return null;
    }

    static final class Step {
        final String name;
        String status = Status.PASSED;
        long durationNanos;
        String error = "";
        Integer parentIndex;
        final List<String[]> parameters = new ArrayList<>();

        Step(String name) {
            this.name = name;
        }
    }
}
