# llmgw4j

> Production requires `gateway.production=true`, non-demo client keys and an admin token.
> Defaults are local demos. The built-in monetary ledger is in-memory, including with Redis;
> durable distributed monetary hard budgets are not supported in production.
> CI runs Maven verify, strict OpenAPI YAML/operation checks and both-engine stream contracts.

> **One governance layer. Two switchable data planes. Evidence you can reproduce.**
>
> Naming: `llmgw` (LLM Gateway) + `4j` (for Java — log4j / dom4j style).

[![CI](https://github.com/leohou0430/llmgw4j/actions/workflows/ci.yml/badge.svg)](https://github.com/leohou0430/llmgw4j/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-25-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.2-brightgreen.svg)](https://spring.io/projects/spring-boot)

[简体中文完整文档](README.md) · [贡献指南](CONTRIBUTING.md) · [安全策略](SECURITY.md) · [压测口径](bench/README.md)

llmgw4j is a **Java LLM API gateway** built on Spring Boot 4.1.2 / JDK 25.

Current release: **v0.3.1**. See [release notes](docs/releases/v0.3.1.md) and [performance evidence](docs/PERFORMANCE.md).

Version 0.3.1: foundation discovery uses a project-owned named SPI inspired by Dubbo, without a Dubbo dependency. Services use explicit Java construction. Provider and tokenizer use aggregator/api/implementation modules; Spring/Reactor remain at HTTP adapters. See [extension and migration guide](docs/EXTENSIONS.md). Internal Java extension APIs changed since v0.2.0; HTTP routes remain compatible.

## Capability overview (parity gaps vs mainstream gateways, see docs/gap-analysis.md)

- **Forwarding governance**: same-route retry + exponential backoff, three-tier
  timeouts (connect / first-byte / idle-read), bidirectional header allowlists,
  SSE heartbeat, weighted LB + failover + circuit breaker, hot reload for both
  the route table and governance parameters
- **Protocol surface (8 byte-faithful protocols)**: chat / responses / messages /
  images / videos / embeddings / rerank / audio-speech — plus multipart uploads
  (audio transcriptions, file uploads), file lifecycle and batch jobs with
  sticky routing. Protocols are a ROUTING dimension, never translated
- **Keys & budgets**: runtime API key management (create/revoke/TTL, JSON store),
  token quotas with in-flight reservation + refunds, **demo estimated $ budgets** priced
  from the model catalog (disabled in production), per-group spend rollups
- **JWT**: optional HS256 verification with issuer/audience constraints and numeric expiry/not-before checks; shared JSON parsing avoids per-validation mapper creation. OIDC/JWKS are not built in.
- **Provider adapters**: `UpstreamAuthenticator` SPI — OpenAI-compatible Bearer
  and native Anthropic (x-api-key + anthropic-version) per route
- **Observability**: Prometheus metrics, TTFT/upstream/overhead timers, audit
  ring, bounded request log, trace-context propagation (`-Potel` for full
  spans), admin console
- **Content & cache**: Guardrail SPI (local regex + external engine), semantic
  cache (Embedder SPI + bucketed vector keys), exact cache
- **Multi-replica**: `-Predis` (Lua-atomic limiter/quota/breaker + shared key
  table, response cache and task stickiness)
- **Engineering**: 117 JUnit tests (including real Redis verification) + both-engine contract, isolation and authentication regressions + Docker/compose + TLS front-proxy deployment guide

It was designed to answer one question properly: *for a streaming LLM proxy,
does a reactive data plane actually beat a virtual-thread data plane?*
So instead of picking one, it implements **both behind a single shared
governance layer**, switchable by one Spring profile, and ships the paired
A/B harness needed to compare them without hidden variables.

## Why it is different

| | llmgw4j | Typical general-purpose gateway | Typical LLM proxy |
|---|---|---|---|
| Understands tokens | ✅ token-based quota & rate limiting | ❌ request/connection only | ✅ |
| Data plane A/B | ✅ **same jar, one variable** (WebFlux vs virtual threads) | ❌ | ❌ |
| Streaming contract | ✅ `data: [DONE]` is the *only* completion signal; never faked | ⚠️ often treats EOF as success | ⚠️ varies |
| Governance | ✅ project-owned named SPIs, native factories | ⚠️ plugin SDK | ⚠️ built-in features |
| TTFT / overhead accounting | ✅ dedicated metrics, headers, audit fields | ❌ | ⚠️ partial |
| Reproducible evidence | ✅ ABBA paired A/B + published load timeline | ❌ | ❌ |

This is a **reference implementation and measurement rig**, not a drop-in
production replacement for Kong / Higress / Envoy. See
[honest limitations](README.md#六诚实的风险清单).

## Architecture

```
  POST /v1/chat/completions
            │
            ▼
  ┌─────────────────────── llmgw-app ───────────────────────┐
  │     --spring.profiles.active=webflux | vt                │
  │   ┌─────────────────┐        ┌───────────────────────┐  │
  │   │ WebFlux / Netty │   OR   │ Servlet + virtual     │  │
  │   │ (reactive)      │        │ threads / Tomcat 11   │  │
  │   └────────┬────────┘        └───────────┬───────────┘  │
  │            └──────────────┬───────────────┘              │
  │                           ▼                              │
  │   llmgw-governance/default (written ONCE, shared by both)  │
  │   Router · TokenLimiter · UsageLedger · CircuitBreaker    │
  │   SemanticCache · ApiKeyRegistry · AuditSink · Metrics    │
  │                           │                              │
  │              project-owned named SPIs + explicit native Java factories
  └───────────────────────────┼──────────────────────────────┘
                              ▼
                 JdkHttpUpstream (java.net.http)
                 mock upstream on :9090 by default
```

Maven layout is **four capability domains**, each with an aggregator, API and implementation modules:

| Domain | API | Implementations |
|---|---|---|
| `llmgw-provider/` | `llmgw-provider-api` | `http-jdk`, `openai`, `openai-sdk`, `anthropic` |
| `llmgw-tokenizer/` | `llmgw-tokenizer-api` | `heuristic`, `jtokkit` (opt-in) |
| `llmgw-engine/` | `llmgw-engine-api` | `llmgw-engine-webflux`, `llmgw-engine-vt` |
| `llmgw-governance/` | `llmgw-governance-api` | `llmgw-governance-default`, `llmgw-governance-redis` (opt-in, `-Predis`) |

Each implementation depends on its domain API and never on a sibling; the
APIs depend only on `llmgw-core`; the data planes never reference a governance
implementation. `llmgw-app` is the composition root (both engines in one fat
jar, selected by Spring profile). Foundation services use explicit construction
and the project-owned named SPI; only the HTTP boundary uses Spring/Reactor.
`./bin/check-module-layout.sh` enforces these rules in CI.

## Quick start

Requirements: **JDK 25**, Maven 3.9+. GraalVM 25 only for native images.
A built-in mock upstream starts on `:9090`, so **no vendor API key is needed**.

```bash
./bin/build.sh
./bin/run-webflux.sh       # or: ./bin/run-vt.sh
```

```bash
# non-streaming
curl -sS -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer sk-demo' \
  -d '{"model":"qwen-plus","messages":[{"role":"user","content":"hi"}],"stream":false}'

# streaming SSE
curl -N -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer sk-demo' \
  -d '{"model":"qwen-plus","messages":[{"role":"user","content":"hi"}],"stream":true}'

# hot reload of the routing table (atomic, in-flight streams are not interrupted)
curl -sS -X POST http://127.0.0.1:8080/admin/routes/reload \
  -H 'Content-Type: application/json' \
  -d '[{"id":"a","model":"*","targetUrl":"http://127.0.0.1:9090/mock/v1","weight":1},
       {"id":"b","model":"*","targetUrl":"http://127.0.0.1:9090/mock/v1","weight":3}]'
```

> **Security before exposing anything**: `/admin/*` is unauthenticated by
> default and `sk-demo` is a demo key. Set `LLMGW_ADMIN_TOKEN` and replace
> the key in any shared environment. See [SECURITY.md](SECURITY.md).

## The streaming contract (non-negotiable)

- For chat, `data: [DONE]` from the upstream is the completion signal. Responses
  and Messages use their own protocol terminators.
  A clean EOF without it is a **failure**, not a successful response, and the
  gateway never fabricates a termination frame.
- Upstream failure **before** the first chunk → `502 + JSON`.
- Upstream failure **after** the first chunk → `event: error` followed by
  `[DONE]`, and the request is billed as failed.
- No upstream fallback after any chunk has been sent (no spliced answers).
- Native Flow cancellation propagates to the HTTP subscription/future; normal
  completion does not abort transport. Engines settle billing/audit on cancellation
  and release reservations.

## Observability

| Metric / field | Meaning |
|---|---|
| `llmgw.ttft` | time to first upstream chunk (nanosecond precision, p50/p99) |
| `llmgw.upstream` | upstream duration (JSON: full; SSE: first byte) |
| `llmgw.overhead` | total − upstream |
| `X-LLMGW-Overhead-Ms` | preflight overhead in the response header (both modes) |
| `X-LLMGW-Upstream-Ms` | non-streaming only — SSE headers are already committed |
| `X-Request-Id`, `X-LLMGW-Config-Version` | request correlation; configuration version on `GET /admin/routes` |
| `ttftMs`, `upstreamMs`, `gatewayOverheadMs` | audit event fields |

Timing starts when the request enters the data plane, so `llmgw.latency`
includes body reading and parsing. Formatting and metric recording happen on
the governance virtual-thread side path, never on the hot path.

## Hot configuration & distributed state

- `RouteConfigSource` SPI + immutable `RouteTable` behind one
  `AtomicReference`. Validation happens before the table is built, then a
  single CAS publishes it. In-flight requests keep their already-resolved
  `Route`; nothing is drained or re-routed. `POST /admin/governance` also updates the supported rate/burst/cache/circuit
  settings. Other startup configuration still requires a restart.
- `llmgw-governance/llmgw-governance-redis` is **opt-in**: built with `-Predis` and activated
  with `gateway.redis.enabled=true`. Lua scripts make shared rate limiting,
  quota reservation and circuit breaking atomic across replicas. The default
  fat jar contains no Redis dependency at all. This trades 2–4 Redis
  round-trips per request for cross-replica consistency.

## Performance and measurement limits

The pre-v0.2.0 frozen performance review measured these changes: the SSE parser copies payload text once; WebFlux header copying avoids multi-value collections. Local allocations dropped by 22%–48% for SSE and 32% for headers. This does not imply equal end-to-end speedups.

The latest frozen-artifact A/B contains 64 samples and 4,217,770 requests, all error-free with matching upstream counts. WebFlux SSE median throughput increased 7.3% / 5.2% in separate governance-on/off batches, with overlapping ranges. JSON results change direction and some p99 values worsen; there is no evidence for a general stable throughput gain. A 4-thread experiment produced an SSE truncation, so the default remains 32.

See the [full report](docs/verification/perf/2026-11-10/perf-review/README.md) for JFR, ranges, raw evidence and limitations. Historical cross-gateway results describe older fixed artifacts, not a new v0.3.1 ranking. Native performance, public TLS and remote governance latency were not remeasured. The SPI/Flow refactor in v0.3.1 has not been benchmarked; benchmark JAR hashes remain explicitly recorded.

The URI cache remains unbounded for high-cardinality dynamic paths. Default concurrency is 64 and non-streaming response size is capped at 4 MiB; uploads and downloads still buffer whole bodies. See [benchmark instructions](bench/cross-gateway/README.md).

## Regression suite

| Script | Assertions |
|---|---|
| `./bin/smoke-stream-errors.sh` | JVM 67 / each native image 34 |
| `./bin/smoke-observability.sh` | 30 (both engines; includes `engine` metric label assertions) |
| `./bin/smoke-hot-reload.sh webflux` / `vt` | 10 each |
| `./bin/smoke-redis-cluster.sh` | 7 |
| `./bin/smoke-v13-extensions.sh` | 54 |
| `./bin/smoke-v13-hardening.sh` | 46 |
| `python3 bin/smoke-tenant-isolation.py` | both engines |
| `python3 bin/smoke-admin-auth.py` / `smoke-jwt-auth.py` | authentication boundaries |

New capabilities get their own script; the historical 67/34 counts stay
comparable across versions.

## Contributing & license

- [CONTRIBUTING.md](CONTRIBUTING.md) — environment, SPI conventions, PR checklist
- [SECURITY.md](SECURITY.md) — private vulnerability reporting and deployment warnings
- [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)
- [CHANGELOG.md](CHANGELOG.md)

Licensed under the **Apache License 2.0** — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
