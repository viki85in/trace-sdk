package org.util.trace.core;

import org.util.trace.Kind;

/**
 * Everything the hot path needs about one traced method, resolved once when the bean is created:
 * operation, component, kind, level and pre-bound capture handles. Immutable.
 */
public final class TraceSite {

    private static final CaptureSpec[] NO_CAPTURES = new CaptureSpec[0];

    private final String operation;
    private final String component;
    private final Kind kind;
    private final int level;
    private final boolean endsExecution;
    private final String retentionClass;
    private final String codeNamespace;
    private final String codeFunction;
    private final int codeLineNumber;
    private final CaptureSpec[] paramCaptures;
    private final CaptureSpec returnCapture;
    private final CaptureSpec traceparentCapture;

    public TraceSite(String operation, String component, Kind kind, int level, boolean endsExecution,
                     String retentionClass, String codeNamespace, String codeFunction, int codeLineNumber,
                     CaptureSpec[] paramCaptures, CaptureSpec returnCapture) {
        if (kind == Kind.INHERIT) {
            throw new IllegalArgumentException("kind must be resolved");
        }
        this.operation = operation;
        this.component = component;
        this.kind = kind;
        this.level = level;
        this.endsExecution = endsExecution;
        this.retentionClass = retentionClass;
        this.codeNamespace = codeNamespace;
        this.codeFunction = codeFunction;
        this.codeLineNumber = codeLineNumber;
        CaptureSpec traceparent = null;
        int others = 0;
        for (CaptureSpec c : paramCaptures) {
            if (CaptureKeys.TRACEPARENT.equals(c.key())) {
                if (traceparent == null) {
                    traceparent = c;
                }
            } else {
                others++;
            }
        }
        CaptureSpec[] rest = others == 0 ? NO_CAPTURES : new CaptureSpec[others];
        int i = 0;
        for (CaptureSpec c : paramCaptures) {
            if (!CaptureKeys.TRACEPARENT.equals(c.key())) {
                rest[i++] = c;
            }
        }
        this.paramCaptures = rest;
        this.traceparentCapture = traceparent;
        this.returnCapture = returnCapture;
    }

    public String operation() {
        return operation;
    }

    public String component() {
        return component;
    }

    public Kind kind() {
        return kind;
    }

    /** Minimum effective level ({@link Levels}) at which this site is recorded. */
    public int level() {
        return level;
    }

    /** {@code L2_STEP} sites are recorded as events on the enclosing span. */
    public boolean isStep() {
        return level == Levels.L2;
    }

    public boolean endsExecution() {
        return endsExecution;
    }

    /** {@code null} means: use {@code trace.retention.class}. */
    public String retentionClass() {
        return retentionClass;
    }

    public String codeNamespace() {
        return codeNamespace;
    }

    public String codeFunction() {
        return codeFunction;
    }

    /** {@code 0} if unknown. */
    public int codeLineNumber() {
        return codeLineNumber;
    }

    CaptureSpec[] paramCaptures() {
        return paramCaptures;
    }

    CaptureSpec returnCapture() {
        return returnCapture;
    }

    CaptureSpec traceparentCapture() {
        return traceparentCapture;
    }

    @Override
    public String toString() {
        return "TraceSite[" + operation + " " + codeNamespace + "#" + codeFunction + "]";
    }
}
