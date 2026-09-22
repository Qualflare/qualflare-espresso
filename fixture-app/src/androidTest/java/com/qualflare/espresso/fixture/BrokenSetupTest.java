package com.qualflare.espresso.fixture;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * A class whose {@code @BeforeClass} throws. Both tests below emit <b>nothing at all</b> — no
 * start, no finish — and the only notification JUnit sends is one failure carrying a SUITE
 * description.
 *
 * <p>Without the listener's synthetic case for that, this class would be invisible: the run would
 * read green with two tests quietly missing, which is the worst way for a report to be wrong. The
 * verifier checks a case named for this class is present and reported as an error.
 */
@RunWith(AndroidJUnit4.class)
public class BrokenSetupTest {

    @BeforeClass
    public static void explode() {
        throw new IllegalStateException("@BeforeClass threw on purpose");
    }

    @Test
    public void neverRuns() {
        throw new AssertionError("unreachable: @BeforeClass already failed");
    }

    @Test
    public void alsoNeverRuns() {
        throw new AssertionError("unreachable: @BeforeClass already failed");
    }
}
