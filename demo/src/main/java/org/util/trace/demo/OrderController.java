package org.util.trace.demo;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    /** POST /orders?tenant=acme&org=00Dxx0000001&items=3&failPayment=false */
    @PostMapping("/orders")
    public ResponseEntity<Map<String, Object>> place(@RequestParam(defaultValue = "acme") String tenant,
                                                     @RequestParam(defaultValue = "00D000000000001") String org,
                                                     @RequestParam(defaultValue = "3") int items,
                                                     @RequestParam(defaultValue = "false") boolean failPayment) {
        List<String> skus = IntStream.rangeClosed(1, items).mapToObj(i -> "SKU-" + i).toList();
        try {
            int reserved = orders.placeOrder(tenant, org, skus, failPayment);
            return ResponseEntity.ok(Map.of("status", "placed", "reserved", reserved));
        } catch (IllegalStateException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("status", "failed", "error", e.getMessage()));
        }
    }
}
