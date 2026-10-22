package org.util.trace.demo;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.util.trace.Capture;
import org.util.trace.Kind;
import org.util.trace.Trace;

/** Pretends to be a call to an external payment gateway (a CLIENT span). */
@Service
@Trace(operation = "demo.payments", component = "payments", kind = Kind.CLIENT)
public class PaymentService {

    public void charge(@Capture(as = "app.amount") int amount, boolean fail) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(30, 90));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (fail) {
            throw new IllegalStateException("payment gateway declined the charge");
        }
    }
}
