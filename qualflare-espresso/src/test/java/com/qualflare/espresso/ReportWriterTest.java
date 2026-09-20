package com.qualflare.espresso;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * The wire shape. These assertions are the contract with {@code qf collect}, so they are written
 * against the rendered text rather than against the objects that produced it.
 */
@RunWith(RobolectricTestRunner.class)
public class ReportWriterTest {

    private static final String UID = "com.example.LoginTest#signsIn";

    private static Accumulator oneCase(String status) {
        Accumulator acc = new Accumulator();
        acc.started(UID, 0L);
        acc.finished(UID, "com.example.LoginTest", "com.example.LoginTest", "signsIn", "signsIn",
                status, 1_500_000L, "", "");
        return acc;
    }

    @Test
    public void the_top_level_shape_is_what_collect_expects() {
        String json = ReportWriter.render(oneCase(Status.PASSED).cases());

        assertTrue(json.contains("\"framework\":\"espresso\""));
        assertTrue(json.contains("\"platform\":\"android\""));
        assertTrue("browser is always present and always empty for a mobile run",
                json.contains("\"browser\":\"\""));
        assertTrue(json.contains("\"environment\":\"development\""));
        assertTrue(json.contains("\"cliName\":\"qualflare-espresso\""));
        // Explicit nulls, not omissions: the server distinguishes "not detected" from "absent".
        assertTrue(json.contains("\"branch\":null"));
        assertTrue(json.contains("\"commit\":null"));
        assertTrue(json.contains("\"milestone\":null"));
    }

    /**
     * The one field whose value differs from every sibling reporter. They hardcode "unit" because
     * the JUnit Platform could be running anything; here every case arrived through
     * AndroidJUnitRunner driving a real app.
     */
    @Test
    public void the_suite_category_is_e2e() {
        String json = ReportWriter.render(oneCase(Status.PASSED).cases());
        assertTrue(json.contains("\"category\":\"e2e\""));
        assertFalse("'unit' would mislabel every Espresso suite", json.contains("\"category\":\"unit\""));
    }

    @Test
    public void the_timestamp_is_iso_8601_in_utc() {
        String json = ReportWriter.render(oneCase(Status.PASSED).cases());
        // java.time is API 26+, so this is SimpleDateFormat; the format still has to be right.
        assertTrue("timestamp should look like 2026-09-20T12:34:56.789Z, was: " + json,
                json.matches(".*\"timestamp\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z\".*"));
    }

    @Test
    public void the_os_string_names_android_not_linux() {
        String json = ReportWriter.render(oneCase(Status.PASSED).cases());
        assertFalse("os.name reports Linux on Android, which is true and useless",
                json.contains("\"os\":\"Linux"));
        assertTrue("os was: " + json, json.contains("\"os\":\"Android "));
    }

    @Test
    public void a_case_carries_its_identity_status_and_duration() {
        String json = ReportWriter.render(oneCase(Status.FAILED).cases());
        assertTrue(json.contains("\"id\":\"com.example.LoginTest#signsIn\""));
        assertTrue(json.contains("\"name\":\"signsIn\""));
        assertTrue(json.contains("\"status\":\"failed\""));
        assertTrue("duration is nanoseconds", json.contains("\"duration\":1500000"));
        assertTrue(json.contains("\"className\":\"com.example.LoginTest\""));
    }

    /** The rule AccumulatorTest hands over: a lone attempt is below the wire's floor. */
    @Test
    public void a_single_attempt_is_not_serialised_but_a_retry_is() {
        String single = ReportWriter.render(oneCase(Status.PASSED).cases());
        assertFalse("one attempt is the normal case and would double the payload for nothing",
                single.contains("\"attempts\""));

        Accumulator acc = new Accumulator();
        acc.started(UID, 0L);
        acc.finished(UID, "S", "C", "n", "n", Status.FAILED, 1_000_000L, "first", "trace");
        acc.started(UID, 0L);
        acc.finished(UID, "S", "C", "n", "n", Status.PASSED, 2_000_000L, "", "");

        String retried = ReportWriter.render(acc.cases());
        assertTrue(retried.contains("\"attempts\""));
        assertTrue(retried.contains("\"isFlaky\":true"));
        assertTrue(retried.contains("\"retryCount\":1"));
        assertTrue("the first attempt's message survives", retried.contains("first"));
    }

    @Test
    public void steps_are_nested_by_index_and_masked_parameters_carry_no_value() {
        Accumulator acc = new Accumulator();
        acc.started(UID, 0L);
        CaseMeta meta = acc.meta(UID);
        Steps.start(meta, "open the login screen");
        Steps.parameter(meta, "user", "ada", false);
        Steps.parameter(meta, "password", "hunter2", true);
        Steps.start(meta, "tap submit");
        Steps.stop(meta, Status.PASSED, 5L, "");
        Steps.stop(meta, Status.PASSED, 10L, "");
        acc.finished(UID, "S", "C", "n", "n", Status.PASSED, 1L, "", "");

        String json = ReportWriter.render(acc.cases());
        assertTrue(json.contains("\"name\":\"open the login screen\""));
        assertTrue(json.contains("\"name\":\"tap submit\""));
        assertTrue("the inner step names its parent by index", json.contains("\"parentIndex\":0"));
        assertTrue(json.contains("\"name\":\"user\""));
        assertTrue(json.contains("\"value\":\"ada\""));
        assertTrue(json.contains("\"masked\":true"));
        assertFalse("a masked parameter's value must never reach the wire", json.contains("hunter2"));
    }

    @Test
    public void control_characters_in_a_failure_message_are_escaped() {
        Accumulator acc = new Accumulator();
        acc.started(UID, 0L);
        // An ANSI escape, which assertion diffs are full of, plus a newline and a quote.
        acc.finished(UID, "S", "C", "n", "n", Status.FAILED, 1L,
                "expected [32m\"a\"[0m\nbut was \"b\"", "");
        String json = ReportWriter.render(acc.cases());
        assertFalse("a raw escape would make the JSON unparseable", json.contains("["));
        assertTrue(json.contains("\\u001b"));
        assertTrue(json.contains("\\n"));
        assertTrue(json.contains("\\\""));
    }

    @Test
    public void cases_group_into_suites_by_class() {
        Accumulator acc = new Accumulator();
        for (String cls : new String[] {"com.example.A", "com.example.A", "com.example.B"}) {
            String uid = cls + "#t" + acc.cases().size();
            acc.started(uid, 0L);
            acc.finished(uid, cls, cls, "t", "t", Status.PASSED, 1L, "", "");
        }
        String json = ReportWriter.render(acc.cases());
        List<String> names = new ArrayList<>();
        int at = 0;
        while ((at = json.indexOf("\"name\":\"com.example.", at)) >= 0) {
            names.add(json.substring(at + 8, json.indexOf('"', at + 9)));
            at += 8;
        }
        assertEquals("two suites, in first-seen order", 2, names.size());
    }
}
