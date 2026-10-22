package org.util.trace.core.site;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.util.trace.Capture;
import org.util.trace.Kind;
import org.util.trace.Level;
import org.util.trace.NoTrace;
import org.util.trace.Trace;
import org.util.trace.core.Levels;
import org.util.trace.core.Span;
import org.util.trace.core.TestSupport;
import org.util.trace.core.TraceRuntime;
import org.util.trace.core.TraceSite;

class SiteFactoryTest {

    @Trace(operation = "orders.service", component = "orders", retention = "short-7d")
    public static class ClassLevel {
        public void placeOrder() {
        }

        public void getHTTPStatusCode(int x) {
        }

        public String getTotal() {
            return "";
        }

        public boolean isOpen() {
            return true;
        }

        public void setTotal(String t) {
        }

        @NoTrace
        public void warmCache() {
        }

        @Override
        public String toString() {
            return "";
        }

        protected void notPublic() {
        }

        public static void staticMethod() {
        }

        @Trace(operation = "orders.custom.name", component = "billing", kind = Kind.CLIENT, retention = "long-90d")
        public void overridden() {
        }

        @Trace(level = Level.L2_STEP)
        public void step() {
        }

        public final void finalMethod() {
        }
    }

    @Trace(operation = "orders.service", component = "orders", excludeAccessors = false)
    public static class WithAccessors {
        public String getTotal() {
            return "";
        }
    }

    public static class MethodLevel {
        @Trace(operation = "payments.gateway.charge", component = "payments")
        public void charge() {
        }

        @Trace(operation = "payments.gateway.refund", component = "payments")
        void packagePrivate() {
        }

        @Trace(operation = "payments.gateway.missing_component")
        public void noComponent() {
        }

        @Trace(component = "payments")
        public void noOperation() {
        }

        @Trace(operation = "Payments.Charge", component = "payments")
        public void badOperation() {
        }

        @Trace(operation = "payments.api.post", component = "payments", kind = Kind.ENTRY)
        public void entry() {
        }

        public void plain() {
        }
    }

    public static class Order {
        public List<String> getItems() {
            return List.of();
        }

        public Object getCustomer() {
            return null;
        }

        public final int priority = 3;
    }

    public static class Captures {
        @Trace(operation = "orders.capture.test", component = "orders")
        public void method(@Capture(as = "records.count", path = "items.size") Order order,
                           @Capture(as = "app.priority", path = "priority") Order order2,
                           @Capture(as = "app.customer", path = "customer") Order order3,
                           @Capture(as = "bad.key") String bad,
                           @Capture(as = "app.list") List<String> notScalar,
                           @Capture(as = "app.nope", path = "doesNotExist") Order missing,
                           @Capture(as = "retry.attempt") int attempt) {
        }
    }

    private static TraceSite site(Class<?> type, String name) {
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return SiteFactory.create(m);
            }
        }
        throw new AssertionError(name);
    }

    @Test
    void classPrefixPlusSnakeCaseMethodName() {
        TraceSite s = site(ClassLevel.class, "placeOrder");
        assertThat(s.operation()).isEqualTo("orders.service.place_order");
        assertThat(s.component()).isEqualTo("orders");
        assertThat(s.kind()).isEqualTo(Kind.INTERNAL);
        assertThat(s.level()).isEqualTo(Levels.L1);
        assertThat(s.retentionClass()).isEqualTo("short-7d");
        assertThat(s.codeNamespace()).isEqualTo(ClassLevel.class.getName());
        assertThat(s.codeFunction()).isEqualTo("placeOrder");
        assertThat(site(ClassLevel.class, "getHTTPStatusCode").operation())
                .isEqualTo("orders.service.get_http_status_code");
    }

    @Test
    void snakeCase() {
        assertThat(SiteFactory.snakeCase("placeOrder")).isEqualTo("place_order");
        assertThat(SiteFactory.snakeCase("parseURL")).isEqualTo("parse_url");
        assertThat(SiteFactory.snakeCase("v2Charge")).isEqualTo("v2_charge");
        assertThat(SiteFactory.snakeCase("run")).isEqualTo("run");
    }

    @Test
    void classLevelExclusions() {
        assertThat(site(ClassLevel.class, "getTotal")).isNull();
        assertThat(site(ClassLevel.class, "isOpen")).isNull();
        assertThat(site(ClassLevel.class, "setTotal")).isNull();
        assertThat(site(ClassLevel.class, "warmCache")).isNull();
        assertThat(site(ClassLevel.class, "toString")).isNull();
        assertThat(site(ClassLevel.class, "notPublic")).isNull();
        assertThat(site(ClassLevel.class, "staticMethod")).isNull();
        assertThat(site(ClassLevel.class, "finalMethod")).isNull();
        assertThat(site(WithAccessors.class, "getTotal")).isNotNull();
    }

    @Test
    void methodValuesOverrideClassValues() {
        TraceSite s = site(ClassLevel.class, "overridden");
        assertThat(s.operation()).isEqualTo("orders.custom.name");
        assertThat(s.component()).isEqualTo("billing");
        assertThat(s.kind()).isEqualTo(Kind.CLIENT);
        assertThat(s.retentionClass()).isEqualTo("long-90d");
        TraceSite step = site(ClassLevel.class, "step");
        assertThat(step.isStep()).isTrue();
        assertThat(step.operation()).isEqualTo("orders.service.step");
    }

    @Test
    void methodLevelRules() {
        assertThat(site(MethodLevel.class, "charge").operation()).isEqualTo("payments.gateway.charge");
        assertThat(site(MethodLevel.class, "charge").retentionClass()).isNull();
        assertThat(site(MethodLevel.class, "packagePrivate")).isNull();
        assertThat(site(MethodLevel.class, "noComponent")).isNull();
        assertThat(site(MethodLevel.class, "noOperation")).isNull();
        assertThat(site(MethodLevel.class, "badOperation")).isNull();
        assertThat(site(MethodLevel.class, "plain")).isNull();
    }

    @Test
    void entryDefaultsToL0() {
        TraceSite s = site(MethodLevel.class, "entry");
        assertThat(s.kind()).isEqualTo(Kind.ENTRY);
        assertThat(s.level()).isEqualTo(Levels.L0);
    }

    @Test
    void invalidCapturesAreSkippedWithoutDroppingTheSite() {
        TraceSite s = site(Captures.class, "method");
        assertThat(s).isNotNull();
        TraceRuntime runtime = TraceRuntime.create(TestSupport.config(), new TestSupport.CapturingExporter());
        Order order = new Order();
        Span span = runtime.tracer().begin(s, new Object[] {order, order, order, "x", List.of("a"), order, 2});
        runtime.tracer().end(span, null, null);

        Map<String, Object> attrs = new HashMap<>();
        for (int i = 0; i < span.attributeCount(); i++) {
            attrs.put(span.attributeKey(i), span.attributeValue(i));
        }
        assertThat(attrs).containsOnlyKeys("records.count", "app.priority", "retry.attempt");
        assertThat(attrs.get("records.count")).isEqualTo(0);
        assertThat(attrs.get("app.priority")).isEqualTo(3);
        assertThat(attrs.get("retry.attempt")).isEqualTo(2);
    }

    @Test
    void declaresTrace() {
        assertThat(SiteFactory.declaresTrace(ClassLevel.class)).isTrue();
        assertThat(SiteFactory.declaresTrace(MethodLevel.class)).isTrue();
        assertThat(SiteFactory.declaresTrace(Order.class)).isFalse();
    }
}
