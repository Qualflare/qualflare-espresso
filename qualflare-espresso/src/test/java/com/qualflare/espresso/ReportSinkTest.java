package com.qualflare.espresso;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.platform.io.PlatformTestStorage;
import androidx.test.platform.io.PlatformTestStorageRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Both delivery routes, and the rule that binds them: one sink for the report and its images.
 *
 * <p>The fallback is not an edge case. The spike measured Gradle passing
 * {@code additionalTestOutputDir} only from API 29, so on API 24–28 the file route is what every
 * run uses — it earns the same coverage as the storage route.
 */
@RunWith(RobolectricTestRunner.class)
public class ReportSinkTest {

    @After
    public void resetInstrumentationArgs() {
        InstrumentationRegistry.registerInstance(
                InstrumentationRegistry.getInstrumentation(), new Bundle());
    }

    /**
     * A storage stand-in built as a dynamic proxy rather than an {@code implements} clause.
     *
     * <p>PlatformTestStorage grows: monitor 1.8.0 has {@code isTestStorageFilePath} and
     * {@code getOutputFileUri} that 1.4.0 (our floor) does not, and implementing the interface
     * directly made this test fail to compile once per added method — coupling the test to one
     * version of a dependency the library deliberately treats as {@code compileOnly}. A proxy
     * answers what the reporter actually calls and throws for anything else, so a new call site
     * shows up as a clear failure instead of silently returning null. Same reasoning as the testng
     * sibling's Fakes.java.
     */
    private static final class FakeStorage {
        final Map<String, ByteArrayOutputStream> written = new LinkedHashMap<>();
        final Object proxy;

        FakeStorage() {
            proxy = Proxy.newProxyInstance(
                    PlatformTestStorage.class.getClassLoader(),
                    new Class<?>[] {PlatformTestStorage.class},
                    (p, method, args) -> {
                        switch (method.getName()) {
                            case "openOutputFile":
                                String name = (String) args[0];
                                ByteArrayOutputStream out = new ByteArrayOutputStream();
                                written.put(name, out);
                                return out;
                            case "toString":
                                return "FakeStorage";
                            case "hashCode":
                                return System.identityHashCode(p);
                            case "equals":
                                return p == args[0];
                            default:
                                throw new UnsupportedOperationException(
                                        "the reporter called " + method.getName()
                                                + ", which this fake does not model");
                        }
                    });
        }

        PlatformTestStorage asStorage() {
            return (PlatformTestStorage) proxy;
        }
    }

    private static void withOutputDirArgument(String value) {
        Bundle args = new Bundle();
        if (value != null) {
            args.putString("additionalTestOutputDir", value);
        }
        InstrumentationRegistry.registerInstance(InstrumentationRegistry.getInstrumentation(), args);
    }

    private static void write(ReportSink sink, String name, String body) throws IOException {
        try (OutputStream out = sink.open(name);
                Writer writer = new OutputStreamWriter(out, "UTF-8")) {
            writer.write(body);
        }
    }

    @Test
    public void storage_is_used_when_gradle_will_collect_it() throws Exception {
        FakeStorage storage = new FakeStorage();
        PlatformTestStorageRegistry.registerInstance(storage.asStorage());
        withOutputDirArgument("/sdcard/Android/media/pkg/additional_test_output");

        ReportSink sink = ReportSink.resolve(RuntimeEnvironment.getApplication());
        assertNotNull(sink);
        write(sink, "qualflare-espresso-1-2-3.json", "{\"framework\":\"espresso\"}");

        assertTrue("the report went through storage",
                storage.written.containsKey("qualflare-espresso-1-2-3.json"));
        assertEquals("{\"framework\":\"espresso\"}",
                storage.written.get("qualflare-espresso-1-2-3.json").toString("UTF-8"));
        assertNull("nothing to tell the user on this path", sink.describe());
    }

    /**
     * The rule that keeps localImagePath resolvable: {@code qf collect} looks for an attachment
     * beside the report it found, so both must travel the same way.
     */
    @Test
    public void the_report_and_its_images_come_from_one_sink() throws Exception {
        FakeStorage storage = new FakeStorage();
        PlatformTestStorageRegistry.registerInstance(storage.asStorage());
        withOutputDirArgument("/sdcard/Android/media/pkg/additional_test_output");

        ReportSink sink = ReportSink.resolve(RuntimeEnvironment.getApplication());
        write(sink, "report.json", "{}");
        write(sink, "qf-attach-1-shot.png", "not really a png");

        assertEquals("both files came from the same sink", 2, storage.written.size());
        assertTrue(storage.written.containsKey("qf-attach-1-shot.png"));
    }

    /**
     * API 24–28: Gradle passes no additionalTestOutputDir, so storage output would sit on the
     * device uncollected. The sink writes to the app's files directory instead and says exactly how
     * to fetch it.
     */
    @Test
    public void without_the_output_dir_argument_it_writes_a_file_and_says_how_to_fetch_it()
            throws Exception {
        FakeStorage storage = new FakeStorage();
        PlatformTestStorageRegistry.registerInstance(storage.asStorage());
        withOutputDirArgument(null);

        ReportSink sink = ReportSink.resolve(RuntimeEnvironment.getApplication());
        assertNotNull(sink);
        write(sink, "report.json", "{\"on\":\"disk\"}");

        assertTrue("storage must NOT have been used: nothing would collect it",
                storage.written.isEmpty());

        File dir = new File(RuntimeEnvironment.getApplication().getExternalFilesDir(null),
                Config.DEFAULT_OUTPUT_DIR);
        File report = new File(dir, "report.json");
        assertTrue("the report is in the app's files dir at " + report, report.isFile());

        String note = sink.describe();
        assertNotNull("this path asks something of the user, so it must say so", note);
        assertTrue("the note gives the actual command", note.contains("adb pull"));
        assertTrue("and names the directory to pull", note.contains(dir.getAbsolutePath()));
        assertTrue("and how to upload afterwards", note.contains("qf <project> collect"));
    }

    @Test
    public void the_output_dir_option_is_honoured_on_the_file_route() throws Exception {
        withOutputDirArgument(null);
        System.setProperty("qualflare.outputDir", "custom-results");
        try {
            ReportSink sink = ReportSink.resolve(RuntimeEnvironment.getApplication());
            write(sink, "report.json", "{}");
            File expected = new File(
                    RuntimeEnvironment.getApplication().getExternalFilesDir(null), "custom-results");
            assertTrue(new File(expected, "report.json").isFile());
        } finally {
            System.clearProperty("qualflare.outputDir");
        }
    }
}
