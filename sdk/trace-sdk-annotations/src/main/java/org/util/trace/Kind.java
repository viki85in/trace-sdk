package org.util.trace;

/** Span kind of a traced method. */
public enum Kind {
    /** Not set: inherit from the class annotation, else {@link #INTERNAL}. */
    INHERIT,
    /** A service entry point: API handler, message consumer, scheduled job. */
    ENTRY,
    INTERNAL,
    /** An outbound call to another service. */
    CLIENT,
    PRODUCER,
    CONSUMER
}
