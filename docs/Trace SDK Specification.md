# Lightweight Execution Tracing SDK — Specification

| | |
|---|---|
| **Status** | Draft v0.9 |
| **Date** | 2026-10-22 |
| **Owner** | Vikram Subramanian |
| **Repository** | Standalone repository (not part of any application monorepo) |
| **Target** | Spring applications only in v1: Spring Framework 6.x / Spring Boot 3.x (see D-1) |
| **Scope** | Publishing side only (in-process SDK + export). Storage, indexing, and query belong to a separate downstream service. |

**Changes in v0.9:**
- New §15 Wire Size Optimization Options: where the bytes go in a span on the wire and four options for reducing them (trim within OTLP, per-trace constants, batch-level dictionary, custom compact format), with their cost to NFR-2. No option is adopted; §2–§11 are unchanged.
- The ≤ 300 B/span target (NFR-1) stays a target to confirm. The §10.9 figure of ≈ 36 B/span is from near-identical synthetic spans and is a lower bound, so the options are to be ranked after measuring real spans.

**Changes in v0.8:**
- New §10.7 (standard volumes and load profiles), §10.8 (benchmark method) and §10.9 (first results). The macro benchmark lives in `bench/` and reports avg, p50, p90, p95, p99, p99.9 and max per execution at tiers derived from NFR-4.1.
- NFR-4.1 gains a note that the cost points to sampling, with an optional `all` mode. D-1 Option B cons are expanded and §13.3 records why the proxy limits are acceptable.
- Requirements in §2–§11 are unchanged. The first results exceed two NFR-1 targets (throughput loss, exporter CPU); see §10.9.

**Changes in v0.7:**
- New §14 Test Cases: failure-mode and configuration scenarios in Gherkin, grouped by category, each with its test status (automated / manual / not tested) and result. Requirements in §2–§11 are unchanged.
- The scenarios run from the demo project (`trace-sdk-demo`): 15 automated tests plus 1 manual run against a real Jaeger.
- New §13.4 "What I expected to be the hardest" completes the design notes (drafted for the owner to confirm).

**Changes in v0.6:**
- Owner decision: v1 supports **Spring Framework 6.x / Spring Boot 3.x only**. Spring 5.x and other containers are out of scope for v1.

**Changes in v0.5:**
- New decision record D-1 (§2.1) on how `@Trace` methods get their before/after trace code. It compares two options: Proxy AOP and Weaving AOP. **Chosen: Proxy AOP.**
- Every section affected by D-1 is updated: FR-3, FR-4, FR-5, NFR-1, NFR-6, NFR-8, A-6, A-7, C-6, C-7, C-8, and §7–§12.
- The runtime agent (`-javaagent`) is no longer part of v1. It is kept as the alternative option (Option B).

**Changes in v0.4:**
- The SDK is now general-purpose. Content specific to Core and Flow was removed.
- `@Trace` can be placed on a class.
- Developers add annotations only. Everything else is internal.
- Public methods only.
- Formal record format (§6).
- Module layout and distribution (§7).
- Configuration (§8).
- SDK documentation, with usage examples (§11).
- OTLP over HTTP to a collector is the publishing path.
- When the buffer is full, new records are dropped and counted.
- Sampling is still deferred.

**Changes in v0.3:** cost model (NFR-4.1), assumption A-9.
**Changes in v0.2:** counts instead of payloads, bulk fan-out, levels, completion, heartbeat, retention note, measurement.

---

## 1. Problem Statement

We want to record what happens during an execution so it can be inspected and reconstructed later: what ran, in what order, how long each step took, and what failed. Existing tracing often adds enough overhead that it is left off. The goal is tracing that can stay **on by default** without noticeably slowing execution.

This spec defines a **tracing SDK**: something a JVM application adds as a dependency. Developers enable tracing **only by adding annotations**. Everything else is internal to the SDK: context propagation, buffering, batching, export, retries, and health reporting. The SDK only *publishes*. A collector receives the records, and a separate service stores and indexes them.

```
 ┌──────────────────── Application JVM ────────────────────┐
 │  Application code + @Trace annotations                    │
 │      │  (container proxy runs trace advice before/after)  │
 │      ▼                                                    │
 │  Trace context ─► bounded buffer ─► exporter thread       │──OTLP/HTTP──► Collector ──► Store / Index
 │   (hot path)      (drop when full)  (batch, gzip, retry)  │              (ELK / Splunk / Pinot)
 └───────────────────────────────────────────────────────────┘
```

---

## 2. Scope

**In scope**
- Annotations: `@Trace` (class or method), `@Capture`, `@CaptureReturn`, `@NoTrace`.
- A compile-time checker that enforces the annotation rules.
- A framework-free core that handles context, buffering, OTLP/HTTP export, and heartbeats.
- A Spring integration module that registers the trace advice (Proxy AOP, D-1) automatically, and also propagates trace context through container-managed executors and HTTP clients.
- Configuration through system properties, environment variables, or a file, plus an optional admin page and JMX.
- SDK documentation and usage examples (§11).
- A benchmark harness and a harness that checks traces can be rebuilt correctly.

**Out of scope**
- Storage, indexing, search UI, and retention enforcement (downstream service).
- Capturing input/output payloads. Only counts and identifiers are recorded.
- Integration with specific applications.
- **Any framework other than Spring 6.x / Boot 3.x in v1:** no Spring 5.x / Boot 2.x, and no Micronaut, Quarkus/CDI, or Guice. Plain Java apps without a container are also out of scope.
- Metrics and logging SDKs.
- Sampling (deferred, §12).

### 2.1 Decision D-1: how `@Trace` methods get trace code

An annotation is only metadata. It does nothing until some code reads it and runs trace logic before and after the method: start a span, call the method, record duration and status, end the span. This is AOP ("around advice"). Java offers two ways to attach that advice.

**Option A: Proxy AOP. ✅ CHOSEN**

The container (Spring) creates application objects. For every bean with a `@Trace` class or method, it hands out a **proxy** (a JDK dynamic proxy or a CGLIB subclass). Each call to a public method on the proxy runs the SDK's trace advice, which then calls the real method.

| | |
|---|---|
| What the app adds | The `trace-sdk-spring` dependency. Spring Boot picks up the advice automatically; nothing else is needed. No JVM flag and no build plugin. |
| Pros | Plain dependency: add it and it works. A well-known model (same as `@Transactional`). No bytecode rewriting. Matches the public-only rule, since proxies only intercept calls made from outside the object. |
| Cons / limits | **Only container-managed beans are traced.** Objects created with `new` are not. **Calls a class makes to its own methods are not traced** (`this.foo()` skips the proxy). `final` classes and methods can't be proxied with CGLIB (minor: rejected at compile time, §7.2; removing `final` or using an interface avoids it). Each call through a proxy has a dispatch cost, even when tracing is off (NFR-1). Depends on a framework: Spring in v1, other containers need their own adapter. |

**Option B: Weaving AOP (not chosen; kept as an alternative)**

The same before/after advice is written **directly into the method's bytecode**. This is done either when the class loads (a Java agent, `-javaagent`) or during the build (a Maven or Gradle plugin).

| | |
|---|---|
| What the app adds | The annotations dependency, plus either a JVM flag or a build plugin. |
| Pros | Works for any object and for calls a class makes to its own methods. Needs no framework. Lowest cost when tracing is off (one integer comparison). |
| Cons / limits | **Deploy-time change:** either a `-javaagent` flag in every launch configuration (scripts, Dockerfiles, manifests; owned by platform or ops teams), or a build plugin written and maintained for each build tool (Maven, Gradle, Bazel, sbt). If the flag or plugin is forgotten, the annotations are present but no traces appear, and nothing reports the mistake. **Bytecode rewriting:** needs our own ASM or ByteBuddy code, retested on each new Java release; can clash with other agents, coverage tools and profilers; injected code appears in stack traces at lines the developer never wrote; and support depends on JDK, flags, build tool and other agents together. |

**Consequences of choosing A (reflected throughout this spec)**
- **v1 supports Spring Framework 6.x / Spring Boot 3.x only** (owner decision). Spring 5.x, other containers, and apps without a container are not supported in v1. Other containers are possible later options (§7.4).
- Annotations use `RUNTIME` retention, because the container reads them through reflection **once, when it creates the bean**. There is no reflection on each call: capture paths are resolved to `MethodHandle`s at that point.
- Trace context passes automatically between threads only through **container-managed** executors, and is added automatically only to **container-managed** HTTP clients (FR-5).
- The disabled-path performance target is relaxed to account for the proxy's per-call cost (NFR-1). It must be confirmed by measurement (§10).
- To trace a step implemented by a call a class makes to its own methods, move that step into a separate bean (§11.4).
- If these limits prove too costly, Option B can be added later as a separate module. The annotations and record format don't change.

---

## 3. Drivers

### 3.1 Functional Requirements

#### FR-1 Entry-point tracing and service-owned scope
- A trace starts at the first traced method on a thread that has no active trace context. This is normally a service **entry point**: an API handler, message consumer, or scheduled job.
- If the entry method receives an inbound W3C `traceparent` through a `@Capture(as = "traceparent")` parameter, the trace continues that one instead of starting a new one.
- Service owners decide *what* is traced. The SDK documentation recommends where (§11.4).

#### FR-2 Granularity levels

| Level | Records | Recommended for |
|---|---|---|
| `L0_ENTRY` | Entry span only: outcome, duration, error | Highest-volume paths |
| `L1_BOUNDARY` | L0 + a span for each traced public boundary method (component APIs, outbound calls, data access, async hand-offs) | **Default** |
| `L2_STEP` | L1 + methods annotated `level = L2_STEP`, recorded as **events** on the enclosing span, not as separate spans | Debugging |

**How levels work**
1. Each traced method has a minimum level: from its annotation, or else inherited from the class annotation, or else `L1_BOUNDARY`.
2. **The effective level is resolved once, at the entry span**, from configuration: `max(trace.level, trace.level.<operation> override)`. It is stored in the trace context.
3. Children inherit the effective level: across threads (FR-5) and across processes (in `tracestate`, `tsdk=lvl:1`).
4. Each traced method checks `if (ctx.level < site.level) → no-op`. Below the threshold there is no allocation, no timestamp, and no record.
5. An operation override can raise the level for the subtree below that span only.
6. `trace.enabled=false` (the kill switch) sets the effective level to `OFF`.

#### FR-3 Annotation-only enablement
Developers add **annotations and nothing else**. There is no tracing API to call. Buffering, export, retries, context propagation, and health reporting are all internal.

- **Class-level `@Trace`:** traces every `public` method *declared in that class*, with these exclusions:
  - Constructors, synthetic and bridge methods, and lambdas.
  - Overrides of `Object` methods (`toString`, `equals`, `hashCode`).
  - JavaBean accessors (`getX()`, `isX()`, `setX(v)`), unless `excludeAccessors = false`.
  - Methods annotated `@NoTrace`.
- **Class-level `operation` is a prefix.** A method's operation name becomes `<prefix>.<method_name_in_snake_case>`, e.g. `orders.service` + `placeOrder` → `orders.service.place_order`.
- **Method-level `@Trace`** can stand alone, or refine the class settings. Values set on the method override the class values; values left unset are inherited.
- **Public methods only.** `@Trace` on a non-`public` method is a **compile error** (§7.2). At runtime the advice also matches only public methods, and the SDK logs a warning for any others it finds.
- **Only calls through the proxy are traced** (D-1). The bean must be managed by the container. Calls a class makes to its own methods (`this.method()`) are not traced.
- Further rules:
  - A `public` method on a non-public class is allowed.
  - `@Trace` on an abstract or interface method is a compile error. Annotate the concrete method instead.
  - An override is traced only if its own class or the method itself is annotated.
