package org.util.trace.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.util.trace.core.TestSupport.config;
import static org.util.trace.core.TestSupport.site;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.util.trace.core.Fixtures.OrderService;
import org.util.trace.core.buffer.SpanBuffer;
import org.util.trace.core.config.TraceConfig;

class TracerTest {

    private static final TraceSite PLACE_ORDER = site(OrderService.class, "placeOrder");
    private static final TraceSite RESERVE = site(OrderService.class, "reserve");
    private static final TraceSite APPLY_RULES = site(OrderService.class, "applyRules");
    private static final TraceSite PROCESS = site(OrderService.class, "process");
    private static final TraceSite POST = site(OrderService.class, "post");
    private static final TraceSite NO_CAPTURES = site(OrderService.class, "noCaptures");

    private final Stats stats = new Stats();
    private SpanBuffer<Span> buffer = new SpanBuffer<>(1024);
    private Tracer tracer = new Tracer(config(), buffer, stats);

    @AfterEach
    void noLeakedContext() {
        assertThat(Tracer.current()).isNull();
    }

    private void use(TraceConfig config) {
        tracer = new Tracer(config, buffer, stats);
    }

    private List<Span> drain() {
        List<Span> out = new ArrayList<>();
        for (Span s; (s = buffer.poll()) != null; ) {
            out.add(s);
        }
        return out;
    }

    private Span entry() {
        return tracer.begin(PLACE_ORDER, new Object[] {"t-1", "00D1", List.of("a", "b", "c")});
    }

    @Test
    void nestedSpansShareTheTraceAndLinkToTheirParent() {
        Span root = entry();
        Span child = tracer.begin(RESERVE, new Object[] {"SKU-9"});
        assertThat(Tracer.current()).isSameAs(child);
        tracer.end(child, true, null);
        assertThat(Tracer.current()).isSameAs(root);
        tracer.end(root, "ok", null);

        List<Span> spans = drain();
        assertThat(spans).extracting(s -> s.site().operation())
                .containsExactly("orders.service.reserve", "orders.service.place_order");
        Span c = spans.get(0);
        Span r = spans.get(1);
        assertThat(c.trace()).isSameAs(r.trace());
        assertThat(c.parentSpanId()).isEqualTo(r.spanId());
        assertThat(r.parentSpanId()).isZero();
        assertThat(r.seq()).isEqualTo(1);
        assertThat(c.seq()).isEqualTo(2);
        assertThat(c.attribute("app.sku")).isEqualTo("SKU-9");
        assertThat(c.attribute("app.reserved")).isEqualTo(true);
        assertThat(r.attribute("records.count")).isEqualTo(3);
        assertThat(r.isExecutionComplete()).isTrue();
        assertThat(c.isExecutionComplete()).isFalse();
        assertThat(r.durationNanos()).isGreaterThanOrEqualTo(c.durationNanos());
        assertThat(r.retentionClass()).isEqualTo("standard-30d");
        assertThat(stats.spansStarted()).isEqualTo(2);
    }

    @Test
    void tenantAndOrgAreCapturedAtTheEntryAndInherited() {
        Span root = entry();
        Span child = tracer.begin(NO_CAPTURES, new Object[0]);
        tracer.end(child, null, null);
        tracer.end(root, null, null);

        for (Span s : drain()) {
            assertThat(s.trace().tenantId()).isEqualTo("t-1");
            assertThat(s.trace().orgId()).isEqualTo("00D1");
            // Context IDs live on the trace, not duplicated as span attributes.
            assertThat(s.attribute("tenant.id")).isNull();
        }
        assertThat(stats.missingTenant()).isZero();
    }

    @Test
    void missingTenantIsCountedButTheSpanIsStillRecorded() {
        Span root = tracer.begin(NO_CAPTURES, new Object[0]);
        tracer.end(root, null, null);

        assertThat(drain()).hasSize(1);
        assertThat(stats.missingTenant()).isEqualTo(1);
    }

    @Test
    void entrySpanIsAlwaysRecordedButChildrenBelowTheLevelAreNot() {
        use(config("trace.level", "L0_ENTRY"));
        Span root = tracer.begin(NO_CAPTURES, new Object[0]); // an L1 site, but it is the entry
        assertThat(root).isNotNull();
        assertThat(tracer.begin(RESERVE, new Object[] {"x"})).isNull();
        tracer.end(root, null, null);

        assertThat(drain()).hasSize(1);
    }

    @Test
    void stepsAreSkippedAtL1() {
        Span root = entry();
        assertThat(tracer.begin(APPLY_RULES, new Object[] {4})).isNull();
        tracer.end(root, null, null);

        assertThat(drain().get(0).events()).isEmpty();
    }

    @Test
    void stepsAreEventsOnTheEnclosingSpanAtL2() {
        use(config("trace.level", "L2_STEP"));
        Span root = entry();
        Span step = tracer.begin(APPLY_RULES, new Object[] {4});
        assertThat(Tracer.current()).as("a step is not a new parent").isSameAs(root);
        tracer.end(step, null, null);
        tracer.end(root, null, null);

        List<Span> spans = drain();
        assertThat(spans).hasSize(1);
        Span event = spans.get(0).events().get(0);
        assertThat(event.site().operation()).isEqualTo("orders.service.apply_rules");
        assertThat(event.attribute("records.count")).isEqualTo(4);
        assertThat(event.seq()).isEqualTo(2);
    }

    @Test
    void stepWithoutAnEnclosingSpanIsNotRecorded() {
        use(config("trace.level", "L2_STEP"));
        assertThat(tracer.begin(APPLY_RULES, new Object[] {1})).isNull();
    }

