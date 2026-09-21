package com.qualflare.espresso;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Which route an attachment takes, and what happens at each limit. */
@RunWith(RobolectricTestRunner.class)
public class AttachmentsTest {

    /** Captures what was written so a test can assert the image really landed in the sink. */
    private static final class RecordingSink extends ReportSink {
        final Map<String, ByteArrayOutputStream> written = new LinkedHashMap<>();

        @Override
        OutputStream open(String fileName) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            written.put(fileName, out);
            return out;
        }

        @Override
        String describe() {
            return null;
        }
    }

    private RecordingSink sink;

    @Before
    public void reset() {
        sink = new RecordingSink();
        Attachments.resetBudgets();
    }

    @Test
    public void an_image_is_written_beside_the_report_and_referenced_by_a_bare_filename() {
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        Attachments.Attachment a = Attachments.of(sink, "failure shot", png, "image/png");

        assertNotNull(a);
        assertNull("an image must not be inlined: it would eat the run's budget", a.content);
        assertNotNull(a.localImagePath);
        assertFalse("qf collect resolves this next to the report, so it must not be a path",
                a.localImagePath.contains("/"));
        assertTrue("the name is prefixed so two tests' screenshot.png cannot collide",
                a.localImagePath.startsWith("qf-attach-"));
        assertTrue(a.localImagePath.endsWith(".png"));
        assertEquals("the bytes went to the sink", 1, sink.written.size());
        assertEquals(png.length, sink.written.values().iterator().next().size());
        assertEquals(png.length, a.fileSize);
    }

    @Test
    public void a_non_image_is_inlined_as_base64_without_line_breaks() {
        byte[] body = "col1,col2\n1,2\n".getBytes();
        Attachments.Attachment a = Attachments.of(sink, "data.csv", body, "text/csv");

        assertNotNull(a);
        assertNull(a.localImagePath);
        assertNotNull(a.content);
        assertFalse("android.util.Base64 wraps at 76 chars unless NO_WRAP is passed",
                a.content.contains("\n"));
        assertTrue("nothing was written to the sink for an inlined attachment",
                sink.written.isEmpty());
    }

    @Test
    public void a_large_payload_past_the_budget_keeps_its_size_but_loses_its_content() {
        // One under the cap, then one that cannot fit.
        byte[] big = new byte[(int) (Attachments.INLINE_BUDGET_BYTES / 2)];
        assertNotNull(Attachments.of(sink, "first.bin", big, "application/octet-stream"));

        byte[] tooMuch = new byte[(int) Attachments.INLINE_BUDGET_BYTES];
        Attachments.Attachment a =
                Attachments.of(sink, "second.bin", tooMuch, "application/octet-stream");

        assertNotNull("the attachment is still recorded", a);
        assertNull("but its content is not sent", a.content);
        assertEquals("the size is kept, so the report shows what was dropped",
                tooMuch.length, a.fileSize);
    }

    @Test
    public void past_the_image_file_ceiling_an_image_is_recorded_without_its_file() {
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G'};
        for (int i = 0; i < Attachments.MAX_IMAGE_FILES_PER_RUN; i++) {
            assertNotNull(Attachments.of(sink, "shot" + i, png, "image/png"));
        }
        assertEquals(Attachments.MAX_IMAGE_FILES_PER_RUN, sink.written.size());

        Attachments.Attachment a = Attachments.of(sink, "one-too-many", png, "image/png");
        assertNotNull("the report still shows a screenshot was taken", a);
        assertNull(a.localImagePath);
        assertEquals("no more files written to the device", Attachments.MAX_IMAGE_FILES_PER_RUN,
                sink.written.size());
    }

    @Test
    public void a_file_is_read_from_disk_and_a_missing_one_is_simply_dropped() throws IOException {
        File dir = File.createTempFile("qualflare", "-probe").getParentFile();
        File real = new File(dir, "report-" + System.nanoTime() + ".txt");
        try (OutputStream out = new FileOutputStream(real)) {
            out.write("hello".getBytes());
        }

        Attachments.Attachment a = Attachments.of(sink, "notes.txt", real, "text/plain");
        assertNotNull(a);
        assertEquals(5L, a.fileSize);

        assertNull("a missing file must never fail the run",
                Attachments.of(sink, "gone.txt", new File(dir, "does-not-exist"), "text/plain"));
        real.delete();
    }

    @Test
    public void a_name_with_no_extension_gets_one_from_its_mime_type() {
        Attachments.Attachment png = Attachments.of(sink, "shot", new byte[] {1}, "image/png");
        assertTrue(png.localImagePath.endsWith(".png"));

        Attachments.resetBudgets();
        Attachments.Attachment jpg = Attachments.of(sink, "photo", new byte[] {1}, "image/jpeg");
        assertTrue(jpg.localImagePath.endsWith(".jpg"));
    }

    @Test
    public void a_name_with_path_separators_cannot_escape_the_output_directory() {
        Attachments.Attachment a =
                Attachments.of(sink, "../../etc/passwd", new byte[] {1}, "image/png");
        assertNotNull(a);
        assertFalse("a traversal in the NAME must not become a path",
                a.localImagePath.contains("/"));
        assertFalse(a.localImagePath.contains(".."));
    }
}
