package com.qualflare.espresso;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Option precedence, and the thing the siblings never had to worry about: resolving options must
 * not throw when there is no instrumentation.
 */
@RunWith(RobolectricTestRunner.class)
public class ConfigTest {

    @After
    public void clearProperties() {
        System.clearProperty("qualflare.environment");
        System.clearProperty("qualflare.enabled");
        System.clearProperty("qualflare.platform");
        System.clearProperty("qualflare.branch");
    }

    /**
     * The regression this class was rewritten for. InstrumentationRegistry.getArguments() throws
     * IllegalStateException with nothing registered, which is every unit test; a static
     * initialiser touching it would make the reporter unloadable in its own test suite.
     */
    @Test
    public void resolving_options_without_instrumentation_does_not_throw() {
        assertEquals(Config.DEFAULT_ENVIRONMENT, Config.environment());
        assertEquals(Config.DEFAULT_LANGUAGE, Config.language());
        assertEquals(Config.DEFAULT_OUTPUT_DIR, Config.outputDir());
        assertNull(Config.branch());
        assertTrue(Config.enabled());
    }

    @Test
    public void the_platform_default_is_android_not_api() {
        assertEquals("an Espresso suite runs on a device and we know it", "android", Config.platform());
    }

    @Test
    public void a_system_property_beats_the_default() {
        System.setProperty("qualflare.environment", "staging");
        assertEquals("staging", Config.environment());
    }

    @Test
    public void an_empty_value_falls_through_rather_than_winning() {
        System.setProperty("qualflare.environment", "   ");
        assertEquals("a declared-but-unset property must not override anything",
                Config.DEFAULT_ENVIRONMENT, Config.environment());
    }

    /**
     * Note how the arguments have to be supplied: {@code getArguments()} returns a COPY of the
     * bundle, documented as such in androidx.test, so mutating what it hands back changes nothing.
     * The first version of this test did exactly that and failed with
     * {@code expected:<from-[argument]> but was:<from-[property]>} — the test was wrong, not the
     * precedence. Registering an instance is the only way to set them.
     */
    @Test
    public void an_instrumentation_argument_beats_a_system_property() {
        Bundle args = new Bundle();
        args.putString("qualflare.environment", "from-argument");
        InstrumentationRegistry.registerInstance(InstrumentationRegistry.getInstrumentation(), args);
        System.setProperty("qualflare.environment", "from-property");
        try {
            assertEquals("the instrumentation argument is the tier a CI run can actually set",
                    "from-argument", Config.environment());
        } finally {
            InstrumentationRegistry.registerInstance(
                    InstrumentationRegistry.getInstrumentation(), new Bundle());
        }
    }

    @Test
    public void disabled_spellings_are_exactly_the_ones_documented() {
        for (String off : new String[] {"0", "false", "no", "off", "FALSE", "Off", " no "}) {
            assertTrue(off + " should read as off", Config.isDisabledSpelling(off));
        }
        for (String on : new String[] {"1", "true", "yes", "on", "TRU", "", "maybe", null}) {
            assertFalse(on + " should not read as off", Config.isDisabledSpelling(on));
        }
    }

    @Test
    public void enabled_is_false_only_for_a_recognised_falsehood() {
        System.setProperty("qualflare.enabled", "false");
        assertFalse(Config.enabled());

        // A typo does NOT disable the reporter here. The listener warns about it instead: losing a
        // whole run's report to a mistyped flag is worse than reporting when asked not to.
        System.setProperty("qualflare.enabled", "TRU");
        assertTrue(Config.enabled());
    }
}
