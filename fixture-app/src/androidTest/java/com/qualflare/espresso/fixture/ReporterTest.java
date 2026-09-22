package com.qualflare.espresso.fixture;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.qualflare.espresso.Qualflare;
import com.qualflare.espresso.QualflareRule;

import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * The suite the emulator legs run, and the reason they exist: every claim the reporter makes about
 * a real device is a test here, checked by {@code tools/verify.py} against the report that reaches
 * the host.
 *
 * <p>Several of these tests <b>fail on purpose</b>. {@code connectedDebugAndroidTest} is therefore
 * expected to exit non-zero, and a green Gradle run would mean the fixture stopped testing what it
 * is for. What the leg checks is the report, not the exit code.
 *
 * <p>The rule ordering below is the one thing here worth copying into a real suite:
 * {@code outerRule(activity).around(qualflare)} puts the screenshot rule INSIDE the activity's
 * lifetime. Reversed, every failure screenshot is a picture of the launcher.
 */
@RunWith(AndroidJUnit4.class)
public class ReporterTest {

    private final ActivityScenarioRule<MainActivity> activity =
            new ActivityScenarioRule<>(MainActivity.class);

    @Rule
    public final RuleChain rules = RuleChain.outerRule(activity).around(new QualflareRule());

    @Test
    public void passes() {
        onView(withContentDescription(MainActivity.GREETING)).check(matches(isDisplayed()));
    }

    @Test
    @Ignore("expected in the report as skipped, which is the point")
    public void isIgnored() {
        fail("never runs");
    }

    @Test
    public void skipsOnAnAssumption() {
        assumeTrue("an assumption that does not hold", false);
        fail("never reached");
    }

    /** TestTimedOutException must read as a timeout, not as a generic error. */
    @Test(timeout = 500L)
    public void timesOut() throws InterruptedException {
        Thread.sleep(10_000L);
    }

    /**
     * The commonest Espresso failure there is. NoMatchingViewException must land as
     * <b>failed</b>: it is a view assertion that did not hold, and reporting it as an error would
     * mislabel most of the failures a real Espresso suite produces.
     */
    @Test
    public void failsOnAMissingView() {
        onView(withContentDescription("no view has this description")).check(matches(isDisplayed()));
    }

    /**
     * Metadata written from inside {@code onActivity}, which runs on the MAIN LOOPER while the test
     * body runs on the instrumentation thread. A ThreadLocal current-case would drop exactly this,
     * so the verifier checks the step arrived.
     */
    @Test
    public void recordsMetadataFromTheMainLooper() {
        Qualflare.label("team", "identity");
        Qualflare.tag("smoke", "login");
        Qualflare.link("https://example.test/QF-1", Qualflare.ISSUE, "QF-1");
        Qualflare.priority(Qualflare.HIGH);
        Qualflare.description("metadata written from two threads lands on one case");

        activity.getScenario().onActivity(new ActivityScenario.ActivityAction<MainActivity>() {
            @Override
            public void perform(MainActivity a) {
                Qualflare.step("a step recorded on the main looper", new Runnable() {
                    @Override
                    public void run() {
                        Qualflare.parameter("thread", Thread.currentThread().getName());
                    }
                });
            }
        });

        Qualflare.step("a step recorded on the instrumentation thread", new Runnable() {
            @Override
            public void run() {
                Qualflare.maskedParameter("password");
            }
        });
    }

    /** One attachment of each kind: inlined bytes, and an image that travels beside the report. */
    @Test
    public void attachesBytesAndAnImage() throws IOException {
        Qualflare.attachment("notes.txt", "written by the fixture".getBytes("UTF-8"), "text/plain");

        // A file on the device, so the File overload is exercised on a real filesystem.
        File tmp = new File(
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                        .getTargetContext().getCacheDir(),
                "fixture-attachment.txt");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write("from a file".getBytes("UTF-8"));
        }
        Qualflare.attachment("from-a-file.txt", tmp, "text/plain");

        Qualflare.screenshot("the app, while it is up", captureScreen());
        assertEquals("the fixture's own sanity check", 11, "from a file".length());
    }

    /**
     * Fails with the activity still on screen, so {@link QualflareRule} has something worth
     * photographing. The verifier checks the attachment exists AND that its file is beside the
     * report — a localImagePath that resolves to nothing is the failure mode this catches.
     */
    @Test
    public void takesAScreenshotWhenItFails() {
        onView(withContentDescription(MainActivity.GREETING)).check(matches(isDisplayed()));
        fail("deliberate failure with the app on screen");
    }

    private static android.graphics.Bitmap captureScreen() {
        return androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().takeScreenshot();
    }
}
