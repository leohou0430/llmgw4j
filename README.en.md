# llmgw4j

> **One governance layer. Two switchable data planes. Evidence you can reproduce.**
>
> Naming: `llmgw` (LLM Gateway) + `4j` (for Java — log4j / dom4j style).

[![CI](https://github.com/leohou0430/llmgw4j/actions/workflows/ci.yml/badge.svg)](https://github.com/leohou0430/llmgw4j/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-25-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.2-brightgreen.svg)](https://spring.io/projects/spring-boot)

[简体中文完整文档](README.md) · [贡献指南](CONTRIBUTING.md) · [安全策略](SECURITY.md) · [压测口径](bench/README.md)

llmgw4j is a **Java LLM API gateway** built on Spring Boot 4.1.2 / JDK 25.
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
| Governance | ✅ 10 SPIs, no vendor lock-in | ⚠️ plugin SDK | ⚠️ built-in features |
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
  │              all of them are SPIs + @ConditionalOnMissingBean
  └───────────────────────────┼──────────────────────────────┘
                              ▼
                 JdkHttpUpstream (java.net.http)
                 mock upstream on :9090 by default
```

Maven layout is **two capability domains, each an aggregator + one API +
pluggable implementations**, both rooted at `llmgw-core` (domain SPIs):

| Domain | API | Implementations |
|---|---|---|
| `llmgw-engine/` | `llmgw-engine-api` | `llmgw-engine-webflux`, `llmgw-engine-vt` |
| `llmgw-governance/` | `llmgw-governance-api` | `llmgw-governance-default`, `llmgw-governance-redis` (opt-in, `-Predis`) |

Each implementation depends on its domain API and never on a sibling; the
APIs depend only on `llmgw-core`; the data planes never reference a governance
implementation. `llmgw-app` is the composition root (both engines in one fat
jar, selected by Spring profile); `llmgw-provider-openai` is a single SDK
verifier and will adopt the same layout when a second provider arrives.
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

- `data: [DONE]` from the upstream is the **only** completion signal.
  A clean EOF without it is a **failure**, not a successful response, and the
  gateway never fabricates a termination frame.
- Upstream failure **before** the first chunk → `502 + JSON`.
- Upstream failure **after** the first chunk → `event: error` followed by
  `[DONE]`, and the request is billed as failed.
- No upstream fallback after any chunk has been sent (no spliced answers).
- Downstream cancellation only hooks `onCancel`; billing and audit always
  settle via `doFinally`, and quota reservations are refunded.

## Observability (v10)

| Metric / field | Meaning |
|---|---|
| `llmgw.ttft` | time to first upstream chunk (nanosecond precision, p50/p99) |
| `llmgw.upstream` | upstream duration (JSON: full; SSE: first byte) |
| `llmgw.overhead` | total − upstream |
| `X-LLMGW-Overhead-Ms` | preflight overhead in the response header (both modes) |
| `X-LLMGW-Upstream-Ms` | non-streaming only — SSE headers are already committed |
| `X-Request-Id`, `X-LLMGW-Config-Version` | correlation and routing-table version |
| `ttftMs`, `upstreamMs`, `gatewayOverheadMs` | audit event fields |

Timing starts when the request enters the data plane, so `llmgw.latency`
includes body reading and parsing. Formatting and metric recording happen on
the governance virtual-thread side path, never on the hot path.

## Hot configuration & distributed state

- `RouteConfigSource` SPI + immutable `RouteTable` behind one
  `AtomicReference`. Validation happens before the table is built, then a
  single CAS publishes it. In-flight requests keep their already-resolved
  `Route`; nothing is drained or re-routed. Hot reload currently covers
  **the routing table only**.
- `llmgw-governance/llmgw-governance-redis` is **opt-in**: built with `-Predis` and activated
  with `gateway.redis.enabled=true`. Lua scripts make shared rate limiting,
  quota reservation and circuit breaking atomic across replicas. The default
  fat jar contains no Redis dependency at all. This trades 2–4 Redis
  round-trips per request for cross-replica consistency.

## Benchmarks and the data-quality red line

The project deliberately publishes its own measurement discipline, because
several historical experiments were ruined by host overload (`vm.loadavg`
80–110) and produced contradictory numbers (JSON −50% while SSE appeared
+4.6× in the same run).

Before any performance number is accepted:

1. a **15s-granularity host load timeline** must exist;
2. **per-round raw CSV** must be checked for consistent paired-difference
   signs — flipping directions means noise, not a result;
3. the `0/0` wire experiment and the `20/10` main matrix are **separate
   regimes** and must never be subtracted row-by-row;
4. both arms must be build-identical except for the tested variable.

A 2026-11-08 finding: on this 16-core host, a 50 VU wire run **itself**
pushes load1 from 4–7 to 100+ within a minute even with no external load
and no leftover processes, so ABBA interleaving cannot cancel the drift.
Two fresh paired runs produced opposite medians and are archived as invalid
samples. A conclusive A/B needs a dedicated idle host, or lower VUS with
inter-round cooldown; see `bench/README.md`.

Overloaded runs are archived for reproduction but **never** become a
conclusion. See [bench/README.md](bench/README.md) and the
[benchmark issue template](.github/ISSUE_TEMPLATE/benchmark_report.yml).

## Regression suite

| Script | Assertions |
|---|---|
| `./bin/smoke-stream-errors.sh` | JVM 67 / each native image 34 |
| `./bin/smoke-observability.sh` | 30 (both engines; includes `engine` metric label assertions) |
| `./bin/smoke-hot-reload.sh webflux` / `vt` | 10 each |
| `./bin/smoke-redis-cluster.sh` | 7 |

New capabilities get their own script; the historical 67/34 counts stay
comparable across versions.

## Contributing & license

- [CONTRIBUTING.md](CONTRIBUTING.md) — environment, SPI conventions, PR checklist
- [SECURITY.md](SECURITY.md) — private vulnerability reporting and deployment warnings
- [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)
- [CHANGELOG.md](CHANGELOG.md)

Licensed under the **Apache License 2.0** — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
