package org.util.trace.core.export.otlp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.util.trace.core.TestSupport.site;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Status;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.util.trace.core.Fixtures.OrderService;
import org.util.trace.core.Ids;
import org.util.trace.core.Span;
import org.util.trace.core.TestSupport;
import org.util.trace.core.TestSupport.CapturingExporter;
import org.util.trace.core.TraceRuntime;
import org.util.trace.core.Tracer;
import org.util.trace.core.config.TraceConfig;

class OtlpEncodersTest {

    private static TraceConfig config;
    private static List<Span> spans;

    /** Records: entry (OK, with a step event) → reserve (ERROR with exception). */
    @BeforeAll
    static void record() {
        config = TestSupport.config("trace.level", "L2_STEP", "trace.service.version", "2.1.0",
                "trace.environment", "test", "trace.capture.exception.message", "true");
        CapturingExporter exporter = new CapturingExporter();
        TraceRuntime runtime = TraceRuntime.create(config, exporter);
        runtime.start();
        Tracer tracer = runtime.tracer();
        Span root = tracer.begin(site(OrderService.class, "placeOrder"),
                new Object[] {"t-042", "00D5g000004XyZ", List.of("a", "b")});
        Span step = tracer.begin(site(OrderService.class, "applyRules"), new Object[] {2});
        tracer.end(step, null, null);
        Span child = tracer.begin(site(OrderService.class, "reserve"), new Object[] {"SKU \"1\"\n"});
        tracer.end(child, null, new IllegalArgumentException("out of stock"));
        tracer.end(root, "ok", null);
        runtime.shutdown();
        spans = exporter.spans();
        assertThat(spans).hasSize(2);
    }

    // ---------------------------------------------------------------- protobuf

    @Test
    void protobufIsValidOtlp() throws Exception {
        byte[] bytes = new OtlpProtoEncoder(config).encode(spans);
        ExportTraceServiceRequest request = ExportTraceServiceRequest.parseFrom(bytes);

        assertThat(request.getResourceSpansCount()).isEqualTo(1);
        ResourceSpans rs = request.getResourceSpans(0);
        Map<String, AnyValue> resource = attrs(rs.getResource().getAttributesList());
        assertThat(resource.get("service.name").getStringValue()).isEqualTo("test-service");
        assertThat(resource.get("service.version").getStringValue()).isEqualTo("2.1.0");
        assertThat(resource.get("deployment.environment").getStringValue()).isEqualTo("test");
        assertThat(resource.get("process.pid").getIntValue()).isEqualTo(ProcessHandle.current().pid());
        assertThat(resource.get("tsdk.schema.version").getIntValue()).isEqualTo(1);
        assertThat(resource).containsKeys("host.name", "telemetry.sdk.name", "telemetry.sdk.version");

        ScopeSpans ss = rs.getScopeSpans(0);
        assertThat(ss.getScope().getName()).isEqualTo("trace-sdk");
        assertThat(ss.getSpansCount()).isEqualTo(2);

        io.opentelemetry.proto.trace.v1.Span child = ss.getSpans(0);
        io.opentelemetry.proto.trace.v1.Span root = ss.getSpans(1);
        assertThat(root.getName()).isEqualTo("orders.service.place_order");
        assertThat(root.getKind()).isEqualTo(io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_SERVER);
        assertThat(root.getTraceId().size()).isEqualTo(16);
        assertThat(root.getSpanId().size()).isEqualTo(8);
        assertThat(root.getParentSpanId().isEmpty()).isTrue();
        assertThat(child.getTraceId()).isEqualTo(root.getTraceId());
        assertThat(child.getParentSpanId()).isEqualTo(root.getSpanId());
        assertThat(hex(root.getTraceId().toByteArray()))
                .isEqualTo(Ids.hex(spans.get(1).trace().traceIdHigh()) + Ids.hex(spans.get(1).trace().traceIdLow()));
        assertThat(root.getEndTimeUnixNano()).isGreaterThanOrEqualTo(root.getStartTimeUnixNano());
        assertThat(root.getStartTimeUnixNano()).isGreaterThan(1_700_000_000_000_000_000L);
        assertThat(root.getStatus().getCode()).isEqualTo(Status.StatusCode.STATUS_CODE_OK);

        Map<String, AnyValue> ra = attrs(root.getAttributesList());
        assertThat(ra.get("tenant.id").getStringValue()).isEqualTo("t-042");
        assertThat(ra.get("org.id").getStringValue()).isEqualTo("00D5g000004XyZ");
        assertThat(ra.get("component").getStringValue()).isEqualTo("orders");
        assertThat(ra.get("code.namespace").getStringValue()).isEqualTo(OrderService.class.getName());
        assertThat(ra.get("code.function").getStringValue()).isEqualTo("placeOrder");
        assertThat(ra.get("trace.seq").getIntValue()).isEqualTo(1);
        assertThat(ra.get("trace.level").getIntValue()).isEqualTo(2);
        assertThat(ra.get("records.count").getIntValue()).isEqualTo(2);
        assertThat(ra.get("retention.class").getStringValue()).isEqualTo("standard-30d");
        assertThat(ra.get("execution.complete").getBoolValue()).isTrue();
        assertThat(ra.get("duration.ns").getIntValue()).isEqualTo(spans.get(1).durationNanos());
        assertThat(ra).containsKey("thread.name");

        assertThat(root.getEventsCount()).isEqualTo(1);
        assertThat(root.getEvents(0).getName()).isEqualTo("orders.service.apply_rules");
        assertThat(attrs(root.getEvents(0).getAttributesList()).get("trace.seq").getIntValue()).isEqualTo(2);

        assertThat(child.getStatus().getCode()).isEqualTo(Status.StatusCode.STATUS_CODE_ERROR);
        assertThat(attrs(child.getAttributesList()).get("app.sku").getStringValue()).isEqualTo("SKU \"1\"\n");
        assertThat(child.getEventsCount()).isEqualTo(1);
        Map<String, AnyValue> ex = attrs(child.getEvents(0).getAttributesList());
        assertThat(child.getEvents(0).getName()).isEqualTo("exception");
        assertThat(ex.get("exception.type").getStringValue()).isEqualTo("java.lang.IllegalArgumentException");
        assertThat(ex.get("exception.message").getStringValue()).isEqualTo("out of stock");
        assertThat(ex.get("exception.stacktrace").getStringValue()).startsWith("java.lang.IllegalArgumentException");
    }

