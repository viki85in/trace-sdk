package org.util.trace.bench;

/** Synthetic CPU work standing in for the application's own logic. */
final class Work {

    /** Work per traced method. Small on purpose: a small base cost makes tracing overhead easy to see. */
    static final long NS = Long.getLong("bench.work.ns", 5_000L);

    private Work() {
    }

    static void burn(long nanos) {
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }
}
