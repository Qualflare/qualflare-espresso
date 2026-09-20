package com.qualflare.espresso;

import java.util.ArrayList;
import java.util.List;

/**
 * The author-supplied half of a case: everything JUnit itself has no concept of.
 *
 * <p>Rows are plain arrays rather than small classes on purpose. They exist only between
 * {@link Replay} and {@link ReportWriter}, never cross a public boundary, and a handful of
 * value types would be more ceremony than the two-field pairs justify.
 */
final class CaseMeta {
    /** {name, value} */
    final List<String[]> labels = new ArrayList<>();
    final List<String> tags = new ArrayList<>();
    /** {url, type, name} */
    final List<String[]> links = new ArrayList<>();
    /** {name, value-or-null, "1" when masked} */
    final List<String[]> parameters = new ArrayList<>();
    final List<Steps.Step> steps = new ArrayList<>();
    /**
     * Indices of the steps currently open, innermost last; {@link Steps#DROPPED} for a step
     * dropped at the cap. Held here rather than in a local because steps are pushed live as
     * the test runs, not replayed from a stream afterwards.
     */
    final List<Integer> open = new ArrayList<>();
    boolean stepsTruncated;
    final List<Attachments.Attachment> attachments = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();

    String priority = "";
    String description = "";

    /**
     * True when the test recorded nothing at all. The live model needs this where the JUnit 5
     * sibling could simply check whether any report entries had arrived: a CaseMeta now exists
     * for every started test, so its mere presence says nothing.
     */
    boolean isEmpty() {
        return labels.isEmpty() && tags.isEmpty() && links.isEmpty() && parameters.isEmpty()
                && steps.isEmpty() && attachments.isEmpty() && warnings.isEmpty()
                && priority.isEmpty() && description.isEmpty();
    }
}
