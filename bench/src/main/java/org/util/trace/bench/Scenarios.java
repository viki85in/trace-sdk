package org.util.trace.bench;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.ConfigurableApplicationContext;

/** A workload: what one execution does. */
interface Scenario {

    void execute(int seq);

    /** Workloads: api (10 spans), api-fail (api with 10% failures), bulk (500 records, 11 spans), chain (depth 10). */
    static Scenario create(String name, ConfigurableApplicationContext ctx) {
        String[] tenants = new String[100];
        String[] orgs = new String[100];
        for (int i = 0; i < 100; i++) {
            tenants[i] = "tenant-" + i;
            orgs[i] = "00D" + String.format("%012d", i);
        }
        switch (name) {
            case "api":
            case "api-fail": {
                Services.Gateway gateway = ctx.getBean(Services.Gateway.class);
                double failRate = name.equals("api") ? 0 : 0.10;
                return seq -> {
                    boolean fail = failRate > 0 && ThreadLocalRandom.current().nextDouble() < failRate;
                    try {
                        gateway.handle(tenants[seq % 100], orgs[seq % 100], seq, fail);
                    } catch (IllegalStateException expected) {
                        // the synthetic payment failure
                    }
                };
            }
            case "bulk": {
                Services.Bulk bulk = ctx.getBean(Services.Bulk.class);
                return seq -> bulk.process(tenants[seq % 100], orgs[seq % 100], 500);
            }
            case "chain": {
                Services.ChainNode head = ctx.getBean("chain0", Services.ChainNode.class);
                return seq -> head.step(tenants[seq % 100], 0);
            }
            default:
                throw new IllegalArgumentException("unknown workload: " + name);
        }
    }
}
