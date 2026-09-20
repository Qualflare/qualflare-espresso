package com.qualflare.espresso;

/**
 * Just enough JSON to emit the wire format, hand-rolled on purpose.
 *
 * <p>A test reporter is a dependency of everyone's test classpath. Pulling Jackson or Gson
 * in would put a second copy of it in front of whatever the project already uses, which is
 * a genuinely common source of "works on my machine". Zero dependencies is a feature here,
 * the same promise qualflare-go makes about its go.mod graph.
 */
final class Json {
    private final StringBuilder sb = new StringBuilder(4096);
    private boolean needComma = false;

    private Json() {}

    static Json object() {
        Json j = new Json();
        j.sb.append('{');
        return j;
    }

    Json field(String name, String value) {
        if (value == null) {
            return this;
        }
        comma();
        quote(name).append(':');
        quote(value);
        return this;
    }

    /** Emits an explicit null rather than omitting: the wire wants value-or-null here. */
    Json nullableField(String name, String value) {
        comma();
        quote(name).append(':');
        if (value == null) {
            sb.append("null");
        } else {
            quote(value);
        }
        return this;
    }

    Json field(String name, long value) {
        comma();
        quote(name).append(':').append(value);
        return this;
    }

    Json field(String name, boolean value) {
        comma();
        quote(name).append(':').append(value);
        return this;
    }

    Json raw(String name, String rawJson) {
        comma();
        quote(name).append(':').append(rawJson);
        return this;
    }

    String end() {
        return sb.append('}').toString();
    }

    static String escape(String s) {
        Json j = new Json();
        j.sb.setLength(0);
        j.quote(s);
        return j.sb.toString();
    }

    private void comma() {
        if (needComma) {
            sb.append(',');
        }
        needComma = true;
    }

    private StringBuilder quote(String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    // Control characters must be escaped or the document is invalid, and
                    // test output is full of them: coloured assertion diffs arrive with
                    // ANSI escapes embedded in the failure message.
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"');
    }
}
