package com.qualflare.espresso;

/**
 * Attachment records, and the caps that keep one test from filling a device.
 *
 * <p><b>Incomplete by design at this stage.</b> The holder and the caps are here because
 * {@link Accumulator} and {@link CaseMeta} need them; the part that turns a {@code File},
 * {@code byte[]} or {@code Bitmap} into one of these — copying images through the report sink and
 * base64-encoding everything else against a budget — is task 10 of the plan and is not written
 * yet. Nothing calls a conversion path that does not exist: {@link Qualflare} has no attachment
 * method until then.
 *
 * <p>Two Android-specific rules the JVM siblings do not have, recorded now so task 10 honours
 * them:
 *
 * <ul>
 *   <li>Images are written through the SAME sink as the report and referenced by a bare
 *       filename. The spike proved a second stream from one sink works
 *       (docs/SPIKE-2026-09-20.md); splitting them would leave every {@code localImagePath}
 *       dangling, because {@code qf collect} resolves it relative to the report.
 *   <li>Encoding uses {@code android.util.Base64} with {@code NO_WRAP}, not
 *       {@code java.util.Base64}, which is API 26+.
 * </ul>
 */
final class Attachments {

    /** The server keeps 50 per case; sending more is bytes spent on rows that are discarded. */
    static final int MAX_PER_CASE = 50;

    /**
     * Total inline budget for one run, charged in ENCODED bytes because that is what travels.
     * Images do not draw on it: they are copied beside the report and referenced by path.
     */
    static final long INLINE_BUDGET_BYTES = 8L * 1024L * 1024L;

    /**
     * A per-run ceiling on copied image files, which the JVM reporters have no equivalent of.
     * There, the constraint was upload size; on a device it is storage, and a phone with a full
     * data partition fails in ways that look nothing like a reporting problem.
     */
    static final int MAX_IMAGE_FILES_PER_RUN = 100;

    private Attachments() {}

    /** One attachment, already resolved: either inline content or a file beside the report. */
    static final class Attachment {
        final String name;
        final String mimeType;
        /** Base64, for non-images within budget. Null when the payload is a file. */
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