- **Required fields:**
  - `operation`: on a method without a class annotation, or as the prefix on a class.
  - `component`: on the class, or on the method if there is no class annotation.
- **Class, method, and line are recorded automatically**: `code.namespace`, `code.function`, `code.lineno`.
- **Captures are declarative.** Parameters marked `@Capture` and return values marked `@CaptureReturn` record **scalar** values only (numbers, booleans, enums, short strings, IDs), optionally through a property path such as `items.size`. Nothing is captured implicitly. Paths are checked at compile time and resolved to `MethodHandle`s once, when the bean is created. There is no reflective lookup on each call.

#### FR-4 Usable by any Spring application
- **One dependency, no code:** add `trace-sdk-spring`. Spring Boot auto-configuration registers the trace advice. In plain Spring (no Boot), add one `@Import(TraceSdkConfiguration.class)`.
- No JVM flag, no build plugin, no deploy-time change. Works with any build tool: Maven, Gradle, Bazel, sbt.
- v1 supports Spring 6.x / Boot 3.x applications only (D-1).
- If the integration module is absent, or `trace.enabled=false`, the annotations do nothing. There are no errors, so tracing is safe to roll out and remove.
- The SDK uses the application's own Spring (`spring-aop`, `spring-context`) and declares it as a `provided`/`compileOnly` dependency. It never brings its own copy. Any other internal dependency is **shaded and relocated**.

#### FR-5 Cross-thread, async, and bulk linkage
- **One trace per execution, not per record.** A bulk operation over N records is one trace. Counts are attributes (`batch.size`, `records.count`).
- **Fan-out is a tree.** When work is split into m async tasks, each task's spans are children of the span that submitted it.
- **In-process threads:** the SDK propagates context automatically through **container-managed** executors. A post-processor wraps every `Executor`, `ExecutorService`, `TaskExecutor`, and `TaskScheduler` bean, including virtual-thread executors defined as beans. It also covers Spring `@Async` methods. No developer code is involved. At `L1` and above, each propagated task is recorded as an `async.task` span, which includes how long the task waited in the queue.
  - **Not covered (D-1):** executors the application creates with `new` or `Executors.*`, `ForkJoinPool.commonPool()`, and `CompletableFuture.*Async` without an explicit bean executor. Work submitted to these starts a new trace. The fix is to define the executor as a bean (§11.2).
- **Cross-process (outbound):** the SDK automatically adds `traceparent` / `tracestate` headers to requests made with container-managed `RestClient`, `RestTemplate`, and `WebClient`, by registering an interceptor on each builder. Raw `java.net.http.HttpClient` and `HttpURLConnection` calls are not covered.
- **Cross-process (inbound):** the entry method receives the header value through `@Capture(as = "traceparent")` on a parameter (§11.3).
- **Fan-in:** a span with several parents carries **links** to them. Fan-in is detected when a traced method captures more than one inbound `traceparent`, e.g. through a collection path.
- **Parallel branches** are sibling spans. A per-trace monotonic `trace.seq` keeps order without relying on clocks.
- **Durable hand-offs** (work queued to another process to run later) are a known limitation (§12, open question 3).
- **Retries:** each attempt is its own span, and `retry.attempt` is captured from a parameter.

#### FR-6 Payloads: counts, not contents
- Contents are never recorded. Payload size does not affect trace size.

#### FR-7 Failure recording
- Spans end with a status: `OK` when the method returns normally, `ERROR` when it throws.
- On error the span records:
  - An `exception` event with `exception.type`.
  - A stack trace bounded to `trace.stacktrace.max.frames` frames.
  - The exception message, **only if** `trace.capture.exception.message=true` (off by default, because messages often contain user data).
- Partial failure is shown by capturing `records.failed`. If it is captured and greater than 0, the span's status is set to `ERROR` with `failure.kind=PARTIAL`.

#### FR-8 Retention
- Retention is agreed by stakeholders and enforced downstream. The SDK attaches `retention.class`: from `trace.retention.class`, or overridden by `@Trace(retention = "...")`.

> **Note — retention is the main cost control.** Without an enforced retention policy, storage grows without limit. Retention keeps storage steady at *execution volume × bytes per execution × retention window*. Retention classes must be defined and enforced before GA.

#### FR-9 Dynamic control
- These settings can change at runtime without a restart: `trace.enabled` (the kill switch), `trace.level`, per-operation level overrides, and exception-message capture. Changes come through the admin page, JMX, or a watched config file (§8).

#### FR-10 Required attributes
The fields the owner specified are mandatory on every span: **tenant ID, organization ID, timestamp, trace ID, parent span, span, and time consumed**. The full schema is in §6.

- `tenant.id` and `org.id` are captured once at the entry method (`@Capture(as = "tenant.id")`). They are **inherited by every span in the trace**, across threads and processes (`tracestate`).
- If either is missing, the span is still recorded with the value `_missing_`, and the `spans.missing_tenant` counter goes up. The SDK never throws.

#### FR-11 Completion and completeness
- **Completion:** `@Trace(completion = Completion.ENDS_EXECUTION)` marks the method whose end means the execution is finished. When it ends, the span records `execution.complete=true`.
- **Completeness:** the reconstructor treats a trace as **complete** only when all three of these hold:
  1. The completion marker has arrived.
  2. `trace.seq` values `1..max` have no gaps. A gap means a lost record.
  3. Every span's `async.spawned` count equals the number of `async.task` children received.
- Any span ending after the buffer drops a record of the same trace carries the running count `trace.dropped`.

#### FR-12 SDK health reporting (heartbeat)
- Every `trace.heartbeat.interval.s` seconds, the exporter sends a `trace_sdk.heartbeat` record (§6.5). It carries **cumulative** counters, so the first heartbeat after an outage reports everything lost during it.
- If a host stops sending heartbeats, the collector or store can detect it.
- The same counters are available over JMX and on the admin page (§8.4).

### 3.2 Non-Functional Requirements

#### NFR-1 Performance (primary driver)

| Metric | Target |
|---|---|
| Overhead when disabled / below level | ≤ 200 ns per traced call, including proxy dispatch (**provisional**; confirm via JMH, §10). No allocation by the SDK beyond what Spring's invocation itself allocates. |
| Overhead per span at `L1` | ≤ 1.5 µs p50, ≤ 5 µs p99 on the calling thread, including proxy dispatch (**provisional**) |
| Added end-to-end latency at `L1`, reference workload | ≤ 2% p50, ≤ 5% p99 |
| Throughput reduction at `L1` | ≤ 3% |
| Allocation per span (hot path) | ≤ 256 B |
| SDK memory | Hard-bounded (`trace.buffer.max.bytes`, default 16 MB) |
| Exporter CPU | ≤ 2% of one core at reference load |
| Network | Batched + gzip; ≤ 300 B/span on the wire at `L1` |
| Startup | ≤ 200 ms added to application context startup (advisor matching, proxy creation, `MethodHandle` resolution) |

- **Synchronous (on the execution path):** ID generation, timestamps, `trace.seq`, context push/pop, copying captured scalars into a pre-allocated record, enqueue.
- **Asynchronous (off the execution path):** encoding, compression, batching, HTTP export, retry, heartbeat.
- **Interception is Proxy AOP (D-1).** Proxy dispatch costs something on every call, even when tracing is off. The SDK keeps the rest as small as possible:
  - Which methods match, their operation names, and their capture handles are all worked out **once, when the bean is created**, and cached for each method.
  - The advice first checks the level (one integer compare) and then returns `invocation.proceed()` immediately if the method is below level.
  - The SDK adds only one advisor per bean, never a chain of advisors.
- The disabled-path cost is measured against the weaving alternative (S2, §10.1). If it exceeds the targets, D-1 is revisited.
- The SDK never blocks or throws into the caller, and never holds locks across application code.

#### NFR-2 Vendor-agnostic
- The wire format is OTLP. Changing the backend is a collector configuration change.

#### NFR-3 Searchability (downstream contract)
- Indexing is eventually consistent (target ≤ 5 min p95, owned downstream).
- Operation names are low-cardinality, and the attribute keys are fixed (§6), so the backend can index them cheaply.

#### NFR-4 Cost
- Bytes per execution are bounded by level and step count, not by record count or payload size.
- Storage stays steady only when retention is enforced (FR-8).

##### NFR-4.1 Network and storage cost model
Network and storage costs are incurred when records are **written**, not when they are queried. Filtering or sampling at query time makes queries faster, but does not reduce any of these costs.

| Symbol | Meaning | Illustrative value | Source |
|---|---|---|---|
| `E` | Executions per day | 1,000,000,000 | *Assumed — replace with measured volume* |
| `S` | Spans per execution | 10 at `L1` | FR-2 |
| `B` | Bytes per span on the wire (compressed) | 300 B | NFR-1 |
| `U` | Bytes per span as ingested (uncompressed) | 1 KB | *Assumed — replace with measured* |
| `X` | Stored bytes ÷ wire bytes (indexes, replicas) | 2–5× | *Typical; backend-dependent* |
| `R` | Retention window (days) | 30 | FR-8 |
| `P` | Peak-to-average ratio | 3× | *Assumed* |
| `H` | Hosts emitting traces | 1,000 | *Assumed* |

| Quantity | Formula | Worked example (`L1`) |
|---|---|---|
| Spans per second | `E × S ÷ 86,400` | ≈ 116 K avg, ≈ 347 K peak |
| Wire bytes per day | `E × S × B` | 3 TB/day |
| Network bandwidth into collectors | `E × S × B ÷ 86,400` (× `P` for peak) | ≈ 35 MB/s (≈ 280 Mbit/s) avg; ≈ 104 MB/s (≈ 830 Mbit/s) peak |
| Bandwidth per host | above ÷ `H` | ≈ 35 KB/s avg, ≈ 104 KB/s peak |
| Ingest volume per day | `E × S × U` | ≈ 10 TB/day |
| Storage at steady state | `E × S × B × X × R` | 90 TB wire-equivalent → **≈ 180–450 TB** |

| Change | Wire bytes per day | Steady-state storage (30 d) |
|---|---|---|
| `L0` (S = 1) | 0.3 TB | 18–45 TB |
| `L1` (S = 10) — baseline | 3 TB | 180–450 TB |
| `L2` (≈ 30 spans + events) | ≈ 9 TB | 540–1,350 TB |
| 7 d retention (`L1`) | 3 TB | 42–105 TB |
| 90 d retention (`L1`) | 3 TB | 540–1,350 TB |

What this shows:
- Per-host network cost is minor. The totals arriving at the collector and storage tiers are what matter.
- Storage depends on volume × level × retention. Indexing cost does not depend on retention.
- The illustrative inputs must be replaced with measured values (§10) before sizing.

