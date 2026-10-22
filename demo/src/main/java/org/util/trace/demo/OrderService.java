package org.util.trace.demo;

import java.util.List;
import org.springframework.stereotype.Service;
import org.util.trace.Capture;
import org.util.trace.Completion;
import org.util.trace.Kind;
import org.util.trace.Trace;

/** The entry point of an execution. Everything below it nests under this span. */
@Service
@Trace(operation = "demo.orders", component = "orders")
public class OrderService {

    private final InventoryService inventory;
    private final PaymentService payments;

    public OrderService(InventoryService inventory, PaymentService payments) {
        this.inventory = inventory;
        this.payments = payments;
    }

    @Trace(kind = Kind.ENTRY, completion = Completion.ENDS_EXECUTION)
    public int placeOrder(@Capture(as = "tenant.id") String tenant,
                          @Capture(as = "org.id") String org,
                          @Capture(as = "records.count", path = "size") List<String> skus,
                          @Capture(as = "app.fail_payment") boolean failPayment) {
        int reserved = 0;
        for (String sku : skus) {
            inventory.check(sku);          // L2 step: an event on this span, not a span
            reserved += inventory.reserve(sku);
        }
        payments.charge(reserved * 10, failPayment);
        return reserved;
    }
}
