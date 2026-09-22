package com.qualflare.espresso.fixture;

import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;

/**
 * Re-runs a failed test, at the RUNNER level rather than inside a rule.
 *
 * <p>The distinction is the whole reason this class exists. A retry {@code TestRule} loops inside
 * one statement, so JUnit — and therefore the reporter — sees a single start/finish and a single
 * attempt: a test that failed twice and passed on the third try is indistinguishable from one that
 * passed first time. A runner that calls {@code runChild} again fires start, failure and finish
 * once per attempt under the SAME description, which is what lets the reporter accumulate them
 * into one case with an attempts array and mark it flaky.
 *
 * <p>This is a fixture, not part of the library: it exists so the emulator leg can prove the
 * accumulation is real. Third-party retry runners behave the same way, which is why the reporter
 * keys cases on the description rather than on anything per-attempt.
 */
public class RetryRunner extends BlockJUnit4ClassRunner {

    private static final int MAX_ATTEMPTS = 3;

    public RetryRunner(Class<?> testClass) throws InitializationError {
        super(testClass);
    }

    @Override
    protected void runChild(FrameworkMethod method, RunNotifier notifier) {
        Description description = describeChild(method);
        if (isIgnored(method)) {
            notifier.fireTestIgnored(description);
            return;
        }
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            FailureWatcher watcher = new FailureWatcher(description);
            notifier.addListener(watcher);
            try {
                super.runChild(method, notifier);
            } finally {
                notifier.removeListener(watcher);
            }
            if (!watcher.failed) {
                return;
            }
        }
    }

    /** Notices whether THIS test failed, without swallowing the notification anyone else sees. */
    private static final class FailureWatcher extends RunListener {
        private final Description description;
        boolean failed;

        FailureWatcher(Description description) {
            this.description = description;
        }

        @Override
        public void testFailure(Failure failure) {
            if (description.equals(failure.getDescription())) {
                failed = true;
            }
        }
    }
}
