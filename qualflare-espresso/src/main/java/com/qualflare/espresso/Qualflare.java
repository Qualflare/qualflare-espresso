package com.qualflare.espresso;

import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What a test can add to its own report: labels, tags, links, a priority, a description,
 * parameters and nested steps.
 *
 * <p>Every method is safe to call from anywhere. Nothing here throws, nothing returns an error, and
 * a call made outside a test is dropped with a single warning — a reporting API that can break a
 * test suite is worse than no API.
 *
 * <pre>
 * &#64;Test public void signsIn() {
 *     Qualflare.label("team", "identity");
 *     Qualflare.tag("smoke");
 *     Qualflare.step("enter credentials", () -&gt; {
 *         onView(withId(R.id.email)).perform(typeText("ada@example.com"));
 *     });
 * }
 * </pre>
 *
 * <p><b>Why a static volatile and not a ThreadLocal.</b> {@code Qualflare.step()} is routinely
 * called from inside {@code ActivityScenario.onActivity{}} or a {@code ViewAction}, both of which
 * run on the <b>main looper</b>, while the test body runs on the instrumentation thread. The spike
 * measured exactly that (docs/SPIKE-2026-09-20.md): {@code onActivity thread = main},
 * {@code test thread = Instr: androidx.test.runner.AndroidJUnitRunner}. A ThreadLocal would have
 * silently dropped the calls test authors are most likely to write. One slot is safe because
 * AndroidJUnitRunner runs tests serially.
 */
public final class Qualflare {

    /** Link types the server understands. */
    public static final String ISSUE = "issue";
    public static final String TMS = "tms";
    public static final String CUSTOM = "custom";

    public static final String HIGH = "high";
    public static final String MEDIUM = "medium";
    public static final String LOW = "low";

    private static volatile Accumulator accumulator;
    private static volatile String currentKey;
    /** The sink attachments are written through: the same one the report uses. */
    private static volatile ReportSink sink;

    /** One warning per process, not one per stray call: a flood teaches nothing. */
    private static final AtomicBoolean warned = new AtomicBoolean(false);

    private Qualflare() {}

    // ---------------------------------------------------------------- listener plumbing

    static void begin(Accumulator acc, String key) {
        accumulator = acc;
        currentKey = key;
    }

    static void sink(ReportSink reportSink) {
        sink = reportSink;
    }

    static void end() {
        currentKey = null;
    }

    /**
     * Whether there is a case to attach to. Lets {@link QualflareRule} skip a full-screen capture
     * when reporting is off, instead of spending it and then dropping the result with a warning.
     */
    static boolean hasCase() {
        return accumulator != null && currentKey != null;
    }

    // ---------------------------------------------------------------- the public API

    /** A name/value pair shown on the case, e.g. {@code label("team", "identity")}. */
    public static void label(String name, String value) {
        CaseMeta m = meta();
        if (m == null || name == null) {
            return;
        }
        synchronized (m) {
            m.labels.add(new String[] {name, value == null ? "" : value});
        }
    }

    public static void tag(String... tags) {
        CaseMeta m = meta();
        if (m == null || tags == null) {
            return;
        }
        synchronized (m) {
            for (String t : tags) {
                if (t != null && !t.isEmpty()) {
                    m.tags.add(t);
                }
            }
        }
    }

    /** A link with no type, which the server files as {@link #CUSTOM}. */
    public static void link(String url) {
        link(url, CUSTOM, null);
    }

    public static void link(String url, String type, String name) {
        CaseMeta m = meta();
        if (m == null || url == null || url.isEmpty()) {
            return;
        }
        synchronized (m) {
            m.links.add(new String[] {url, type == null ? CUSTOM : type, name == null ? "" : name});
        }
    }

    /** {@link #HIGH}, {@link #MEDIUM} or {@link #LOW}; anything else is passed through. */
    public static void priority(String priority) {
        CaseMeta m = meta();
        if (m == null || priority == null) {
            return;
        }
        synchronized (m) {
            m.priority = priority;
        }
    }

    public static void description(String description) {
        CaseMeta m = meta();
        if (m == null || description == null) {
            return;
        }
        synchronized (m) {
            m.description = description;
        }
    }

