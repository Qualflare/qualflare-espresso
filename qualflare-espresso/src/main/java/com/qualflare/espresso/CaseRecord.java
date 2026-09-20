package com.qualflare.espresso;

import java.util.ArrayList;
import java.util.List;

/**
 * One test, and every attempt at it.
 *
 * <p>Keyed on the JUnit uniqueId, which is what makes re-run accumulation work: Surefire
 * re-executes a failed test in a new TestPlan but the same JVM, and the uniqueId is the
 * same across those plans. Measured on Surefire 3.5.6 with rerunFailingTestsCount=3 --
 * one test arrived as FAILED, FAILED, SUCCESSFUL across plans 1..3, and was absent from
 * plan 4 because Surefire narrows each re-run to what is still failing. This is unchanged
 * from 3.5.2; only the session scoping around it changed, in 3.5.4.
 *
 * <p>Not thread-safe on its own; {@link Accumulator} owns the locking.
 */
final class CaseRecord {
    final String uniqueId;
    final String suiteName;
    final String className;
    final String displayName;
    final String legacyName;
    final List<Attempt> attempts = new ArrayList<>();

    /**
     * Author metadata from the LAST attempt only.
     *
     * <p>Same rule the CucumberJS reporter documents: attempts carry their own status,
     * duration and error, but steps, labels, tags and parameters come from the final
     * attempt. Replaying an abandoned attempt's step trace alongside the winning one
     * would show a step tree that never existed in that shape.
     */
    CaseMeta meta = new CaseMeta();

    CaseRecord(String uniqueId, String suiteName, String className, String displayName, String legacyName) {
        this.uniqueId = uniqueId;
        this.suiteName = suiteName;
        this.className = className;
        this.displayName = displayName;
        this.legacyName = legacyName;
    }

    /**
     * The final attempt wins, which is what makes a rerun-to-green read as green.
     * Surefire's own summary agrees -- it counts that case as a Flake, not a Failure.
     */
    String status() {
        return attempts.isEmpty() ? Status.SKIPPED : attempts.get(attempts.size() - 1).status;
    }

    long durationNanos() {
        return attempts.isEmpty() ? 0L : attempts.get(attempts.size() - 1).durationNanos;
    }

    String message() {
        return attempts.isEmpty() ? "" : attempts.get(attempts.size() - 1).message;
    }

    /**
     * Flaky means it genuinely recovered: at least one failing attempt, and a final pass.
     * A test that failed every attempt is not flaky, it is broken -- calling it flaky
     * would hide a hard failure behind a softer word.
     */
    boolean isFlaky() {
        if (attempts.size() < 2 || !Status.PASSED.equals(status())) {
            return false;
        }
        for (int i = 0; i < attempts.size() - 1; i++) {
            String s = attempts.get(i).status;
            if (Status.FAILED.equals(s) || Status.ERROR.equals(s) || Status.TIMEOUT.equals(s)) {
                return true;
            }
        }
        return false;
    }

    /** 1-based count of retries, i.e. attempts beyond the first. */
    int retryCount() {
        return Math.max(0, attempts.size() - 1);
    }
}
