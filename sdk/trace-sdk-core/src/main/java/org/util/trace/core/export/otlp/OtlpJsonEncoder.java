package org.util.trace.core.export.otlp;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.util.trace.core.Ids;
import org.util.trace.core.Span;
import org.util.trace.core.config.TraceConfig;

/**
 * OTLP/JSON encoder (OTLP JSON mapping: lowerCamelCase keys, hex trace/span ids, 64-bit integers
 * as strings, enums as integers).
 */
final class OtlpJsonEncoder implements OtlpEncoder {

    private final TraceConfig config;
    private final StringBuilder out = new StringBuilder(64 * 1024);
    private String resource;

    OtlpJsonEncoder(TraceConfig config) {
        this.config = config;
    }

    @Override
    public String contentType() {
        return "application/json";
    }

    @Override
    public byte[] encode(List<Span> spans) {
        out.setLength(0);
        if (resource == null) {
            StringBuilder r = new StringBuilder(512);
            r.append("{\"attributes\":[");
            SpanModel.resource(config, new Attributes(r));
            r.append("]}");
            resource = r.toString();
        }
        out.append("{\"resourceSpans\":[{\"resource\":").append(resource)
                .append(",\"scopeSpans\":[{\"scope\":{\"name\":");
        string(out, SpanModel.SCOPE_NAME);
        out.append(",\"version\":");
        string(out, SpanModel.sdkVersion());
        out.append("},\"spans\":[");
        for (int i = 0; i < spans.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            span(spans.get(i));
        }
        out.append("]}]}]}");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void span(Span span) {
        out.append("{\"traceId\":\"");
        Ids.appendHex(out, span.trace().traceIdHigh());
        Ids.appendHex(out, span.trace().traceIdLow());
        out.append("\",\"spanId\":\"");
        Ids.appendHex(out, span.spanId());
        out.append('"');
        if (span.parentSpanId() != 0) {
            out.append(",\"parentSpanId\":\"");
            Ids.appendHex(out, span.parentSpanId());
            out.append('"');
        }
        out.append(",\"name\":");
        string(out, span.site().operation());
        out.append(",\"kind\":").append(SpanModel.kind(span.site().kind()));
        out.append(",\"startTimeUnixNano\":\"").append(span.startEpochNanos()).append('"');
        out.append(",\"endTimeUnixNano\":\"").append(SpanModel.endEpochNanos(span)).append('"');
        out.append(",\"attributes\":[");
        SpanModel.spanAttributes(span, new Attributes(out));
        out.append(']');

        boolean hasException = span.exceptionType() != null;
        if (hasException || !span.events().isEmpty()) {
            out.append(",\"events\":[");
            boolean first = true;
            if (hasException) {
                event(SpanModel.endEpochNanos(span), "exception");
                SpanModel.exceptionAttributes(span, new Attributes(out));
                out.append("]}");
                first = false;
            }
            for (Span step : span.events()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                event(step.startEpochNanos(), step.site().operation());
                SpanModel.stepAttributes(step, new Attributes(out));
                out.append("]}");
            }
            out.append(']');
        }
        out.append(",\"status\":{\"code\":")
                .append(span.isError() ? SpanModel.STATUS_ERROR : SpanModel.STATUS_OK)
                .append("}}");
    }

    /** Opens an event object up to its attribute array. */
    private void event(long timeUnixNano, String name) {
        out.append("{\"timeUnixNano\":\"").append(timeUnixNano).append("\",\"name\":");
        string(out, name);
        out.append(",\"attributes\":[");
    }

    static void string(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    /** Appends {@code {"key":..,"value":{..}}} objects, comma-separated. */
    private static final class Attributes implements AttributeSink {
        private final StringBuilder sb;
        private boolean first = true;

        Attributes(StringBuilder sb) {
            this.sb = sb;
        }

        private void key(String key) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"key\":");
            OtlpJsonEncoder.string(sb, key);
            sb.append(",\"value\":{");
        }

        @Override
        public void string(String key, String value) {
            key(key);
            sb.append("\"stringValue\":");
            OtlpJsonEncoder.string(sb, value);
            sb.append("}}");
        }

        @Override
        public void integer(String key, long value) {
            key(key);
            sb.append("\"intValue\":\"").append(value).append("\"}}");
        }

        @Override
        public void floating(String key, double value) {
            key(key);
            sb.append("\"doubleValue\":");
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                sb.append('"').append(Double.isNaN(value) ? "NaN" : value > 0 ? "Infinity" : "-Infinity").append('"');
            } else {
                sb.append(value);
            }
            sb.append("}}");
        }

        @Override
        public void bool(String key, boolean value) {
            key(key);
            sb.append("\"boolValue\":").append(value).append("}}");
        }
    }
}
