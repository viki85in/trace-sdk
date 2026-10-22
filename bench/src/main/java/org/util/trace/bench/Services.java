package org.util.trace.bench;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import org.util.trace.Capture;
import org.util.trace.Completion;
import org.util.trace.Kind;
import org.util.trace.Level;
import org.util.trace.Trace;

/** The synthetic application. Identical code runs with and without the SDK; only the proxies differ. */
final class Services {

    private Services() {
    }

    @Configuration(proxyBeanMethods = false)
    @Import({Gateway.class, Auth.class, Catalog.class, Pricing.class, Rules.class, Inventory.class, Payments.class,
            Bulk.class, BulkWorker.class, ChainConfig.class})
    static class All {
    }

    // ---- api: 1 entry + 9 boundary calls = 10 spans at L1; 2 step events at L2 ----------------------------

    @Component
    @Trace(operation = "bench.api", component = "api")
    public static class Gateway {
        private final Auth auth;
        private final Catalog catalog;
        private final Pricing pricing;
        private final Inventory inventory;
        private final Payments payments;

        Gateway(Auth auth, Catalog catalog, Pricing pricing, Inventory inventory, Payments payments) {
            this.auth = auth;
            this.catalog = catalog;
            this.pricing = pricing;
            this.inventory = inventory;
            this.payments = payments;
        }

        @Trace(kind = Kind.ENTRY, completion = Completion.ENDS_EXECUTION)
        public int handle(@Capture(as = "tenant.id") String tenant, @Capture(as = "org.id") String org,
                          int seq, boolean fail) {
            Work.burn(Work.NS);
            auth.verify(seq);
            for (int i = 0; i < 3; i++) {
                catalog.lookup(i);
            }
            for (int i = 0; i < 2; i++) {
                pricing.quote(i);
            }
            for (int i = 0; i < 2; i++) {
                inventory.reserve(i);
            }
            payments.charge(100 + seq % 50, fail);
            return seq;
        }
    }

    @Component
    @Trace(operation = "bench.auth", component = "auth")
    public static class Auth {
        public void verify(@Capture(as = "app.seq") int seq) {
            Work.burn(Work.NS);
        }
    }

    @Component
    @Trace(operation = "bench.catalog", component = "catalog")
    public static class Catalog {
        public void lookup(@Capture(as = "app.sku") int sku) {
            Work.burn(Work.NS);
        }
    }

    @Component
    @Trace(operation = "bench.pricing", component = "pricing")
    public static class Pricing {
        private final Rules rules;

        Pricing(Rules rules) {
            this.rules = rules;
        }

        public void quote(@Capture(as = "app.line") int line) {
            Work.burn(Work.NS);
            rules.apply(line);
        }
    }

    @Component
    @Trace(operation = "bench.rules", component = "pricing")
    public static class Rules {
        @Trace(level = Level.L2_STEP)
        public void apply(@Capture(as = "app.line") int line) {
            Work.burn(Work.NS / 5);
        }
    }

    @Component
    @Trace(operation = "bench.inventory", component = "inventory")
    public static class Inventory {
        public void reserve(@Capture(as = "app.sku") int sku) {
            Work.burn(Work.NS);
        }
    }

    @Component
    @Trace(operation = "bench.payments", component = "payments", kind = Kind.CLIENT)
    public static class Payments {
        public void charge(@Capture(as = "app.amount") int amount, boolean fail) {
            Work.burn(Work.NS);
            if (fail) {
                throw new IllegalStateException("payment declined");
            }
        }
    }

    // ---- bulk: one entry, one span per 50-record chunk; counts are captured, records are not traced -------

    @Component
    @Trace(operation = "bench.bulk", component = "bulk")
    public static class Bulk {
        private final BulkWorker worker;

        Bulk(BulkWorker worker) {
            this.worker = worker;
        }

        @Trace(kind = Kind.ENTRY, completion = Completion.ENDS_EXECUTION)
        public int process(@Capture(as = "tenant.id") String tenant, @Capture(as = "org.id") String org,
                           @Capture(as = "records.count") int count) {
            Work.burn(Work.NS);
            for (int done = 0; done < count; done += 50) {
                worker.chunk(Math.min(50, count - done));
            }
            return count;
        }
    }

    @Component
    @Trace(operation = "bench.bulk.worker", component = "bulk")
    public static class BulkWorker {
        public void chunk(@Capture(as = "batch.size") int n) {
            Work.burn(Work.NS / 25 * n / 2 + 200);
        }
    }

    // ---- chain: 10 nested beans, depth 10 ------------------------------------------------------------------

    @Trace(operation = "bench.chain", component = "chain")
    public static class ChainNode {
        private final ChainNode next;

        ChainNode(ChainNode next) {
            this.next = next;
        }

        public void step(@Capture(as = "tenant.id") String tenant, @Capture(as = "app.depth") int depth) {
            Work.burn(Work.NS);
            if (next != null) {
                next.step(tenant, depth + 1);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ChainConfig {
        @Bean ChainNode chain9() { return new ChainNode(null); }
        @Bean ChainNode chain8(ChainNode chain9) { return new ChainNode(chain9); }
        @Bean ChainNode chain7(ChainNode chain8) { return new ChainNode(chain8); }
        @Bean ChainNode chain6(ChainNode chain7) { return new ChainNode(chain7); }
        @Bean ChainNode chain5(ChainNode chain6) { return new ChainNode(chain6); }
        @Bean ChainNode chain4(ChainNode chain5) { return new ChainNode(chain5); }
        @Bean ChainNode chain3(ChainNode chain4) { return new ChainNode(chain4); }
        @Bean ChainNode chain2(ChainNode chain3) { return new ChainNode(chain3); }
        @Bean ChainNode chain1(ChainNode chain2) { return new ChainNode(chain2); }
        @Bean ChainNode chain0(ChainNode chain1) { return new ChainNode(chain1); }
    }
}
