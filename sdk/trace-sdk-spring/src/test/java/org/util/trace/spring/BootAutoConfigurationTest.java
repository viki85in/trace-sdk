package org.util.trace.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.util.trace.spring.FakeCollector.attribute;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.util.trace.core.TraceRuntime;
import org.util.trace.spring.Fixtures.OrderService;
import org.util.trace.spring.Fixtures.PaymentGateway;
import org.util.trace.spring.autoconfigure.TraceSdkAutoConfiguration;

/** Spring Boot: adding the jar is enough. */
class BootAutoConfigurationTest {

    private final FakeCollector collector = new FakeCollector();

    @AfterEach
    void stop() {
        collector.close();
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, TraceSdkAutoConfiguration.class))
                .withUserConfiguration(TestApp.class);
    }

    private static String[] pairs(Map<String, Object> props) {
        return props.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new);
    }

    @Test
    void autoConfiguredWithSpringApplicationNameAndGzippedProtobufDefaultsOverridden() {
        Map<String, Object> props = collector.properties();
        props.remove("trace.service.name");
        props.put("spring.application.name", "boot-app");
        runner().withPropertyValues(pairs(props)).run(ctx -> {
            assertThat(ctx).hasSingleBean(TraceRuntime.class);
            assertThat(ctx.getBean(TraceRuntime.class).config().serviceName()).isEqualTo("boot-app");
            assertThat(AopUtils.isCglibProxy(ctx.getBean(PaymentGateway.class))).isTrue();
            ctx.getBean(OrderService.class).placeOrder("t-9", "00Dyy", List.of("a"));
        });
        assertThat(collector.names()).containsExactlyInAnyOrder(
                "shop.inventory.reserve", "shop.payments.charge", "shop.orders.place_order");
        assertThat(attribute(collector.span("shop.payments.charge"), "tenant.id")).isEqualTo("t-9");
    }

    @Test
    void perOperationLevelOverrideFromBootProperties() {
        runner().withPropertyValues(pairs(collector.properties(
                "trace.level.shop.orders.place_order", "L2_STEP"))).run(ctx ->
                ctx.getBean(OrderService.class).placeOrder("t-9", "00Dyy", List.of("a")));
        assertThat(collector.span("shop.orders.place_order").path("events").get(0).path("name").asText())
                .isEqualTo("shop.inventory.check");
    }

    @Test
    void contextStartsWithoutAServiceName() {
        runner().withPropertyValues("trace.exporter.endpoint=" + collector.endpoint()).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(TraceRuntime.class).isActive()).isFalse();
            ctx.getBean(OrderService.class).placeOrder("t-9", "00Dyy", List.of("a"));
        });
        assertThat(collector.spans()).isEmpty();
    }

    @Test
    void autoConfigurationIsRegisteredForBoot() throws Exception {
        String imports = new String(getClass().getClassLoader().getResourceAsStream(
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports").readAllBytes());
        assertThat(imports).contains(TraceSdkAutoConfiguration.class.getName());
    }
}
