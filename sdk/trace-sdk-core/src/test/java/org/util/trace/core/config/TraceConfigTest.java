package org.util.trace.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.util.trace.core.Levels;

class TraceConfigTest {

    private static TraceConfig load(Map<String, String> sys, Map<String, String> env, Map<String, String> app) {
        return TraceConfig.load(ConfigSource.map("sys", sys), ConfigSource.environment(env),
                List.of(ConfigSource.map("app", app)));
    }

    @Test
    void defaults() {
        TraceConfig c = load(Map.of(), Map.of(), Map.of("trace.service.name", "svc"));
        assertThat(c.enabled()).isTrue();
        assertThat(c.level()).isEqualTo(Levels.L1);
        assertThat(c.endpoint().toString()).isEqualTo("http://localhost:4318/v1/traces");
        assertThat(c.protocol()).isEqualTo(TraceConfig.Protocol.HTTP_PROTOBUF);
        assertThat(c.gzip()).isTrue();
        assertThat(c.bufferMaxSpans()).isEqualTo(32768);
        assertThat(c.batchMaxSpans()).isEqualTo(512);
        assertThat(c.retentionClass()).isEqualTo("standard-30d");
        assertThat(c.captureExceptionMessage()).isFalse();
        assertThat(c.stacktraceMaxFrames()).isEqualTo(20);
    }

    @Test
    void precedenceIsSystemPropertyThenEnvironmentThenApplication() {
        TraceConfig c = load(
                Map.of("trace.level", "L2_STEP"),
                Map.of("TRACE_LEVEL", "L0_ENTRY", "TRACE_SERVICE_NAME", "from-env"),
                Map.of("trace.level", "L0_ENTRY", "trace.service.name", "from-app", "trace.batch.max.spans", "64"));
        assertThat(c.level()).isEqualTo(Levels.L2);
        assertThat(c.serviceName()).isEqualTo("from-env");
        assertThat(c.batchMaxSpans()).isEqualTo(64);
    }

    @Test
    void invalidValuesFallThroughToTheNextSource() {
        TraceConfig c = load(Map.of("trace.batch.max.spans", "-3", "trace.exporter.endpoint", "ftp://x"),
                Map.of(), Map.of("trace.service.name", "svc", "trace.batch.max.spans", "128"));
        assertThat(c.batchMaxSpans()).isEqualTo(128);
        assertThat(c.endpoint().toString()).isEqualTo(TraceConfig.DEFAULT_ENDPOINT);
    }

    @Test
    void serviceNameFallsBackToSpringApplicationName() {
        assertThat(load(Map.of(), Map.of(), Map.of("spring.application.name", "boot-app")).serviceName())
                .isEqualTo("boot-app");
        assertThat(load(Map.of(), Map.of(), Map.of()).serviceName()).isNull();
    }

    @Test
    void headersAndProtocol() {
        TraceConfig c = load(Map.of(), Map.of(), Map.of("trace.service.name", "svc",
                "trace.exporter.headers", "Authorization=Bearer abc, X-Tenant=t1",
                "trace.exporter.protocol", "http/json", "trace.exporter.compression", "none"));
        assertThat(c.headers()).containsExactly(Map.entry("Authorization", "Bearer abc"), Map.entry("X-Tenant", "t1"));
        assertThat(c.protocol()).isEqualTo(TraceConfig.Protocol.HTTP_JSON);
        assertThat(c.gzip()).isFalse();
    }

    @Test
    void perOperationOverridesHigherSourcesWin() {
        TraceConfig c = load(Map.of("trace.level.orders.service.place_order", "L2_STEP"), Map.of(),
                Map.of("trace.service.name", "svc", "trace.level.orders.service.place_order", "L0_ENTRY",
                        "trace.level.orders.service.cancel", "L2", "trace.level.bad", "nope"));
        assertThat(c.levelOverride("orders.service.place_order")).isEqualTo(Levels.L2);
        assertThat(c.levelOverride("orders.service.cancel")).isEqualTo(Levels.L2);
        assertThat(c.levelOverride("bad")).isEqualTo(Levels.OFF);
        assertThat(c.levelOverride("unknown")).isEqualTo(Levels.OFF);
    }

    @Test
    void runtimeUpdatesApplyToDynamicKeysOnly() {
        TraceConfig c = load(Map.of("trace.level", "L2_STEP"), Map.of(), Map.of("trace.service.name", "svc"));
        assertThat(c.update("trace.level", "L0_ENTRY")).as("runtime beats system properties").isTrue();
        assertThat(c.level()).isEqualTo(Levels.L0);
        assertThat(c.update("trace.level", "L9")).isFalse();
        assertThat(c.level()).isEqualTo(Levels.L0);
        assertThat(c.update("trace.batch.max.spans", "1")).as("static key").isFalse();
        assertThat(c.update("trace.level.orders.x", "L2_STEP")).isTrue();
        assertThat(c.levelOverride("orders.x")).isEqualTo(Levels.L2);
        assertThat(c.update("trace.capture.exception.message", "true")).isTrue();
        assertThat(c.captureExceptionMessage()).isTrue();
    }

    @Test
    void configFileIsTheLowestExplicitSource(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("trace.properties");
        Files.writeString(file, "trace.service.name=from-file\ntrace.level=L2_STEP\ntrace.batch.max.spans=7\n");
        TraceConfig c = load(Map.of("trace.config.file", file.toString()), Map.of(),
                Map.of("trace.batch.max.spans", "9"));
        assertThat(c.serviceName()).isEqualTo("from-file");
        assertThat(c.level()).isEqualTo(Levels.L2);
        assertThat(c.batchMaxSpans()).isEqualTo(9);
    }
}
