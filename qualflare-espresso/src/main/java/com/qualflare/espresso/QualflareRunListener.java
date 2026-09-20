package com.qualflare.espresso;

import android.util.Log;

import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

/**
 * The reporter's entry point. Registered by the consumer with one line:
 *
 * <pre>
 * testInstrumentationRunnerArguments["listener"] = "com.qualflare.espresso.QualflareRunListener"
 * </pre>
 *
 * <p><b>Currently a spike stub.</b> It records nothing and writes nothing; every callback
 * just logs. It exists at this stage to settle three things that decide the real
 * implementation's shape, and that can only be answered on a device:
 *
 * <ol>
 *   <li>whether the {@code listener} instrumentation argument invokes us at all,</li>
 *   <li>where {@code testFailure} falls relative to {@code @After} teardown — which decides
 *       whether screenshot-on-failure can live here or has to be a TestRule,</li>
 *   <li>whether R8 keeps a class that is named only by a string in a build file.</li>
 * </ol>
 *
 * <p>AndroidJUnitRunner constructs this reflectively and needs a public no-arg
 * constructor; {@code consumer-rules.pro} keeps it for minified consumers.
 */
public final class QualflareRunListener extends RunListener {

    /** Log tag, and the marker the spike greps for. */
    static final String TAG = "QualflareEspresso";

    /** Required by AndroidJUnitRunner's reflective instantiation. */
    public QualflareRunListener() {}

    @Override
    public void testRunStarted(Description description) {
        Log.i(TAG, "SPIKE listener testRunStarted, tests=" + description.testCount()
                + ", thread=" + Thread.currentThread().getName());
    }

    @Override
    public void testStarted(Description description) {
        Log.i(TAG, "SPIKE listener testStarted " + description.getClassName()
                + "#" + description.getMethodName());
    }

    @Override
    public void testFailure(Failure failure) {
        // The ordering that matters: if this line appears AFTER the fixture's "@After
        // finished" line, an automatic screenshot taken here would photograph whatever
        // replaced the closed activity.
        Log.i(TAG, "SPIKE listener testFailure "
                + failure.getDescription().getMethodName()
                + " isSuite=" + failure.getDescription().isSuite()
                + " exception=" + (failure.getException() == null
                        ? "null" : failure.getException().getClass().getName()));
    }

    @Override
    public void testAssumptionFailure(Failure failure) {
        Log.i(TAG, "SPIKE listener testAssumptionFailure "
                + failure.getDescription().getMethodName());
    }

    @Override
    public void testIgnored(Description description) {
        Log.i(TAG, "SPIKE listener testIgnored " + description.getMethodName());
    }

    @Override
    public void testFinished(Description description) {
        Log.i(TAG, "SPIKE listener testFinished " + description.getMethodName());
    }

    @Override
    public void testRunFinished(Result result) {
        Log.i(TAG, "SPIKE listener testRunFinished run=" + result.getRunCount()
                + " failures=" + result.getFailureCount()
                + " ignored=" + result.getIgnoreCount());
    }
}