    /** A parameter on the innermost open step, or on the case when none is open. */
    public static void parameter(String name, String value) {
        CaseMeta m = meta();
        if (m == null || name == null) {
            return;
        }
        synchronized (m) {
            Steps.parameter(m, name, value == null ? "" : value, false);
        }
    }

    /**
     * A parameter whose value is recorded as masked.
     *
     * <p>There is deliberately no overload taking a value: a signature that cannot accept a secret
     * cannot leak one.
     */
    public static void maskedParameter(String name) {
        CaseMeta m = meta();
        if (m == null || name == null) {
            return;
        }
        synchronized (m) {
            Steps.parameter(m, name, null, true);
        }
    }

    /**
     * Runs {@code body} as a named, timed step, nested inside any step already open.
     *
     * <p>Exceptions propagate. A step that swallowed them would turn a failing test green, which is
     * a far worse bug than a missing step.
     */
    public static void step(String name, Runnable body) {
        if (body == null) {
            return;
        }
        CaseMeta m = meta();
        if (m == null) {
            body.run(); // still run it: the test's behaviour must not depend on the reporter
            return;
        }
        synchronized (m) {
            Steps.start(m, name == null ? "step" : name);
        }
        long started = System.nanoTime();
        try {
            body.run();
            synchronized (m) {
                Steps.stop(m, Status.PASSED, System.nanoTime() - started, "");
            }
        } catch (Throwable t) {
            synchronized (m) {
                Steps.stop(m, Status.of(t), System.nanoTime() - started, describe(t));
            }
            // Rethrown as-is, not wrapped, so the test sees exactly what it threw and
            // Status.of classifies the original type. A Runnable cannot throw a checked
            // exception, so the last branch is unreachable in practice and exists only to
            // satisfy the compiler.
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            }
            if (t instanceof Error) {
                throw (Error) t;
            }
            throw new RuntimeException(t);
        }
    }

    /**
     * Attaches bytes to the running test.
     *
     * <p>Images are written beside the report; anything else is inlined against a per-run budget.
     * Silently does nothing when there is no case or no sink — an attachment is never worth failing
     * a test over.
     */
    public static void attachment(String name, byte[] content, String mimeType) {
        record(Attachments.of(sinkOrNull(), name, content, mimeType));
    }

    /** As above, from a file on the device. {@code File}, not {@code Path}: Path is API 26+. */
    public static void attachment(String name, File file, String mimeType) {
        record(Attachments.of(sinkOrNull(), name, file, mimeType));
    }

    /**
     * A screenshot from a bitmap the caller already has, e.g. from Espresso's
     * {@code captureToBitmap()} or {@code DeviceCapture.takeScreenshot()}.
     *
     * <p>The bitmap is never recycled here: it belongs to the caller, and recycling someone else's
     * bitmap turns a reporting call into a crash on the next draw.
     */
    public static void screenshot(String name, Bitmap bitmap) {
        if (bitmap == null) {
            return;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            attachment(name == null ? "screenshot" : name, out.toByteArray(), "image/png");
        } catch (Throwable t) {
            // A recycled bitmap, or an OOM on a very large screen. Never fatal.
        }
    }

    private static ReportSink sinkOrNull() {
        return sink;
    }

    private static void record(Attachments.Attachment a) {
        if (a == null) {
            return;
        }
        Accumulator acc = accumulator;
        String key = currentKey;
        if (acc == null || key == null) {
            warnOnce();
            return;
        }
        acc.attachment(key, a);
    }

    // ---------------------------------------------------------------- internals

    private static CaseMeta meta() {
        Accumulator acc = accumulator;
        String key = currentKey;
        if (acc == null || key == null) {
            warnOnce();
            return null;
        }
        CaseMeta m = acc.meta(key);
        if (m == null) {
            warnOnce();
        }
        return m;
    }

    private static void warnOnce() {
        if (warned.compareAndSet(false, true)) {
            Notes.warn("a Qualflare.* call was made outside a running test and was ignored."
                    + " If this is inside @BeforeClass, a @ClassRule or a helper thread, there is"
                    + " no case to attach it to.");
        }
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getName() : m;
    }
}
