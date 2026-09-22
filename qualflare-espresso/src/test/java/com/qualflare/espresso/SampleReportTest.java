package com.qualflare.espresso;

import static org.junit.Assert.assertTrue;

import org.junit.AssumptionViolatedException;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.RunWith;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.TestTimedOutException;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

/**
 * Writes a report shaped exactly like the one the fixture suite produces on a device, into
 * {@code build/sample-report}, so that CI can run {@code tools/verify.py} against it on every push.
 *
 * <p><b>What this does and does not prove.</b> It proves the verifier and the renderer agree: if
 * a field is renamed, a status stops being emitted, or an attachment stops carrying its
 * localImagePath, the verifier fails here rather than three weeks later on a dispatch-only
 * emulator leg. It does <i>not</i> prove the device behaves this way — that is what the emulator
 * legs are for, and the sequences fired below are the ones the JUnit-4 unit tests already pin
 * down, not a guess about JUnit's callbacks.
 *
 * <p>A verifier that is only ever run against the thing it was written from is a verifier nobody
 * has checked. The build/sample-report directory is therefore also fed to verify.py in a
 * deliberately broken form by {@code tools/verify-selftest.sh}.
 */
@RunWith(RobolectricTestRunner.class)
public class SampleReportTest {

    /** The fixture's package, because the verifier looks up cases by their real names. */
    private static final String PKG = "com.qualflare.espresso.fixture";
    private static final String REPORTER = PKG + ".ReporterTest";

    /** A four-byte PNG stand-in: the verifier checks the file exists and is not empty. */
    private static final byte[] PNG = new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 26, '\n'};

    private static Description test(String cls, String method) {
        return Description.createTestDescription(cls, method);
    }

    @Test
    public void writes_the_sample_report_that_tools_verify_py_checks() throws IOException {
        File dir = new File("build/sample-report");
        deleteContents(dir);
        assertTrue("could not create " + dir.getAbsolutePath(), dir.isDirectory() || dir.mkdirs());

        DirectorySink sink = new DirectorySink(dir);
        Attachments.resetBudgets();

        QualflareRunListener listener = new QualflareRunListener();
        RunNotifier n = new RunNotifier();
        n.addListener(listener);
        listener.testRunStarted(Description.createSuiteDescription("fixture"));
        listener.sink = sink;
        Qualflare.sink(sink);

        // --- a pass
        pass(n, test(REPORTER, "passes"));

        // --- @Ignore: no start, no finish, only this
        n.fireTestIgnored(test(REPORTER, "isIgnored"));

        // --- an assumption that did not hold
        Description assumption = test(REPORTER, "skipsOnAnAssumption");
        n.fireTestStarted(assumption);
        n.fireTestAssumptionFailed(new Failure(assumption,
                new AssumptionViolatedException("an assumption that does not hold")));
        n.fireTestFinished(assumption);

        // --- @Test(timeout)
        fail(n, test(REPORTER, "timesOut"), new TestTimedOutException(500, TimeUnit.MILLISECONDS));

        // --- the commonest Espresso failure. The real exception cannot be built off a device
        // (it needs a root view), and Status classifies an AssertionError as failed either way;
        // what the verifier reads is the message, which is Espresso's verbatim.
        fail(n, test(REPORTER, "failsOnAMissingView"), new AssertionError(
                "No views in hierarchy found matching: with content description: is"
                        + " \"no view has this description\""));

        // --- metadata, including a step the device records from the main looper
        Description meta = test(REPORTER, "recordsMetadataFromTheMainLooper");
        n.fireTestStarted(meta);
        Qualflare.label("team", "identity");
        Qualflare.tag("smoke", "login");
        Qualflare.link("https://example.test/QF-1", Qualflare.ISSUE, "QF-1");
        Qualflare.priority(Qualflare.HIGH);
        Qualflare.description("metadata written from two threads lands on one case");
        Qualflare.step("a step recorded on the main looper",
                () -> Qualflare.parameter("thread", "main"));
        Qualflare.step("a step recorded on the instrumentation thread",
                () -> Qualflare.maskedParameter("password"));
        n.fireTestFinished(meta);

        // --- attachments: inline bytes, a file, and an image that travels beside the report
        Description attach = test(REPORTER, "attachesBytesAndAnImage");
        n.fireTestStarted(attach);
        Qualflare.attachment("notes.txt", "written by the fixture".getBytes("UTF-8"), "text/plain");
        File onDisk = new File(dir, "fixture-attachment-source.txt");
        try (OutputStream out = new FileOutputStream(onDisk)) {
            out.write("from a file".getBytes("UTF-8"));
        }
        Qualflare.attachment("from-a-file.txt", onDisk, "text/plain");
        Qualflare.attachment("the app, while it is up", PNG, "image/png");
        n.fireTestFinished(attach);

        // --- a failure photographed by QualflareRule while the app was still up
        Description shot = test(REPORTER, "takesAScreenshotWhenItFails");
        n.fireTestStarted(shot);
        Qualflare.attachment("failure screenshot", PNG, "image/png");
        n.fireTestFailure(new Failure(shot,
                new AssertionError("deliberate failure with the app on screen")));
        n.fireTestFinished(shot);

        // --- @BeforeClass threw: a failure with a SUITE description and no tests of its own
        n.fireTestFailure(new Failure(Description.createSuiteDescription(PKG + ".BrokenSetupTest"),
                new IllegalStateException("@BeforeClass threw on purpose")));

        // --- a retry runner re-firing the same description: one case, two attempts
        Description flaky = test(PKG + ".FlakyRetryTest", "flakesOnceThenPasses");
        n.fireTestStarted(flaky);
        n.fireTestFailure(new Failure(flaky, new AssertionError("failing on purpose, attempt 1")));
        n.fireTestFinished(flaky);
        pass(n, flaky);

        n.fireTestRunFinished(new Result());

        onDisk.delete(); // not part of the report; only the source of the File attachment
        assertTrue("the sample report was not written to " + dir.getAbsolutePath(),
                reportCount(dir) == 1);
    }

    private static void pass(RunNotifier n, Description d) {
        n.fireTestStarted(d);
        n.fireTestFinished(d);
    }

    private static void fail(RunNotifier n, Description d, Throwable t) {
        n.fireTestStarted(d);
        n.fireTestFailure(new Failure(d, t));
        n.fireTestFinished(d);
    }

    private static int reportCount(File dir) {
        File[] files = dir.listFiles();
        int n = 0;
        if (files != null) {
            for (File f : files) {
                if (f.getName().startsWith("qualflare-espresso-") && f.getName().endsWith(".json")) {
                    n++;
                }
            }
        }
        return n;
    }

    private static void deleteContents(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            // A stale report from an earlier run would make the verifier check yesterday's shape.
            f.delete();
        }
    }

    /** The FileSink's behaviour without a Context: writes bare filenames into one directory. */
    private static final class DirectorySink extends ReportSink {
        private final File dir;

        DirectorySink(File dir) {
            this.dir = dir;
        }

        @Override
        OutputStream open(String fileName) throws IOException {
            return new FileOutputStream(new File(dir, fileName));
        }

        @Override
        String describe() {
            return null;
        }
    }
}
