package com.qualflare.espresso;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runner.notification.RunNotifier;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.io.OutputStream;

/**
 * What is on disk <b>while</b> the run is still going.
 *
 * <p>The scenario is a process that never reaches {@code testRunFinished}: the emulator is killed,
 * the app is force-stopped, a native crash takes the process down between tests. Reported only at
 * the end, that costs the whole run — and the suite most likely to die that way is the flaky one
 * whose results matter most.
 */
@RunWith(RobolectricTestRunner.class)
public class IncrementalFlushTest {

    private QualflareRunListener listener;
    private RunNotifier notifier;
    private FakeSink sink;

    @Before
    public void setUp() {
        listener = new QualflareRunListener();
        notifier = new RunNotifier();
        notifier.addListener(listener);
        listener.testRunStarted(Description.createSuiteDescription("suite"));
        // Off a device ReportSink.resolve finds neither storage nor a context.
        sink = new FakeSink();
        listener.sink = sink;
        listener.flushIntervalMs = 0L; // drive the throttle explicitly rather than by sleeping
    }

    private void run(String method) {
        Description d = Description.createTestDescription("com.example.LoginTest", method);
        notifier.fireTestStarted(d);
        notifier.fireTestFinished(d);
    }

    /** The whole point: no testRunFinished is ever fired here. */
    @Test
    public void a_run_that_is_killed_still_leaves_a_report_of_every_finished_case() {
        run("first");
        run("second");

        String json = onDisk();
        assertTrue("a case that finished before the kill is in the file", json.contains("first"));
        assertTrue(json.contains("second"));
        assertTrue("and what is there is a whole document, not a prefix of one",
                json.trim().startsWith("{") && json.trim().endsWith("}"));
    }

    /**
     * Each flush REPLACES the file. Both delivery routes truncate on reopen — measured, see
     * {@link ReportWriter#flush} — so a run leaves one report rather than a trail of partials for
     * {@code qf collect} to merge into duplicate cases.
     */
    @Test
    public void every_flush_rewrites_one_file_rather_than_leaving_a_trail() {
        run("first");
        run("second");
        run("third");

        assertEquals("three flushes, one report", 1, sink.written.size());
        String json = onDisk();
        assertEquals("and no case is written twice", 1, occurrences(json, "\"name\":\"third\""));
    }

    @Test
    public void the_throttle_holds_a_flush_back_until_its_interval_has_passed() {
        listener.flushIntervalMs = 60_000L;
        run("first");
        run("second");

        String json = onDisk();
        assertTrue("the first finish flushes immediately: an early crash must not cost everything",
                json.contains("first"));
        assertFalse("the second is inside the window, so the whole report is not rewritten for it",
                json.contains("second"));

        // Until the run ends, at which point everything is written regardless of the throttle.
        notifier.fireTestRunFinished(new org.junit.runner.Result());
        assertTrue(onDisk().contains("second"));
    }

    /** An ignored test is a finished case too, and a suite can be a long stretch of them. */
    @Test
    public void an_ignored_test_is_flushed_like_any_other_case() {
        notifier.fireTestIgnored(
                Description.createTestDescription("com.example.LoginTest", "notYetWritten"));

        assertTrue(onDisk().contains("notYetWritten"));
    }

    /**
     * A sink that has started failing must not print once per test for the rest of the run. The
     * final write is the guarantee; the flush is only an optimisation against losing the run.
     */
    @Test
    public void a_failing_flush_gives_up_quietly_and_leaves_the_final_write_to_try() {
        final int[] opened = {0};
        listener.sink = new ReportSink() {
            @Override
            OutputStream open(String fileName) throws IOException {
                opened[0]++;
                throw new IOException("storage went away");
            }

            @Override
            String describe() {
                return null;
            }
        };

        run("first");
        run("second");
        run("third");

        assertEquals("one attempt, then it stops trying", 1, opened[0]);
    }

    private String onDisk() {
        assertEquals("exactly one report file", 1, sink.written.size());
        return sink.written.values().iterator().next().toString();
    }

    private static int occurrences(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
