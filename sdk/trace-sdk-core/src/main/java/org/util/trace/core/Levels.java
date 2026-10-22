package org.util.trace.core;

import java.util.Locale;
import org.util.trace.Level;

/** Levels as small ints, so the hot path compares integers (FR-2). */
public final class Levels {

    public static final int OFF = -1;
    public static final int L0 = 0;
    public static final int L1 = 1;
    public static final int L2 = 2;

    private Levels() {
    }

    public static int of(Level level) {
        switch (level) {
            case L0_ENTRY:
                return L0;
            case L1_BOUNDARY:
                return L1;
            case L2_STEP:
                return L2;
            default:
                throw new IllegalArgumentException("unresolved level " + level);
        }
    }

    /** Parses {@code L0_ENTRY}, {@code L1_BOUNDARY}, {@code L2_STEP} (or {@code L0}/{@code L1}/{@code L2}). */
    public static int parse(String value) {
        String v = value.trim().toUpperCase(Locale.ROOT);
        switch (v) {
            case "L0":
            case "L0_ENTRY":
                return L0;
            case "L1":
            case "L1_BOUNDARY":
                return L1;
            case "L2":
            case "L2_STEP":
                return L2;
            default:
                throw new IllegalArgumentException("expected L0_ENTRY, L1_BOUNDARY or L2_STEP");
        }
    }
}
