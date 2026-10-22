package org.util.trace.core;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * State shared by every span of one trace on this process: identity, the inherited tenant / org /
 * execution IDs, the {@code trace.seq} counter and the running drop count.
 */
public final class TraceState {

    private final long traceIdHigh;
    private final long traceIdLow;
    private final AtomicInteger seq = new AtomicInteger();
    private final AtomicInteger dropped = new AtomicInteger();

    // Set by entry captures; read by spans on other threads.
    private volatile String tenantId;
    private volatile String orgId;
    private volatile String executionId;

    TraceState(long traceIdHigh, long traceIdLow) {
        this.traceIdHigh = traceIdHigh;
        this.traceIdLow = traceIdLow;
    }

    public long traceIdHigh() {
        return traceIdHigh;
    }

    public long traceIdLow() {
        return traceIdLow;
    }

    public String tenantId() {
        return tenantId;
    }

    public String orgId() {
        return orgId;
    }

    public String executionId() {
        return executionId;
    }

    int nextSeq() {
        return seq.incrementAndGet();
    }

    int dropped() {
        return dropped.get();
    }

    void recordDrop() {
        dropped.incrementAndGet();
    }

    /** First value wins: an ID captured at the entry is inherited, not overwritten by children. */
    void setContextId(String key, String value) {
        switch (key) {
            case CaptureKeys.TENANT_ID:
                if (tenantId == null) {
                    tenantId = value;
                }
                break;
            case CaptureKeys.ORG_ID:
                if (orgId == null) {
                    orgId = value;
                }
                break;
            case CaptureKeys.EXECUTION_ID:
                if (executionId == null) {
                    executionId = value;
                }
                break;
            default:
                throw new IllegalArgumentException(key);
        }
    }
}
