package org.util.trace.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.util.trace.demo.TestApp.await;
import static org.util.trace.demo.TestApp.fast;
import static org.util.trace.demo.TestApp.placeOrders;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.util.trace.core.TraceRuntime;
import org.util.trace.core.config.TraceConfig;

/** Scenarios TC-C1..C7: required parameters, defaults, invalid values and missing tenant/org. */
class ConfigurationTest {

    private final ControllableCollector collector = new ControllableCollector();

    @AfterEach
    void stopCollector() {
        collector.stop();
    }

    @Test
    @DisplayName("TC-C1 no service name: tracing is disabled loudly, the application still works")
    void noServiceName() {
        collector.start();
        try (LogWatcher log = new LogWatcher()) {
            TestApp.runner().withPropertyValues("trace.exporter.endpoint=" + collector.endpoint()).run(context -> {
                TraceRuntime runtime = context.getBean(TraceRuntime.class);
                OrderService orders = context.getBean(OrderService.class);

                assertThat(runtime.isActive()).isFalse();
                assertThat(AopUtils.isAopProxy(orders)).as("bean is not proxied when tracing is off").isFalse();
                placeOrders(orders, "acme", 3, 2);
                assertThat(runtime.stats().spansStarted()).isZero();
            });
            assertThat(log.contains("SEVERE: trace-sdk: trace.service.name (or spring.application.name) is not set"))
                    .as("log: %s", log.messages()).isTrue();
        }
        assertThat(collector.spans()).isEmpty();
    }

    @Test
    @DisplayName("TC-C2 blank service name counts as missing")
    void blankServiceName() {
        TestApp.runner().withPropertyValues("trace.service.name=   ").run(context ->
                assertThat(context.getBean(TraceRuntime.class).isActive()).isFalse());
    }

    @Test
    @DisplayName("TC-C3 spring.application.name is used when trace.service.name is not set")
    void springApplicationNameFallback() {
        TestApp.runner().withPropertyValues("spring.application.name=from-spring").run(context -> {
            TraceRuntime runtime = context.getBean(TraceRuntime.class);
            assertThat(runtime.isActive()).isTrue();
            assertThat(runtime.config().serviceName()).isEqualTo("from-spring");
        });
    }

    @Test
    @DisplayName("TC-C4 trace.service.name wins over spring.application.name")
    void traceServiceNameWins() {
        TestApp.runner().withPropertyValues("spring.application.name=from-spring", "trace.service.name=explicit")
                .run(context -> assertThat(context.getBean(TraceRuntime.class).config().serviceName())
                        .isEqualTo("explicit"));
    }

    @Test
    @DisplayName("TC-C5 only the service name set: every other setting takes its default")
    void defaultsApply() {
        TestApp.runner().withPropertyValues("trace.service.name=defaults-only").run(context -> {
            TraceConfig config = context.getBean(TraceRuntime.class).config();
            assertThat(context.getBean(TraceRuntime.class).isActive()).isTrue();
            assertThat(config.endpoint().toString()).isEqualTo("http://localhost:4318/v1/traces");
            assertThat(config.protocol().toString()).containsIgnoringCase("proto");
            assertThat(config.gzip()).isTrue();
            assertThat(config.bufferMaxSpans()).isEqualTo(32_768);
            assertThat(config.batchMaxSpans()).isEqualTo(512);
            assertThat(config.batchMaxDelayMs()).isEqualTo(1_000);
            assertThat(config.timeoutMs()).isEqualTo(10_000);
            assertThat(config.retryMaxAttempts()).isEqualTo(5);
            assertThat(config.level()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("TC-C6 invalid values are logged and ignored; the application starts with defaults")
    void invalidValues() {
        try (LogWatcher log = new LogWatcher()) {
            TestApp.runner().withPropertyValues("trace.service.name=invalid-values",
                    "trace.exporter.endpoint=ftp://nowhere", "trace.level=L9", "trace.buffer.max.spans=-5")
                    .run(context -> {
                        TraceRuntime runtime = context.getBean(TraceRuntime.class);
                        TraceConfig config = runtime.config();
                        assertThat(runtime.isActive()).isTrue();
                        assertThat(config.endpoint().toString()).isEqualTo("http://localhost:4318/v1/traces");
                        assertThat(config.level()).isEqualTo(1);
                        assertThat(config.bufferMaxSpans()).isEqualTo(32_768);
                    });
            List<String> warnings = log.messages().stream().filter(m -> m.startsWith("WARNING")).toList();
            assertThat(warnings).anyMatch(m -> m.contains("trace.exporter.endpoint"));
            assertThat(warnings).anyMatch(m -> m.contains("trace.level"));
            assertThat(warnings).anyMatch(m -> m.contains("trace.buffer.max.spans"));
        }
    }

    @Test
    @DisplayName("TC-C7 tenant and org not supplied: spans are still exported, marked _missing_ and counted")
    void missingTenantAndOrg() {
        collector.start();
        TestApp.runner().withPropertyValues(fast(collector.endpoint())).run(context -> {
            OrderService orders = context.getBean(OrderService.class);
            assertThat(orders.placeOrder(null, null, List.of("SKU-1"), false)).isEqualTo(1);

            java.util.function.Supplier<java.util.Optional<com.fasterxml.jackson.databind.JsonNode>> root = () ->
                    collector.spans().stream().filter(s -> s.path("name").asText().contains("place_order")).findFirst();
            await("the order span to arrive", () -> root.get().isPresent());
            var span = root.get().orElseThrow();
            assertThat(ControllableCollector.attribute(span, "tenant.id")).isEqualTo("_missing_");
            assertThat(ControllableCollector.attribute(span, "org.id")).isEqualTo("_missing_");
            assertThat(context.getBean(TraceRuntime.class).stats().missingTenant()).isPositive();
        });
    }
}
