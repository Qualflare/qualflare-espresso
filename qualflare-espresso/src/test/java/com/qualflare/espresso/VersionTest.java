package com.qualflare.espresso;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/**
 * The version in every report has to be the real one.
 *
 * <p>The JVM siblings read it from the jar manifest, which returns null on Android — dex has no
 * per-package manifest — so it is generated into a source file by the build instead. This test
 * is what makes that wiring load-bearing: without something referencing {@code Version.VALUE},
 * the generator could silently drop out of the task graph and nothing would notice until a
 * release claimed {@code 0.0.0-dev}, which junit5 actually shipped once.
 */
public class VersionTest {

    @Test
    public void the_generated_version_matches_the_build() {
        String expected = System.getProperty("qualflare.expectedVersion");
        assertNotNull("the build must pass qualflare.expectedVersion to the unit tests", expected);
        assertFalse("the build's own version must not be empty", expected.trim().isEmpty());
        assertEquals("the generated constant is stale: run generateVersion", expected, Version.VALUE);
    }

    @Test
    public void the_version_is_never_the_placeholder_the_siblings_can_ship() {
        assertFalse("0.0.0-dev means the manifest fallback leaked in",
                "0.0.0-dev".equals(Version.VALUE));
    }
}
