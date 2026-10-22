package org.util.trace.core;

import java.lang.invoke.MethodHandle;

/**
 * One resolved {@code @Capture} / {@code @CaptureReturn}: the attribute key, the argument index,
 * and the property path pre-resolved to method handles when the site was created.
 */
public final class CaptureSpec {

    static final int RETURN_VALUE = -1;
    private static final int MAX_STRING = 256;

    private final String key;
    private final int paramIndex;
    /** Each handle has type {@code (Object)Object}. Empty means the value itself. */
    private final MethodHandle[] path;

    public CaptureSpec(String key, int paramIndex, MethodHandle[] path) {
        this.key = key;
        this.paramIndex = paramIndex;
        this.path = path.clone();
    }

    public static CaptureSpec forReturn(String key, MethodHandle[] path) {
        return new CaptureSpec(key, RETURN_VALUE, path);
    }

    public String key() {
        return key;
    }

    public int paramIndex() {
        return paramIndex;
    }

    /** Follows the path and returns a scalar, or {@code null} if a step is null, throws, or isn't scalar. */
    Object extract(Object root) {
        Object value = root;
        try {
            for (MethodHandle step : path) {
                if (value == null) {
                    return null;
                }
                value = (Object) step.invokeExact(value);
            }
        } catch (Throwable t) {
            return null;
        }
        return toScalar(value);
    }

    static Object toScalar(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Character) {
            return value.toString();
        }
        if (value instanceof Enum) {
            return ((Enum<?>) value).name();
        }
        if (value instanceof CharSequence) {
            String s = value.toString();
            return s.length() <= MAX_STRING ? s : s.substring(0, MAX_STRING);
        }
        return null;
    }
}
