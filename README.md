# Trace SDK

A lightweight, annotation-only tracing SDK for Spring Boot 3.x applications, exporting OTLP/HTTP.

| Directory | Contents |
|---|---|
| `sdk/` | The SDK (annotations, core, spring integration, bom). Maven multi-module. |
| `demo/` | Demo Spring Boot app and the failure-mode test suite (15 automated tests). |
| `docs/` | `Trace SDK Specification.md`, the design spec (same file as on Google Drive). |

## Build and test

```bash
(cd sdk && mvn install)     # installs 0.1.0-SNAPSHOT locally; the demo depends on it
(cd demo && mvn test)       # TC-J1..S2 scenarios, see spec section 14
```

To see traces in Jaeger and try the failure modes, follow [demo/README.md](demo/README.md).

See the specification for requirements, design decisions and the Gherkin test cases.
