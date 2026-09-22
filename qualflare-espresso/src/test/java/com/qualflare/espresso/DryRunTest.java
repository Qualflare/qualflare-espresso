package com.qualflare.espresso;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.RunWith;
import org.junit.runner.notification.RunNotifier;
import org.robolectric.RobolectricTestRunner;

/**
 * The enumeration pass, and why a report must never come out of it.
 *
 * <p>Android Test Orchestrator lists the whole suite before it runs any of it, and
 * {@code -e log true} does the same for a hand-run dry run. In that pass AndroidJUnitRunner fires
 * testStarted and testFinished for every test <b>without executing any of them</b>. A listener
 * that takes those at face value writes a report in which every test passed, with no steps, no
 * attachments and no failures.
 *
 * <p>That is worse than writing nothing: it is a complete, plausible, entirely green run. And
 * under the orchestrator it is written first, so whoever merges the run's reports reads the
 * enumeration instead of the tests. This is not hypothetical -- it is what the API 29 and 34
 * orchestrator legs produced before this guard existed: twelve cases, all passed, all empty.
 */
@RunWith(RobolectricTestRunner.class)
public class DryRunTest {

    @After
    public void clearArguments() {
        InstrumentationRegistry.registerInstance(
                InstrumentationRegistry.getInstrumentation(), new Bundle());
    }

    private static void withArgument(String name, String value) {
        Bundle args = new Bundle();
        args.putString(name, value);
        InstrumentationRegistry.registerInstance(InstrumentationRegistry.getInstrumentation(), args);
    }

    /** Runs a suite that would otherwise produce two passing cases and a written report. */
    private static FakeSink runASuite() {
        QualflareRunListener listener = new QualflareRunListener();
        RunNotifier n = new RunNotifier();
        n.addListener(listener);
        listener.testRunStarted(Description.createSuiteDescription("suite"));
        FakeSink sink = new FakeSink();
        listener.sink = sink;

        for (String method : new String[] {"first", "second"}) {
            Description d = Description.createTestDescription("com.example.LoginTest", method);
            n.fireTestStarted(d);
            n.fireTestFinished(d);
        }
        n.fireTestRunFinished(new Result());
        return sink;
    }

    @Test
    public void the_orchestrators_enumeration_pass_writes_no_report() {
        withArgument("listTestsForOrchestrator", "true");
        assertTrue(Config.isDryRun());
        assertTrue("a green report from a pass where nothing ran is the worst kind of wrong",
                runASuite().written.isEmpty());
    }

    @Test
    public void a_hand_run_dry_run_writes_no_report() {
        withArgument("log", "true");
        assertTrue(Config.isDryRun());
        assertTrue(runASuite().written.isEmpty());
    }

    @Test
    public void an_ordinary_run_still_writes_one() {
        assertFalse(Config.isDryRun());
        assertEquals("with no dry-run argument the report is written as usual",
                1, runASuite().written.size());
    }

    /**
     * Only the exact spellings androidx.test uses for "yes". The argument is set by the runner
     * itself, not by a person, but reading {@code log=false} as a dry run would silence the
     * reporter on every ordinary run that happens to pass the argument explicitly.
     */
    @Test
    public void only_a_true_value_counts_as_a_dry_run() {
        for (String off : new String[] {"false", "0", "", "  ", "no"}) {
            withArgument("log", off);
            assertFalse("log=" + off + " must not silence the reporter", Config.isDryRun());
        }
        for (String on : new String[] {"true", "TRUE", " 1 "}) {
            withArgument("log", on);
            assertTrue("log=" + on + " is a dry run", Config.isDryRun());
        }
    }
}
