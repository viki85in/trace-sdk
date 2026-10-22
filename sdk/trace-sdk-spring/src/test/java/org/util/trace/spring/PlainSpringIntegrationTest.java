package org.util.trace.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.util.trace.spring.FakeCollector.attribute;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.util.trace.core.TraceRuntime;
import org.util.trace.spring.Fixtures.FinalService;
import org.util.trace.spring.Fixtures.InventoryService;
import org.util.trace.spring.Fixtures.OrderService;
import org.util.trace.spring.Fixtures.PaymentGateway;

/** A plain (non-Boot) Spring context that opts in with {@code @Import(TraceSdkConfiguration.class)}. */
class PlainSpringIntegrationTest {

    @Configuration(proxyBeanMethods = false)
    @Import({TraceSdkConfiguration.class, TestApp.class})
    static class App {
    }

    private final FakeCollector collector = new FakeCollector();

    @AfterEach
    void stop() {
        collector.close();
    }

    /** Runs {@code body} in a fresh context, then closes it so every span is flushed to the collector. */
    private void run(Consumer<AnnotationConfigApplicationContext> body, String... properties) {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("test", collector.properties(properties)));
            ctx.register(App.class);
            ctx.refresh();
            body.accept(ctx);
        }
    }

    @Test
    void recordsANestedTraceWithInheritedContext() {
        run(ctx -> assertThat(ctx.getBean(OrderService.class)
                .placeOrder("t-1", "00Dxx", List.of("a", "b"))).isEqualTo(2));

        assertThat(collector.names()).containsExactlyInAnyOrder(
                "shop.inventory.reserve", "shop.inventory.reserve", "shop.payments.charge", "shop.orders.place_order");
        JsonNode root = collector.span("shop.orders.place_order");
        assertThat(root.path("kind").asInt()).isEqualTo(2);
        assertThat(root.has("parentSpanId")).isFalse();
        assertThat(attribute(root, "records.count")).isEqualTo("2");
        assertThat(attribute(root, "execution.complete")).isEqualTo("true");

        for (JsonNode s : collector.spans()) {
            assertThat(s.path("traceId").asText()).isEqualTo(root.path("traceId").asText());
            assertThat(attribute(s, "tenant.id")).isEqualTo("t-1");
            assertThat(attribute(s, "org.id")).isEqualTo("00Dxx");
            if (s != root) {
                assertThat(s.path("parentSpanId").asText()).isEqualTo(root.path("spanId").asText());
            }
        }
        JsonNode charge = collector.span("shop.payments.charge");
        assertThat(charge.path("kind").asInt()).isEqualTo(3);
        assertThat(attribute(charge, "app.amount")).isEqualTo("2");
        assertThat(attribute(charge, "component")).isEqualTo("payments");
        JsonNode reserve = collector.spans().stream()
                .filter(s -> s.path("name").asText().equals("shop.inventory.reserve")).findFirst().orElseThrow();
        assertThat(attribute(reserve, "app.reserved")).isEqualTo("1");
        assertThat(root.has("events")).as("steps are off at L1").isFalse();
    }

    @Test
    void stepsBecomeEventsAtL2() {
        run(ctx -> ctx.getBean(OrderService.class).placeOrder("t-1", "00Dxx", List.of("a")), "trace.level", "L2_STEP");

        JsonNode root = collector.span("shop.orders.place_order");
        assertThat(root.path("events")).hasSize(1);
        assertThat(root.path("events").get(0).path("name").asText()).isEqualTo("shop.inventory.check");
        assertThat(attribute(root.path("events").get(0), "code.function")).isEqualTo("check");
        assertThat(collector.names()).doesNotContain("shop.inventory.check");
    }

    @Test
    void selfInvocationNoTraceAndAccessorsAreNotTraced() {
        run(ctx -> {
            OrderService orders = ctx.getBean(OrderService.class);
            orders.warmCache();
            orders.getStatus();
            orders.audit(); // through the proxy, outside any trace: a root L1 span is still allowed
            orders.placeOrder("t-1", "00Dxx", List.of());
        });
        assertThat(collector.names()).containsExactlyInAnyOrder(
                "shop.orders.audit", "shop.payments.charge", "shop.orders.place_order");
    }

    @Test
    void exceptionsMarkTheSpanAsFailedAndPropagate() {
        run(ctx -> assertThatThrownBy(() -> ctx.getBean(OrderService.class).cancel("t-1"))
                .isInstanceOf(IllegalStateException.class).hasMessage("already shipped"));

        JsonNode cancel = collector.span("shop.orders.cancel");
        assertThat(cancel.path("status").path("code").asInt()).isEqualTo(2);
        JsonNode event = cancel.path("events").get(0);
        assertThat(event.path("name").asText()).isEqualTo("exception");
        assertThat(attribute(event, "exception.type")).isEqualTo("java.lang.IllegalStateException");
        assertThat(attribute(event, "exception.message")).as("messages are off by default").isNull();
    }

    @Test
    void killSwitchAtRuntime() {
        run(ctx -> {
            TraceRuntime runtime = ctx.getBean(TraceRuntime.class);
            assertThat(runtime.config().update("trace.enabled", "false")).isTrue();
            ctx.getBean(OrderService.class).placeOrder("t-1", "00Dxx", List.of("a"));
            assertThat(runtime.config().update("trace.enabled", "true")).isTrue();
            ctx.getBean(PaymentGateway.class).charge(5);
        });
        assertThat(collector.names()).containsExactly("shop.payments.charge");
    }

    @Test
    void proxiesAreClassBasedAndFinalClassesAreLeftAlone() {
        run(ctx -> {
            assertThat(AopUtils.isCglibProxy(ctx.getBean(PaymentGateway.class))).isTrue();
            assertThat(AopUtils.isAopProxy(ctx.getBean(InventoryService.class))).isTrue();
            FinalService fin = ctx.getBean(FinalService.class);
            assertThat(AopUtils.isAopProxy(fin)).isFalse();
            assertThat(fin.hello()).isEqualTo("hi");
        });
        assertThat(collector.spans()).isEmpty();
    }

    @Test
    void withoutAServiceNameNothingIsProxied() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                    java.util.Map.of("trace.exporter.endpoint", collector.endpoint())));
            ctx.register(App.class);
            ctx.refresh();
            assertThat(ctx.getBean(TraceRuntime.class).isActive()).isFalse();
            assertThat(AopUtils.isAopProxy(ctx.getBean(OrderService.class))).isFalse();
            ctx.getBean(OrderService.class).placeOrder("t-1", "00Dxx", List.of("a"));
        }
        assertThat(collector.spans()).isEmpty();
    }
}
