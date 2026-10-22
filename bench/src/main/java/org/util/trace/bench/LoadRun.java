package org.util.trace.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.management.OperatingSystemMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.HdrHistogram.Histogram;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.util.trace.core.Stats;
import org.util.trace.core.TraceRuntime;
import org.util.trace.spring.autoconfigure.TraceSdkAutoConfiguration;

/**
 * One measured run in its own JVM. Open loop: executions are scheduled at fixed intended start times and latency is
 * measured from the intended start, so a stall is charged to every execution that queued behind it (no coordinated
 * omission). Rate 0 means closed loop (maximum throughput).
 */
final class LoadRun {

    private LoadRun() {
    }

    static void run(Args a) throws Exception {
        String strategy = a.str("strategy", "L1");          // S0 | S1 | L0 | L1 | L2
        String workload = a.str("workload", "api");
        double rate = a.dbl("rate", 100);                   // executions per second, 0 = closed loop
        int threads = a.num("threads", 8);
        int warmupSec = a.num("warmup", 8);
        int measureSec = a.num("measure", 20);
        String sink = a.str("sink", "http://127.0.0.1:4318");

        List<String> props = new ArrayList<>(List.of(
                "spring.application.name=bench",
                "trace.exporter.endpoint=" + sink + "/v1/traces"));
        switch (strategy) {
            case "S0": break;
            case "S1": props.add("trace.enabled=false"); break;
            case "L0": props.add("trace.level=L0_ENTRY"); break;
            case "L1": props.add("trace.level=L1_BOUNDARY"); break;
            case "L2": props.add("trace.level=L2_STEP"); break;
            default: throw new IllegalArgumentException("unknown strategy " + strategy);
        }
        SpringApplicationBuilder builder = new SpringApplicationBuilder(Services.All.class)
                .web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false)
                .properties(props.toArray(new String[0]));
        if (!strategy.equals("S0")) {
            builder.sources(AopAutoConfiguration.class, TraceSdkAutoConfiguration.class);
        }
        ConfigurableApplicationContext ctx = builder.run();
        Stats stats = strategy.equals("S0") ? null : ctx.getBean(TraceRuntime.class).stats();
        Scenario scenario = Scenario.create(workload, ctx);

        // Warm up: half closed-loop (JIT), half at the target rate.
        drive(scenario, 0, threads, warmupSec / 2.0, null, null);
        drive(scenario, rate, threads, warmupSec / 2.0, null, null);

        // The closed-loop warm-up burst can overrun the buffer; let the exporter finish before measuring.
        awaitDrained(stats);
        sinkCall(sink, "/reset");

        com.sun.management.ThreadMXBean tmx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long gcCount0 = gc(true), gcMs0 = gc(false);
        long cpu0 = os.getProcessCpuTime();
        long exporterCpu0 = exporterCpu(tmx);
        Counters before = Counters.of(stats);

        Histogram hist = new Histogram(3_600_000_000_000L, 3);
        AtomicLong allocated = new AtomicLong();
        long t0 = System.nanoTime();
        long executed = drive(scenario, rate, threads, measureSec, hist, allocated);
        double elapsed = (System.nanoTime() - t0) / 1e9;

        long cpuMs = (os.getProcessCpuTime() - cpu0) / 1_000_000;
        long exporterCpuMs = (exporterCpu(tmx) - exporterCpu0) / 1_000_000;
        long gcCount = gc(true) - gcCount0, gcMs = gc(false) - gcMs0;

