package com.qualflare.espresso;

import android.os.Bundle;

import java.util.Locale;

/**
 * Options, in precedence order: <b>instrumentation argument → system property → environment →
 * default</b>.
 *
 * <p>The JVM siblings have only the last three, and on Android the ones they rely on barely work.
 * A system property cannot be set per run the way {@code systemPropertyVariables} does under
 * Surefire — the test JVM is the app process, started by the runner — and an instrumented app
 * inherits no useful environment. The channel that *is* settable is
 * {@code testInstrumentationRunnerArguments}, which arrive as a {@link Bundle} through
 * {@code InstrumentationRegistry.getArguments()}. The other two tiers are kept because they cost
 * nothing and preserve the family's mental model, and because they are how a Robolectric test
 * sets an option.
 *
 * <p><b>Everything here resolves lazily.</b> {@code InstrumentationRegistry.getArguments()} throws
 * {@link IllegalStateException} when no instrumentation is registered — which is every JVM and
 * Robolectric test — and on a bare JVM the class may be absent entirely
 * ({@link NoClassDefFoundError}). A static initialiser touching it would make the reporter
 * unloadable in exactly the environment its own unit tests run in.
 */
final class Config {

    static final String DEFAULT_OUTPUT_DIR = "qualflare-results";
    static final String DEFAULT_ENVIRONMENT = "development";
    static final String DEFAULT_LANGUAGE = "en-US";
    /** Not the siblings' "api": an Espresso suite runs on a device, and we know it. */
    static final String DEFAULT_PLATFORM = "android";

    private Config() {}

    /**
     * Where reports and copied images are written when the test-storage route is unavailable.
     * Only a directory NAME by default: an absolute path is resolved against the app's files
     * directory by the sink, because the process working directory on Android is {@code /}, which
     * is not writable.
     */
    static String outputDir() {
        return resolve("qualflare.outputDir", "QUALFLARE_OUTPUT_DIR", DEFAULT_OUTPUT_DIR);
    }

    static String environment() {
        return resolve("qualflare.environment", "QUALFLARE_ENVIRONMENT", DEFAULT_ENVIRONMENT);
    }

    static String language() {
        return resolve("qualflare.language", "QUALFLARE_LANGUAGE", DEFAULT_LANGUAGE);
    }

    static String platform() {
        return resolve("qualflare.platform", "QUALFLARE_PLATFORM", DEFAULT_PLATFORM);
    }

    /** Null, not empty: the wire distinguishes an absent branch from a blank one. */
    static String branch() {
        return nullable("qualflare.branch", "QUALFLARE_BRANCH");
    }

    static String commit() {
        return nullable("qualflare.commit", "QUALFLARE_COMMIT");
    }

    /**
     * Anything other than a recognised falsehood is true, and an unrecognised value is reported
     * by the caller rather than swallowed — a typo like {@code TRU} silently losing a run's
     * report is the failure this shape exists to avoid.
     */
    static boolean enabled() {
        String raw = resolve("qualflare.enabled", "QUALFLARE_ENABLED", "true");
        return !isDisabledSpelling(raw);
    }

    /** The exact spellings meant as "off". Public so the listener can warn about the others. */
    static boolean isDisabledSpelling(String raw) {
        if (raw == null) {
            return false;
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "0":
            case "false":
            case "no":
            case "off":
                return true;
            default:
                return false;
        }
    }

    /**
     * Gradle's own argument, not one of ours: it names the directory Gradle will pull off the
     * device after the run. Its presence is what tells the sink that test-storage output will
     * actually be collected -- measured absent on API 24 and present on 29 and 34
     * (docs/SPIKE-2026-09-20.md).
     */
    static String additionalTestOutputDir() {
        String v = argument("additionalTestOutputDir");
        return isSet(v) ? v : null;
    }

    /**
     * True when the runner is <b>listing</b> tests rather than running them.
     *
     * <p>Android Test Orchestrator asks the runner to enumerate the suite before executing any of
     * it, and {@code -e log true} does the same for anyone running a dry run by hand. In that pass
     * AndroidJUnitRunner fires the full testStarted/testFinished sequence for every test WITHOUT
     * executing a single one, so a listener that believes what it is told writes a report in which
     * every test passed, carrying no steps, no attachments and no failures.
     *
     * <p>That report is not merely useless, it is dangerous: it is a complete, valid-looking green
     * run, and under the orchestrator it is written FIRST, so whoever merges reports sees the
     * enumeration rather than the tests. Measured on API 29 and 34 orchestrator legs, where all
     * twelve cases came back passed with empty metadata.
     *
     * <p>Both argument names are androidx.test's own: {@code RunnerArgs.ARGUMENT_LOG_ONLY} and
     * {@code ARGUMENT_LIST_TESTS_FOR_ORCHESTRATOR}, read out of runner 1.7.0.
     */
    static boolean isDryRun() {
        return isTrue(argument("log")) || isTrue(argument("listTestsForOrchestrator"));
    }

    private static boolean isTrue(String raw) {
        if (!isSet(raw)) {
            return false;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return v.equals("true") || v.equals("1");
    }

    private static String resolve(String property, String env, String fallback) {
        String v = argument(property);
        if (isSet(v)) {
            return v;
        }
        v = System.getProperty(property);
        if (isSet(v)) {
            return v;
        }
        v = getenv(env);
        return isSet(v) ? v : fallback;
    }

    private static String nullable(String property, String env) {
        String v = argument(property);
        if (isSet(v)) {
            return v;
        }
        v = System.getProperty(property);
        if (isSet(v)) {
            return v;
        }
        v = getenv(env);
        return isSet(v) ? v : null;
    }

    /**
     * One instrumentation argument, or null when there is no instrumentation — which is the
     * normal case in a unit test, not an error.
     *
     * <p>Reflection rather than a direct call so the library loads on a bare JVM with no
     * androidx.test on the classpath at all. The alternative is a hard reference that turns every
     * plain unit test into a NoClassDefFoundError.
     */
    private static String argument(String name) {
        try {
            Class<?> registry = Class.forName("androidx.test.platform.app.InstrumentationRegistry");
            Object args = registry.getMethod("getArguments").invoke(null);
            if (args instanceof Bundle) {
                return ((Bundle) args).getString(name);
            }
            return null;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return null; // no androidx.test here: a plain JVM test
        } catch (Throwable t) {
            // IllegalStateException wrapped in InvocationTargetException: instrumentation exists
            // as a class but was never registered. Also covers a SecurityException from an odd
            // classloader. Never fatal: the next tier answers.
            return null;
        }
    }

    /** Wrapped because a SecurityManager can refuse, and a refusal is not an answer. */
    private static String getenv(String name) {
        try {
            return System.getenv(name);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * An empty value falls through rather than winning. A declared-but-unset property is the
     * common case in build files, and treating "" as a choice would override a real setting with
     * nothing.
     */
    private static boolean isSet(String v) {
        return v != null && !v.trim().isEmpty();
    }
}
