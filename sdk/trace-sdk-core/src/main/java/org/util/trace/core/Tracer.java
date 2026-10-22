package org.util.trace.core;

import org.util.trace.core.buffer.SpanBuffer;
import org.util.trace.core.config.TraceConfig;

/**
 * The hot path: starts and ends spans around a traced call (spec §9).
 *
 * <p>Usage by an interceptor:
 * <pre>{@code
 * Span span = tracer.begin(site, args);      // null: not recorded, just proceed
 * try { result = proceed(); } catch (Throwable t) { error = t; throw t; }
 * finally { if (span != null) tracer.end(span, result, error); }
 * }</pre>
 *
 * <p>Synchronous work is limited to IDs, timestamps, {@code trace.seq}, context push/pop, copying
 * captured scalars and the enqueue. Encoding and export happen on the exporter thread.
 */
public final class Tracer {

    private static final ThreadLocal<Span> CURRENT = new ThreadLocal<>();

    private final TraceConfig config;
    private final SpanBuffer<Span> buffer;
    private final Stats stats;

    Tracer(TraceConfig config, SpanBuffer<Span> buffer, Stats stats) {
        this.config = config;
        this.buffer = buffer;
        this.stats = stats;
    }

    /** The span active on this thread, or {@code null}. */
    public static Span current() {
        return CURRENT.get();
    }

    /**
     * Starts a span (or a step event) for {@code site}, or returns {@code null} if tracing is off or
     * the site is below the effective level. Never throws for valid sites.
     */
    public Span begin(TraceSite site, Object[] args) {
        if (!config.enabled()) {
            return null;
        }
        Span parent = CURRENT.get();
        TraceState trace;
        long parentSpanId;
        int level;
        if (parent == null) {
            if (site.isStep()) {
                return null; // a step has no enclosing span to attach to
            }
            // The entry span is always recorded; the effective level is fixed here (FR-2).
            level = Math.max(config.level(), config.levelOverride(site.operation()));
            Traceparent remote = null;
            CaptureSpec tp = site.traceparentCapture();
            if (tp != null) {
                remote = Traceparent.parse(tp.extract(args[tp.paramIndex()]));
            }
            if (remote != null) {
                trace = new TraceState(remote.traceIdHigh, remote.traceIdLow);
                parentSpanId = remote.parentSpanId;
            } else {
                trace = new TraceState(Ids.randomNonZero(), Ids.randomNonZero());
                parentSpanId = 0L;
            }
        } else {
            level = parent.level();
            if (site.level() > level) {
                return null;
            }
            int override = config.levelOverride(site.operation());
            if (override > level) {
                level = override; // raises the level for this subtree only
            }
            trace = parent.trace();
            parentSpanId = parent.spanId();
        }

        boolean step = site.isStep();
        Span span = new Span(trace, site, step ? 0L : Ids.randomNonZero(), parentSpanId, parent, level,
                System.nanoTime());
        for (CaptureSpec capture : site.paramCaptures()) {
            Object value = capture.extract(args[capture.paramIndex()]);
            if (value == null) {
                continue;
            }
            if (CaptureKeys.isContextId(capture.key())) {
                trace.setContextId(capture.key(), value.toString());
            } else {
                span.addAttribute(capture.key(), value);
            }
        }
        if (!step) {
            stats.spansStarted.increment();
            CURRENT.set(span);
        }
        return span;
    }

    /** Ends a span returned by {@link #begin}. {@code error} is what the method threw, if anything. */
    public void end(Span span, Object returnValue, Throwable error) {
        span.end(System.nanoTime() - span.startNanoTime());
        Span parent = span.parent;
        span.parent = null;

        CaptureSpec rc = span.site().returnCapture();
        if (rc != null && error == null) {
            Object value = rc.extract(returnValue);
            if (value != null) {
                if (CaptureKeys.isContextId(rc.key())) {
                    span.trace().setContextId(rc.key(), value.toString());
                } else {
                    span.addAttribute(rc.key(), value);
                }
            }
        }

        if (span.site().isStep()) {
            if (error != null) {
                span.addAttribute("exception.type", error.getClass().getName());
            }
            if (parent != null) {
                parent.addEvent(span);
            }
            return;
        }

        if (parent == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(parent);
        }

        if (error != null) {
            span.recordException(error.getClass().getName(),
                    config.captureExceptionMessage() ? error.getMessage() : null,
                    boundedStackTrace(error, config.stacktraceMaxFrames()));
        }
        Object failed = span.attribute(CaptureKeys.RECORDS_FAILED);
        if (failed instanceof Number && ((Number) failed).longValue() > 0) {
            Object count = span.attribute(CaptureKeys.RECORDS_COUNT);
            boolean all = count instanceof Number && ((Number) count).longValue() == ((Number) failed).longValue();
            span.markError(all ? "ALL" : "PARTIAL");
        }
        if (span.site().endsExecution()) {
            span.markExecutionComplete();
        }
        String retention = span.site().retentionClass();
        span.setRetentionClass(retention != null ? retention : config.retentionClass());

        TraceState trace = span.trace();
        if (trace.tenantId() == null || trace.orgId() == null) {
            stats.missingTenant.increment();
        }
        int dropped = trace.dropped();
        if (dropped > 0) {
            span.setDroppedSoFar(dropped);
        }
        if (!buffer.offer(span)) {
            trace.recordDrop();
            stats.droppedBufferFull.increment();
        }
    }

    static String boundedStackTrace(Throwable error, int maxFrames) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(error.getClass().getName());
        StackTraceElement[] frames = error.getStackTrace();
        int n = Math.min(frames.length, maxFrames);
        for (int i = 0; i < n; i++) {
            sb.append("\n\tat ").append(frames[i]);
        }
        if (frames.length > n) {
            sb.append("\n\t... ").append(frames.length - n).append(" more");
        }
        return sb.toString();
    }
}