    @Test
    void protobufEncoderIsReusableAcrossBatches() throws Exception {
        OtlpProtoEncoder encoder = new OtlpProtoEncoder(config);
        byte[] first = encoder.encode(spans);
        byte[] second = encoder.encode(spans.subList(0, 1));
        assertThat(ExportTraceServiceRequest.parseFrom(first).getResourceSpans(0).getScopeSpans(0).getSpansCount())
                .isEqualTo(2);
        assertThat(ExportTraceServiceRequest.parseFrom(second).getResourceSpans(0).getScopeSpans(0).getSpansCount())
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------- JSON

    @Test
    void jsonFollowsTheOtlpJsonMapping() throws Exception {
        JsonNode json = new ObjectMapper().readTree(new OtlpJsonEncoder(config).encode(spans));
        JsonNode rs = json.get("resourceSpans").get(0);
        assertThat(jsonAttrs(rs.get("resource").get("attributes")).get("service.name").get("stringValue").asText())
                .isEqualTo("test-service");
        JsonNode scopeSpans = rs.get("scopeSpans").get(0);
        assertThat(scopeSpans.get("scope").get("name").asText()).isEqualTo("trace-sdk");

        JsonNode child = scopeSpans.get("spans").get(0);
        JsonNode root = scopeSpans.get("spans").get(1);
        assertThat(root.get("traceId").asText()).hasSize(32).matches("[0-9a-f]+");
        assertThat(root.get("spanId").asText()).hasSize(16);
        assertThat(root.has("parentSpanId")).isFalse();
        assertThat(child.get("parentSpanId").asText()).isEqualTo(root.get("spanId").asText());
        assertThat(root.get("name").asText()).isEqualTo("orders.service.place_order");
        assertThat(root.get("kind").asInt()).isEqualTo(2);
        assertThat(root.get("startTimeUnixNano").isTextual()).isTrue();
        assertThat(root.get("status").get("code").asInt()).isEqualTo(1);
        Map<String, JsonNode> ra = jsonAttrs(root.get("attributes"));
        assertThat(ra.get("records.count").get("intValue").asText()).isEqualTo("2");
        assertThat(ra.get("execution.complete").get("boolValue").asBoolean()).isTrue();
        assertThat(root.get("events").get(0).get("name").asText()).isEqualTo("orders.service.apply_rules");

        assertThat(jsonAttrs(child.get("attributes")).get("app.sku").get("stringValue").asText())
                .isEqualTo("SKU \"1\"\n");
        assertThat(child.get("status").get("code").asInt()).isEqualTo(2);
        assertThat(child.get("events").get(0).get("name").asText()).isEqualTo("exception");
    }

    private static Map<String, AnyValue> attrs(List<KeyValue> list) {
        Map<String, AnyValue> out = new HashMap<>();
        for (KeyValue kv : list) {
            assertThat(out.put(kv.getKey(), kv.getValue())).as("duplicate key %s", kv.getKey()).isNull();
        }
        return out;
    }

    private static Map<String, JsonNode> jsonAttrs(JsonNode array) {
        Map<String, JsonNode> out = new HashMap<>();
        for (JsonNode kv : array) {
            out.put(kv.get("key").asText(), kv.get("value"));
        }
        return out;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
