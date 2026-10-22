package org.util.trace.core;

import java.lang.System.Logger;
import org.util.trace.core.buffer.SpanBuffer;
import org.util.trace.core.config.TraceConfig;
import org.util.trace.core.export.ExportLoop;
import org.util.trace.core.export.OtlpHttpExporter;
import org.util.trace.core.export.SpanExporter;

/**
 * One SDK instance: configuration, tracer, buffer and exporter thread. Integration modules create
 * one per application context and call {@link #start()} / {@link #shutdown()}.
 *
 * <p>If {@code trace.service.name} (or {@code spring.application.name}) is missing the runtime is
 * inactive: {@link #tracer()} returns {@code null} and nothing is recorded (spec §8.1).
 */
public final class TraceRuntime {

    private static final Logger LOG = System.getLogger("org.util.trace");

    private final TraceConfig config;
    private final Stats stats = new Stats();
    private final Tracer tracer;
    private final ExportLoop exportLoop;
    private Thread shutdownHook;
    private boolean started;

    private TraceRuntime(TraceConfig config, SpanExporter exporter) {
        this.config = config;
        if (config.serviceName() == null) {
            LOG.log(Logger.Level.ERROR,
                    "trace-sdk: trace.service.name (or spring.application.name) is not set; tracing is disabled");
            this.tracer = null;
            this.exportLoop = null;
            return;
        }
        SpanBuffer<Span> buffer = new SpanBuffer<>(config.bufferMaxSpans());
        this.tracer = new Tracer(config, buffer, stats);
        this.exportLoop = new ExportLoop(buffer, exporter != null ? exporter : new OtlpHttpExporter(config, stats),
                stats, config.batchMaxSpans(), config.batchMaxDelayMs());
    }

    public static TraceRuntime create(TraceConfig config) {
        return new TraceRuntime(config, null);
    }

    /** With a custom exporter; used by tests. */
    public static TraceRuntime create(TraceConfig config, SpanExporter exporter) {
        return new TraceRuntime(config, exporter);
    }

    public boolean isActive() {
        return tracer != null;
    }

    /** {@code null} if inactive. */
    public Tracer tracer() {
        return tracer;
    }

    public TraceConfig config() {
        return config;
    }

    public Stats stats() {
        return stats;
    }

    public synchronized void start() {
        if (started || !isActive()) {
            return;
        }
        started = true;
        exportLoop.start();
        // Fallback if the container doesn't close cleanly.
        shutdownHook = new Thread(this::flushOnExit, "trace-sdk-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            shutdownHook = null; // already shutting down
        }
    }

    /** Flushes buffered spans, waiting at most {@code trace.exporter.timeout.ms}. Idempotent. */
    public synchronized void shutdown() {
        if (!started) {
            return;
        }
        started = false;
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException e) {
                // JVM is shutting down; the hook is running or will run.
            }
            shutdownHook = null;
        }
        exportLoop.shutdown(config.timeoutMs());
    }

    private void flushOnExit() {
        exportLoop.shutdown(config.timeoutMs());
    }
}
