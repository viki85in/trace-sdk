package org.util.trace.core;

import java.time.Instant;

/**
 * Converts {@link System#nanoTime()} readings to Unix epoch nanoseconds without reading the wall
 * clock on every span. Start timestamps come from a fixed offset taken at class load; durations
 * always come from the monotonic clock.
 */
public final class WallClock {

    private static final long OFFSET;

    static {
        Instant now = Instant.now();
        long nano = System.nanoTime();
        OFFSET = now.getEpochSecond() * 1_000_000_000L + now.getNano() - nano;
    }

    private WallClock() {
    }

    public static long toEpochNanos(long nanoTime) {
        return nanoTime + OFFSET;
    }
}
