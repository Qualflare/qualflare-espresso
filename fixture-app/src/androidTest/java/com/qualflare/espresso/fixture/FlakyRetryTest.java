package com.qualflare.espresso.fixture;

import static org.junit.Assert.assertTrue;

import com.qualflare.espresso.Qualflare;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fails once, then passes — the shape that has to arrive as ONE case with two attempts and
 * {@code isFlaky}, not as two cases or as a plain pass that hides the failure.
 *
 * <p>The counter is static because the retry happens inside one process. Under Android Test
 * Orchestrator each test method gets its own process, but a retry of a method still happens within
 * that method's process, so this holds on both legs.
 */
@RunWith(RetryRunner.class)
public class FlakyRetryTest {

    private static final AtomicInteger attempts = new AtomicInteger();

    @Test
    public void flakesOnceThenPasses() {
        int attempt = attempts.incrementAndGet();
        Qualflare.label("attempt", String.valueOf(attempt));
        assertTrue("failing on purpose, attempt " + attempt, attempt > 1);
    }
}
