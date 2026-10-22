package org.util.trace.core.export.otlp;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import org.util.trace.Kind;
import org.util.trace.core.CaptureKeys;
import org.util.trace.core.Span;
import org.util.trace.core.TraceState;
import org.util.trace.core.config.TraceConfig;

/** Maps SDK spans to the OTLP record format of spec §6. Shared by the protobuf and JSON encoders. */
final class SpanModel {

    static final String SCOPE_NAME = "trace-sdk";
    static final int SCHEMA_VERSION = 1;

    static final int KIND_INTERNAL = 1;
    static final int KIND_SERVER = 2;
    static final int KIND_CLIENT = 3;
    static final int KIND_PRODUCER = 4;
    static final int KIND_CONSUMER = 5;

    static final int STATUS_OK = 1;
    static final int STATUS_ERROR = 2;

    private SpanModel() {
    }

    static String sdkVersion() {
        String v = SpanModel.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    /** §6.1. Computed once per exporter. */
    static void resource(TraceConfig config, AttributeSink sink) {
        sink.string("service.name", config.serviceName());
        if (config.serviceVersion() != null) {
            sink.string("service.version", config.serviceVersion());
        }
        if (config.environment() != null) {
            sink.string("deployment.environment", config.environment());
        }
        sink.string("host.name", hostName());
        sink.integer("process.pid", ProcessHandle.current().pid());
        sink.string("telemetry.sdk.name", SCOPE_NAME);
        sink.string("telemetry.sdk.version", sdkVersion());
        sink.integer("tsdk.schema.version", SCHEMA_VERSION);
    }

    static int kind(Kind kind) {
        switch (kind) {
            case ENTRY:
                return KIND_SERVER;
            case CLIENT:
                return KIND_CLIENT;
            case PRODUCER:
                return KIND_PRODUCER;
            case CONSUMER:
                return KIND_CONSUMER;
            default:
                return KIND_INTERNAL;
        }
    }

    /** §6.2, in a fixed order. */
    static void spanAttributes(Span span, AttributeSink sink) {
        TraceState trace = span.trace();
        sink.string(CaptureKeys.TENANT_ID, orMissing(trace.tenantId()));
        sink.string(CaptureKeys.ORG_ID, orMissing(trace.orgId()));
        sink.string("component", span.site().component());
        sink.string("code.namespace", span.site().codeNamespace());
        sink.string("code.function", span.site().codeFunction());
        if (span.site().codeLineNumber() > 0) {
            sink.integer("code.lineno", span.site().codeLineNumber());
        }
        sink.integer("trace.seq", span.seq());
        sink.integer("trace.level", span.level());
        if (trace.executionId() != null) {
            sink.string(CaptureKeys.EXECUTION_ID, trace.executionId());
        }
        sink.string("retention.class", span.retentionClass());
        sink.string("thread.name", span.threadName());
        sink.integer("duration.ns", span.durationNanos());
        captures(span, sink);
        if (span.isExecutionComplete()) {
            sink.bool("execution.complete", true);
        }
        if (span.droppedSoFar() > 0) {
            sink.integer("trace.dropped", span.droppedSoFar());
        }
        if (span.failureKind() != null) {
            sink.string("failure.kind", span.failureKind());
        }
    }

    /** §6.3: attributes of a step event. */
    static void stepAttributes(Span step, AttributeSink sink) {
        sink.string("code.function", step.site().codeFunction());
        sink.integer("duration.ns", step.durationNanos());
        sink.integer("trace.seq", step.seq());
        captures(step, sink);
    }

    /** §6.3: attributes of the {@code exception} event. */
    static void exceptionAttributes(Span span, AttributeSink sink) {
        sink.string("exception.type", span.exceptionType());
        if (span.exceptionStackTrace() != null) {
            sink.string("exception.stacktrace", span.exceptionStackTrace());
        }
        if (span.exceptionMessage() != null) {
            sink.string("exception.message", span.exceptionMessage());
        }
    }

    static long endEpochNanos(Span span) {
        return span.startEpochNanos() + span.durationNanos();
    }

    private static void captures(Span span, AttributeSink sink) {
        for (int i = 0; i < span.attributeCount(); i++) {
            sink.scalar(span.attributeKey(i), span.attributeValue(i));
        }
    }

    private static String orMissing(String value) {
        return value == null ? CaptureKeys.MISSING : value;
    }

    private static String hostName() {
        String env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) {
            return env;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            String jvm = ManagementFactory.getRuntimeMXBean().getName(); // pid@host
            int at = jvm.indexOf('@');
            return at >= 0 ? jvm.substring(at + 1) : "unknown";
        }
    }
}
