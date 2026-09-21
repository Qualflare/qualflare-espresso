package com.qualflare.espresso;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * How bytes become a wire attachment.
 *
 * <p>Two routes, chosen by whether the upload endpoint can take the payload out of band:
 *
 * <ul>
 *   <li><b>png, jpeg, gif</b> are written beside the report and referenced by
 *       {@code localImagePath}, so a screenshot never competes with the run's inline budget.
 *   <li><b>everything else</b> is base64-inlined, because the endpoint has nowhere else to put it.
 * </ul>
 *
 * <p>Three differences from the JVM siblings, each forced by the platform:
 *
 * <ul>
 *   <li>{@code android.util.Base64} with {@code NO_WRAP}, not {@code java.util.Base64} (API 26+).
 *       Without NO_WRAP the encoder inserts newlines every 76 characters, which is legal inside a
 *       JSON string but inflates every attachment for nothing.
 *   <li>Images are written <b>through the report sink</b>, not with a filesystem copy. The report
 *       and its images have to travel the same way or {@code localImagePath} — which
 *       {@code qf collect} resolves relative to the report — dangles. The spike confirmed a second
 *       stream from one storage instance works.
 *   <li>A per-run ceiling on image files as well as on inline bytes. There the constraint was
 *       upload size; on a device it is storage, and a phone with a full data partition fails in
 *       ways that look nothing like a reporting problem.
 * </ul>
 *
 * <p>Needs {@code @qualflare/cli} 0.1.24 or newer, the first release that reads
 * {@code localImagePath}.
 */
final class Attachments {

    /** The server keeps 50 per case; more is bytes spent on rows that are discarded. */
    static final int MAX_PER_CASE = 50;

    /** Charged in ENCODED bytes: base64 inflates by ~4/3 and what matters is what travels. */
    static final long INLINE_BUDGET_BYTES = 8L * 1024L * 1024L;

    /** Device storage, not upload size, is the limit for copied files. */
    static final int MAX_IMAGE_FILES_PER_RUN = 100;

    private static final AtomicLong inlinedBytes = new AtomicLong();
    private static final AtomicInteger imageFiles = new AtomicInteger();

    private Attachments() {}

    /**
     * From bytes already in hand — a screenshot, a serialised payload.
     *
     * @return a wire-ready attachment, or null when it cannot be recorded. A missing attachment must
     *     never fail a run: the test already passed or failed on its own merits.
     */
    static Attachment of(ReportSink sink, String name, byte[] content, String mimeType) {
        if (name == null || content == null) {
            return null;
        }
        String mime = mimeType == null ? "" : mimeType;
        long size = content.length;
        try {
            if (isImage(mime)) {
                if (imageFiles.incrementAndGet() > MAX_IMAGE_FILES_PER_RUN) {
                    imageFiles.decrementAndGet();
                    // Recorded without a payload rather than dropped: the report still shows that
                    // a screenshot was taken, which is the difference between "nothing here" and
                    // "too many to keep".
                    return new Attachment(name, mime, null, null, size);
                }
                String target = fileNameFor(name, mime);
                try (OutputStream out = sink.open(target)) {
                    out.write(content);
                }
                return new Attachment(name, mime, null, target, size);
            }

            long encoded = 4L * ((size + 2L) / 3L);
            if (inlinedBytes.addAndGet(encoded) > INLINE_BUDGET_BYTES) {
                inlinedBytes.addAndGet(-encoded);
                return new Attachment(name, mime, null, null, size);
            }
            return new Attachment(name, mime, Base64.encodeToString(content, Base64.NO_WRAP), null,
                    size);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** From a file on the device, read once into memory — attachments are small by policy. */
    static Attachment of(ReportSink sink, String name, File file, String mimeType) {
        if (file == null || !file.isFile() || !file.canRead()) {
            return null;
        }
        try {
            return of(sink, name, readAll(file), mimeType);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Prefixed and randomised: two tests can both produce "screenshot.png", and the second must not
     * overwrite the first. The result is a BARE filename, because {@code qf collect} resolves it
     * next to the report.
     */
    private static String fileNameFor(String name, String mime) {
        // Anything outside this set becomes an underscore, so a name can never contain a path
        // separator. Runs of dots collapse too: "../../etc/passwd" would otherwise sanitise to
        // ".._.._etc_passwd", which is a harmless filename but reads like a traversal attempt to
        // the next person who greps the output directory.
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("\\.{2,}", ".");
        if (safe.isEmpty() || ".".equals(safe)) {
            safe = "attachment";
        }
        if (safe.indexOf('.') < 0) {
            safe = safe + extensionFor(mime);
        }
        return String.format(Locale.ROOT, "qf-attach-%d-%s",
                ThreadLocalRandom.current().nextInt(1_000_000), safe);
    }

    private static String extensionFor(String mime) {
        if (mime.startsWith("image/jpeg") || mime.startsWith("image/jpg")) {
            return ".jpg";
        }
        if (mime.startsWith("image/gif")) {
            return ".gif";
        }
        return ".png";
    }

    private static byte[] readAll(File file) throws IOException {
        // Files.readAllBytes is java.nio.file, which is API 26+.
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static boolean isImage(String mime) {
        return mime.startsWith("image/png")
                || mime.startsWith("image/jpeg")
                || mime.startsWith("image/jpg")
                || mime.startsWith("image/gif");
    }

    /** Test-only: budgets are per run, and a test run is many "runs". */
    static void resetBudgets() {
        inlinedBytes.set(0);
        imageFiles.set(0);
    }

    static final class Attachment {
        final String name;
        final String mimeType;
        /** Base64, for non-images within budget. Null when the payload is a file or was dropped. */
        final String content;
        /** Bare filename of a copy written next to the report. Null for inline content. */
        final String localImagePath;
        final long fileSize;

        Attachment(String name, String mimeType, String content, String localImagePath,
                long fileSize) {
            this.name = name;
            this.mimeType = mimeType;
            this.content = content;
            this.localImagePath = localImagePath;
            this.fileSize = fileSize;
        }
    }
}
