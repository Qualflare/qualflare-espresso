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
            return new StorageSink(storage);
        }
        if (context != null) {
            File dir = new File(context.getExternalFilesDir(null), Config.outputDir());
            if (dir.exists() || dir.mkdirs()) {
                return new FileSink(dir, context.getPackageName());
            }
        }
        // Storage exists but Gradle will not collect it, and there is no context to fall back on.
        // Writing to storage anyway beats writing nowhere: the files are on the device and the
        // note says how to fetch them.
        if (storage != null) {
            return new StorageSink(storage);
        }
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

        StorageSink(Object storage) {
            this.storage = storage;
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
            // Silent on purpose: this is the path where nothing is asked of the user.
            return null;
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
            // The exact command, not a description of one. Gradle only passes
            // additionalTestOutputDir from API 29, so on 24-28 this is how the report travels.
            return "wrote to " + dir.getAbsolutePath()
                    + " (this device's API level predates Gradle's additional-test-output support)"
                    + "\n  adb pull " + dir.getAbsolutePath() + " ./qualflare-results"
                    + "\n  qf <project> collect ./qualflare-results"
                    + "\n  (package " + packageName + ")";
        }
    }
}
