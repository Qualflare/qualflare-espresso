package com.qualflare.espresso;

import android.os.Build;
import android.os.Process;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ThreadLocalRandom;

/** Serialises the accumulated run into the report directory {@code qf collect} reads. */
final class ReportWriter {

    /** The server drops an attempts array shorter than this, so it is not worth sending. */
    private static final int MIN_ATTEMPTS_TO_SEND = 2;

    private ReportWriter() {}

    /**
     * Chosen ONCE per JVM, not once per write.
     *
     * <p>Two requirements pull in opposite directions. Between processes the name must be
     * UNIQUE: under Android Test Orchestrator every test runs in its own process writing into the
     * same output directory, and {@code qf collect} merges every file it finds, so a collision
     * would lose a test's results. Within a process it must be STABLE, because the report is
     * written many times -- once per incremental flush and again when the run finishes -- and a
     * fresh name each time would leave a trail of partial reports for collect to merge into
     * duplicate cases.
     *
     * <p>Stable plus unique means: compute it once, from the pid and a random suffix.
     */
    private static String fileName;

    /**
     * Resolved on first use, not in a static initialiser: android.os.Process is unavailable on a
     * bare JVM, and this class is exercised by unit tests that never touch a device.
     *
     * <p>ProcessHandle.current().pid() -- what the JVM siblings use -- does not exist on Android at
     * any API level and cannot be desugared. Process.myPid() is the equivalent, and matters more
     * here than there: under Android Test Orchestrator every test runs in its OWN process, so the
     * pid is what keeps those per-test reports from overwriting one another.
     */
    private static synchronized String fileName() {
        if (fileName == null) {
            fileName = String.format(Locale.ROOT, "qualflare-espresso-%d-%d-%d.json",
                    pid(),
                    System.currentTimeMillis(),
                    ThreadLocalRandom.current().nextInt(100000));
        }
        return fileName;
    }

    private static int pid() {
        try {
            return Process.myPid();
        } catch (Throwable t) {
            return 0; // a JVM test: the random suffix still keeps names distinct
        }
    }

    /**
     * Writes the report and says nothing.
     *
     * <p>This is the incremental flush, which runs many times in a run, so it neither logs nor
     * reports anomalies -- doing either here would print the same warning once per test.
     *
     * <p>Rewriting the whole report under the SAME name is safe on both delivery routes, and that
     * was measured rather than assumed: {@code FileTestStorage.openOutputFile(name)} delegates to
     * {@code new FileOutputStream(file, false)}, and the orchestrator's content-provider storage
     * opens the same uri with mode {@code "wt"} -- write and truncate. Neither appends, so the
     * last flush to win is the whole report rather than several concatenated ones.
     */
    static String flush(ReportSink sink, Collection<CaseRecord> cases) throws IOException {
        String name = fileName();
        try (OutputStream out = sink.open(name);
                Writer writer = new OutputStreamWriter(out, "UTF-8")) {
            writer.write(render(cases));
        }
        return name;
    }

    /** The final write: the same bytes as a flush, plus everything a build log should show. */
    static String write(ReportSink sink, Collection<CaseRecord> cases) throws IOException {
        String name = flush(sink, cases);
        Notes.say("wrote " + cases.size() + " case(s) to " + name);
        String note = sink.describe();
        if (note != null) {
            // The file route asks something of the user, so it has to say so where a build log
            // shows it.
            Notes.say(note);
        }

        // Anomalies go to stderr, where a build log will actually show them. Replay
        // records these and nothing used to read them, so a user whose steps were
        // truncated was never told -- the report just quietly had fewer steps than the
        // test emitted.
        for (CaseRecord c : cases) {
            for (String w : c.meta.warnings) {
                Notes.warn(c.displayName + ": " + w);
            }
        }
        return name;
    }

    static String render(Collection<CaseRecord> cases) {
        Map<String, List<CaseRecord>> bySuite = new LinkedHashMap<>();
        for (CaseRecord c : cases) {
            bySuite.computeIfAbsent(c.suiteName, k -> new ArrayList<>()).add(c);
        }

        StringBuilder suites = new StringBuilder("[");
        boolean firstSuite = true;
        for (Map.Entry<String, List<CaseRecord>> e : bySuite.entrySet()) {
            if (!firstSuite) {
                suites.append(',');
            }
            firstSuite = false;
            suites.append(renderSuite(e.getKey(), e.getValue()));
        }
        suites.append(']');

        String metadata = Json.object()
                .field("version", Version.VALUE)
                // SimpleDateFormat, not Instant: java.time is API 26+ and this library targets 24.
                .field("timestamp", timestamp())
                .field("cliName", "qualflare-espresso")
                .end();

        return Json.object()
                .field("framework", "espresso")
                .field("platform", Config.platform())
                // os.name says "Linux" on Android, which is true and useless.
                .field("os", androidOs())
                .field("browser", "")
                .field("environment", Config.environment())
                .field("language", Config.language())
                .raw("metadata", metadata)
                // Explicit nulls, not omissions: the wire contract distinguishes "not
                // detected" from "absent", and the server treats a missing key differently.
                .nullableField("branch", Config.branch())
                .nullableField("commit", Config.commit())
                .raw("milestone", "null")
                .raw("suites", suites.toString())
                .end();
    }

    private static String renderSuite(String suiteName, List<CaseRecord> cases) {
        long total = 0L;
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < cases.size(); i++) {
            if (i > 0) {
                arr.append(',');
            }
            CaseRecord c = cases.get(i);
            total += c.durationNanos();
            arr.append(renderCase(c));
        }
        arr.append(']');

