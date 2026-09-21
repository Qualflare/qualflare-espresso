package com.qualflare.espresso;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A sink that keeps what was written in memory, so a test can assert an image really travelled the
 * report's own route rather than just that a filename was invented for it.
 */
final class FakeSink extends ReportSink {

    final Map<String, ByteArrayOutputStream> written = new LinkedHashMap<>();

    @Override
    OutputStream open(String fileName) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        written.put(fileName, out);
        return out;
    }

    @Override
    String describe() {
        return null;
    }
}