> **Note — if the measured cost is prohibitive, the next step is sampling (owner direction).**
> The figures above assume every execution is traced in full (A-9). If measured volume turns the result into a cost that is too high (the illustrative inputs give ≈ 3 TB/day on the wire and ≈ 180–450 TB stored at `L1` and 30 days), the levers already in the spec (level and retention) may not be enough on their own. In that case:
> - **Add sampling.** Only a configurable share of executions is traced, and cost falls roughly in proportion to the share kept (A-9).
> - **Keep an optional `all` mode, enabled by configuration.** A service or environment can switch back to tracing every execution when it needs full reconstruction, for example while debugging or for a critical flow.
> - **The decision is made once per execution.** It would be made at the entry span and carried to the whole trace, across threads and processes, in the same way as the effective level (FR-2). A trace is then kept in full or not at all, so kept traces stay complete (FR-11).
>
> What remains open: the sampling method and default rate, whether `all` mode applies per service or per operation, and how the reconstructor tells a dropped-by-sampling trace from a lost one (§12 lists sampling as deferred). Until sampling is decided, sizing uses A-9.

#### NFR-5 Reliability and failure isolation
- If the pipeline fails, the application does not notice.
- **When the buffer is full, new records are dropped**, counted by reason, and reported in the heartbeat and in `trace.dropped`. There is no disk spill.
- Export retries use bounded exponential backoff with jitter. Batches waiting to be retried count toward the memory cap.

#### NFR-6 Lightweight footprint
- `trace-sdk-annotations` ≤ 30 KB, with zero dependencies.
- `trace-sdk-core` ≤ 1 MB, JDK only.
- `trace-sdk-spring` ≤ 200 KB, with Spring as a `provided` dependency (not bundled).

#### NFR-7 Tenant isolation and transport security
- Every span carries `tenant.id` and `org.id`. A span never carries data from more than one tenant.
- Export over HTTPS is supported, with optional mTLS (§8.2).

#### NFR-8 Compatibility and evolution
- **Java 17+** runtime, as required by Spring Framework 6 / Boot 3. Tested on 17, 21, and 25. `trace-sdk-core` itself compiles for Java 11, so future adapters can run on older runtimes.
- **Spring Framework 6.x / Spring Boot 3.x only.** Spring 5.x / Boot 2.x is not supported in v1.
- Semantic versioning. The record schema is versioned (`tsdk.schema.version`), and downstream must accept N-1.
- Works alongside other Spring advisors (`@Transactional`, `@Cacheable`). The trace advisor is ordered **outermost** (`Ordered.HIGHEST_PRECEDENCE`) so that span time includes them.
- Works alongside the OpenTelemetry Java agent. If both are present, each uses its own context, and the open question on bridging them (§12) decides whether the two context systems are joined.

---

## 4. Assumptions

| # | Assumption | Impact if wrong |
|---|---|---|
| A-1 | **Confirmed by owner:** an OTLP collector tier exists and is reachable over HTTP from every host. | — |
| A-2 | **Confirmed by owner:** a storage/indexing service receives from the collector and enforces retention. | — |
| A-3 | Non-real-time visibility (minutes) is acceptable. | Would need a streaming index path. |
| A-4 | Clock skew between hosts is bounded (NTP). Order comes from `trace.seq` and parent links. | Cross-host timing stays approximate. |
| A-5 | Developers follow the naming convention (enforced at compile time). | Too many distinct names to index cheaply. |
| A-6 | Consuming applications are Spring-managed (Spring Framework 6 / Boot 3), and the code worth tracing lives in container beans. | Needs another container adapter, or the weaving alternative (D-1 Option B). |
| A-7 | Consuming applications run on Java 17+ and Spring 6.x / Boot 3.x. | Those apps must upgrade before adopting the SDK; v1 has no Spring 5 support. |
| A-8 | Stakeholders agree on retention classes and TTLs before GA. | Provisional defaults apply. |
| A-9 | **Full trace image of every execution.** Until sampling is decided (§12), sizing assumes every execution is traced in full at its effective level. | If sampling is adopted, cost drops roughly in proportion to the sample rate, but executions that weren't kept can't be reconstructed. |
| A-10 | The service boundaries worth tracing are calls *between* beans, not calls a class makes to its own methods. | Those steps are invisible unless they move to separate beans or D-1 is revisited. |

> **Note on A-9 — what a full trace image of every execution requires**
> - **Cost grows linearly with volume.** Network, ingest, and storage grow one-for-one with execution volume. Nothing caps them on the write side.
> - **Sizing for peak.** The collector and storage tiers must absorb peak volume (`P` × average) without back-pressuring the SDK. Otherwise the buffer fills and records are dropped (NFR-5), and the guarantee no longer holds in exactly the periods where traces matter most.
> - **Two controls remain.** Level controls bytes per execution. Retention controls storage. Indexing cost has only one control: level.
> - **The benefit:** every execution can be reconstructed within the retention window.
> - **Under the illustrative inputs:** ≈ 3 TB/day on the wire and ≈ 180–450 TB stored at 30-day retention for `L1`.

---

## 5. Constraints

| # | Constraint | Source |
|---|---|---|
| C-1 | The SDK must never block, throw into, or change the behaviour of the application. | Reliability |
| C-2 | Memory is hard-capped. No unbounded queues. | Shared JVM |
| C-3 | Wire protocol is OTLP. v1 transport is OTLP/HTTP (protobuf or JSON), because it needs only the JDK's built-in HTTP client. | NFR-2, NFR-6 |
| C-4 | Developer-facing surface is annotations only. No tracing API calls in application code. | Owner requirement |
| C-5 | Only `public` methods may be traced (enforced at compile time and at runtime). | Owner requirement |
| C-6 | Interception is Proxy AOP through the container (D-1). Method matching, naming, and capture-handle resolution happen once, when the bean is created. There is no reflective lookup on each call. | D-1, NFR-1 |
| C-7 | `trace-sdk-annotations` has zero dependencies. `trace-sdk-core` uses only the JDK. Spring is `provided` and never bundled. | FR-4 |
| C-8 | Framework dependencies are confined to the integration module (`trace-sdk-spring`). The annotations and core remain framework-free. | D-1 |
| C-9 | No payload contents recorded. Captures are scalar only. Exception messages are off by default. | FR-6, FR-7 |
| C-10 | Every span carries `tenant.id` and `org.id`. | Tenant isolation |
| C-11 | `operation` must match `^[a-z0-9]+(\.[a-z0-9_]+)+$` and must not contain IDs or other high-cardinality values. | Index cost |
| C-12 | The SDK only publishes. It never queries or waits for the storage service. | Scope |

---

## 6. Record Format

Records are encoded as **OTLP `TracesData`** (protobuf by default, JSON optional), so any OTLP collector accepts them unchanged. This section defines how each field maps into OTLP.

### 6.1 Resource (sent once per export batch)

| Attribute | Type | Source | Example |
|---|---|---|---|
| `service.name` | string | `trace.service.name` (**required**) | `order-service` |
| `service.version` | string | `trace.service.version` | `2.14.0` |
| `deployment.environment` | string | `trace.environment` | `prod` |
| `host.name` | string | Detected automatically | `ip-10-0-3-17` |
| `process.pid` | int | Detected automatically | `4121` |
| `telemetry.sdk.name` | string | Constant | `trace-sdk` |
| `telemetry.sdk.version` | string | Constant | `1.0.0` |
| `tsdk.schema.version` | int | Constant | `1` |

Instrumentation scope: `name = "trace-sdk"`, `version = <sdk version>`.

### 6.2 Span

| Logical field | OTLP field / attribute | Type | Required | Source |
|---|---|---|---|---|
| Trace ID | `trace_id` | 16 bytes (32 hex) | ✔ | Generated at entry, or taken from inbound `traceparent` |
| Span ID | `span_id` | 8 bytes (16 hex) | ✔ | Generated |
| Parent span | `parent_span_id` | 8 bytes | ✔ (empty for root) | Context |
| Operation | `name` | string | ✔ | `@Trace.operation` (or prefix + method) |
| Kind | `kind` | enum | ✔ | `ENTRY`→`SERVER`, `INTERNAL`, `CLIENT`, `PRODUCER`, `CONSUMER` |
| Timestamp (start) | `start_time_unix_nano` | fixed64 | ✔ | Wall clock at start |
| Time consumed | `end_time_unix_nano` = start + monotonic duration; also `duration.ns` | fixed64 / int | ✔ | Monotonic clock (`System.nanoTime`) |
| Status | `status.code` | enum | ✔ | `OK` / `ERROR` (OTLP `STATUS_CODE_OK` / `STATUS_CODE_ERROR`) |
| Tenant ID | `tenant.id` | string | ✔ | Captured at entry, inherited |
| Organization ID | `org.id` | string | ✔ | Captured at entry, inherited |
| Component | `component` | string | ✔ | `@Trace.component` |
| Class | `code.namespace` | string | ✔ | Added automatically |
| Method | `code.function` | string | ✔ | Added automatically |
| Line | `code.lineno` | int | ✔ | Added automatically |
| Sequence | `trace.seq` | int | ✔ | Per-trace monotonic counter, starting at 1 |
| Level | `trace.level` | int | ✔ | Effective level |
| Execution ID | `execution.id` | string | — | `@Capture(as = "execution.id")`; inherited when set |
| Retention class | `retention.class` | string | ✔ | Config or `@Trace.retention` |
| Thread | `thread.name` | string | ✔ | Current thread |
| Captured values | `<as>` key from `@Capture` / `@CaptureReturn` | scalar | — | Annotation |
| Async spawned | `async.spawned` | int | when > 0 | Count of tasks submitted while this span was active |
| Completion | `execution.complete` | bool | when true | `completion = ENDS_EXECUTION` |
| Dropped so far | `trace.dropped` | int | when > 0 | Buffer drops for this trace |
| Failure kind | `failure.kind` | string | on partial failure | `PARTIAL` / `ALL` |

**Reserved capture keys** (with a fixed meaning): `tenant.id`, `org.id`, `execution.id`, `traceparent`, `records.count`, `records.failed`, `batch.size`, `retry.attempt`. Application-specific keys must use the `app.` prefix (e.g. `app.region`).

### 6.3 Span events

| Event | When | Attributes |
|---|---|---|
| `exception` | The method threw | `exception.type`, `exception.stacktrace` (bounded), `exception.message` (only if enabled) |
| `<operation>` (step) | A method with `level = L2_STEP` ran and the effective level ≥ `L2` | `code.function`, `duration.ns`, `trace.seq`, captured values |

### 6.4 Async task span
Emitted at `L1`+ for each task whose context was propagated to another thread:
- `name = "async.task"`, `kind = INTERNAL`, parent = the submitting span.
- Attributes: `queue.wait.ns` (time from submission to start), `executor.class`, plus the inherited mandatory fields.

### 6.5 Heartbeat record
- Sent as a span named `trace_sdk.heartbeat`, with zero duration and no parent, under the same resource. This keeps one signal type and one encoder.
- Its trace ID is random. Its counters are **cumulative since process start**.

| Attribute | Type |
|---|---|
| `tsdk.spans.started`, `tsdk.spans.exported` | int |
| `tsdk.spans.dropped.buffer_full`, `tsdk.spans.dropped.export_failed` | int |
| `tsdk.spans.missing_tenant` | int |
| `tsdk.export.errors`, `tsdk.export.latency.p99.ms` | int |
| `tsdk.buffer.high_water.bytes` | int |
| `tsdk.exporter.cpu.ns` | int |
| `tsdk.hotpath.p50.ns`, `tsdk.hotpath.p99.ns` | int |
| `tsdk.config.enabled`, `tsdk.config.level` | bool / int |

