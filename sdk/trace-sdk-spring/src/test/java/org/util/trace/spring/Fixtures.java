package org.util.trace.spring;

import java.util.List;
import org.util.trace.Capture;
import org.util.trace.CaptureReturn;
import org.util.trace.Completion;
import org.util.trace.Kind;
import org.util.trace.Level;
import org.util.trace.NoTrace;
import org.util.trace.Trace;

final class Fixtures {

    private Fixtures() {
    }

    @Trace(operation = "shop.orders", component = "orders")
    public static class OrderService {
        private final InventoryService inventory;
        private final PaymentGateway payments;
        private String status = "new";

        public OrderService(InventoryService inventory, PaymentGateway payments) {
            this.inventory = inventory;
            this.payments = payments;
        }

        @Trace(kind = Kind.ENTRY, completion = Completion.ENDS_EXECUTION)
        public int placeOrder(@Capture(as = "tenant.id") String tenant, @Capture(as = "org.id") String org,
                              @Capture(as = "records.count", path = "size") List<String> skus) {
            int reserved = 0;
            for (String sku : skus) {
                inventory.check(sku);
                reserved += inventory.reserve(sku);
            }
            audit(); // self-invocation: bypasses the proxy, so not traced
            payments.charge(reserved);
            return reserved;
        }

        public void audit() {
        }

        @Trace(kind = Kind.ENTRY)
        public void cancel(@Capture(as = "tenant.id") String tenant) {
            throw new IllegalStateException("already shipped");
        }

        @NoTrace
        public void warmCache() {
        }

        public String getStatus() {
            return status;
        }
    }

    @Trace(operation = "shop.inventory", component = "inventory")
    public static class InventoryService {
        @CaptureReturn(as = "app.reserved")
        public int reserve(@Capture(as = "app.sku") String sku) {
            return 1;
        }

        @Trace(level = Level.L2_STEP)
        public void check(String sku) {
        }
    }

    public interface Gateway {
        void charge(int amount);
    }

    /** Implements an interface, yet must still be injectable by class (class proxying). */
    @Trace(operation = "shop.payments", component = "payments", kind = Kind.CLIENT)
    public static class PaymentGateway implements Gateway {
        @Override
        public void charge(@Capture(as = "app.amount") int amount) {
        }
    }

    /** Cannot be proxied; the SDK must warn and leave it alone without breaking the context. */
    @Trace(operation = "shop.final", component = "final")
    public static final class FinalService {
        public String hello() {
            return "hi";
        }
    }
}
