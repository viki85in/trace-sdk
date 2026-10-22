package org.util.trace.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Writes results.csv and report.md. Overhead rows compare each run with the S0 (no SDK) run of the same cell. */
final class Report {

    private static final String[] COLS = {"tier", "percentile", "strategy", "workload", "targetRate", "achievedRate",
        "avgUs", "p50Us", "p90Us", "p95Us", "p99Us", "p999Us", "maxUs", "allocBytesPerExec", "gcCount", "gcMs",
        "cpuCoresUsed", "exporterCpuMs", "spansStarted", "spansExported", "droppedBufferFull",
        "droppedExportFailed", "internalErrors", "wireBytesPerSpan"};

    private Report() {
    }

    static void write(Path out, List<Map<String, Object>> rows, int warmup, int measure, int threads) throws IOException {
        StringBuilder csv = new StringBuilder(String.join(",", COLS)).append('\n');
        for (Map<String, Object> r : rows) {
            List<String> cells = new ArrayList<>();
            for (String c : COLS) {
                cells.add(String.valueOf(r.get(c)));
            }
            csv.append(String.join(",", cells)).append('\n');
        }
        Files.writeString(out.resolve("results.csv"), csv.toString());

        StringBuilder md = new StringBuilder();
        md.append("# Trace SDK benchmark report\n\n");
        md.append("- JDK ").append(System.getProperty("java.version")).append(", ")
                .append(Runtime.getRuntime().availableProcessors()).append(" cores, ")
                .append(System.getProperty("os.name")).append('\n');
        md.append("- Per run: ").append(warmup).append(" s warm-up, ").append(measure).append(" s measured, ")
                .append(threads).append(" load threads, fresh JVM (2 GB heap), local no-op OTLP sink\n");
        md.append("- Latency is measured from each execution's *intended* start (open loop), in microseconds\n");
        md.append("- One execution of the `api` workload = 10 spans at L1\n\n");

        md.append("## Latency per execution (µs) and overhead versus no SDK (S0)\n\n");
        md.append("| tier | load | strategy | workload | rate/s | avg | p90 | p95 | p99 | Δavg | Δp99 | alloc B/exec | cores | drops |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Map<String, Object> r : rows) {
            Map<String, Object> base = find(rows, r, "S0");
            String dAvg = "-", dP99 = "-";
            if (base != null && !"S0".equals(r.get("strategy"))) {
                dAvg = fmt(num(r, "avgUs") - num(base, "avgUs"));
                dP99 = fmt(num(r, "p99Us") - num(base, "p99Us"));
            }
            md.append(String.format("| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |%n",
                    r.get("tier"), r.get("percentile"), r.get("strategy"), r.get("workload"), r.get("achievedRate"),
                    fmt(num(r, "avgUs")), fmt(num(r, "p90Us")), fmt(num(r, "p95Us")), fmt(num(r, "p99Us")), dAvg, dP99,
                    r.get("allocBytesPerExec"), r.get("cpuCoresUsed"),
                    (num(r, "droppedBufferFull") + num(r, "droppedExportFailed")) == 0 ? "0"
                            : String.valueOf((long) (num(r, "droppedBufferFull") + num(r, "droppedExportFailed")))));
        }
        md.append("\n## Export accounting\n\n");
        md.append("| tier | load | strategy | workload | spans started | exported | dropped (full) | dropped (failed) | wire B/span | exporter CPU ms |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (Map<String, Object> r : rows) {
            if (num(r, "spansStarted") == 0) {
                continue;
            }
            md.append(String.format("| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |%n", r.get("tier"),
                    r.get("percentile"), r.get("strategy"), r.get("workload"), r.get("spansStarted"),
                    r.get("spansExported"), r.get("droppedBufferFull"), r.get("droppedExportFailed"),
                    r.get("wireBytesPerSpan"), r.get("exporterCpuMs")));
        }
        Files.writeString(out.resolve("report.md"), md.toString());
    }

    private static Map<String, Object> find(List<Map<String, Object>> rows, Map<String, Object> r, String strategy) {
        for (Map<String, Object> o : rows) {
            if (strategy.equals(o.get("strategy")) && o.get("tier").equals(r.get("tier"))
                    && o.get("percentile").equals(r.get("percentile")) && o.get("workload").equals(r.get("workload"))) {
                return o;
            }
        }
        return null;
    }

    private static double num(Map<String, Object> r, String k) {
        Object v = r.get(k);
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }
}