### 6.6 Example (OTLP/JSON, one span, abbreviated)
```json
{
  "resourceSpans": [{
    "resource": { "attributes": [
      { "key": "service.name",        "value": { "stringValue": "order-service" } },
      { "key": "tsdk.schema.version", "value": { "intValue": "1" } } ] },
    "scopeSpans": [{
      "scope": { "name": "trace-sdk", "version": "1.0.0" },
      "spans": [{
        "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
        "spanId": "00f067aa0ba902b7",
        "parentSpanId": "a3ce929d0e0e4736",
        "name": "orders.service.place_order",
        "kind": "SPAN_KIND_INTERNAL",
        "startTimeUnixNano": "1792310400000000000",
        "endTimeUnixNano":   "1792310400004213000",
        "status": { "code": "STATUS_CODE_OK" },
        "attributes": [
          { "key": "tenant.id",      "value": { "stringValue": "t-042" } },
          { "key": "org.id",         "value": { "stringValue": "00D5g000004XyZ" } },
          { "key": "component",      "value": { "stringValue": "order-service" } },
          { "key": "code.namespace", "value": { "stringValue": "com.acme.orders.OrderService" } },
          { "key": "code.function",  "value": { "stringValue": "placeOrder" } },
          { "key": "trace.seq",      "value": { "intValue": "3" } },
          { "key": "duration.ns",    "value": { "intValue": "4213000" } },
          { "key": "records.count",  "value": { "intValue": "12" } },
          { "key": "retention.class","value": { "stringValue": "standard-30d" } }
        ]
      }]
    }]
  }]
}
```

---

## 7. Module Layout and Distribution

Standalone repository, published to Maven Central (or an internal artifact repository) under a single group ID, e.g. `com.<org>.tracesdk`.

### 7.1 Artifacts

| Artifact | What it is | Who adds it | Dependencies |
|---|---|---|---|
| `trace-sdk-annotations` | `@Trace`, `@Capture`, `@CaptureReturn`, `@NoTrace`, and the enums `Kind`, `Level`, `Completion`. Retention `RUNTIME` (the container reads them when it creates the bean). | Pulled in transitively by `trace-sdk-spring` | None |
| `trace-sdk-processor` | Annotation processor that enforces the rules at compile time | Build: **annotation-processor path** | None at runtime |
| `trace-sdk-core` | Framework-free runtime: context, buffer, OTLP/HTTP exporter, heartbeat, config, admin page, JMX | Pulled in transitively | JDK only (`java.net.http`, `java.util.zip`, `com.sun.net.httpserver`, JMX) |
| `trace-sdk-spring` | **The one dependency developers add.** Contains: the trace advisor (Proxy AOP, D-1), Boot auto-configuration, executor-wrapping post-processor, `RestClient`/`RestTemplate`/`WebClient` interceptors, optional Actuator endpoint | Developers: **compile/runtime scope** | `trace-sdk-annotations`, `trace-sdk-core`; Spring `provided` |
| `trace-sdk-bom` | Version alignment | Optional | — |

