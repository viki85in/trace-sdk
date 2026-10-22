package org.util.trace.demo;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.util.trace.Capture;
import org.util.trace.CaptureReturn;
import org.util.trace.Level;
import org.util.trace.Trace;

@Service
@Trace(operation = "demo.inventory", component = "inventory")
public class InventoryService {

    /** Cheap logical step: only recorded at L2_STEP, as an event on the caller's span. */
    @Trace(level = Level.L2_STEP)
    public void check(@Capture(as = "app.sku") String sku) {
        pause(2, 5);
    }

    @CaptureReturn(as = "app.reserved")
    public int reserve(@Capture(as = "app.sku") String sku) {
        pause(10, 40);
        return 1;
    }

    private static void pause(int minMs, int maxMs) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(minMs, maxMs + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
