package org.util.trace.bench;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Standard volumes (spec NFR-4.1): 1 billion executions a day across the fleet, 10 spans each.
 * A "tier" says how many hosts share that volume; a "percentile" says how far above the daily
 * average the load is. The percentile multipliers are ASSUMED (the spec fixes only the 3x peak,
 * which is used for p99); replace them with measured traffic when it is available.
 */
final class Profiles {

    static final double EXECUTIONS_PER_DAY = 1_000_000_000d;
    static final double FLEET_EXECUTIONS_PER_SECOND = EXECUTIONS_PER_DAY / 86_400d;

    /** Hosts sharing the fleet volume. Fewer hosts means a hotter host. */
    static final Map<String, Integer> TIER_HOSTS = new LinkedHashMap<>();
    /** Multiple of the daily-average rate. */
    static final Map<String, Double> PERCENTILE_FACTOR = new LinkedHashMap<>();

    static {
        TIER_HOSTS.put("standard", 1000); // the spec's H = 1000
        TIER_HOSTS.put("dense", 100);
        TIER_HOSTS.put("hot", 10);
        TIER_HOSTS.put("extreme", 1);     // the whole fleet's volume through one JVM
        PERCENTILE_FACTOR.put("avg", 1.0);
        PERCENTILE_FACTOR.put("p90", 2.0);
        PERCENTILE_FACTOR.put("p95", 2.5);
        PERCENTILE_FACTOR.put("p99", 3.0); // = the spec's peak-to-average ratio P
    }

    private Profiles() {
    }

    static double rate(String tier, String percentile) {
        return FLEET_EXECUTIONS_PER_SECOND / TIER_HOSTS.get(tier) * PERCENTILE_FACTOR.get(percentile);
    }
}
