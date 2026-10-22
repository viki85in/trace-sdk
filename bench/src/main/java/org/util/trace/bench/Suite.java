package org.util.trace.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs the matrix: a sink process, then one fresh JVM per run. Writes results.jsonl, results.csv and report.md. */
final class Suite {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Suite() {
    }

    /** One planned run. */
    record Plan(String tier, String percentile, String strategy, String workload, double rate, String note) {
    }

    static void run(Args a) throws Exception {
        String profile = a.str("profile", "smoke");
        Path out = Path.of(a.str("out", "results/" + profile + "-" + System.currentTimeMillis()));
        Files.createDirectories(out);
        int warmup = a.num("warmup", profile.equals("smoke") ? 3 : 8);
        int measure = a.num("measure", profile.equals("smoke") ? 5 : 20);
        int threads = a.num("threads", 8);
        List<Plan> plans = plan(profile);

        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String jar = System.getProperty("java.class.path");
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        Process sink = new ProcessBuilder(javaBin, "-Xmx256m", "-jar", jar, "sink", "--port=" + port)
                .redirectErrorStream(true).redirectOutput(out.resolve("sink.log").toFile()).start();
        Thread.sleep(1500);
        String sinkUrl = "http://127.0.0.1:" + port;

        List<Map<String, Object>> results = new ArrayList<>();
        try {
            int i = 0;
            for (Plan p : plans) {
                i++;
                System.out.printf("[%d/%d] %s %s %s %s rate=%.1f%n", i, plans.size(), p.tier(), p.percentile(),
                        p.strategy(), p.workload(), p.rate());
                List<String> cmd = new ArrayList<>(List.of(javaBin, "-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch",
                        "-jar", jar, "run", "--strategy=" + p.strategy(), "--workload=" + p.workload(),
                        "--rate=" + p.rate(), "--threads=" + threads, "--warmup=" + warmup,
                        "--measure=" + measure, "--sink=" + sinkUrl));
                Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                Map<String, Object> result = null;
                StringBuilder log = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith("RESULT ")) {
                            result = JSON.readValue(line.substring(7), Map.class);
                        } else {
                            log.append(line).append('\n');
                        }
                    }
                }
                proc.waitFor();
                if (result == null) {
                    System.out.println("  FAILED, no result. Output tail:\n" + log.substring(Math.max(0, log.length() - 1500)));
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("tier", p.tier());
                row.put("percentile", p.percentile());
                row.put("note", p.note());
                row.putAll(result);
                results.add(row);
                System.out.printf("  achieved=%s/s avg=%sus p99=%sus drops=%s%n", row.get("achievedRate"),
                        row.get("avgUs"), row.get("p99Us"), row.get("droppedBufferFull"));
                Files.writeString(out.resolve("results.jsonl"), JSON.writeValueAsString(row) + "\n",
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            }
        } finally {
            sink.destroy();
        }
        Report.write(out, results, warmup, measure, threads);
        System.out.println("results in " + out.toAbsolutePath());
    }

    static List<Plan> plan(String profile) {
        List<Plan> p = new ArrayList<>();
        if (profile.equals("smoke")) {
            for (String s : List.of("S0", "L1")) {
                p.add(plan("dense", "avg", s, "api", ""));
            }
            return p;
        }
        String[] pct = {"avg", "p90", "p95", "p99"};
        for (String tier : List.of("dense", "hot")) {
            for (String q : pct) {
                for (String s : List.of("S0", "S1", "L1")) {
                    p.add(plan(tier, q, s, "api", ""));
                }
            }
        }
        for (String tier : List.of("standard", "extreme")) {
            for (String q : List.of("avg", "p99")) {
                for (String s : List.of("S0", "L1")) {
                    p.add(plan(tier, q, s, "api", ""));
                }
            }
        }
        for (String s : List.of("L0", "L2")) {
            p.add(plan("hot", "p99", s, "api", "level comparison"));
        }
        for (String w : List.of("bulk", "chain", "api-fail")) {
            for (String s : List.of("S0", "L1")) {
                p.add(plan("hot", "p99", s, w, "workload"));
            }
        }
        for (String s : List.of("S0", "S1", "L0", "L1", "L2")) {
            p.add(new Plan("saturation", "-", s, "api", 0, "closed loop"));
        }
        return p;
    }

    private static Plan plan(String tier, String pct, String strategy, String workload, String note) {
        return new Plan(tier, pct, strategy, workload, Profiles.rate(tier, pct), note);
    }
}
