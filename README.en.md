# llmgw4j

> Production requires `gateway.production=true`, non-demo client keys, administrator bootstrap credentials and the configured production checks.
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

Current release: **v0.5.0**. See [release and upgrade notes](docs/releases/v0.5.0.md).

The application targets **one Java process with SQLite**. Client keys, cumulative project/key token quotas, published routes and the file/batch catalog share one durable authority. Redis remains an optional governance backend; this release does not support multiple gateway replicas sharing the SQLite database.

The `/control/` console provides account/session/CSRF authentication, scoped roles, organizations, keys, quota reconciliation, route publication/rollback, encrypted provider credentials and audit queries. Set `LLMGW_ADMIN_USERNAME` and `LLMGW_ADMIN_PASSWORD` for first use, and `LLMGW_ROOT_KEY` for encrypted credentials and routing snapshots. Configure the required production legacy token to leave the unauthenticated local-demo exception; legacy token access is then disabled unless restricted migration mode is explicitly enabled. See [operations](docs/control-plane-operations.md).

[Remediation evidence](docs/reviews/2026-11-16-remediation/README.md) records 16 fixes, JVM profiles, both native engines and bounded 2 CPU / 2 GiB upload tests. Historical performance and live-provider evidence retain their original artifact scope; no general speedup is claimed for v0.4.0.

## Capability overview (parity gaps vs mainstream gateways, see docs/gap-analysis.md)

- **Forwarding governance**: same-route retry + exponential backoff, three-tier
  timeouts (connect / first-byte / idle-read), bidirectional header allowlists,
  SSE heartbeat, weighted LB + failover + circuit breaker, hot reload for both
  the route table and governance parameters
- **Protocol surface (8 byte-faithful protocols)**: chat / responses / messages /
  images / videos / embeddings / rerank / audio-speech — plus multipart uploads
  (audio transcriptions, file uploads), file lifecycle and batch jobs with
  a project-scoped durable catalog. Byte-faithful forwarding is the default; controlled chat/responses/messages conversion to DeepSeek Chat is opt-in
- **Keys & budgets**: runtime API key management (create/rotate/revoke, SQLite authority),
  durable project/key token quotas with atomic reservations, known-outcome settlement and conservative unknown-outcome retention, **demo estimated $ budgets** priced
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
- **Engineering**: module-level JUnit tests (including real Redis verification; current counts are in the audit evidence) + both-engine contract, isolation and authentication regressions + Docker/compose + TLS front-proxy deployment guide

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

```

Publish or roll back routes through a platform-admin `/control` session with CSRF and `expectedVersion`. See [operations](docs/control-plane-operations.md). SQLite usage restores automatically; legacy JSON snapshot replacement returns409. Configure the production legacy token to leave the unauthenticated demo exception, keep token mode disabled, replace demo keys and isolate management traffic. See [SECURITY.md](SECURITY.md).

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
  successful SQLite transaction publishes it through `/control/routes/publish`. In-flight requests keep their already-resolved
  `Route`; nothing is drained or re-routed. `POST /admin/governance` also updates the supported rate/burst/cache/circuit
  settings. Other startup configuration still requires a restart.
- `llmgw-governance/llmgw-governance-redis` is **opt-in**: built with `-Predis` and activated
  with `gateway.redis.enabled=true`. Lua scripts provide rate limiting, circuit breaking and cache governance. The application still uses SQLite for keys, token reservations and published routes; multi-replica application deployment is outside this release scope. The default jar contains no Redis dependency.

## Performance and measurement limits

The pre-v0.2.0 frozen performance review measured these changes: the SSE parser copies payload text once; WebFlux header copying avoids multi-value collections. Local allocations dropped by 22%–48% for SSE and 32% for headers. This does not imply equal end-to-end speedups.

The latest frozen-artifact A/B contains 64 samples and 4,217,770 requests, all error-free with matching upstream counts. WebFlux SSE median throughput increased 7.3% / 5.2% in separate governance-on/off batches, with overlapping ranges. JSON results change direction and some p99 values worsen; there is no evidence for a general stable throughput gain. A 4-thread experiment produced an SSE truncation, so the default remains 32.

See the [full report](docs/verification/perf/2026-11-10/perf-review/README.md) for JFR, ranges, raw evidence and limitations. Historical cross-gateway results describe older fixed artifacts, not a new v0.3.1 ranking. Native performance, public TLS and remote governance latency were not remeasured. The SPI/Flow refactor in v0.3.1 has not been benchmarked; benchmark JAR hashes remain explicitly recorded.

The unreleased [lifecycle and large-frame review](docs/verification/lifecycle/2026-11-11/README.md) adds linear line decoding, bounded WebFlux response buffers, correct client-disconnect classification, and byte-level idle-read progress. Three profiles pass 211 tests each; frozen-JAR contracts and the 2C2G/one-instance slow-reader and capacity scenarios pass. The fresh WebFlux native build and functional contracts also pass. [Measurements](docs/verification/lifecycle/2026-11-11/capacity-results.md) show mixed throughput/latency changes, not a general speedup.

See the [current 2C2G capacity audit](docs/verification/capacity/2026-11-11/README.md) for unreleased changes and verification. The unreleased [overload review](docs/verification/perf/2026-11-11/overload/README.md) moves WebFlux admission before argument resolution and adds `Retry-After: 1` to capacity rejections on both engines; client cooperation is measured separately from server execution cost. The unreleased [heartbeat review](docs/verification/backpressure/2026-11-11/README.md) preserves error status before successful upstream headers, isolates blocking heartbeats from shared deadlines, and drops excess heartbeat ticks under WebFlux backpressure. The unreleased [SSE line boundary](docs/verification/sse/2026-11-11/README.md) caps each upstream line at 1 MiB by default before decoding, with a configurable limit and no total size cap for valid streams. The working-tree transport now caps URI caching at 1,024 entries; once full, new paths are resolved without retention. Default concurrency is 64 and non-streaming response size is capped at 4 MiB; uploads and downloads still buffer whole bodies. See [benchmark instructions](bench/cross-gateway/README.md).

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