Internal packages: `context`, `buffer`, `export.otlp` (a hand-written OTLP protobuf/JSON encoder, so there's no protobuf dependency), `config`, `admin`, `health` in core; `aop`, `propagation`, `autoconfigure` in spring. None of these is a public API.

### 7.2 Compile-time rules (`trace-sdk-processor`)
These are build errors:
- `@Trace` on a non-`public`, abstract, or interface method.
- A missing `operation` or `component` (after inheritance from the class).
- An `operation` that doesn't match C-11.
- A `@Capture` / `@CaptureReturn` path that doesn't resolve to a scalar type.
- An `as` key that is neither reserved nor `app.`-prefixed.
- `@NoTrace` on a class.
- `@Trace` on a `final` class, or on a `final` method. CGLIB proxies can't intercept these (D-1).

### 7.3 Runtime behaviour without the processor
The integration module runs the same checks when it creates each bean, logs a warning, and skips invalid sites. An application that forgot the processor still runs safely.

### 7.4 Later options (not in v1)
- **More container adapters (post-v1, not committed):** Micronaut, Quarkus/CDI, Guice. Each is a small module that registers the same advice using that container's interceptor mechanism.
- **Weaving alternative (D-1 Option B):** a Java agent, or a Maven/Gradle build plugin. For apps without a container, or for calls a class makes to its own methods.
- **OTLP/gRPC transport** as an optional module.
- **An OpenTelemetry bridge**, so a host that also uses OTel shares one context.

---

## 8. Configuration

### 8.1 Sources and precedence (highest wins)
1. **Runtime override:** the admin page or JMX. Applies only to dynamic keys, and is lost on restart unless `trace.admin.persist=true`.
2. **JVM system property:** `-Dtrace.level=L2_STEP`.
3. **Environment variable:** the key in upper case with `.` replaced by `_` (e.g. `TRACE_LEVEL=L2_STEP`).
4. **Spring configuration:** `trace.*` keys in `application.properties` / `application.yml`, profiles, or a config server. These are read through the Spring `Environment`.
5. **Config file:** `trace.config.file` (default: `trace-sdk.properties` in the working directory, then on the classpath). Watched for changes every `trace.config.reload.s`.
6. **Built-in defaults.**

Invalid values are logged and ignored, and the previous value stays in effect. The SDK never fails application startup because of its own configuration. The one exception is `trace.service.name`: if it is missing, it falls back to `spring.application.name`. If that is also missing, the SDK disables itself and logs an error.

### 8.2 Keys

| Key | Default | Dynamic | Description |
|---|---|---|---|
| `trace.enabled` | `true` | ✔ | Kill switch |
| `trace.level` | `L1_BOUNDARY` | ✔ | Default effective level |
| `trace.level.<operation>` | — | ✔ | Per-operation override, e.g. `trace.level.orders.service.place_order=L2_STEP` |
| `trace.service.name` | — (**required**) | | Resource `service.name` |
| `trace.service.version` | — | | Resource `service.version` |
| `trace.environment` | — | | Resource `deployment.environment` |
| `trace.retention.class` | `standard-30d` | ✔ | Default retention class |
| `trace.exporter.endpoint` | `http://localhost:4318/v1/traces` | | Collector OTLP/HTTP endpoint |
| `trace.exporter.protocol` | `http/protobuf` | | `http/protobuf` or `http/json` |
| `trace.exporter.compression` | `gzip` | | `gzip` or `none` |
| `trace.exporter.headers` | — | | e.g. `Authorization=Bearer …,X-Tenant=…` |
| `trace.exporter.timeout.ms` | `10000` | | Per-request timeout |
| `trace.exporter.tls.truststore` / `.keystore` / `.password` | — | | HTTPS and mTLS |
| `trace.exporter.retry.max.attempts` | `5` | | Exponential backoff with jitter |
| `trace.buffer.max.spans` | `32768` | | Buffer capacity (records) |
| `trace.buffer.max.bytes` | `16777216` | | Buffer capacity (bytes). Whichever limit is reached first applies |
| `trace.batch.max.spans` | `512` | | Records per export request |
| `trace.batch.max.delay.ms` | `1000` | | Maximum wait before a partial batch is sent |
| `trace.capture.exception.message` | `false` | ✔ | Record exception messages |
| `trace.stacktrace.max.frames` | `20` | ✔ | Stack trace bound |
| `trace.heartbeat.interval.s` | `60` | | Heartbeat period |
| `trace.selftiming.sample` | `1024` | ✔ | Time the SDK's own hot path for 1 in N spans |
| `trace.propagation.executors` | `true` | | Wrap container-managed executor and scheduler beans |
| `trace.propagation.http` | `true` | | Automatic `traceparent` on container-managed `RestClient` / `RestTemplate` / `WebClient` |
| `trace.aop.order` | `HIGHEST_PRECEDENCE` | | Advisor order relative to other Spring advisors |
| `trace.actuator.enabled` | `true` | | Expose `/actuator/tracesdk` when Spring Boot Actuator is present |
| `trace.config.file` | `trace-sdk.properties` | | Config file location |
| `trace.config.reload.s` | `30` | | File watch interval |
| `trace.admin.enabled` | `false` | | Turn on the built-in admin page |
| `trace.admin.bind` | `127.0.0.1` | | Admin page bind address |
| `trace.admin.port` | `9464` | | Admin page port |
| `trace.admin.token` | — | | **Required** if the admin page is on; requests without it are rejected |
| `trace.admin.persist` | `false` | | Write runtime overrides to the config file |
| `trace.jmx.enabled` | `true` | | Register the `tracesdk:type=Config` and `tracesdk:type=Stats` MBeans |

### 8.3 Minimal setup
With `trace-sdk-spring` on the classpath, configuration is all that remains:
```yaml
# application.yml
trace:
  service.name: order-service
  exporter.endpoint: https://otel-collector.internal:4318/v1/traces
```
Or, without touching the app:
```bash
export TRACE_SERVICE_NAME=order-service
export TRACE_EXPORTER_ENDPOINT=https://otel-collector.internal:4318/v1/traces
```

### 8.4 The configuration page (suggestion)
The owner asked for a simple page in the consuming application. There are four options, and the application can use any of them. For Spring Boot apps the Actuator endpoint is the most natural fit.

| Option | How | When to use |
|---|---|---|
| **Actuator endpoint** (Spring Boot) | `GET /actuator/tracesdk` returns effective config and stats. `POST` changes dynamic keys. Secured by the app's existing Actuator security. | Spring Boot apps with Actuator (recommended) |
| **Built-in admin page** | `trace.admin.enabled=true` starts a tiny page on the JDK's built-in HTTP server (`com.sun.net.httpserver`). `GET /` shows effective config (with its source) plus live stats. A form `POST`s changes to dynamic keys. Token required. Binds to localhost by default. | Simplest. Expose it through the app's reverse proxy or admin network |
| **JMX** | `tracesdk:type=Config` has a setter for each dynamic key. `tracesdk:type=Stats` exposes the heartbeat counters. | Apps that already have a JMX console, or an admin page that can call JMX |
| **Watched config file** | Edit `trace-sdk.properties`. The change is picked up within `trace.config.reload.s`. | Config-management tooling, or ops without UI access |

Admin page layout (illustrative):
```
Trace SDK — order-service (prod)                     [token required]
──────────────────────────────────────────────────────────────────
Enabled            [✔]                         source: default
Level              [L1_BOUNDARY ▾]             source: env TRACE_LEVEL
Exception messages [ ]                         source: default
Level overrides    orders.service.place_order = L2_STEP   [remove] [+ add]
                                                         [Apply]
──────────────────────────────────────────────────────────────────
Spans started 1,204,331 · exported 1,204,107 · dropped 0 · export errors 0
Buffer high-water 2.1 MB / 16 MB · Hot path p99 0.8 µs · Last export 0.4 s ago
```

---

## 9. Internal Design Notes (non-public)

- **Interception (Proxy AOP, D-1):**
  - A single `Advisor` is registered. Its pointcut matches public methods on beans that have `@Trace` on the class or on the method.
  - The pointcut is evaluated **once per method, when the bean is created**. For each matched method, a `TraceSite` is cached: operation, component, kind, level, completion, and capture `MethodHandle`s.
  - The advice (`MethodInterceptor.invoke`) looks up the `TraceSite`, which is pre-bound per method, and runs the level check. If the method is below level, it returns `proceed()`. Otherwise it pushes context, records the start time and `trace.seq`, and calls `proceed()`. In a `finally` block it records duration and status, then enqueues the record.
  - The advisor is ordered outermost, so time spent in `@Transactional` and other advice is counted in the span.
- **Context:**
  - Held per thread, with a trace-scoped shared state (`seq` counter, `dropped` counter, tenant/org/execution IDs, effective level).
  - Copied into wrapped tasks at submission time by the executor-bean wrapper (a `BeanPostProcessor` plus a `TaskDecorator`).
- **Buffer:**
  - A bounded multi-producer, single-consumer ring of pre-allocated records.
  - The producer path never takes a lock. When it's full: drop the new record, increment counters.
- **Exporter:**
  - One daemon thread drains the buffer into batches, encodes OTLP, gzips, and POSTs with `java.net.http.HttpClient`.
  - Retries with backoff. Gives up after `retry.max.attempts` and counts `dropped.export_failed`.
- **Shutdown:** on context close (`SmartLifecycle.stop`), with a JVM shutdown hook as fallback, the SDK flushes the buffer, waiting at most `trace.exporter.timeout.ms`.

---

## 10. Verification and Measurement

### 10.1 Strategies compared against the baseline
| Strategy | Description |
|---|---|
| S0 Baseline | No `trace-sdk-spring`, so no proxy |
| S1 Proxy present, `trace.enabled=false` | Disabled-path cost (proxy dispatch + level check) |
| S2 Weaving prototype (D-1 Option B) | Alternative, measured for comparison. Input to revisiting D-1. |
| S3 Sync export | Encode and send on the calling thread (worst case) |
| S4 Proposed | Proxy AOP + async buffered export at `L0`, `L1`, `L2` |

### 10.2 Scenarios
1. Bulk operations over 1, 200, and 2,000 records, including fan-out into m async tasks.
2. Nested traced calls (depth 1, 5, 10), mixing in-process calls and outbound HTTP.
3. Sync and async (thread pools, `CompletableFuture`, virtual threads).
4. Failures and retries partway through, including partial failure.
5. Collector unavailable or slow (buffer fills, drops are counted, the application is unaffected).

### 10.3 Pre-production measurement

| Layer | Tool | Measures |
|---|---|---|
| Micro | JMH with `-prof gc` | Per-call ns and bytes: disabled, below level, enabled |
| Macro | Reference app, S0 vs S4 per level, interleaved runs, ≥ 30 iterations | Latency percentiles (HdrHistogram), throughput, confidence intervals |
| Memory / GC | JFR | Allocation rate, GC pauses, buffer occupancy |
| CPU | JFR; `ThreadMXBean` for the exporter thread | Exporter CPU share |
| Network | Exporter byte counters | Bytes/span, bytes/execution |
| Storage | Storage service | Bytes stored per execution (input to NFR-4.1) |
| Startup | Application context start-to-ready, with and without `trace-sdk-spring` | Proxy creation and advisor matching cost |

### 10.4 Production self-measurement
- For 1 in `trace.selftiming.sample` spans, the SDK times its own hot path. p50/p99 are reported in the heartbeat.
- The heartbeat also carries exporter CPU and bytes.
- Net overhead on real traffic is confirmed by a **kill-switch A/B**: tracing is disabled on a small set of hosts and compared with the rest.

### 10.5 Trace quality
A reconstruction harness rebuilds each trace from exported records and compares it with a ground-truth log. It scores:
- Node recall.
- `trace.seq` ordering.
- Parent/child and fan-out correctness.
- Error attribution.
- Completeness accuracy (FR-11).

### 10.6 Exit criteria
S4 at `L1` meets every NFR-1 target and scores 100% on reconstruction when the pipeline is healthy.

### 10.7 Standard volumes and load profiles

The macro benchmark (`bench/`) drives a synthetic application at volumes derived from the NFR-4.1 cost model. A **tier** says how much of the fleet's volume lands on one JVM; a **load level** says how far above the daily average the traffic is.

| Tier | Hosts sharing the volume | Executions/s per JVM at average load | Meaning |
|---|---|---|---|
| standard | 1,000 (the NFR-4.1 `H`) | 11.6 | A typical host |
| dense | 100 | 115.7 | A well-used service tier |
| hot | 10 | 1,157 | A concentrated tier (≈ 11.6K spans/s at L1) |
| extreme | 1 | 11,574 | The whole fleet's volume through one JVM; a stress case, not an expectation |

| Load level | Multiple of average | Basis |
|---|---|---|
| avg | 1.0× | `E` = 10⁹ executions/day ÷ 86,400 s |
| p90 | 2.0× | **Assumed** |
| p95 | 2.5× | **Assumed** |
| p99 | 3.0× | The NFR-4.1 peak-to-average ratio `P` |

The p90 and p95 multipliers are placeholders; replace them with measured traffic percentiles as soon as production data exists. Results are reported for each (tier, load level) cell as **avg, p50, p90, p95, p99, p99.9 and max latency per execution**, measured from the intended start time of each execution (open loop, so queuing behind a stall is not hidden), next to the S0 baseline for the same cell. Each run also reports allocation per execution, GC count and time, process CPU, exporter-thread CPU, spans started/exported/dropped, and wire bytes per span.

Workloads: `api` (10 spans at L1, 2 step events at L2), `api-fail` (the same with 10% failures), `bulk` (500 records in 50-record chunks), `chain` (depth 10). Strategies: S0, S1, and the SDK at L0, L1 and L2. A closed-loop saturation run reports maximum throughput per strategy.

Caveats: the application work in each span is a fixed 5 µs spin, so percentage overhead scales with it; the sink is a local no-op collector; synthetic spans compress better than real ones, so wire bytes per span is a lower bound.

### 10.8 Benchmark method

- **Harness:** `bench/` (standalone Maven project). `suite` starts a sink process and then one fresh JVM per run (2 GB heap, pre-touched); `run` executes a single cell; `sink` is a no-op OTLP/HTTP collector that counts requests and bytes.
- **Open loop:** executions are scheduled at fixed intended start times across 8 load threads, and latency is recorded (HdrHistogram, ns) from the intended start. Rate 0 is closed loop and measures saturation throughput.
- **Phases:** 8 s warm-up (half closed loop for JIT, half at the target rate), drain of the exporter, then 20 s measured. Span accounting is read after a second drain, so started = exported + dropped.
- **Recorded per run:** latency distribution, achieved rate, allocation per execution (`ThreadMXBean`), GC count and time, process CPU, exporter-thread CPU, spans started/exported/dropped, sink requests and bytes.
- **Matrix (45 runs):** dense and hot tiers × {avg, p90, p95, p99} × {S0, S1, L1}; standard and extreme × {avg, p99} × {S0, L1}; L0 and L2 at hot/p99; bulk, chain and api-fail at hot/p99 × {S0, L1}; saturation × {S0, S1, L0, L1, L2}. A one-minute smoke profile runs S0 and L1 at dense/avg.
- **Application work:** each synthetic call spins 5 µs (`bench.work.ns`). Overhead as a percentage depends on this; absolute microsecond deltas are the portable figure.
- **Limits:** the host was shared (load average about 6), runs are single-shot, and below saturation thread wake-up jitter dominates latency (S0 averages 100–130 µs for about 60 µs of work), so low-rate Δ values are noisy. Repeat on a quiet machine, interleaved, ≥ 30 iterations (§10.3) before treating a delta as a finding.

### 10.9 First results (2026-10-22)

Environment: 36-core Linux host, JDK 21, local sink. Full tables are in `bench/results/2026-10-22-full/report.md`.

**Saturation (closed loop, `api`, 10 spans per execution):**

| Strategy | Executions/s | vs S0 | Added per execution |
|---|---|---|---|
| S0 | 150.8K | | |
| S1 (`trace.enabled=false`) | 147.8K | −2.0% | 1.1 µs avg, 1.3 µs p99 |
| L0 | 144.7K | −4.1% | 2.2 µs |
| L1 | 141.4K | −6.3% | 3.5 µs avg, 4.5 µs p99 |
| L2 | 141.1K | −6.4% | 3.6 µs |

**Against NFR-1:**

| Target | Result | Status |
|---|---|---|
| Throughput reduction at L1 ≤ 3% | 6.3% in a worst case where the application does nothing but spin | **Not met** (re-test with a realistic workload) |
| Per span at L1 ≤ 1.5 µs p50, ≤ 5 µs p99 | ≈ 0.35 µs avg, 4.5 µs p99 per execution (10 spans) | Met |
| Disabled path ≤ 200 ns per call | 1.1 µs per execution of 12 calls ≈ 90 ns per call | Met (provisional) |
| Allocation ≤ 256 B per span | ≈ 166 B SDK share (2,879 B per execution; 1,215 B is Spring's proxy invocation) | Met |
| Exporter CPU ≤ 2% of one core | ≈ 11 µs per span: 4.5% of a core at dense/p99 (3.5K spans/s), ≈ 40% at hot/p99 (35K spans/s) | **Not met** |
| ≤ 300 B per span on the wire | ≈ 36 B, from near-identical synthetic spans | Not conclusive |

**Volume:**
- No drops at standard, dense or hot, including p99.
- At the extreme tier, average load (116K spans/s) is exported with no drops. At p99 (347K spans/s into one JVM), about 49% of spans are dropped at the full buffer: one exporter thread sustains about 175K spans/s. Drops are counted and the application is unaffected (NFR behaviour as specified).
- Level cost: at hot/p99, L0 adds 43.8 µs, L1 49.2 µs and L2 16.8 µs on average, which is within the noise of that cell; use the saturation rows for level comparison.
- Workloads `bulk`, `chain` and `api-fail` show no drops at hot/p99.

**Follow-ups:** define the reference load for the exporter-CPU target; test a faster or parallel exporter and cheaper encoding if the target stands; add the L2 and sampling cost to the table once sampling exists; repeat on a quiet host.

---

## 11. SDK Documentation (required content and draft)

The SDK ships with a README and a user guide containing **at least** the sections below. The drafts below are the starting text.

### 11.1 Quick start
**1. Add the SDK and the processor.**

Maven:
```xml
<dependency>
  <groupId>com.example.tracesdk</groupId>
  <artifactId>trace-sdk-spring</artifactId>
  <version>1.0.0</version>
</dependency>
<!-- in maven-compiler-plugin -->
<annotationProcessorPaths>
  <path>
    <groupId>com.example.tracesdk</groupId>
    <artifactId>trace-sdk-processor</artifactId>
    <version>1.0.0</version>
  </path>
</annotationProcessorPaths>
```
Gradle:
```kotlin
implementation("com.example.tracesdk:trace-sdk-spring:1.0.0")
annotationProcessor("com.example.tracesdk:trace-sdk-processor:1.0.0")
```
> Recent JDKs no longer run annotation processors automatically, so the processor must be declared explicitly as shown.

**2. Annotate** the public methods of your Spring beans: entry points and boundaries (§11.2). With Spring Boot, the advice is registered automatically. In plain Spring, add `@Import(TraceSdkConfiguration.class)`.

**3. Set the service name and endpoint** (§8.3). No JVM flag is needed.

**4. Verify:** open `/actuator/tracesdk`, the admin page, or JMX `tracesdk:type=Stats`, and check that `spans.exported` is increasing.

> **Important (Proxy AOP):** only calls that go through the Spring proxy are traced. The class must be a bean (`@Service`, `@Component`, `@Bean`…), and the caller must call it through its injected reference, not through `this`.

### 11.2 Usage examples

**Class-level: trace every public method of a service.**
```java
@Service
@Trace(operation = "orders.service", component = "order-service")
public class OrderService {

    // traced as "orders.service.place_order"; this is the entry point, so it captures tenant and org
    @Trace(kind = Kind.ENTRY, completion = Completion.ENDS_EXECUTION)
    public Receipt placeOrder(@Capture(as = "tenant.id") String tenantId,
                              @Capture(as = "org.id")    String orgId,
                              @Capture(as = "records.count", path = "items.size") Order order) { ... }

    // traced as "orders.service.cancel_order"
    public void cancelOrder(@Capture(as = "execution.id") String orderId) { ... }

    public Money getTotal() { ... }            // accessor: not traced (excludeAccessors = true)

    @NoTrace
    public void warmCache() { ... }            // explicitly excluded

    private void validate(Order o) { ... }     // private: never traced
}
// Note: if placeOrder() calls this.cancelOrder(), that inner call is NOT traced (self-invocation bypasses the proxy).
```

**Method-level only:**
```java
@Component
public class PaymentGateway {
    @Trace(operation = "payments.gateway.charge", component = "payments", kind = Kind.CLIENT)
    @CaptureReturn(as = "app.auth_code_present", path = "authorized")
    public ChargeResult charge(@Capture(as = "retry.attempt") int attempt, Charge c) { ... }
}
```

**Step events (recorded only at `L2_STEP`):**
```java
@Component
@Trace(operation = "pricing.engine", component = "pricing")
public class PricingEngine {
    @Trace(level = Level.L2_STEP)               // an event on the caller's span, not a separate span
    public Discount applyRules(@Capture(as = "records.count", path = "lines.size") Cart cart) { ... }
}
// Called from another bean (e.g. OrderService) via its injected PricingEngine reference.
```

**Continuing an inbound trace (cross-process):**
```java
@Trace(operation = "orders.api.post", component = "order-service", kind = Kind.ENTRY)
public Response post(@Capture(as = "traceparent") String traceparentHeader,
                     @Capture(as = "tenant.id") String tenantId,
                     @Capture(as = "org.id") String orgId,
                     OrderRequest body) { ... }
```

**Async fan-out needs no extra code, as long as the executor is a bean.** Tasks submitted from inside a traced method to a container-managed executor become children automatically:
```java
@Bean ExecutorService orderPool() { return Executors.newFixedThreadPool(16); }   // wrapped by the SDK

@Service @Trace(operation = "orders.batch", component = "order-service")
public class BatchService {
    private final ExecutorService orderPool;   // injected bean
    private final OrderWorker worker;          // injected bean; its @Trace methods are traced

    public BatchResult process(List<Order> orders) {
        var futures = partition(orders, 50).stream()
            .map(chunk -> CompletableFuture.supplyAsync(() -> worker.handle(chunk), orderPool))  // each → async.task span
            .toList();
        ...
    }
}
```
Using `CompletableFuture.supplyAsync(...)` without an executor (the common pool), or an executor created inside the method, starts a **separate** trace.

### 11.3 Annotation reference
- **`@Trace`** (class or method):
  - `operation`: the full name on a method, or the prefix on a class.
  - `component`.
  - `kind` (`ENTRY`, `INTERNAL`, `CLIENT`, `PRODUCER`, `CONSUMER`).
  - `level` (`L0_ENTRY`, `L1_BOUNDARY`, `L2_STEP`).
  - `completion` (`NONE`, `ENDS_EXECUTION`).
  - `retention`.
  - `excludeAccessors` (class only, default `true`).
- **`@Capture(as, path = "")`** (parameter): records a scalar argument, or a scalar reached through `path`.
- **`@CaptureReturn(as, path = "")`** (method): records a scalar from the return value.
- **`@NoTrace`** (method): excludes the method from a class-level `@Trace`.
- Reserved `as` keys and the `app.` prefix: §6.2.

### 11.4 Where to put `@Trace` — usage guidance
**Do annotate:**
- **Service entry points:** API handlers, message/queue consumers, scheduled jobs, CLI commands. This is where a trace starts, and where `tenant.id` / `org.id` must be captured.
- **Public methods at component boundaries:** a service's public API, outbound clients (HTTP, RPC), repositories and DAOs, async hand-offs. These are where the time goes and where failures happen.

**Don't annotate:**
- **Private, protected, or package-private methods.** The build rejects them. They are implementation details. Their time is already inside the enclosing public method's span, and tracing them multiplies span count without adding useful information.
- **Getters, setters, `toString`, `equals`, `hashCode`**, and trivial delegating methods.
- **Methods called once per record or per loop iteration.** Capture the count on the enclosing method instead (`records.count`). Span count should grow with **steps**, not with **records**. That is what keeps cost predictable (NFR-4.1).
- **Tight inner loops and hot utility code** (parsers, formatters, collection helpers).
- **Methods only ever called from inside the same class.** The proxy never sees those calls (D-1). If the step matters, move it to its own bean.
- **Classes that aren't beans** (created with `new`). They are never proxied.

**Rules of thumb:**
- A single request should produce roughly **5–30 spans at `L1`**. If it produces hundreds, something per-record is annotated.
- Use `L2_STEP` for cheap logical steps you want to see only while debugging.
- Put `completion = ENDS_EXECUTION` on the method that ends the execution. Usually that's the entry method. For async work, it's the last step.

### 11.5 Naming
- Pattern: `<domain>.<component>.<action>`, lower case, dot-separated, e.g. `orders.service.place_order`.
- **Good:** `payments.gateway.charge`, `inventory.repo.reserve`.
- **Bad:**
  - `PlaceOrder`: has upper case.
  - `orders.place_order.12345`: contains an ID.
  - `orders.service.place_order_for_tenant_t042`: contains a tenant.
  - `doWork`: too vague.

### 11.6 Configuration and operations
- Full key reference (§8.2), minimal setup (§8.3), and the configuration page (§8.4).
- Reading the heartbeat and stats.

### 11.7 Troubleshooting
| Symptom | Likely cause |
|---|---|
| No spans | `trace-sdk-spring` missing, or auto-configuration excluded; `trace.service.name` / `spring.application.name` missing; `trace.enabled=false`; collector endpoint unreachable (check `export.errors`) |
| A method has `@Trace` but no span appears | The class isn't a bean; the call is `this.method()` (self-invocation); the class or method is `final`; the method isn't public |
| Build error "@Trace on non-public method" | Move the annotation to the public entry or boundary method |
| `tenant.id = _missing_` | Entry method doesn't capture `tenant.id` / `org.id` |
| Too many spans per request | A per-record or helper method is traced. See §11.4 |
| `spans.dropped.buffer_full` increasing | Collector slow or down, or buffer too small for peak |
| Async work shows up as separate traces | The executor isn't a bean (created with `new`/`Executors.*`, or the common pool), or `trace.propagation.executors=false` |

### 11.8 Reviewer checklist
- [ ] Entry methods are `kind = ENTRY` and capture `tenant.id` and `org.id`.
- [ ] Only public entry or boundary methods are annotated. Nothing runs per record.
- [ ] Annotated classes are beans, and traced steps are called through injected references (not `this`).
- [ ] Async work uses executor beans.
- [ ] Operation names follow §11.5.
- [ ] Captures are counts or IDs only. No payloads, no personal data.
- [ ] `completion` is set on exactly one step per execution.

---

## 12. Open Questions and Deferred Topics

**Open questions**
1. Which storage backend comes first (Splunk, ELK, or Pinot)? This determines which attributes are indexed.
2. Who signs off on retention classes and their TTLs?
3. **Durable hand-offs:** when work is queued to another process to run later, how does the trace context get into the queued message without an API call? Options:
   - (a) Accept that this starts a new trace, linked by a captured `execution.id`.
   - (b) Allow one read-only accessor as an exception to annotation-only, e.g. a call that returns the current `traceparent` string.
   - (c) Add automatic propagation for container-managed messaging templates (`KafkaTemplate`, `JmsTemplate`) and listeners later.
4. What do `tenant.id` and `org.id` mean in the consuming applications? Are they always both present?
5. Should an OpenTelemetry bridge (§7.4) be part of v1?
6. **D-1 follow-up:** if the measured proxy overhead (S1 vs S2) exceeds the NFR-1 targets, should Option B be added?

**Deferred**
- **Sampling.**

---

## 13. Design Notes: How the Design Was Reached

These notes record the owner's reasoning, one topic at a time. They explain why the design looks the way it does. The requirements in §2–§11 are the normative part; if a note here disagrees with a requirement, the requirement wins.

### 13.1 How the problem was decomposed

The starting point was a note titled "Flow tracing without a performance penalty". The owner turned it into a general-purpose SDK and broke the work down in three ways.

**By responsibility.** Only the publishing happens in the SDK. Another service receives the trace lines, then stores and indexes them.

| Part | Job | In this spec? |
|---|---|---|
| SDK, inside the application | Produce trace records and publish them | Yes |
| Collector | Receive records and route them | No. Assumed to exist (A-1, confirmed by the owner) |
| Storage, index, query | Keep, search, and expire records | No. Assumed to exist (A-2, confirmed by the owner) |

**By requirement.** The owner wrote the drivers, and each one became a numbered requirement:

| Owner's driver | Became |
|---|---|
| Trace starts at the entry points of the traced service; service owners decide what to trace; granularity follows standard practice | FR-1, FR-2 |
| Very easy to enable: annotate, and require key fields such as class and method | FR-3 |
| Usable by any service owner | FR-4 |
| Retention policy agreed with stakeholders | FR-8 |
| Tracing spans threads and gives one unified view | FR-5 |
| Performance: least compute, network, and storage | NFR-1, NFR-6 |
| Vendor agnostic; OTLP is suitable | NFR-2 |
| Searchability: non-real-time, eventually consistent | NFR-3 |
| Cost: inexpensive, with retention keeping storage flat for similar volume | NFR-4 |

Later questions from the owner added three more: a kill switch (FR-9), a completion marker in the annotation (FR-11), and a way for the SDK to report its own health (FR-12).

**By pipeline stage, inside the SDK.** Annotate, intercept, carry context, buffer, export. Each stage has one job, and developers only touch the first. This is the basis of the module layout in §7.

### 13.2 How scope was defined

Scope was set one decision at a time. For each question the owner did one of three things: decided it now, deferred it on purpose, or excluded it.

**Decided now**

| Decision | Owner's direction |
|---|---|
| Record counts and identifiers, not contents | Asked why redaction mattered, the owner noted that no specific details are captured. The record holds tenant id, organization id, timestamp, trace id, parent span, span, time consumed, and counts. Payload size then does not matter (FR-6). |
| One trace per execution | Executions running together do not each write their own set of traces. A bulk action that splits into many async pieces is one trace with many child spans (FR-5). |
| Annotation is the only developer touchpoint | The buffer, publishing, and the rest are internal wiring (FR-3). |
| Public methods only | Private methods are never traced. The documentation says service-level entry points are the right place for `@Trace` (§11.4). |
| Publish over OTLP/HTTP to a collector | Treated as a configuration choice (§13.3). |
| Dropping records when the buffer is full is acceptable | The application is protected first (NFR-5). |
| v1 supports Spring Framework 6.x / Spring Boot 3.x only | Spring 5, other containers, and plain Java apps are out for v1 (D-1, NFR-8). |

**Deferred on purpose**

| Topic | Where it stands |
|---|---|
| Sampling | Deferred twice. The owner questioned the need, since filtering can happen when traces are queried. The cost of keeping every execution was written down instead (NFR-4.1, A-9). |
| Durable hand-offs between processes | To be discussed separately (§12 question 3). |
| Storage backend and retention values | Depend on stakeholders (§12 questions 1 and 2). |

**Excluded**

- Storage, indexing, search UI, and retention enforcement. These belong to the downstream service.
- How Flow, or any specific application, consumes the SDK. The SDK is general-purpose, and the documentation carries usage examples instead.

### 13.3 Approaches considered

#### How `@Trace` methods get trace code (decision D-1)

The first draft treated an annotation as plain metadata that needs something to act on it, and so assumed a Java agent (`-javaagent`) that rewrites methods as classes load. The owner expected something different: an AOP-style design, where trace code runs before and after any method that carries `@Trace`. The two options were then recorded side by side in §2.1.

| | Proxy AOP | Weaving AOP |
|---|---|---|
| How | The container hands out a proxy that runs the trace code around each public call | Trace code is written into the method's bytecode (agent or build plugin) |
| Developer adds | One dependency | A dependency plus a JVM flag or build plugin |
| Misses | Calls a class makes to its own methods, objects the container did not create, `final` classes | Nothing |
| Cost when tracing is off | A proxy dispatch on every call | One integer comparison |
| Outcome | **Chosen** | Kept as an alternative (§12 question 6) |

Proxy AOP was chosen because it needs nothing beyond adding the dependency, and developers already know the model from `@Transactional`. Its limits were accepted, each with a mitigation: move a step into its own bean to make it visible (§11.4), set the disabled-path target as provisional until measured (NFR-1), and compare both options in the benchmarks (§10).

#### Why the proxy limits are acceptable (owner's view)

The three coverage gaps of Proxy AOP differ in weight, and the owner judged each one:

| Gap | Weight | Owner's reasoning |
|---|---|---|
| Calls a class makes to its own methods (`this.foo()`) | Accepted | The calls worth tracing are service calls, and those typically come from outside the object. A step that matters can be moved into its own bean. |
| Objects the container did not create | Accepted | In a Spring application, services should only be created as beans. Creating them any other way breaks what Spring provides (injection, proxies, lifecycle), so such objects are not service boundaries. |
| `final` classes and methods | Minor | CGLIB cannot subclass or override them, but the build rejects `@Trace` on them (§7.2), and removing `final` or coding to an interface fixes it. A JDK proxy also works when callers use the interface type. |

Cases to watch: a service whose public methods call each other (for example a batch handler looping over `this.processOne()`) shows one span for the whole batch instead of one per item. This agrees with the span-count guidance in §11.4, but it follows from the proxy design and not from a deliberate choice, so it is worth knowing when reading traces.

The deciding comparison with Option B is where the cost lands. Proxy AOP costs some coverage and a per-call dispatch, both small and measurable (§10). Weaving costs a deploy-time change in every service, with silent failure when it is forgotten, plus bytecode tooling to maintain. The first driver is ease of enabling, so the spec accepts the first set of costs.

#### How records leave the application

| Option | Description | Outcome |
|---|---|---|
| A | Send OTLP over HTTP to a collector | **Chosen.** The owner treated it as "just a configuration". |
| B | Write trace lines to a local file for a log shipper to pick up (collector `filelog` receiver, Filebeat, or Logstash) | Still possible later. A and B differ only in the hop out of the SDK, so the exporter sits behind an interface. |

A keeps the SDK independent of the backend: moving between Elastic, Splunk, and Pinot is a collector change, not an SDK release.

#### When to decide what is kept (sampling)

| Option | Description | Outcome |
|---|---|---|
| Decide at write time | Keep only some executions | Deferred |
| Keep everything, filter at query time | Every execution is recorded in full and filtered when read | The owner's position |

The consequence is that cost is not reduced by dropping executions. It is controlled by retention, and the network and storage figures are written down in NFR-4.1 with the assumption in A-9.

#### Behaviour under pressure

When the buffer is full, the SDK drops the new record and counts it (NFR-5). The owner found this reasonable. The alternatives would put cost back on the execution path: blocking the caller adds latency, and spilling to disk adds I/O and a new failure mode. Both work against the first driver, performance.

#### How settings are changed

The owner asked for settings to be available as system variables or parameters, and for a simple page in the consuming application, and invited suggestions. The result is layered sources (§8.1) and four ways to view and change settings (§8.4): an Actuator endpoint, a built-in admin page, JMX, and a watched file. For Spring Boot applications the Actuator endpoint is recommended.

#### How much to trace

Tracing everything was set aside in favour of starting at the entry points of the traced service and letting service owners decide what to trace. Granularity follows three levels (FR-2). The entry point is always recorded, boundaries are the default, and cheap logical steps are recorded only on request, as events on the enclosing span.

#### Key tradeoffs

Each row names what the design chose, what it gave up, and why.

| Tradeoff | Chosen | Given up | Why |
|---|---|---|---|
| Ease of enabling vs coverage | Proxy AOP: add a dependency and annotate | Calls a class makes to its own methods, objects the container did not create, `final` classes | The first requirement is that enabling takes little effort. Missed calls can be fixed by moving a step into its own bean. |
| Overhead vs detail | Three levels, counts instead of contents, steps recorded as events | Per-call payloads and fine-grained spans by default | Performance is the primary driver. Detail is available on request, per operation. |
| Completeness vs cost | Keep every execution in full, filter at query time | Cheap storage through sampling | Sampling is deferred. Cost is controlled by retention instead, so the retention policy needs sign-off. |
| Application safety vs completeness | Drop new records when the buffer is full; export on a background thread | A trace can have gaps during a long collector outage | The application must never slow down or fail because of tracing. Drops are counted, and the trace says it is incomplete. |
| Independence from the backend vs one more tier | OTLP over HTTP to a collector | Having to run and size a collector | Changing the storage backend becomes a collector change, not an SDK release. |
| Reach vs simplicity | Spring Framework 6.x / Spring Boot 3.x only in v1 | Spring 5, other containers, plain Java apps | One integration to build, test, and measure well. Other containers can be added later. |
| Annotation-only API vs flexibility | No code-level API for developers | Cases that need code, such as passing trace context through a queued message to another process | A single, easy-to-learn touchpoint. The gap is recorded as open question 3. |
| Fast search vs freshness | Eventually consistent indexing downstream | Real-time search | Accepted in the requirements (NFR-3). |

### 13.4 What I expected to be the hardest

*Drafted from where the questions and decisions concentrated during design, not from a direct statement. Owner to confirm or correct.*

| Expected difficulty | Why it looked hard | How the design handles it | What happened in the build |
|---|---|---|---|
| Keeping overhead near zero | The SDK runs inside the request path, so any cost is paid by every call, and there was no agreed way to measure it. | Annotation-only API, sites resolved once per method, a lock-free bounded buffer, and all export work on one background thread. §10 defines how to measure. | Per-span memory was measured (about 220–350 B). Latency overhead is still to be measured under §10.3. |
| One trace across threads and bulk fan-out | Work moves between threads and one request can touch many records, so a single trace is easy to break or to make huge. | Trace context carried by the proxy and a `traceparent` form for hand-offs; bulk work recorded as counts (`records.count`) rather than one span per record. | Context propagation is covered by unit tests; hand-offs across durable queues remain an open question (§12). |
| Never hurting the application | A tracing failure must not become an outage, and a down or full collector is the likely case. | A full buffer drops and counts; export failures are counted, never thrown; shutdown flush has a timeout. | Verified in §14 (TC-J1 to TC-JB1, TC-S1). |
| Knowing when a trace is complete | Traces end at different times on different threads, and there is no central place that sees the end. | Each span carries `execution.complete`; completeness is judged downstream. | Left to the downstream service; not tested here. |
| Keeping cost flat as usage grows | Storage and query cost grow with spans kept. | Levels (`L1`, `L2_STEP`) and a `retention.class` on every span so the downstream service can expire by class. | Level and retention attributes are exported; retention itself is downstream. |
| Annotation-only API versus durable hand-offs | Annotations are simple but cannot see state that leaves the process. | Public methods only, class-level `@Trace`, and an explicit `traceparent` helper. | Self-invocation, non-beans and final classes are not traced (the cost of choosing proxy AOP, D-1). |

---

## 14. Test Cases

Scenarios for how the SDK behaves when its collector is unavailable, its buffer fills, or its configuration is missing or wrong. They were run from the demo project (`trace-sdk-demo`), which uses the real SDK in a Spring context against a controllable fake collector and, for the manual run, a real Jaeger v2.22.0.

**Status key:** *Automated* = a JUnit test that runs on every `mvn test`; *Manual* = run by hand against the real Jaeger; *Not tested* = no test yet.

**Out of scope for this section:** user permissions, reporting, and notifications (for example Slack).

### 14.1 Summary

| Category | Scenarios | Status | Result |
|---|---|---|---|
| A. Collector (Jaeger) down | TC-J1, TC-J2, TC-J3, TC-M1 | Automated (3), Manual (1) | All pass |
| B. Buffer full | TC-B1, TC-B2 | Automated | All pass |
| C. Collector down and buffer full | TC-JB1 | Automated | Pass |
| D. Required and invalid configuration | TC-C1 to TC-C7 | Automated | All pass |
| E. Collector rejects writes ("storage full") | TC-S1, TC-S2 | Automated | All pass |

Automated total: 15 tests, 0 failures (four consecutive full runs after the last fix). Test classes: `ResilienceTest` (8) and `ConfigurationTest` (7).

### 14.2 Category A — Collector (Jaeger) down

```gherkin
Feature: The application keeps working when the collector is down

  Background:
    Given a Spring application using the SDK with a short export timeout and 2 retry attempts
    And tracing is enabled

  # TC-J1 — Automated — PASS (ResilienceTest.collectorDown)
  Scenario: Collector is down while the application serves requests
    Given no collector is listening on the configured endpoint
    When the application handles 10 orders
    Then every order returns its normal result
    And the slowest order takes under 1 second
    And "dropped, export failed" becomes greater than 0
    And "export errors" becomes greater than 0
    And "internal errors" stays 0

  # TC-J2 — Automated — PASS (ResilienceTest.collectorRecovers)
  Scenario: Collector comes back after an outage
    Given the collector was down and spans were dropped
    When the collector starts on the same endpoint
    And the application handles more orders
    Then new spans arrive at the collector

  # TC-J3 — Automated — PASS (ResilienceTest.shutdownWithCollectorDown)
  Scenario: Application shuts down while the collector is down
    Given no collector is listening and spans are waiting to be exported
    When the application context is closed
    Then the context closes in under 10 seconds without hanging or throwing

  # TC-M1 — Manual — PASS (2026-10-20, real Jaeger v2.22.0, default 5 attempts)
  Scenario: Real Jaeger is stopped, traffic continues, Jaeger is restarted
    Given the demo app is running and traces appear in Jaeger
    When Jaeger is stopped and 7 orders are sent (5 succeed, 2 fail payment on purpose)
    Then the responses are 200 x5 and 422 x2, as when Jaeger is up
    And all 7 calls finish in 1.1 seconds in total
    And the application log shows one warning: "export ... failed: java.net.ConnectException after 5 attempts"
    When Jaeger is restarted and 7 more orders are sent
    Then 7 new root spans appear in Jaeger
```

**Observed in TC-M1:** Jaeger's default storage is in memory, so everything it held before the restart was gone (service list empty after restart). This is Jaeger's behavior, not the SDK's. The 7 spans from the outage were never delivered.

### 14.3 Category B — Buffer full

```gherkin
Feature: A full buffer drops new spans without affecting the application

  # TC-B1 — Automated — PASS (ResilienceTest.bufferFull)
  Scenario: Buffer fills because the collector is slow
    Given a healthy collector that answers each request after 400 ms
    And the buffer holds 4 spans and a batch is 2 spans
    When the application handles a burst of 30 orders
    Then every order returns its normal result
    And "dropped, buffer full" becomes greater than 0
    And "internal errors" stays 0
    And after shutdown: started = exported + dropped(buffer full) + dropped(export failed)

  # TC-B2 — Automated — PASS (ResilienceTest.bufferNotFull)
  Scenario: Buffer is large enough for the load
    Given a healthy collector and the default buffer size
    When the application handles 20 orders
    Then nothing is dropped
    And exported = started
```

**Known gap (not tested):** `trace.buffer.max.bytes` is not enforced; the buffer is limited by span count only (measured about 220–350 bytes per buffered span).

### 14.4 Category C — Collector down and buffer full

```gherkin
Feature: Both failures together

  # TC-JB1 — Automated — PASS (ResilienceTest.collectorDownAndBufferFull)
  Scenario: Collector is down and the buffer is small
    Given no collector is listening
    And the buffer holds 4 spans and a batch is 2 spans
    When the application handles a burst of 40 orders
    Then every order returns its normal result
    And the slowest order takes under 1 second
    And "dropped, buffer full" is greater than 0
    And "dropped, export failed" is greater than 0
    And "internal errors" stays 0
    And exported = 0
    And after shutdown: started = exported + dropped(buffer full) + dropped(export failed)
```

**Why this matters:** the exporter thread both drains the buffer and does the retrying. While it retries against a dead collector the buffer is not drained, so new spans are dropped as "buffer full" rather than "export failed". Both counters are expected to grow, and the test checks that they add up exactly.

### 14.5 Category D — Required parameters and what happens when they are missing or wrong

**The only required parameter is the service name:** `trace.service.name`, or else `spring.application.name`. Everything else has a default (endpoint `http://localhost:4318/v1/traces`, protocol `http/protobuf`, gzip on, level `L1`, buffer 32768 spans, batch 512 spans, 1000 ms flush delay, 10000 ms timeout, 5 attempts).

```gherkin
Feature: Required parameters and defaults

  # TC-C1 — Automated — PASS (ConfigurationTest.noServiceName)
  Scenario: No service name is set
    Given neither trace.service.name nor spring.application.name is set
    When the application starts
    Then the application starts normally
    And the SDK is inactive and its beans are not wrapped in a proxy
    And one SEVERE log line says "trace.service.name (or spring.application.name) is not set; tracing is disabled"
    And orders still work and no spans are started or sent

  # TC-C2 — Automated — PASS (ConfigurationTest.blankServiceName)
  Scenario: Service name is blank
    Given trace.service.name is "   "
    Then it is treated as missing and the SDK is inactive

  # TC-C3 — Automated — PASS (ConfigurationTest.springApplicationNameFallback)
  Scenario: Service name comes from the Spring application name
    Given only spring.application.name is "from-spring"
    Then the SDK is active and the service name is "from-spring"

  # TC-C4 — Automated — PASS (ConfigurationTest.traceServiceNameWins)
  Scenario: Both names are set
    Given trace.service.name is "explicit" and spring.application.name is "from-spring"
    Then the service name is "explicit"

  # TC-C5 — Automated — PASS (ConfigurationTest.defaultsApply)
  Scenario: Only the service name is set
    Given only trace.service.name is set
    Then the SDK is active
    And the endpoint, protocol, compression, buffer, batch, delay, timeout, retry count and level are the defaults listed above

  # TC-C6 — Automated — PASS (ConfigurationTest.invalidValues)
  Scenario: Optional settings have invalid values
    Given trace.exporter.endpoint is "ftp://nowhere", trace.level is "L9" and trace.buffer.max.spans is "-5"
    When the application starts
    Then the SDK is active and uses the default for each invalid setting
    And one WARNING per invalid setting names the setting

  # TC-C7 — Automated — PASS (ConfigurationTest.missingTenantAndOrg)
  Scenario: Tenant and org are not supplied by the caller
    Given the tracing level captures tenant.id and org.id from the method arguments
    When an order is placed with a null tenant and a null org
    Then the order succeeds
    And the span is exported with tenant.id = "_missing_" and org.id = "_missing_"
    And the "missing tenant" counter is greater than 0
```

### 14.6 Category E — Collector rejects writes ("storage full")

**Interpretation to confirm:** "processor storage is full" is read here as the downstream collector or its storage refusing writes. The SDK cannot see downstream storage; it only sees the HTTP answer. These scenarios therefore simulate the answer a full backend would give.

```gherkin
Feature: The collector refuses to accept spans

  # TC-S1 — Automated — PASS (ResilienceTest.storageFullRejectsWrites)
  Scenario: Collector answers 507 Insufficient Storage
    Given a collector that answers every request with HTTP 507
    When the application handles 5 orders
    Then every order returns its normal result
    And the SDK does not retry (507 is not a retryable status)
    And "dropped, export failed" and "export errors" become greater than 0
    And the collector stores nothing
    And after shutdown: started = exported + dropped(buffer full) + dropped(export failed)

  # TC-S2 — Automated — PASS (ResilienceTest.overloadedThenRecovers)
  Scenario: Collector answers 503 Service Unavailable and then recovers
    Given a collector that answers 503 and the SDK allows 5 attempts
    When the first request is rejected and the collector then starts answering 200
    Then the retry delivers the batch
    And "export errors" is greater than 0
```

### 14.7 Not tested, and gaps

| Item | Why it is not covered |
|---|---|
| Real Jaeger with its storage actually full | Jaeger's default in-memory storage evicts old traces rather than rejecting writes, so the "full" answer cannot be produced locally. TC-S1 simulates the answer instead. |
| `trace.buffer.max.bytes` | Not enforced by the SDK (see §14.3). |
| Collector that accepts the connection and then never answers | Only the timeout setting covers it; no dedicated scenario. |
| Memory growth under a long outage | Not measured; the buffer is bounded by span count, so growth is bounded. |
| User permissions, reporting, notifications (for example Slack) | Out of scope for this section, as requested. |

### 14.8 How to run

- Automated: `cd trace-sdk-demo && mvn test` (needs the SDK installed locally; takes about 30 seconds). Each test's display name starts with its scenario ID.
- Manual (TC-M1): start Jaeger and the demo app, run `./run-demo.sh`, stop Jaeger and run it again, restart Jaeger and run it a third time. Wait for the old Jaeger process to exit before starting the new one (port 8888 is held until then).

---

## 15. Wire Size Optimization Options

Options for sending fewer bytes per span. **None is adopted in v1**; this section records them so the choice can be made once real span sizes are measured. The target stays NFR-1 (≤ 300 B/span on the wire at `L1`). The §10.9 figure of ≈ 36 B/span comes from near-identical synthetic spans and is a lower bound, not evidence that the target is met.

### 15.1 Where the bytes go

Measured on one real demo trace (4 spans, OTLP/JSON, uncompressed, 4,993 B for the request):

| Source | Why it costs bytes | Fixable? |
|---|---|---|
| Attribute keys | Each span repeats about 11 key strings (`tenant.id`, `code.namespace`, `retention.class`, …). In protobuf these are still strings; only the OTLP envelope loses its field names. | Yes: dictionary or per-trace constants |
| Values equal on every span of a trace | `tenant.id`, `org.id`, `retention.class`, `trace.level`, `component`, often `code.namespace` and `thread.name` | Yes: send once |
| Derivable values | `duration.ns` equals `endTimeUnixNano − startTimeUnixNano` | Yes: drop |
| JSON wrapping | `{"key":…,"value":{"stringValue":…}}` per attribute | Not applicable to the default protobuf encoding |
| IDs and timestamps | 16 B trace ID, 8 B span ID, 8 B parent ID, two 8 B timestamps per span | Largely no |

gzip already compresses repeated keys within a batch of up to 512 spans, so the saving from each option is smaller on the wire than before compression. Measure after gzip.

### 15.2 Options

| # | Option | What changes | Expected effect | Cost |
|---|---|---|---|---|
| W-1 | **Trim within OTLP** | Drop `duration.ns` and per-span `thread.name`; omit attributes that carry no information at the chosen level | Small | A downstream consumer that reads `duration.ns` must compute it. Otherwise none; output stays plain OTLP. |
| W-2 | **Per-trace constants at the resource level** | Put `tenant.id`, `org.id`, `retention.class`, `component` on the OTLP resource, or group spans into one `ResourceSpans` per tenant, so they are sent once per group | Medium | Plain OTLP, but a batch must be split per tenant/org (NFR-7 already forbids mixing tenants in a span). More resource groups per request. Collectors and backends must index resource attributes as they do span attributes. |
| W-3 | **Batch-level key dictionary** | Replace attribute key strings with small integers defined once per batch (or once per connection, with a version) | Medium to large, mostly before gzip | **Not OTLP.** Needs a receiver or translator in front of the collector. |
| W-4 | **Compact predefined format** | Both sides agree on a schema version (`tsdk.schema.version` is already sent). Spans carry values only, in fixed order or as columns (all span IDs, then all durations, …); `app.*` captures use the dictionary of W-3 | Large | **Not OTLP.** Custom receiver required; every schema change needs a coordinated SDK and receiver release; user-defined `app.*` keys still need dictionary entries. |

### 15.3 Trade-off

W-1 and W-2 keep the wire format valid OTLP, so NFR-2 (vendor-agnostic; changing the backend is a collector change) still holds. W-3 and W-4 give up NFR-2 for the stretch between the SDK and the receiver: a standard OTLP collector, Jaeger or an OTel backend cannot read the stream directly, and the translator becomes a component to build, run and size.

### 15.4 Recommendation (for confirmation)

1. Adopt W-1 and W-2 when convenient, since they cost nothing in compatibility.
2. Measure bytes per span, after gzip, on realistic traffic (not the synthetic benchmark workload).
3. Consider W-3 or W-4 only if measured bytes per span exceed the NFR-1 target, or if sizing at the NFR-4.1 volumes (≈ 3 TB/day on the wire at `L1`) makes transport cost material.

### 15.5 Open points

- Which attributes may be moved to the resource without changing how the backend indexes them (depends on §12 question 1, the first storage backend).
- Whether the translator for W-3/W-4 is run as part of the collector tier or as a separate hop.
- Whether `code.lineno` should be on the wire at all. The spec lists it as required (§6.2), but the demo trace did not contain it; check the SDK against §6.2 before sizing.