        // Let the exporter drain, then read the books.
        Counters after = Counters.of(stats);
        awaitDrained(stats);
        after = Counters.of(stats);
        Map<String, Object> sinkStats = new ObjectMapper().readValue(sinkCall(sink, "/stats"), Map.class);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("strategy", strategy);
        r.put("workload", workload);
        r.put("threads", threads);
        r.put("targetRate", rate);
        r.put("executions", executed);
        r.put("achievedRate", round(executed / elapsed));
        r.put("avgUs", round(hist.getMean() / 1000.0));
        r.put("p50Us", round(hist.getValueAtPercentile(50) / 1000.0));
        r.put("p90Us", round(hist.getValueAtPercentile(90) / 1000.0));
        r.put("p95Us", round(hist.getValueAtPercentile(95) / 1000.0));
        r.put("p99Us", round(hist.getValueAtPercentile(99) / 1000.0));
        r.put("p999Us", round(hist.getValueAtPercentile(99.9) / 1000.0));
        r.put("maxUs", round(hist.getMaxValue() / 1000.0));
        r.put("allocBytesPerExec", executed == 0 ? 0 : allocated.get() / executed);
        r.put("gcCount", gcCount);
        r.put("gcMs", gcMs);
        r.put("processCpuMs", cpuMs);
        r.put("cpuCoresUsed", round(cpuMs / 1000.0 / elapsed));
        r.put("exporterCpuMs", exporterCpuMs);
        r.put("spansStarted", after.started() - before.started());
        r.put("spansExported", after.exported() - before.exported());
        r.put("droppedBufferFull", after.droppedFull() - before.droppedFull());
        r.put("droppedExportFailed", after.droppedFailed() - before.droppedFailed());
        r.put("internalErrors", after.internalErrors() - before.internalErrors());
        r.put("sinkRequests", sinkStats.get("requests"));
        r.put("sinkBytes", sinkStats.get("bytes"));
        long exported = after.exported() - before.exported();
        r.put("wireBytesPerSpan", exported == 0 ? 0 : round(((Number) sinkStats.get("bytes")).doubleValue() / exported));
        System.out.println("RESULT " + new ObjectMapper().writeValueAsString(r));
        ctx.close();
    }

    /** Runs the scenario on {@code threads} threads for {@code seconds}; returns executions completed. */
    private static long drive(Scenario s, double rate, int threads, double seconds, Histogram out, AtomicLong alloc)
            throws Exception {
        com.sun.management.ThreadMXBean tmx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long start = System.nanoTime() + 50_000_000L;
        long end = start + (long) (seconds * 1e9);
        double intervalNs = rate > 0 ? 1e9 / rate : 0;
        AtomicLong count = new AtomicLong();
        CountDownLatch done = new CountDownLatch(threads);
        List<Histogram> hists = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            Histogram h = out == null ? null : new Histogram(3_600_000_000_000L, 3);
            if (h != null) {
                hists.add(h);
            }
            Thread th = new Thread(() -> {
                long a0 = tmx.getThreadAllocatedBytes(Thread.currentThread().getId());
                long n = 0;
                long slot = id;
                int seq = id;
                while (true) {
                    long intended;
                    if (rate > 0) {
                        intended = start + (long) (slot * intervalNs);
                        if (intended >= end) {
                            break;
                        }
                        long wait;
                        while ((wait = intended - System.nanoTime()) > 0) {
                            if (wait > 200_000) {
                                java.util.concurrent.locks.LockSupport.parkNanos(wait - 100_000);
                            } else {
                                Thread.onSpinWait();
                            }
                        }
                        slot += threads;
                    } else {
                        intended = System.nanoTime();
                        if (intended >= end) {
                            break;
                        }
                    }
                    s.execute(seq);
                    seq += threads;
                    if (h != null) {
                        h.recordValue(Math.max(1, System.nanoTime() - intended));
                    }
                    n++;
                }
                count.addAndGet(n);
                if (alloc != null) {
                    alloc.addAndGet(tmx.getThreadAllocatedBytes(Thread.currentThread().getId()) - a0);
                }
                done.countDown();
            }, "bench-load-" + t);
            th.start();
        }
        done.await();
        if (out != null) {
            hists.forEach(out::add);
        }
        return count.get();
    }

    private static void awaitDrained(Stats stats) throws InterruptedException {
        if (stats == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Counters c = Counters.of(stats);
            if (c.started() - c.exported() - c.droppedFull() - c.droppedFailed() <= 0) {
                return;
            }
            Thread.sleep(100);
        }
    }

    private static long exporterCpu(com.sun.management.ThreadMXBean tmx) {
        for (ThreadInfo ti : tmx.dumpAllThreads(false, false)) {
            if ("trace-sdk-exporter".equals(ti.getThreadName())) {
                return Math.max(0, tmx.getThreadCpuTime(ti.getThreadId()));
            }
        }
        return 0;
    }

    private static long gc(boolean count) {
        long sum = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            sum += count ? b.getCollectionCount() : b.getCollectionTime();
        }
        return sum;
    }

    static String sinkCall(String base, String path) throws Exception {
        HttpClient c = HttpClient.newHttpClient();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        HttpRequest req = path.equals("/reset") ? b.POST(HttpRequest.BodyPublishers.noBody()).build() : b.GET().build();
        return c.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private record Counters(long started, long exported, long droppedFull, long droppedFailed, long internalErrors) {
        static Counters of(Stats s) {
            return s == null ? new Counters(0, 0, 0, 0, 0)
                    : new Counters(s.spansStarted(), s.spansExported(), s.droppedBufferFull(),
                            s.droppedExportFailed(), s.internalErrors());
        }
    }
}
