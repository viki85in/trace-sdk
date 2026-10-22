package org.util.trace.core.export;

import java.util.List;
import org.util.trace.core.Span;

/** Sends one batch. Called only from the exporter thread. Internal to the SDK. */
public interface SpanExporter {

    /**
     * Returns {@code true} if the batch was accepted. Must not throw. Retries, if any, must give up
     * by {@code deadlineNanos} ({@link System#nanoTime()} based; {@link Long#MAX_VALUE} for none).
     */
    boolean export(List<Span> spans, long deadlineNanos);
}
