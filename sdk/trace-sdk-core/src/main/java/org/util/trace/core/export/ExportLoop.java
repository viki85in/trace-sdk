package org.util.trace.core.export;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.util.trace.core.Span;
import org.util.trace.core.Stats;
import org.util.trace.core.buffer.SpanBuffer;

/**
 * The single exporter thread: drains the buffer into batches of up to {@code trace.batch.max.spans},
 * sending a partial batch after {@code trace.batch.max.delay.ms}. Producers never signal it, so the
 * hot path stays free of wake-ups; it parks briefly when the buffer is empty.
 */
public final class ExportLoop implements Runnable {

    private static final long IDLE_PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

    private final SpanBuffer<Span> buffer;
    private final SpanExporter exporter;
    private final Stats stats;
    private final int batchMaxSpans;
    private final long batchMaxDelayNanos;

    private volatile Thread thread;
    private volatile boolean stopping;
    private volatile long stopDeadlineNanos = Long.MAX_VALUE;

    public ExportLoop(SpanBuffer<Span> buffer, SpanExporter exporter, Stats stats, int batchMaxSpans,
                      long batchMaxDelayMillis) {
        this.buffer = buffer;
        this.exporter = exporter;
        this.stats = stats;
        this.batchMaxSpans = batchMaxSpans;
        this.batchMaxDelayNanos = TimeUnit.MILLISECONDS.toNanos(batchMaxDelayMillis);
    }

    public synchronized void start() {
        if (thread != null) {
            return;
        }
        Thread t = new Thread(this, "trace-sdk-exporter");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    /** Flushes what is buffered, waiting at most {@code timeoutMillis}. */
    public void shutdown(long timeoutMillis) {
        Thread t = thread;
        if (t == null) {
            return;
        }
        stopDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        stopping = true;
        LockSupport.unpark(t);
        try {
            t.join(timeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void run() {
        List<Span> batch = new ArrayList<>(batchMaxSpans);
        long batchDeadline = 0;
        while (true) {
            Span span = buffer.poll();
            if (span != null) {
                if (batch.isEmpty()) {
                    batchDeadline = System.nanoTime() + batchMaxDelayNanos;
                }
                batch.add(span);
                if (batch.size() >= batchMaxSpans) {
                    flush(batch);
                }
                continue;
            }
            if (stopping) {
                flush(batch);
                return;
            }
            if (!batch.isEmpty() && System.nanoTime() - batchDeadline >= 0) {
                flush(batch);
                continue;
            }
            LockSupport.parkNanos(this, IDLE_PARK_NANOS);
        }
    }

    private void flush(List<Span> batch) {
        if (batch.isEmpty()) {
            return;
        }
        boolean ok;
        try {
            ok = exporter.export(batch, stopping ? stopDeadlineNanos : Long.MAX_VALUE);
        } catch (Throwable t) {
            stats.recordExportError();
            ok = false;
        }
        if (ok) {
            stats.recordExported(batch.size());
        } else {
            stats.recordExportFailed(batch.size());
        }
        batch.clear();
    }
}
