package org.util.trace.core.export.otlp;

/** Receives typed attributes, so both encoders share one definition of what a span contains. */
interface AttributeSink {

    void string(String key, String value);

    void integer(String key, long value);

    void floating(String key, double value);

    void bool(String key, boolean value);

    /** Dispatches a captured scalar (see {@code CaptureSpec}) to the matching typed method. */
    default void scalar(String key, Object value) {
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            integer(key, ((Number) value).longValue());
        } else if (value instanceof Double || value instanceof Float) {
            floating(key, ((Number) value).doubleValue());
        } else if (value instanceof Boolean) {
            bool(key, (Boolean) value);
        } else if (value != null) {
            string(key, value.toString());
        }
    }
}
