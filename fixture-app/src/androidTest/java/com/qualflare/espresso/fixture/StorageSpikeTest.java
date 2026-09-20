package com.qualflare.espresso.fixture;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.os.Build;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.platform.io.PlatformTestStorage;
import androidx.test.platform.io.PlatformTestStorageRegistry;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;

/**
 * Task 0 — the spike. This is not the reporter; it answers the three questions that
 * decide the reporter's shape, before any of it is written:
 *
 * <ol>
 *   <li><b>Does a file written through PlatformTestStorage actually reach the host?</b>
 *       The whole install story ("one Gradle line, no adb") rests on AGP pulling
 *       additionalTestOutputDir into build/outputs/…_additional_output. That is
 *       documented for Gradle Managed Devices and relied on by Jetpack Benchmark for
 *       connected devices, but it has to be true on the API levels we claim — 24 is
 *       the oldest and the likeliest to disappoint.</li>
 *   <li><b>Does R8 keep a listener that is only named in a string?</b> Answered by
 *       building this module's androidTest APK minified, not by this test.</li>
 *   <li><b>When does a failure arrive relative to teardown?</b> {@link #failsAfterTeardown}
 *       records the ordering that decides whether screenshot-on-failure can live in a
 *       RunListener at all.</li>
 * </ol>
 *
 * Everything it learns is printed with the SPIKE marker so one CI log read answers all
 * three.
 */
@RunWith(AndroidJUnit4.class)
public class StorageSpikeTest {

    private static final String MARKER = "SPIKE";

    @Rule public TestName name = new TestName();

    private ActivityScenario<MainActivity> scenario;

    @Test
    public void writesAFileThroughPlatformTestStorage() throws Exception {
        PlatformTestStorage storage = PlatformTestStorageRegistry.getInstance();
        assertNotNull("no PlatformTestStorage registered", storage);

        say("storage implementation = " + storage.getClass().getName());
        say("api = " + Build.VERSION.SDK_INT + " model = " + Build.MODEL);
        say("additionalTestOutputDir arg = "
                + InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"));

        String fileName = "qualflare-spike-api" + Build.VERSION.SDK_INT + ".json";
        try (OutputStream out = storage.openOutputFile(fileName);
                Writer writer = new OutputStreamWriter(out, "UTF-8")) {
            writer.write("{\"spike\":true,\"api\":" + Build.VERSION.SDK_INT + "}");
        }
        say("wrote " + fileName + " through storage");

        // A second file, to prove the report and its screenshots can share one sink —
        // if they cannot, every localImagePath in a report would be a dead link.
        try (OutputStream out = storage.openOutputFile("qf-attach-spike.txt");
                Writer writer = new OutputStreamWriter(out, "UTF-8")) {
            writer.write("second stream from the same sink");
        }
        say("wrote a second file from the same sink");
    }

    /**
     * The ordering question. An activity is open, {@code @After} closes it, and the test
     * body fails. If a RunListener's testFailure callback arrived before teardown, an
     * automatic screenshot there would capture the app; if it arrives after, it captures
     * whatever is on screen once the activity is gone.
     *
     * <p>The assertion failure is the point of the test, so the CI leg expects exactly one
     * failure here. Read the interleaving of the SPIKE lines in the log to settle it.
     */
    @Test
    public void failsAfterTeardown() {
        scenario = ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(new ActivityScenario.ActivityAction<MainActivity>() {
            @Override
            public void perform(MainActivity activity) {
                // Runs on the MAIN LOOPER, not the test thread. This is exactly why the
                // reporter's current-case cannot be a ThreadLocal.
                say("onActivity thread = " + Thread.currentThread().getName()
                        + " (test thread was recorded at start)");
            }
        });
        say("test thread = " + Thread.currentThread().getName());
        say("about to fail with the activity still open");
        fail("deliberate failure, after which @After closes the activity");
    }

    @After
    public void closeActivity() {
        say("@After running" + (scenario != null ? " and closing the activity" : ""));
        if (scenario != null) {
            scenario.close();
            scenario = null;
        }
        say("@After finished; a listener's testFailure has NOT been called yet");
    }

    private void say(String message) {
        // System.out so it lands in the instrumentation output the CI log captures.
        System.out.println(MARKER + " [" + name.getMethodName() + "] " + message);
    }
}