        return Json.object()
                .field("name", suiteName)
                .field("duration", total)
                // "e2e", not the siblings' hardcoded "unit". There, the JUnit Platform could be
                // running anything and guessing would mislabel a Selenium suite; here every case
                // came through AndroidJUnitRunner driving a real app, so we know.
                .field("category", "e2e")
                .raw("cases", arr.toString())
                .end();
    }

    private static String renderCase(CaseRecord c) {
        Json j = Json.object()
                .field("id", c.uniqueId)
                .field("name", c.displayName)
                .field("status", c.status())
                .field("duration", c.durationNanos());

        if (!c.className.isEmpty()) {
            j.field("className", c.className);
        }
        String msg = c.message();
        if (!msg.isEmpty()) {
            j.field("error", msg);
        }
        if (c.retryCount() > 0) {
            j.field("retryCount", c.retryCount());
        }
        if (c.isFlaky()) {
            j.field("isFlaky", true);
        }
        if (c.attempts.size() >= MIN_ATTEMPTS_TO_SEND) {
            j.raw("attempts", renderAttempts(c));
        }

        CaseMeta m = c.meta;
        if (!m.priority.isEmpty()) {
            j.field("priority", m.priority);
        }
        if (!m.description.isEmpty()) {
            j.field("description", m.description);
        }
        if (!m.tags.isEmpty()) {
            j.raw("tags", strings(m.tags));
        }
        if (!m.labels.isEmpty()) {
            j.raw("labels", rows(m.labels, r ->
                    Json.object().field("name", r[0]).field("value", r[1]).end()));
        }
        if (!m.links.isEmpty()) {
            j.raw("links", rows(m.links, r -> {
                Json k = Json.object().field("url", r[0]).field("type", r[1]);
                if (!r[2].isEmpty()) {
                    k.field("name", r[2]);
                }
                return k.end();
            }));
        }
        if (!m.parameters.isEmpty()) {
            j.raw("properties", renderProperties(m.parameters));
        }
        if (!m.steps.isEmpty()) {
            j.raw("steps", renderSteps(m.steps));
        }
        if (!m.attachments.isEmpty()) {
            j.raw("attachments", renderAttachments(m.attachments));
        }
        return j.end();
    }

    private static String renderAttachments(List<Attachments.Attachment> list) {
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                arr.append(',');
            }
            Attachments.Attachment a = list.get(i);
            Json j = Json.object().field("name", a.name);
            if (!a.mimeType.isEmpty()) {
                j.field("mimeType", a.mimeType);
            }
            if (a.content != null) {
                j.field("content", a.content);
            }
            if (a.localImagePath != null) {
                j.field("localImagePath", a.localImagePath);
            }
            j.field("fileSize", a.fileSize);
            arr.append(j.end());
        }
        return arr.append(']').toString();
    }

    /** Case-level parameters ride as `properties`, matching the wire's map shape. */
    private static String renderProperties(List<String[]> params) {
        Json j = Json.object();
        for (String[] p : params) {
            // A masked parameter carries no value at all. `masked` is a display hint the
            // server does not act on, so withholding it here is what keeps it secret.
            j.field(p[0], "1".equals(p[2]) ? "" : (p[1] == null ? "" : p[1]));
        }
        return j.end();
    }

    private static String renderSteps(List<Steps.Step> steps) {
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) {
                arr.append(',');
            }
            Steps.Step s = steps.get(i);
            Json j = Json.object()
                    .field("name", s.name)
                    .field("status", s.status)
                    .field("duration", s.durationNanos);
            if (!s.error.isEmpty()) {
                j.field("error", s.error);
            }
            if (s.parentIndex != null) {
                j.field("parentIndex", s.parentIndex.longValue());
            }
            if (!s.parameters.isEmpty()) {
                j.raw("parameters", rows(s.parameters, r -> {
                    Json k = Json.object().field("name", r[0]);
                    if ("1".equals(r[2])) {
                        k.field("masked", true);
                    } else if (r[1] != null) {
                        k.field("value", r[1]);
                    }
                    return k.end();
                }));
            }
            arr.append(j.end());
        }
        return arr.append(']').toString();
    }

    private static String strings(List<String> values) {
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                arr.append(',');
            }
            arr.append(Json.escape(values.get(i)));
        }
        return arr.append(']').toString();
    }

    private static String rows(List<String[]> rows, java.util.function.Function<String[], String> f) {
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                arr.append(',');
            }
            arr.append(f.apply(rows.get(i)));
        }
        return arr.append(']').toString();
    }

    private static String renderAttempts(CaseRecord c) {
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < c.attempts.size(); i++) {
            if (i > 0) {
                arr.append(',');
            }
            Attempt a = c.attempts.get(i);
            Json j = Json.object()
                    .field("attempt", i + 1) // 1-based; the server drops anything lower
                    .field("status", a.status)
                    .field("duration", a.durationNanos);
            if (!a.message.isEmpty()) {
                j.field("message", a.message);
            }
            if (!a.trace.isEmpty()) {
                j.field("trace", a.trace);
            }
            arr.append(j.end());
        }
        return arr.append(']').toString();
    }

    /** ISO-8601 in UTC, the shape the server parses. */
    private static String timestamp() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    /**
     * Something a person can act on: the release, the API level and the ABI.
     *
     * <p>Falls back to the JVM properties when Build is unavailable, which is how the unit tests
     * see it.
     */
    private static String androidOs() {
        try {
            String abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                    ? Build.SUPPORTED_ABIS[0] : "";
            return ("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") "
                    + abi).trim();
        } catch (Throwable t) {
            return (System.getProperty("os.name", "") + " " + System.getProperty("os.arch", "")).trim();
        }
    }
}
