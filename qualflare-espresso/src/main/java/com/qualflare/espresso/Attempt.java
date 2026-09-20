package com.qualflare.espresso;

/** One execution of a test. Under a rerun there are several of these per case. */
final class Attempt {
    final String status;
    final long durationNanos;
    final String message;
    final String trace;

    Attempt(String status, long durationNanos, String message, String trace) {
        this.status = status;
        this.durationNanos = durationNanos;
        this.message = message;
        this.trace = trace;
    }
}
