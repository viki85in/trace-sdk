# Trace SDK benchmarks

Synthetic workloads and a load generator that measure what the SDK costs an application at the volumes the spec expects (spec §10.7, built on the NFR-4.1 cost model).

## What it measures

Each run is a fresh JVM (2 GB heap, pre-touched) running a synthetic Spring application, driven **open loop**: executions start at fixed intended times and latency is measured from the intended start, so a stall is charged to everything queued behind it. A separate sink process stands in for the collector (accepts OTLP, discards it, counts requests and bytes).

Per run: avg, p50, p90, p95, p99, p99.9 and max latency per execution; allocation per execution; GC count and time; process CPU; exporter-thread CPU; spans started, exported and dropped; wire bytes per span.

| Axis | Values |
|---|---|
| Strategy | `S0` no SDK, `S1` SDK with `trace.enabled=false`, `L0`, `L1`, `L2` (`trace.level`) |
| Tier (hosts sharing 10⁹ executions/day) | standard 1,000 hosts (11.6/s), dense 100 (115.7/s), hot 10 (1,157/s), extreme 1 (11,574/s) |
| Load level | avg 1.0×, p90 2.0×, p95 2.5×, p99 3.0× of the average rate (p90/p95 are assumed; p99 is the spec's peak factor) |
| Workload | `api` (10 spans), `api-fail` (10% failures), `bulk` (500 records), `chain` (depth 10) |

Application work in each span is a 5 µs spin (`-Dbench.work.ns` to change), so percentage overhead depends on it; compare the absolute microsecond deltas.

## Run

```bash
# build the SDK once so the bench can resolve it
mvn -f ../sdk/pom.xml install -DskipTests      # adjust to the repo's SDK build
cd bench && mvn package -DskipTests

# ~1 minute: S0 vs L1 at dense/avg
java -jar target/trace-sdk-bench-0.1.0-SNAPSHOT.jar suite --profile=smoke --out=/tmp/bench-smoke

# ~45 runs, about 40 minutes: the full matrix
java -jar target/trace-sdk-bench-0.1.0-SNAPSHOT.jar suite --profile=full --out=/tmp/bench-full
```

Output directory: `results.jsonl`, `results.csv`, `report.md`, `sink.log`.

A single run: `java -jar ... run --strategy=L1 --workload=api --rate=1157 --threads=8 --warmup=8 --measure=20 --sink=http://127.0.0.1:PORT` (start a sink with `... sink --port=PORT`). `--rate=0` is closed loop (saturation).

## Reading the results

- Compare each row with the S0 row of the same cell (the report prints Δavg and Δp99).
- "drops" should be 0 below the extreme tier. Drops at extreme show where the 32K buffer and one exporter thread stop keeping up.
- Span accounting covers the measured window; the exporter is drained before the window opens and again before it is read, so started = exported + dropped.
- Run on a quiet machine. On a shared host, repeat and compare; the report records the core count but not other tenants' load.
- Synthetic spans compress better than real ones, so wire bytes per span is a lower bound.

## Reference results

`results/2026-10-22-full/` holds the first full run (36-core shared Linux host, JDK 21, load average about 6). Read it with these caveats:

- Below saturation the runs are dominated by thread park/wake jitter (S0 averages about 100–130 µs for roughly 60 µs of work), so Δ values at low rates are noisy; S1 is sometimes faster than S0. The saturation rows are the clean per-call cost.
- Saturation (closed loop, 8 threads, 10 spans per execution): S0 150.8K exec/s, S1 147.8K (−2.0%), L0 144.7K (−4.1%), L1 141.4K (−6.3%), L2 141.1K (−6.4%). Added latency per execution is about 3.5 µs at L1 (about 0.35 µs per span).
- Allocation at L1 is 2,879 B per execution, of which 1,215 B is already present with `trace.enabled=false` (Spring's proxy invocation). The SDK's own share is about 166 B per span.
- Exporter keeps up with 69K spans/s (hot p99, 0 drops, about 0.4 core). The extreme tier at p99 (347K spans/s into one JVM) drops about 49% of spans at the full buffer, as does saturation; one exporter thread tops out near 175K spans/s.
- Exporter CPU is about 11 µs per span (encode, gzip, HTTP), well above the NFR-1 "≤ 2% of one core at reference load" at the hot tier (about 40%); at dense p99 it is about 4.5% of a core.
- Wire size is about 36 B per span, but the synthetic spans are near-identical, so gzip flatters it; do not compare with the 300 B target.
