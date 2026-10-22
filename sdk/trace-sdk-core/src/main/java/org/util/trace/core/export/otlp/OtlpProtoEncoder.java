package org.util.trace.core.export.otlp;

import java.util.List;
import org.util.trace.core.Span;
import org.util.trace.core.config.TraceConfig;

/**
 * Hand-written OTLP protobuf encoder (field numbers from opentelemetry-proto
 * {@code trace/v1/trace.proto} and {@code common/v1/common.proto}), so the SDK needs no protobuf
 * dependency.
 */
final class OtlpProtoEncoder implements OtlpEncoder {

    // ExportTraceServiceRequest / ResourceSpans / ScopeSpans
    private static final int REQUEST_RESOURCE_SPANS = 1;
    private static final int RESOURCE_SPANS_RESOURCE = 1;
    private static final int RESOURCE_SPANS_SCOPE_SPANS = 2;
    private static final int RESOURCE_ATTRIBUTES = 1;
    private static final int SCOPE_SPANS_SCOPE = 1;
    private static final int SCOPE_SPANS_SPANS = 2;
    private static final int SCOPE_NAME = 1;
    private static final int SCOPE_VERSION = 2;
    // Span
    private static final int SPAN_TRACE_ID = 1;
    private static final int SPAN_SPAN_ID = 2;
    private static final int SPAN_PARENT_SPAN_ID = 4;
    private static final int SPAN_NAME = 5;
    private static final int SPAN_KIND = 6;
    private static final int SPAN_START = 7;
    private static final int SPAN_END = 8;
    private static final int SPAN_ATTRIBUTES = 9;
    private static final int SPAN_EVENTS = 11;
    private static final int SPAN_STATUS = 15;
    private static final int EVENT_TIME = 1;
    private static final int EVENT_NAME = 2;
    private static final int EVENT_ATTRIBUTES = 3;
    private static final int STATUS_CODE = 3;
    // KeyValue / AnyValue
    private static final int KV_KEY = 1;
    private static final int KV_VALUE = 2;
    private static final int ANY_STRING = 1;
    private static final int ANY_BOOL = 2;
    private static final int ANY_INT = 3;
    private static final int ANY_DOUBLE = 4;

    private final TraceConfig config;
    private final ProtoWriter root = new ProtoWriter(64 * 1024);
    private byte[] resource;

    OtlpProtoEncoder(TraceConfig config) {
        this.config = config;
    }

    @Override
    public String contentType() {
        return "application/x-protobuf";
    }

    @Override
    public byte[] encode(List<Span> spans) {
        root.reset();
        ProtoWriter resourceSpans = root.begin();
        writeResource(resourceSpans);
        ProtoWriter scopeSpans = resourceSpans.begin();
        ProtoWriter scope = scopeSpans.begin();
        scope.string(SCOPE_NAME, SpanModel.SCOPE_NAME);
        scope.string(SCOPE_VERSION, SpanModel.sdkVersion());
        scopeSpans.end(SCOPE_SPANS_SCOPE, scope);
        for (Span span : spans) {
            ProtoWriter s = scopeSpans.begin();
            writeSpan(s, span);
            scopeSpans.end(SCOPE_SPANS_SPANS, s);
        }
        resourceSpans.end(RESOURCE_SPANS_SCOPE_SPANS, scopeSpans);
        root.end(REQUEST_RESOURCE_SPANS, resourceSpans);
        return root.toByteArray();
    }

    private void writeResource(ProtoWriter resourceSpans) {
        if (resource == null) {
            ProtoWriter scratch = new ProtoWriter(512);
            SpanModel.resource(config, new Attributes(scratch, RESOURCE_ATTRIBUTES));
            resource = scratch.toByteArray();
        }
        ProtoWriter r = resourceSpans.begin();
        // Pre-encoded attribute list; written verbatim as the Resource message body.
        r.rawBytes(resource);
        resourceSpans.end(RESOURCE_SPANS_RESOURCE, r);
    }

    private static void writeSpan(ProtoWriter w, Span span) {
        w.idBytes(SPAN_TRACE_ID, span.trace().traceIdHigh(), span.trace().traceIdLow());
        w.idBytes(SPAN_SPAN_ID, span.spanId());
        if (span.parentSpanId() != 0) {
            w.idBytes(SPAN_PARENT_SPAN_ID, span.parentSpanId());
        }
        w.string(SPAN_NAME, span.site().operation());
        w.uint64(SPAN_KIND, SpanModel.kind(span.site().kind()));
        w.fixed64(SPAN_START, span.startEpochNanos());
        w.fixed64(SPAN_END, SpanModel.endEpochNanos(span));
        SpanModel.spanAttributes(span, new Attributes(w, SPAN_ATTRIBUTES));
        if (span.exceptionType() != null) {
            ProtoWriter e = w.begin();
            e.fixed64(EVENT_TIME, SpanModel.endEpochNanos(span));
            e.string(EVENT_NAME, "exception");
            SpanModel.exceptionAttributes(span, new Attributes(e, EVENT_ATTRIBUTES));
            w.end(SPAN_EVENTS, e);
        }
        for (Span step : span.events()) {
            ProtoWriter e = w.begin();
            e.fixed64(EVENT_TIME, step.startEpochNanos());
            e.string(EVENT_NAME, step.site().operation());
            SpanModel.stepAttributes(step, new Attributes(e, EVENT_ATTRIBUTES));
            w.end(SPAN_EVENTS, e);
        }
        ProtoWriter status = w.begin();
        status.uint64(STATUS_CODE, span.isError() ? SpanModel.STATUS_ERROR : SpanModel.STATUS_OK);
        w.end(SPAN_STATUS, status);
    }

    /** Writes each attribute as a repeated {@code KeyValue} field of the enclosing message. */
    private static final class Attributes implements AttributeSink {
        private final ProtoWriter parent;
        private final int field;

        Attributes(ProtoWriter parent, int field) {
            this.parent = parent;
            this.field = field;
        }

        @Override
        public void string(String key, String value) {
            ProtoWriter kv = parent.begin();
            kv.string(KV_KEY, key);
            ProtoWriter any = kv.begin();
            any.string(ANY_STRING, value);
            kv.end(KV_VALUE, any);
            parent.end(field, kv);
        }

        @Override
        public void integer(String key, long value) {
            ProtoWriter kv = parent.begin();
            kv.string(KV_KEY, key);
            ProtoWriter any = kv.begin();
            any.uint64(ANY_INT, value);
            kv.end(KV_VALUE, any);
            parent.end(field, kv);
        }

        @Override
        public void floating(String key, double value) {
            ProtoWriter kv = parent.begin();
            kv.string(KV_KEY, key);
            ProtoWriter any = kv.begin();
            any.doubleValue(ANY_DOUBLE, value);
            kv.end(KV_VALUE, any);
            parent.end(field, kv);
        }

        @Override
        public void bool(String key, boolean value) {
            ProtoWriter kv = parent.begin();
            kv.string(KV_KEY, key);
            ProtoWriter any = kv.begin();
            any.bool(ANY_BOOL, value);
            kv.end(KV_VALUE, any);
            parent.end(field, kv);
        }
    }
}
