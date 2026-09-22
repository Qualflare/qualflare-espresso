package com.qualflare.espresso;

import java.util.ArrayList;
import java.util.List;

/**
 * One test, and every attempt at it.
 *
 * <p>Keyed on {@code <class>#<method>}, which is what makes retry accumulation work. JUnit 4 has
 * no uniqueId and no rerunning runner of its own; on Android a retry is a rule -- a
 * {@code RetryRule}, or {@code FlakyTest} with {@code AndroidJUnitRunner}'s own retry support --
 * which fires start/failure/finish again under the SAME description. Keyed on the description,
 * those land on one case as successive attempts; keyed on anything per-attempt they would become
 * separate cases and a rerun-to-green would read as one failure plus one pass.
 *
 * <p>JUnit 4 puts parameters in the method name ({@code signsIn[0]}), so parameterised runs key
 * apart naturally and a parameterised retry still accumulates on its own row.
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
     * <p>The same rule the rest of the family follows: attempts carry their own status, duration
     * and error, but steps, labels, tags and parameters come from the final attempt. Replaying an abandoned attempt's step trace alongside the winning one
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
     * The final attempt wins, which is what makes a rerun-to-green read as green -- the case is
     * flaky, not failed, and the earlier attempts are still in the report to show why.
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
