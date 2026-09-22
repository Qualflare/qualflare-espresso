package com.qualflare.espresso;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Where the report and its images are written, and how they leave the device.
 *
 * <p>Two routes, and the spike (docs/SPIKE-2026-09-20.md) measured exactly when each applies:
 *
 * <ul>
 *   <li><b>Test storage</b> — {@code PlatformTestStorage.openOutputFile}. Gradle passes
 *       {@code additionalTestOutputDir} from <b>API 29</b> and pulls the directory to
 *       {@code build/outputs/connected_android_test_additional_output/…} afterwards, so the report
 *       reaches the host with no adb command at all.
 *   <li><b>The app's files directory</b> — on API 24–28 Gradle passes no such argument, so storage
 *       writes somewhere nothing collects. There the sink writes to
 *       {@code getExternalFilesDir(null)/<outputDir>} and prints the exact {@code adb pull} to run.
 *       This is the <b>normal</b> path on those API levels, not an exotic fallback, and is tested
 *       as such.
 * </ul>
 *
 * <p><b>One sink for everything.</b> The report and every copied image must come from the same
 * route: {@code qf collect} resolves an attachment's {@code localImagePath} relative to the report
 * it found, so a JSON in test storage with its PNGs in the app's files directory would leave every
 * image a dead link. The spike confirmed a second stream from one storage instance works.
 *
 * <p>Everything is reflective. {@code androidx.test.platform.io} must not be a hard dependency: the
 * library is {@code compileOnly} against androidx.test so a consumer's own version wins, and on a
 * bare JVM (its own unit tests) those classes are absent entirely. A direct reference would turn
 * every unit test into a {@link NoClassDefFoundError}.
 */
abstract class ReportSink {

    /** Opens a file for writing. The name is a bare filename, never a path. */
    abstract OutputStream open(String fileName) throws IOException;

    /** A human-facing note about where output went, printed once per run, or null when silent. */
    abstract String describe();

    /**
     * Test storage when it is usable, the app's files directory otherwise.
     *
     * @param context may be null; without it only the storage route is possible
     */
    static ReportSink resolve(Context context) {
        Object storage = platformStorage();
        if (storage != null && storageIsCollected()) {
            Notes.say("writing through androidx.test storage; Gradle collects it into"
                    + " build/outputs/connected_android_test_additional_output with no adb"
                    + " command.");
            return new StorageSink(storage, true);
        }
        // Every branch below says which route it took and why. An API 24 leg once produced no
        // report at all and left nothing to explain it, because the decision made here was
        // invisible: the sink knows exactly what went wrong and used to keep it to itself.
        if (context == null) {
            Notes.warn("no app context: InstrumentationRegistry gave no target context, so the"
                    + " app's files directory is not an option.");
        } else {
            File parent = context.getExternalFilesDir(null);
            if (parent == null) {
                Notes.warn("getExternalFilesDir(null) returned null -- external storage is"
                        + " unavailable on this device, so there is nowhere to fall back to.");
            } else {
                File dir = new File(parent, Config.outputDir());
                if (dir.exists() || dir.mkdirs()) {
                    Notes.say("no additionalTestOutputDir on this device (expected below API 29),"
                            + " so the report goes to " + dir.getAbsolutePath());
                    return new FileSink(dir, context.getPackageName());
                }
                Notes.warn("could not create " + dir.getAbsolutePath());
            }
        }
        // Storage exists but nothing will collect it, and the file route was not available.
        // Writing to storage anyway beats writing nowhere: the files are on the device, and now
        // the note says so rather than leaving a silent dead end.
        if (storage != null) {
            Notes.warn("falling back to androidx.test storage, which nothing on this device will"
                    + " collect. The files are on the device but no adb pull is printed for them,"
                    + " because their location is the storage implementation's to choose.");
            return new StorageSink(storage, false);
        }
        Notes.warn("no route to write a report: no test storage and no app context.");
        return null;
    }

    /** The registered PlatformTestStorage, or null off-device. */
    private static Object platformStorage() {
        try {
            Class<?> registry =
                    Class.forName("androidx.test.platform.io.PlatformTestStorageRegistry");
            return registry.getMethod("getInstance").invoke(null);
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return null;
        } catch (Throwable t) {
            // LinkageError included on purpose: androidx.test before 1.4 had this class under a
            // different name, and a consumer pinning an old version must not crash the reporter.
            return null;
        }
    }

    /**
     * Whether anything will collect what storage writes.
     *
     * <p>The presence of {@code additionalTestOutputDir} is the signal, and it is the honest one:
     * Gradle sets it when it intends to pull the directory afterwards. Measured absent on API 24
     * and present on API 29 and 34, plain and orchestrator alike.
     */
    private static boolean storageIsCollected() {
        return Config.additionalTestOutputDir() != null;
    }

    /** Writes through androidx.test's storage, which Gradle pulls to the host. */
    private static final class StorageSink extends ReportSink {
        private final Object storage;
        /** Whether Gradle will pull what this writes. When it will not, describe() says so. */
        private final boolean collected;

        StorageSink(Object storage, boolean collected) {
            this.storage = storage;
            this.collected = collected;
        }

        @Override
        OutputStream open(String fileName) throws IOException {
            try {
                return (OutputStream) storage.getClass()
                        .getMethod("openOutputFile", String.class)
                        .invoke(storage, fileName);
            } catch (Throwable t) {
                throw new IOException("could not open " + fileName + " in test storage", t);
            }
        }

        @Override
        String describe() {
            if (collected) {
                // Silent on purpose: this is the path where nothing is asked of the user.
                return null;
            }
            return "the report went to androidx.test test storage, which nothing is collecting on"
                    + " this device. Run with Gradle (which sets additionalTestOutputDir from API"
                    + " 29), or check `adb logcat -s " + Notes.TAG + "` for the path chosen.";
        }
    }

    /** Writes into the app's own files directory, which someone has to fetch. */
    private static final class FileSink extends ReportSink {
        private final File dir;
        private final String packageName;

        FileSink(File dir, String packageName) {
            this.dir = dir;
            this.packageName = packageName;
        }

        @Override
        OutputStream open(String fileName) throws IOException {
            return new FileOutputStream(new File(dir, fileName));
        }

        @Override
        String describe() {
            // The exact commands, not a description of them -- including the part that is easy
            // to leave out and fatal to omit. This directory belongs to the app, and
            // connectedAndroidTest UNINSTALLS the app when it finishes, taking the report with
            // it. Measured on an API 24 emulator: the reporter wrote ten cases, and by the time
            // the pull ran, /Android/data was empty and `run-as` reported the package unknown.
            // Every other directory available here -- getExternalMediaDirs, getExternalCacheDir,
            // getCacheDir, which is also what androidx.test's own storage falls back to -- is
            // app-specific and dies the same way.
            return "wrote to " + dir.getAbsolutePath()
                    + " (this device's API level predates Gradle's additional-test-output support)"
                    + "\n  This directory is deleted when Gradle uninstalls the app at the end of"
                    + " the run, so keep the app installed for the run you want to collect:"
                    + "\n  ./gradlew connectedAndroidTest"
                    + " -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true"
                    + "\n  adb pull " + dir.getAbsolutePath() + " ./qualflare-results"
                    + "\n  qf <project> collect ./qualflare-results"
                    + "\n  adb uninstall " + packageName;
        }
    }
}
