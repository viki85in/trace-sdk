package org.util.trace.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.util.trace.demo.TestApp.assertAllSpansAccountedFor;
import static org.util.trace.demo.TestApp.await;
import static org.util.trace.demo.TestApp.fast;
import static org.util.trace.demo.TestApp.placeOrders;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.util.trace.core.Stats;
import org.util.trace.core.TraceRuntime;

/** Failure-mode scenarios TC-J1..J3 (collector down), TC-B1..B2 (buffer full) and TC-S1..S2 (collector rejects). */
class ResilienceTest {

    private final ControllableCollector collector = new ControllableCollector();

    @AfterEach
    void stopCollector() {
        collector.stop();
    }

    // ---------------------------------------------------------------- 1. collector (Jaeger) down

    @Test
    @DisplayName("TC-J1 collector down: the application keeps working and the loss is counted")
    void collectorDown() {
        TestApp.runner().withPropertyValues(fast(collector.endpoint())).run(context -> {
            OrderService orders = context.getBean(OrderService.class);
            Stats stats = context.getBean(TraceRuntime.class).stats();

            long slowest = placeOrders(orders, "acme", 10, 3);

            await("failed exports to be counted", () -> stats.droppedExportFailed() > 0);
            assertThat(stats.exportErrors()).isPositive();
            assertThat(stats.internalErrors()).isZero();
            assertThat(slowest).as("slowest business call (ms): tracing must not block it").isLessThan(1_000);
        });
    }

    @Test
    @DisplayName("TC-J2 collector comes back: new spans are delivered again")
    void collectorRecovers() {
        TestApp.runner().withPropertyValues(fast(collector.endpoint())).run(context -> {
            OrderService orders = context.getBean(OrderService.class);
            Stats stats = context.getBean(TraceRuntime.class).stats();

            placeOrders(orders, "acme", 3, 2);
            await("the outage to be noticed", () -> stats.droppedExportFailed() > 0);
            assertThat(collector.spans()).isEmpty();

            collector.start();
            placeOrders(orders, "acme", 3, 2);
            await("spans to reach the restarted collector", () -> !collector.spans().isEmpty());
            assertThat(stats.spansExported()).isPositive();
        });
    }

    @Test
    @DisplayName("TC-J3 collector down at shutdown: closing the context does not hang")
    void shutdownWithCollectorDown() {
        long start = System.nanoTime();
        TestApp.runner().withPropertyValues(fast(collector.endpoint())).run(context ->
                placeOrders(context.getBean(OrderService.class), "acme", 5, 3));
        assertThat((System.nanoTime() - start) / 1_000_000).as("ms to run and close").isLessThan(10_000);
    }

    // ---------------------------------------------------------------- 2. buffer full

    @Test
    @DisplayName("TC-B1 buffer full, collector healthy but slow: new spans are dropped and counted, calls succeed")
    void bufferFull() {
        collector.start();
        collector.respondAfter(400);
        Stats[] holder = new Stats[1];
        TestApp.runner().withPropertyValues(fast(collector.endpoint(),
                "trace.buffer.max.spans=4", "trace.batch.max.spans=2", "trace.exporter.timeout.ms=3000"))
                .run(context -> {
                    holder[0] = context.getBean(TraceRuntime.class).stats();
                    placeOrders(context.getBean(OrderService.class), "acme", 30, 3);
                    assertThat(holder[0].droppedBufferFull()).isPositive();
                    assertThat(holder[0].internalErrors()).isZero();
                });
        assertAllSpansAccountedFor(holder[0]);
    }

    @Test
    @DisplayName("TC-B2 buffer large enough: nothing is dropped")
    void bufferNotFull() {
        collector.start();
        Stats[] holder = new Stats[1];
        TestApp.runner().withPropertyValues(fast(collector.endpoint())).run(context -> {
            holder[0] = context.getBean(TraceRuntime.class).stats();
            placeOrders(context.getBean(OrderService.class), "acme", 20, 3);
        });
        assertThat(holder[0].droppedBufferFull()).isZero();
        assertThat(holder[0].droppedExportFailed()).isZero();
        assertThat(holder[0].spansExported()).isEqualTo(holder[0].spansStarted());
    }

    // ---------------------------------------------------------------- 3. collector down and buffer full

    @Test
    @DisplayName("TC-JB1 collector down and buffer full: calls stay fast, both losses are counted separately")
    void collectorDownAndBufferFull() {
        Stats[] holder = new Stats[1];
        long[] slowest = new long[1];
        TestApp.runner().withPropertyValues(fast(collector.endpoint(),
                "trace.buffer.max.spans=4", "trace.batch.max.spans=2")).run(context -> {
            holder[0] = context.getBean(TraceRuntime.class).stats();
            slowest[0] = placeOrders(context.getBean(OrderService.class), "acme", 40, 3);
            await("export failures", () -> holder[0].droppedExportFailed() > 0);
            assertThat(holder[0].droppedBufferFull()).isPositive();
            assertThat(holder[0].internalErrors()).isZero();
        });
        assertThat(slowest[0]).as("slowest business call (ms)").isLessThan(1_000);
        assertAllSpansAccountedFor(holder[0]);
        assertThat(holder[0].spansExported()).isZero();
    }

    // ---------------------------------------------------------------- 5. collector storage full / rejecting

    @Test
    @DisplayName("TC-S1 collector rejects writes with 507 (storage full): batch dropped, not retried, app unaffected")
    void storageFullRejectsWrites() {
        collector.start();
        collector.respondWith(507);
        Stats[] holder = new Stats[1];
        TestApp.runner().withPropertyValues(fast(collector.endpoint())).run(context -> {
            holder[0] = context.getBean(TraceRuntime.class).stats();
            placeOrders(context.getBean(OrderService.class), "acme", 5, 3);
            await("rejections to be counted", () -> holder[0].droppedExportFailed() > 0);
        });
        assertThat(collector.spans()).isEmpty();
        assertThat(holder[0].exportErrors()).isPositive();
        assertAllSpansAccountedFor(holder[0]);
    }

    @Test
    @DisplayName("TC-S2 collector overloaded (503) then recovers: the batch is delivered by retry")
    void overloadedThenRecovers() {
        collector.start();
        collector.respondWith(503);
        TestApp.runner().withPropertyValues(fast(collector.endpoint(),
                "trace.exporter.retry.max.attempts=5", "trace.exporter.timeout.ms=5000")).run(context -> {
            Stats stats = context.getBean(TraceRuntime.class).stats();
            placeOrders(context.getBean(OrderService.class), "acme", 1, 2);
            await("a first rejected request", () -> collector.requests() > 0);
            collector.respondWith(200);
            await("the retry to deliver the batch", () -> !collector.spans().isEmpty());
            assertThat(stats.exportErrors()).isPositive();
        });
    }
}
