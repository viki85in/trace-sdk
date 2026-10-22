package org.util.trace.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One span, or one step event when the site is an {@code L2_STEP} site. Written only by the thread
 * that runs the traced method; after it ends it is enqueued as-is and becomes read-only, so the
 * exporter reads it without copying.
 */
public final class Span {

    private static final Object[] NO_ATTRIBUTES = new Object[0];

    private final TraceState trace;
    private final TraceSite site;
    private final long spanId;
    private final long parentSpanId;
    private final int level;
    private final int seq;
    private final long startNanoTime;
    private final String threadName;

    /** The span that was current when this one started; restored when it ends, then cleared. */
    Span parent;

    private long durationNanos;
    private Object[] attributes = NO_ATTRIBUTES;
    private int attributeCount;
    private List<Span> events;

    private boolean error;
    private String failureKind;
    private String exceptionType;
    private String exceptionMessage;
    private String exceptionStackTrace;
    private boolean executionComplete;
    private int droppedSoFar;
    private String retentionClass;

    Span(TraceState trace, TraceSite site, long spanId, long parentSpanId, Span parent, int level, long startNanoTime) {
        this.trace = trace;
        this.site = site;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.parent = parent;
        this.level = level;
        this.seq = trace.nextSeq();
        this.startNanoTime = startNanoTime;
        this.threadName = Thread.currentThread().getName();
    }

    // ---------------------------------------------------------------- written by the tracer

    void addAttribute(String key, Object value) {
        int index = attributeCount * 2;
        if (index == attributes.length) {
            Object[] grown = new Object[Math.max(8, attributes.length * 2)];
            System.arraycopy(attributes, 0, grown, 0, attributes.length);
            attributes = grown;
        }
        attributes[index] = key;
        attributes[index + 1] = value;
        attributeCount++;
    }

    Object attribute(String key) {
        for (int i = 0; i < attributeCount; i++) {
            if (key.equals(attributes[i * 2])) {
                return attributes[i * 2 + 1];
            }
        }
        return null;
    }

    void addEvent(Span step) {
        if (events == null) {
            events = new ArrayList<>(4);
        }
        events.add(step);
    }

    long startNanoTime() {
        return startNanoTime;
    }

    void end(long durationNanos) {
        this.durationNanos = durationNanos;
    }

    void markError(String failureKind) {
        this.error = true;
        this.failureKind = failureKind;
    }

    void recordException(String type, String message, String stackTrace) {
        this.error = true;
        this.exceptionType = type;
        this.exceptionMessage = message;
        this.exceptionStackTrace = stackTrace;
    }

    void markExecutionComplete() {
        this.executionComplete = true;
    }

    void setDroppedSoFar(int dropped) {
        this.droppedSoFar = dropped;
    }

    void setRetentionClass(String retentionClass) {
        this.retentionClass = retentionClass;
    }

    // ---------------------------------------------------------------- read by encoders

    public TraceState trace() {
        return trace;
    }

    public TraceSite site() {
        return site;
    }

    public long spanId() {
        return spanId;
    }

    /** {@code 0} for a root span without a remote parent. */
    public long parentSpanId() {
        return parentSpanId;
    }

    public int level() {
        return level;
    }

    public int seq() {
        return seq;
    }

    public long startEpochNanos() {
        return WallClock.toEpochNanos(startNanoTime);
    }

    public long durationNanos() {
        return durationNanos;
    }

    public String threadName() {
        return threadName;
    }

    public int attributeCount() {
        return attributeCount;
    }

    public String attributeKey(int i) {
        return (String) attributes[i * 2];
    }

    public Object attributeValue(int i) {
        return attributes[i * 2 + 1];
    }

    /** Step events recorded on this span, in the order they ended. */
    public List<Span> events() {
        return events == null ? Collections.emptyList() : events;
    }

    public boolean isError() {
        return error;
    }

    /** {@code PARTIAL}, {@code ALL} or {@code null}. */
    public String failureKind() {
        return failureKind;
    }

    /** {@code null} unless the method threw. */
    public String exceptionType() {
        return exceptionType;
    }

    /** {@code null} unless the method threw and message capture is enabled. */
    public String exceptionMessage() {
        return exceptionMessage;
    }

    public String exceptionStackTrace() {
        return exceptionStackTrace;
    }

    public boolean isExecutionComplete() {
        return executionComplete;
    }

    public int droppedSoFar() {
        return droppedSoFar;
    }

    public String retentionClass() {
        return retentionClass;
    }
}
