# Trace SDK demo

A small Spring Boot app that uses the Trace SDK, plus a failure-mode test suite. Use it to see traces in Jaeger and to check how the SDK behaves when the collector is down, the buffer is full, or configuration is missing.

The app is an order service: `OrderController` → `OrderService` → `InventoryService` and `PaymentService`. All three services carry `@Trace`; the only SDK code in the app is annotations plus the `trace-sdk-spring` dependency.

## Prerequisites

| Need | Version | Notes |
|---|---|---|
| JDK | 17 or later | The demo targets Java 17 |
| Maven | 3.8 or later | |
| curl | any | Used by `run-demo.sh` |
| Jaeger | v2.22.0 | Only for the manual run. Docker or a downloaded binary, see below. The automated tests do not need it. |

## 1. Build and install the SDK

The demo depends on `org.util.trace:trace-sdk-spring:0.1.0-SNAPSHOT`, which is not published anywhere. Install it into your local Maven repository first, from the repository root:

```bash
cd sdk
mvn install
cd ..
```

Re-run this whenever you change anything under `sdk/`.

## 2. Run the automated tests (no Jaeger needed)

```bash
cd demo
mvn test
```

This runs 15 tests in about 30 seconds. They start the real SDK inside a Spring context against a fake collector built into the tests, so nothing external is required.

| Class | Tests | Covers |
|---|---|---|
| `ResilienceTest` | TC-J1, J2, J3, B1, B2, JB1, S1, S2 | Collector down, collector recovers, shutdown while down, buffer full, buffer not full, both together, collector answers 507, collector answers 503 then recovers |
| `ConfigurationTest` | TC-C1 to TC-C7 | Missing, blank and fallback service name, defaults, invalid values, missing tenant and org |

Each test's display name starts with its scenario ID. The scenarios are written in Gherkin in `docs/Trace SDK Specification.md`, section 14.

To run one class: `mvn test -Dtest=ResilienceTest`.

## 3. Start Jaeger

The SDK sends spans to `http://localhost:4318/v1/traces` (OTLP over HTTP). Jaeger shows them at `http://localhost:16686`. Pick one of the two ways below.

**With Docker**

```bash
cd demo
docker compose up -d
```

`docker-compose.yml` runs `jaegertracing/jaeger:2.22.0` and publishes ports 16686 and 4318. This file has not been run on the machine used to build the demo (it has no Docker), so treat it as untested.

**Without Docker (Linux x86-64, the setup used to build the demo)**

```bash
mkdir -p ~/jaeger && cd ~/jaeger
curl -L -o jaeger.tar.gz \
  https://github.com/jaegertracing/jaeger/releases/download/v2.22.0/jaeger-2.22.0-linux-amd64.tar.gz
tar xzf jaeger.tar.gz
cd jaeger-2.22.0-linux-amd64
nohup ./jaeger-bin > jaeger.log 2>&1 &
echo $! > jaeger.pid
```

For other platforms use the matching archive from https://github.com/jaegertracing/jaeger/releases/tag/v2.22.0. With no config it runs the default all-in-one setup: OTLP/HTTP on 4318, UI on 16686, **in-memory storage**.

Check it is up: open http://localhost:16686, or run `curl -s localhost:16686/api/v3/services`.

Stop it with `kill $(cat jaeger.pid)`.

## 4. Run the demo app

In a second terminal:

```bash
cd demo
mvn spring-boot:run
```

It listens on port 8080. Settings are in `src/main/resources/application.properties` (service name `orders-demo`, level `L2_STEP`, exception messages on, flush every 500 ms).

## 5. Send traffic

```bash
cd demo
./run-demo.sh
```

This sends 7 orders: 3 for tenant `acme`, 2 for `globex`, and 2 that fail payment on purpose. The HTTP codes print on one line: `200` for each success and `422` for each failure. To send one order yourself:

```bash
curl -X POST "localhost:8080/orders?tenant=acme&org=00Dacme0001&items=3&failPayment=false"
```

| Parameter | Default | Meaning |
|---|---|---|
| `tenant` | `acme` | Recorded as `tenant.id` |
| `org` | `00D000000000001` | Recorded as `org.id` |
| `items` | `3` | Number of SKUs to reserve |
| `failPayment` | `false` | `true` makes the payment step throw |

## 6. What to look for in Jaeger

Open http://localhost:16686, choose service **orders-demo**, and click Find Traces.

- One trace per order. The root span is `demo.orders.place_order` (kind server) with child spans from the inventory and payment services, for example `demo.inventory.reserve` and `demo.payments.charge`.
- `demo.inventory.check` appears as an event on the enclosing span, not as its own span, because it is marked `L2_STEP`.
- Span attributes include `tenant.id`, `org.id`, `component`, `code.namespace`, `code.function`, `trace.seq`, `duration.ns`, `retention.class` and captured values such as `app.sku`.
- The root span has `execution.complete = true`.
- The two failing orders show an error status and an `exception` event with the type and message.

## 7. Try the failure modes by hand

The automated tests cover these; the steps below repeat the Jaeger-outage case (TC-M1 in the spec) against the real Jaeger.

1. With Jaeger and the app running, run `./run-demo.sh`. Traces appear.
2. Stop Jaeger (`kill $(cat jaeger.pid)` or `docker compose stop`). Run `./run-demo.sh` again. The responses are the same as before (`200` and `422`) and return as fast as when Jaeger is up. After a few seconds the app log shows a warning that an export failed after 5 attempts.
3. Start Jaeger again and run `./run-demo.sh`. New traces appear. The spans from step 2 are gone for good: the SDK drops them after its retries.

Things to know:
- Jaeger's default storage is in memory, so a restart also erases every trace it held earlier. That is Jaeger's behaviour, not the SDK's.
- When restarting the binary, wait until the old process has fully exited. Otherwise it can fail with "address already in use" on port 8888.

## 8. Settings worth changing

Set these in `application.properties`, or as `-Dtrace.xxx=...` / `TRACE_XXX` environment variables. The full list is in the spec, section 8.2.

| Setting | Demo value | Effect |
|---|---|---|
| `spring.application.name` or `trace.service.name` | `orders-demo` | **Required.** Without one, the SDK logs an error and stays off. |
| `trace.level` | `L2_STEP` | `L0_ENTRY`, `L1_BOUNDARY` (the SDK default) or `L2_STEP` |
| `trace.exporter.endpoint` | `http://localhost:4318/v1/traces` | Collector address |
| `trace.exporter.protocol` | `http/protobuf` | Or `http/json` |
| `trace.batch.max.delay.ms` | `500` | How long a partial batch waits before it is sent |
| `trace.enabled` | not set (`true`) | `false` turns tracing off |

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `mvn` cannot find `trace-sdk-spring` | The SDK was not installed. Run `mvn install` in `sdk/` (step 1). |
| No `orders-demo` service in Jaeger | Jaeger is not running or not on 4318; the app was started before Jaeger and the first batch was dropped (run `./run-demo.sh` again); or the service name is unset. Check the app log. |
| App log says tracing is disabled | No service name is set. |
| Traces vanish after restarting Jaeger | In-memory storage, see step 7. |
| Jaeger fails with "address already in use" | The previous Jaeger process is still exiting. Wait and retry. |
