package org.util.trace.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.util.trace.core.Stats;
import org.util.trace.spring.autoconfigure.TraceSdkAutoConfiguration;

/** Shared helpers: the demo's service beans plus the SDK, without the web layer. */
final class TestApp {

    private TestApp() {
    }

    static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, TraceSdkAutoConfiguration.class))
                .withUserConfiguration(OrderService.class, InventoryService.class, PaymentService.class);
    }

    /** Properties that keep failure tests fast: short timeouts, few retries, quick flushes. */
    static String[] fast(String endpoint, String... overrides) {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("spring.application.name", "orders-demo-test");
        props.put("trace.exporter.endpoint", endpoint);
        props.put("trace.exporter.protocol", "http/json");
        props.put("trace.exporter.compression", "none");
        props.put("trace.exporter.timeout.ms", "300");
        props.put("trace.exporter.retry.max.attempts", "2");
        props.put("trace.batch.max.delay.ms", "50");
        for (String override : overrides) { // "key=value", replacing the default above
            int eq = override.indexOf('=');
            props.put(override.substring(0, eq), override.substring(eq + 1));
        }
        return props.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new);
    }

    /** Places {@code count} orders for {@code tenant}; every call must succeed. Returns the slowest call in ms. */
    static long placeOrders(OrderService orders, String tenant, int count, int items) {
        List<String> skus = java.util.stream.IntStream.rangeClosed(1, items).mapToObj(i -> "SKU-" + i).toList();
        long slowest = 0;
        for (int i = 0; i < count; i++) {
            long start = System.nanoTime();
            assertThat(orders.placeOrder(tenant, "00Dacme0001", skus, false)).isEqualTo(items);
            slowest = Math.max(slowest, (System.nanoTime() - start) / 1_000_000);
        }
        return slowest;
    }

    /** Every span that was started is either exported or counted as dropped (valid once the context is closed). */
    static void assertAllSpansAccountedFor(Stats stats) {
        assertThat(stats.spansStarted()).as("spans started").isPositive();
        assertThat(stats.spansExported() + stats.droppedBufferFull() + stats.droppedExportFailed())
                .as("exported + dropped(buffer full) + dropped(export failed)")
                .isEqualTo(stats.spansStarted());
    }

    static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for: " + what);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for: " + what);
            }
        }
    }
}
