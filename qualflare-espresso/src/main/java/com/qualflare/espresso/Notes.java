package com.qualflare.espresso;

import android.util.Log;

/**
 * What the reporter says, and where it says it.
 *
 * <p>Both channels, always, because neither one alone is enough. {@code System.out} from an
 * instrumented app reaches Gradle's console on some paths and vanishes on others -- under Android
 * Test Orchestrator each test is a separate process whose stdout Gradle does not relay at all.
 * Logcat always has it, which is where anyone debugging a device run looks anyway, and where
 * {@code adb logcat -s QualflareEspresso} finds it after the fact.
 *
 * <p>This was not an abstract concern: an API 24 leg produced no report and the reporter had said
 * nothing about why, because everything it had to say went only to a stdout nobody could read.
 */
final class Notes {

    /** Greppable and stable: `adb logcat -s QualflareEspresso` is documented against it. */
    static final String TAG = "QualflareEspresso";

    private static final String PREFIX = "[qualflare-espresso] ";

    private Notes() {}

    static void say(String message) {
        System.out.println(PREFIX + message);
        try {
            Log.i(TAG, message);
        } catch (Throwable t) {
            // Off a device android.util.Log is a stub that can throw. Never fatal: this is the
            // class that REPORTS problems and must not become one.
        }
    }

    static void warn(String message) {
        System.err.println(PREFIX + message);
        try {
            Log.w(TAG, message);
        } catch (Throwable t) {
            // As above.
        }
    }
}
