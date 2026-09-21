package com.qualflare.espresso;

import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

/**
 * Optional. Attaches a screenshot to a test that fails, taken while the app is still on screen.
 *
 * <pre>
 * &#64;Rule public final RuleChain rules = RuleChain
 *         .outerRule(ActivityScenarioRule(LoginActivity.class))
 *         .around(new QualflareRule());
 * </pre>
 *
 * <p><b>The ordering is the whole point, and it is easy to get backwards.</b> JUnit runs the
 * outermost rule first and closes it last, so the rule that wants to see the app has to be
 * <b>inside</b> the rule that launches it — {@code outerRule(activity).around(qualflare)}. Reversed,
 * {@code ActivityScenarioRule} has already closed the activity by the time this rule runs, and every
 * screenshot is a picture of the launcher.
 *
 * <p><b>Why this is a rule at all and not automatic.</b> The obvious place for capture-on-failure is
 * {@link QualflareRunListener#testFailure}, and that place is too late for the same reason:
 * {@code RunAfters} runs the {@code @After} methods and <i>then</i> rethrows, so by the time JUnit
 * notifies a listener, every rule has already unwound and the activity is gone. A listener cannot
 * capture the app; only a rule inside the activity's own rule can. That is also why an {@code @After}
 * which closes the activity itself will still defeat this — {@code @After} runs inside all rules.
 *
 * <p>Nothing here can fail a test. No screenshot, a secure window, no case in flight: the rule
 * records what it can and returns.
 */
public final class QualflareRule extends TestWatcher {

    /** Injected in tests; on a device it is always {@link Screenshots#capturePng()}. */
    interface Capture {
        byte[] png();
    }

    private final boolean screenshotOnFailure;
    private final Capture capture;

    /** Captures a screenshot on failure. */
    public QualflareRule() {
        this(true, Screenshots::capturePng);
    }

    /**
     * Keeps the rule's other behaviour without photographing the screen — for a suite whose screens
     * show real customer data, or one where the capture itself is too slow to afford.
     */
    public static QualflareRule withoutScreenshotOnFailure() {
        return new QualflareRule(false, Screenshots::capturePng);
    }

    QualflareRule(boolean screenshotOnFailure, Capture capture) {
        this.screenshotOnFailure = screenshotOnFailure;
        this.capture = capture;
    }

    @Override
    protected void failed(Throwable e, Description description) {
        if (!screenshotOnFailure || !Qualflare.hasCase()) {
            // No case to attach to means reporting is off, or this rule is being used outside a
            // run the listener is watching. Capturing the screen would cost a full-screen bitmap
            // and produce a warning about a stray call, for nothing.
            return;
        }
        byte[] png = capture.png();
        if (png == null) {
            return;
        }
        Qualflare.attachment("failure screenshot", png, "image/png");
    }
}
