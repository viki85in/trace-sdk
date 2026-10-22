package org.util.trace;

/** Granularity level. A method is recorded only when the effective level is at least its level. */
public enum Level {
    /** Not set: inherit from the class annotation, else the default for the method's kind. */
    INHERIT,
    /** Entry span only. */
    L0_ENTRY,
    /** Entry plus public boundary methods. The default. */
    L1_BOUNDARY,
    /** Boundary plus step methods, recorded as events on the enclosing span. */
    L2_STEP
}