    @Test
    void operationOverrideRaisesTheLevelForThatSubtreeOnly() {
        use(config("trace.level.orders.service.reserve", "L2_STEP"));
        Span root = entry();
        assertThat(tracer.begin(APPLY_RULES, new Object[] {1})).as("outside the subtree").isNull();
        Span reserve = tracer.begin(RESERVE, new Object[] {"x"});
        Span step = tracer.begin(APPLY_RULES, new Object[] {2});
        assertThat(step).as("inside the subtree").isNotNull();
        tracer.end(step, null, null);
        tracer.end(reserve, true, null);
        tracer.end(root, null, null);

        List<Span> spans = drain();
        assertThat(spans.get(0).events()).hasSize(1);
        assertThat(spans.get(0).level()).isEqualTo(Levels.L2);
        assertThat(spans.get(1).level()).isEqualTo(Levels.L1);
    }

    @Test
    void entryOverrideSetsTheEffectiveLevelOfTheWholeTrace() {
        use(config("trace.level", "L0_ENTRY", "trace.level.orders.service.place_order", "L1_BOUNDARY"));
        Span root = entry();
        Span child = tracer.begin(RESERVE, new Object[] {"x"});
        assertThat(child).isNotNull();
        tracer.end(child, true, null);
        tracer.end(root, null, null);
        assertThat(drain()).hasSize(2);
    }

    @Test
    void exceptionsMarkTheSpanAsErrorWithABoundedStackTrace() {
        use(config("trace.stacktrace.max.frames", "2"));
        Span root = entry();
        tracer.end(root, null, new IllegalStateException("card 4111 declined"));

        Span s = drain().get(0);
        assertThat(s.isError()).isTrue();
        assertThat(s.exceptionType()).isEqualTo("java.lang.IllegalStateException");
        assertThat(s.exceptionMessage()).as("messages are off by default").isNull();
        assertThat(s.exceptionStackTrace().split("\n\tat ")).hasSize(3);
        assertThat(s.exceptionStackTrace()).contains("more");
    }

    @Test
    void exceptionMessagesAreRecordedOnlyWhenEnabled() {
        use(config("trace.capture.exception.message", "true"));
        Span root = entry();
        tracer.end(root, null, new IllegalStateException("boom"));
        assertThat(drain().get(0).exceptionMessage()).isEqualTo("boom");
    }

    @Test
    void returnValueIsNotCapturedWhenTheMethodThrows() {
        Span root = entry();
        Span child = tracer.begin(RESERVE, new Object[] {"x"});
        tracer.end(child, null, new RuntimeException());
        tracer.end(root, null, null);
        assertThat(drain().get(0).attribute("app.reserved")).isNull();
    }

    @Test
    void recordsFailedMarksPartialOrAllFailure() {
        Span root = entry();
        Span partial = tracer.begin(PROCESS, new Object[] {10, 3});
        tracer.end(partial, null, null);
        Span all = tracer.begin(PROCESS, new Object[] {5, 5});
        tracer.end(all, null, null);
        Span none = tracer.begin(PROCESS, new Object[] {5, 0});
        tracer.end(none, null, null);
        tracer.end(root, null, null);

        List<Span> spans = drain();
        assertThat(spans.get(0).isError()).isTrue();
        assertThat(spans.get(0).failureKind()).isEqualTo("PARTIAL");
        assertThat(spans.get(1).failureKind()).isEqualTo("ALL");
        assertThat(spans.get(2).isError()).isFalse();
        assertThat(spans.get(3).isError()).isFalse();
    }

    @Test
    void inboundTraceparentContinuesTheRemoteTrace() {
        Span root = tracer.begin(POST, new Object[] {
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "t", "o"});
        tracer.end(root, null, null);

        Span s = drain().get(0);
        assertThat(Ids.hex(s.trace().traceIdHigh()) + Ids.hex(s.trace().traceIdLow()))
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(Ids.hex(s.parentSpanId())).isEqualTo("00f067aa0ba902b7");
        assertThat(s.attribute("traceparent")).as("not recorded as an attribute").isNull();
    }

    @Test
    void invalidTraceparentStartsANewTrace() {
        Span root = tracer.begin(POST, new Object[] {"garbage", "t", "o"});
        tracer.end(root, null, null);
        Span s = drain().get(0);
        assertThat(s.parentSpanId()).isZero();
        assertThat(s.trace().traceIdHigh() | s.trace().traceIdLow()).isNotZero();
    }

    @Test
    void whenTheBufferIsFullNewRecordsAreDroppedAndCounted() {
        buffer = new SpanBuffer<>(2);
        tracer = new Tracer(config(), buffer, stats);
        Span root = entry();
        for (int i = 0; i < 3; i++) {
            Span child = tracer.begin(NO_CAPTURES, new Object[0]);
            tracer.end(child, null, null);
        }
        tracer.end(root, null, null);

        assertThat(stats.droppedBufferFull()).isEqualTo(2);
        assertThat(root.trace().dropped()).isEqualTo(2);
        assertThat(drain()).hasSize(2);
        assertThat(root.droppedSoFar()).as("the root ended after a drop").isEqualTo(1);
    }

    @Test
    void killSwitchStopsRecordingAtRuntime() {
        TraceConfig config = config();
        use(config);
        assertThat(config.update("trace.enabled", "false")).isTrue();
        assertThat(entry()).isNull();

        assertThat(config.update("trace.enabled", "true")).isTrue();
        Span root = entry();
        assertThat(root).isNotNull();
        tracer.end(root, null, null);
    }

    @Test
    void contextIsRestoredAfterTheEntryEnds() {
        Span root = entry();
        tracer.end(root, null, null);
        assertThat(Tracer.current()).isNull();
        Span next = entry();
        assertThat(next.trace()).isNotSameAs(root.trace());
        tracer.end(next, null, null);
    }
}
