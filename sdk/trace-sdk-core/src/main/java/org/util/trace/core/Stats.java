package org.util.trace.core;

import java.util.concurrent.atomic.LongAdder;

/** Cumulative counters since process start (reported by the heartbeat, FR-12). */
public final class Stats {

    final LongAdder spansStarted = new LongAdder();
    final LongAdder spansExported = new LongAdder();
    final LongAdder droppedBufferFull = new LongAdder();
    final LongAdder droppedExportFailed = new LongAdder();
    final LongAdder missingTenant = new LongAdder();
    final LongAdder exportErrors = new LongAdder();
    final LongAdder internalErrors = new LongAdder();

    public long spansStarted() {
        return spansStarted.sum();
    }

    public long spansExported() {
        return spansExported.sum();
    }

    public long droppedBufferFull() {
        return droppedBufferFull.sum();
    }

    public long droppedExportFailed() {
        return droppedExportFailed.sum();
    }

    public long missingTenant() {
        return missingTenant.sum();
    }

    public long exportErrors() {
        return exportErrors.sum();
    }

    /** SDK bugs caught on the application path; the application was not affected. */
    public long internalErrors() {
        return internalErrors.sum();
    }

    public void recordInternalError() {
        internalErrors.increment();
    }

    public void recordExported(int spans) {
        spansExported.add(spans);
    }

    public void recordExportFailed(int spans) {
        droppedExportFailed.add(spans);
    }

    public void recordExportError() {
        exportErrors.increment();
    }

    @Override
    public String toString() {
        return "started=" + spansStarted() + " exported=" + spansExported()
                + " dropped.buffer_full=" + droppedBufferFull() + " dropped.export_failed=" + droppedExportFailed()
                + " missing_tenant=" + missingTenant() + " export.errors=" + exportErrors()
                + " internal.errors=" + internalErrors();
    }
}
