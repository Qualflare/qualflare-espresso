package com.qualflare.espresso;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the run has produced so far, keyed by test.
 *
 * <p>It holds the whole run rather than one test at a time because a case can be visited more than
 * once: a {@code RetryRule} or a rerunning runner fires start/failure/finish again for the same
 * test, and those belong in ONE case as successive attempts. Recorded as separate cases they would
 * read as a suite that grew, and the retry history -- the main reason to use a native reporter
 * instead of the XML -- would be gone.
 *
 * <p>Nothing is written from here. {@link QualflareRunListener} owns when the report is written:
 * throttled while the run goes, and once more when it finishes.
 *
 * <p>Synchronised rather than merely built from concurrent collections: {@link Qualflare} writes
 * into the live {@link CaseMeta} from wherever the test calls it, and on Android that is routinely
 * the main looper rather than the instrumentation thread (measured, docs/SPIKE-2026-09-20.md).
 * Appending an attempt is a read-modify-write that must not interleave with those.
 */
final class Accumulator {

    /** Insertion-ordered so the report lists cases in the order they first ran. */
    private final Map<String, CaseRecord> byUniqueId = new LinkedHashMap<>();
    private final Map<String, Long> startedNanos = new LinkedHashMap<>();

    /**
     * Metadata for the attempt currently in flight, keyed by test key.
     *
     * <p>The JUnit 5 sibling buffered report ENTRIES here -- {key, value} strings republished
     * through the platform -- and replayed them into a CaseMeta at finish. JUnit 4 has no
     * publishReportEntry, and on Android the listener shares a process with the test, so
     * {@link Qualflare} writes into this live CaseMeta as the test runs. Same lifetime rules,
     * one less hop.
     */
    private final Map<String, CaseMeta> pendingMeta = new LinkedHashMap<>();
    private final Map<String, List<Attachments.Attachment>> pendingAttachments = new LinkedHashMap<>();

    synchronized void started(String uniqueId, long nanoTime) {
        startedNanos.put(uniqueId, nanoTime);
        // A rerun starts a fresh attempt, so the previous attempt's metadata must not leak
        // into it -- otherwise a retried test accumulates every attempt's steps.
        pendingMeta.put(uniqueId, new CaseMeta());
        pendingAttachments.remove(uniqueId);
    }

    /**
     * The live metadata for a running test, or null when nothing is in flight.
     *
     * <p>{@link Qualflare} calls this, possibly from the main looper rather than the
     * instrumentation thread (measured: docs/SPIKE-2026-09-20.md), which is why every method on
     * this class is synchronized.
     */
    synchronized CaseMeta meta(String uniqueId) {
        return pendingMeta.get(uniqueId);
    }

    /**
     * Attachments land on the case directly rather than waiting for the replay: a file is
     * already resolved by the time it is published, and unlike steps it carries no
     * ordering that needs reconstructing.
     */
    synchronized void attachment(String uniqueId, Attachments.Attachment a) {
        // ALWAYS buffered, never written straight onto the case. On a rerun, finished()
        // replaces rec.meta with a freshly replayed one, so anything attached directly to
        // the previous meta would be silently dropped -- and a screenshot that vanishes
        // only on retried tests is exactly the bug nobody reproduces.
        pendingAttachments.computeIfAbsent(uniqueId, k -> new ArrayList<>()).add(a);
    }



    /**
     * @param nanoTime System.nanoTime() at finish; the elapsed time is computed here
     *                 rather than trusted from the caller so a missing start degrades to
     *                 zero instead of a wild negative.
     */
    synchronized void finished(String uniqueId, String suiteName, String className,
                               String displayName, String legacyName,
                               String status, long nanoTime, String message, String trace) {
        Long start = startedNanos.remove(uniqueId);
        long elapsed = start == null ? 0L : Math.max(0L, nanoTime - start);

        CaseRecord rec = byUniqueId.computeIfAbsent(uniqueId,
                id -> new CaseRecord(id, suiteName, className, displayName, legacyName));
        rec.attempts.add(new Attempt(status, elapsed, message, trace));

        // Replace rather than merge: the final attempt's metadata is the case's metadata.
        CaseMeta live = pendingMeta.remove(uniqueId);
        List<Attachments.Attachment> files = pendingAttachments.remove(uniqueId);
        if (live != null && !live.isEmpty()) {
            rec.meta = live;
        }
        if (files != null) {
            for (Attachments.Attachment a : files) {
                if (rec.meta.attachments.size() < Attachments.MAX_PER_CASE) {
                    rec.meta.attachments.add(a);
                }
            }
        }
    }

    /**
     * A skip has no duration and no attempt of its own worth recording as a retry -- it
     * never ran. Recorded as a single attempt so the case exists in the report; a skipped
     * test missing entirely would look like a shrinking suite.
     */
    synchronized void skipped(String uniqueId, String suiteName, String className,
                              String displayName, String legacyName, String reason) {
        CaseRecord rec = byUniqueId.computeIfAbsent(uniqueId,
                id -> new CaseRecord(id, suiteName, className, displayName, legacyName));
        if (rec.attempts.isEmpty()) {
            rec.attempts.add(new Attempt(Status.SKIPPED, 0L, reason == null ? "" : reason, ""));
        }
    }

    synchronized Collection<CaseRecord> cases() {
        return new ArrayList<>(byUniqueId.values());
    }

    synchronized boolean isEmpty() {
        return byUniqueId.isEmpty();
    }

    synchronized void clear() {
        byUniqueId.clear();
        startedNanos.clear();
        pendingMeta.clear();
        pendingAttachments.clear();
    }
}
