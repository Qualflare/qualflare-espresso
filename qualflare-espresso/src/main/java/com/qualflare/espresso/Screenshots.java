package com.qualflare.espresso;

import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;

/**
 * One way to photograph the device: {@code UiAutomation.takeScreenshot()}.
 *
 * <p>Espresso's {@code captureToBitmap()} was the obvious alternative and is the wrong one here.
 * It draws a <b>view</b>, which means it needs a resumed activity with a view root, it misses
 * anything in another window — a dialog, a permission prompt, an IME, a crash dialog — and it drags
 * in {@code espresso-core} for a library that otherwise needs nothing at test time. UiAutomation
 * takes what is actually on the screen, which is what someone looking at a failed test wants to see,
 * and it has been available since API 18.
 *
 * <p>It returns null rather than throwing when the screen cannot be read: a window with
 * {@code FLAG_SECURE} (a payment or password screen) is the common case, and a test that fails
 * <i>there</i> is exactly the test that must not fail twice.
 *
 * <p>Reflective for the same reason as {@link Config}: androidx.test is {@code compileOnly}, and off
 * a device — every unit test — the class is not there at all.
 */
final class Screenshots {

    private Screenshots() {}

    /** PNG bytes of the whole screen, or null when nothing could be captured. Never throws. */
    static byte[] capturePng() {
        Bitmap bitmap = null;
        try {
            Class<?> registry = Class.forName("androidx.test.platform.app.InstrumentationRegistry");
            Object instrumentation = registry.getMethod("getInstrumentation").invoke(null);
            Object automation = instrumentation.getClass().getMethod("getUiAutomation")
                    .invoke(instrumentation);
            bitmap = (Bitmap) automation.getClass().getMethod("takeScreenshot").invoke(automation);
            if (bitmap == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            return out.toByteArray();
        } catch (Throwable t) {
            // No instrumentation, no UiAutomation, a secure window, an OOM on a tablet-sized
            // bitmap. A screenshot is a nicety; the test's own verdict is the point.
            return null;
        } finally {
            // This bitmap came from UiAutomation, so it is ours to release -- unlike the one a
            // caller hands to Qualflare.screenshot(), which belongs to them.
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }
}
