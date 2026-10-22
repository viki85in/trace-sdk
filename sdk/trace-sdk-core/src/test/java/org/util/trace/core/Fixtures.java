package org.util.trace.core;

import java.util.List;
import org.util.trace.Capture;
import org.util.trace.CaptureReturn;
import org.util.trace.Completion;
import org.util.trace.Kind;
import org.util.trace.Level;
import org.util.trace.Trace;

/** Annotated classes used to build sites in tests. Never invoked. */
public final class Fixtures {

    private Fixtures() {
    }

    @Trace(operation = "orders.service", component = "orders")
    public static class OrderService {

        @Trace(kind = Kind.ENTRY, completion = Completion.ENDS_EXECUTION)
        public String placeOrder(@Capture(as = "tenant.id") String tenantId,
                                 @Capture(as = "org.id") String orgId,
                                 @Capture(as = "records.count", path = "size") List<String> items) {
            return "ok";
        }

        @CaptureReturn(as = "app.reserved")
        public boolean reserve(@Capture(as = "app.sku") String sku) {
            return true;
        }

        @Trace(level = Level.L2_STEP)
        public void applyRules(@Capture(as = "records.count") int lines) {
        }

        public void process(@Capture(as = "records.count") int count, @Capture(as = "records.failed") int failed) {
        }

        @Trace(operation = "orders.api.post", kind = Kind.ENTRY)
        public void post(@Capture(as = "traceparent") String traceparent,
                         @Capture(as = "tenant.id") String tenantId,
                         @Capture(as = "org.id") String orgId) {
        }

        public void noCaptures() {
        }
    }
}
