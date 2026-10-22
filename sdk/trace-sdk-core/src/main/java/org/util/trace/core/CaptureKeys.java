package org.util.trace.core;

import java.util.Set;

/** Reserved capture keys (spec §6.2). Any other key must start with {@code app.}. */
public final class CaptureKeys {

    public static final String TENANT_ID = "tenant.id";
    public static final String ORG_ID = "org.id";
    public static final String EXECUTION_ID = "execution.id";
    public static final String TRACEPARENT = "traceparent";
    public static final String RECORDS_COUNT = "records.count";
    public static final String RECORDS_FAILED = "records.failed";
    public static final String BATCH_SIZE = "batch.size";
    public static final String RETRY_ATTEMPT = "retry.attempt";

    public static final String APP_PREFIX = "app.";

    /** Value recorded when an entry did not capture tenant or org (FR-10). */
    public static final String MISSING = "_missing_";

    private static final Set<String> RESERVED = Set.of(
            TENANT_ID, ORG_ID, EXECUTION_ID, TRACEPARENT, RECORDS_COUNT, RECORDS_FAILED, BATCH_SIZE, RETRY_ATTEMPT);

    private CaptureKeys() {
    }

    public static boolean isValid(String key) {
        return RESERVED.contains(key) || (key.startsWith(APP_PREFIX) && key.length() > APP_PREFIX.length());
    }

    /** Keys that go to the shared {@link TraceState} rather than the span's own attributes. */
    static boolean isContextId(String key) {
        return TENANT_ID.equals(key) || ORG_ID.equals(key) || EXECUTION_ID.equals(key);
    }
}
