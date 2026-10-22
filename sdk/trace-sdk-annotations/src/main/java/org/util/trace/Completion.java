package org.util.trace;

/** Whether the end of a traced method marks the end of the execution. */
public enum Completion {
    NONE,
    /** When this method ends, the span records {@code execution.complete=true}. */
    ENDS_EXECUTION
}
