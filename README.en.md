# llmgw4j

[![CI](https://github.com/leohou0430/llmgw4j/actions/workflows/ci.yml/badge.svg)](https://github.com/leohou0430/llmgw4j/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-25-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.2-brightgreen.svg)](https://spring.io/projects/spring-boot)

[简体中文完整文档](README.md) · [CONTRIBUTING](CONTRIBUTING.md) · [SECURITY](SECURITY.md) · [benchmark rules](bench/README.md)

llmgw4j is a **Java LLM API gateway** built on Spring Boot 4.1.2 / JDK 25.

> **One governance layer. Two switchable data planes. Evidence you can reproduce.**
>
> Naming: `llmgw` (LLM Gateway) + `4j` (for Java — log4j / dom4j style).

> Production requires `gateway.production=true`, non-demo client keys, administrator bootstrap credentials and the configured production checks; defaults are local demos. Money admission has two separate paths: **tenant prepaid billing** (`gateway.billing.enabled=true`, ADR-0007: durable SQLite accounts, recharge/overdraft, per-call frozen customer price versions, estimated reservations, verified settlement, unknown outcomes pending reconciliation across restarts) and the **legacy demo budget** (`budgetMicroUsd` on keys/groups, rejected in production without durable accounting). Enable `gateway.billing.strict-budget` (ADR-0009) when a provable amount bound is required; calls without an explicit output bound are rejected. Billing does not claim a hard cap on the final amount owed.

Current release: **v0.5.0** — see [release and upgrade notes](docs/releases/v0.5.0.md). The application targets **one Java process with SQLite** (ADR-0006): keys, project/key token quotas, tenant billing ledgers, idempotency and approval state, the resource catalog and published routes share one durable authority; SQLite migrates to V13. Redis remains an optional governance backend; multiple gateway replicas sharing this database are not supported.

The `/control/` console provides account/session/CSRF authentication, scoped roles, organizations, keys, quota and money reconciliation, prices, approvals, recordings, route publication/rollback, encrypted provider credentials and audit queries. Set `LLMGW_ADMIN_USERNAME` and `LLMGW_ADMIN_PASSWORD` for first use, and `LLMGW_ROOT_KEY` for encrypted credentials and routing snapshots. Configure the required production legacy token to leave the unauthenticated local-demo exception; legacy token access is then disabled unless restricted migration mode is explicitly enabled. See [operations](docs/control-plane-operations.md).

Evidence for v0.5.0: [chapter 9 governance review](docs/reviews/2026-11-20-chapter9-governance-review/README.md) and the [spring-javaformat introduction verification](docs/verification/spring-javaformat-introduction/2026-11-20/README.md). Earlier [remediation evidence](docs/reviews/2026-11-16-remediation/README.md) keeps its original artifact scope. Historical performance and live-provider numbers retain their original scope; no speedup or regression is claimed for this release.

## Capability overview (parity gaps vs mainstream gateways, see docs/gap-analysis.md)

- **Forwarding governance**: same-route retry + exponential backoff, three-tier
  timeouts (connect / first-byte / idle-read), bidirectional header allowlists,
  SSE heartbeat, weighted LB + failover + circuit breaker, hot reload for both
  the route table and governance parameters
- **Protocol surface (8 byte-faithful protocols)**: chat / responses / messages /
  images / videos / embeddings / rerank / audio-speech, plus multipart uploads,
  file lifecycle and batch jobs on a project-scoped durable catalog. Byte-faithful
  forwarding is the default; controlled chat/responses/messages conversion to
  DeepSeek Chat is opt-in. Gemini is reached as `provider: gemini` over HTTP and
  adds only OpenAI-Chat conversion (ADR-0008)
- **Keys & quotas**: runtime key management (create/rotate/revoke, SHA-256 at rest,
  SQLite authority), durable project/key token quotas with atomic in-flight
  reservations, known-outcome settlement and conservative unknown-outcome
  retention, per-model rate limits, cross-attempt deadline budget
  (`total-deadline-ms` / `X-LLMGW-Deadline`), attribution headers
  (`X-LLMGW-Session-Id` / `X-LLMGW-Turn-Id`)
- **Tenant prepaid billing** (`gateway.billing.enabled`, ADR-0007): durable
  accounts with recharge/overdraft, project sub-limits, customer price versions
  frozen per call, estimated reservations and authoritative settlement, unknown
  outcomes pending reconciliation across restarts, persisted micro-CNY tails,
  cache hits priced at zero, retries billed once through the final attempt.
  A missing price rejects admission with 402 instead of silently recording zero.
  `gateway.billing.strict-budget` (ADR-0009) requires an explicit output bound,
  otherwise 400 `strict_budget_requires_output_bound`
- **Customer idempotency** (`Idempotency-Key`): evaluated after authentication and
  before money reservation; the same key with the same digest returns 409 with the
  original call identity (no SSE replay, no double charge); a different digest under
  the same key is also 409; unknown outcomes report `EXISTING_UNKNOWN` and are not
  resubmitted
- **Approvals & MCP**: approval state machine `PENDING → APPROVED/REJECTED/EXPIRED → REVOKED`
  where approval is not execution — the actor revalidates by parameter digest before
  running (ADR-0011); MCP gateway `/mcp/{server}` with four-step admission,
  default-deny tool authorization and a modern downstream plus one-way legacy bridge
  (ADR-0010); disabled by default, and both engines answer JSON-RPC 404 while disabled
- **Provider adapters**: `UpstreamAuthenticator` SPI — OpenAI-compatible Bearer,
  Anthropic (`x-api-key` + `anthropic-version`) and Gemini (`x-goog-api-key`) per
  route; caller keys are never forwarded upstream
- **JWT**: optional HS256 verification with issuer/audience constraints and numeric
  expiry/not-before checks. OIDC/JWKS are not built in
- **Observability**: Prometheus metrics, TTFT/upstream/overhead timers, audit ring,
  bounded request log, trace-context propagation (`-Potel`), `gen_ai.*` span bridge,
  admin console
- **Content & cache**: Guardrail SPI (local regex + external engine), semantic cache
  (Embedder SPI + bucketed vector keys), exact cache, invalidation and bypass
- **Optional Redis**: `-Predis` supplies limiter, breaker and cache governance with a
  fail-closed failure policy (governance unavailable → 503 + `Retry-After`). Keys,
  token/billing ledgers and published routes remain SQLite-owned; single-replica scope
- **Engineering**: `mvn clean verify` runs 1230 tests (including real Redis and both
  engines); Docker/compose, TLS front-proxy guide, optional tokenizer (`-Pjtokkit`)
  and OTel (`-Potel`); source style is enforced by spring-javaformat in the
  `validate` phase

It was designed to answer one question properly: *for a streaming LLM proxy,
does a reactive data plane actually beat a virtual-thread data plane?*
So instead of picking one, it implements **both behind a single shared
governance layer**, switchable by one Spring profile, and ships the paired
A/B harness needed to compare them without hidden variables.

## Why it is different

| | llmgw4j | Typical general-purpose gateway | Typical LLM proxy |
|---|---|---|---|
| Understands tokens and money | ✅ token quotas, reservations, tenant prepaid billing, strict mode | ❌ request/connection only | ⚠️ varies |
| Data plane A/B | ✅ **same jar, one variable** (WebFlux vs virtual threads) | ❌ | ❌ |
| Streaming contract | ✅ protocol terminator is the *only* completion signal; never faked | ⚠️ often treats EOF as success | ⚠️ varies |
| Idempotency & approvals | ✅ durable, admission-seam gates | ❌ | ⚠️ partial |
| Governance | ✅ project-owned named SPIs, explicit native factories | ⚠️ plugin SDK | ⚠️ built-in features |
| TTFT / overhead accounting | ✅ dedicated metrics, headers, audit fields | ❌ | ⚠️ partial |
| Reproducible evidence | ✅ ABBA paired A/B + published load timeline | ❌ | ❌ |

This is a **reference implementation and measurement rig**, not a drop-in
production replacement for Kong / Higress / Envoy. See
[honest limitations](README.md#六风险清单当前).

## Architecture

```
  POST /v1/{chat,responses,messages,images,videos,...}
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
  │   control-sqlite (+ billing / idempotency / approval /    │
  │   mcp / recording modules, loaded by switch)              │
  │                           │                              │
  │              project-owned named SPIs + explicit native Java factories
  └───────────────────────────┼──────────────────────────────┘
                              ▼
                 JdkHttpUpstream (java.net.http)
                 mock upstream on :9090 by default
```

Maven layout is **fourteen capability domains plus the composition root**, each with
an aggregator, one API module and one module per implementation:

| Domain | API | Implementations |
|---|---|---|
| `llmgw-provider/` | `llmgw-provider-api` | `http-jdk`, `openai`, `openai-sdk`, `anthropic`, `gemini` |
| `llmgw-tokenizer/` | `llmgw-tokenizer-api` | `heuristic`, `jtokkit` (opt-in) |
| `llmgw-engine/` | `llmgw-engine-api` | `llmgw-engine-webflux`, `llmgw-engine-vt` |
| `llmgw-governance/` | `llmgw-governance-api` | `llmgw-governance-default`, `llmgw-governance-redis` (opt-in) |
| `llmgw-conversion/` | `llmgw-conversion-api` | `chat`, `responses`, `messages`, `gemini`, `openai-chat`, `deepseek`, `module` |
| `llmgw-persistence/` | `llmgw-persistence-api` | `sqlite` (migrations V1…V13, ledgers, payload store, backups) |
| `llmgw-billing/` | `llmgw-billing-api` | `sqlite` (accounts, prices, reservations, pending reconciliation) |
| `llmgw-idempotency/` | `llmgw-idempotency-api` | `sqlite` |
| `llmgw-approval/` | `llmgw-approval-api` | `sqlite` |
| `llmgw-mcp/` | `llmgw-mcp-api` | `default` |
| `llmgw-recording/` | `llmgw-recording-api` | `local` |
| `llmgw-catalog/`, `llmgw-identity/`, `llmgw-control/` | matching `-api` | `default` / `default` / `default` |
| `llmgw-core` | — | protocol DTOs, routing, governance contracts, extension context; no Spring/Reactor |

Each implementation depends on its domain API and never on a sibling; the APIs
depend only on `llmgw-core`; the data planes never reference a governance
implementation. `llmgw-app` is the composition root (both engines in one fat jar,
selected by Spring profile) and loads `in-memory` → optional `redis` → `jdk-http`
→ `conversion` → `control-sqlite` → optional `billing-sqlite` / `recording-local`
→ `idempotency-sqlite` → `approval-sqlite` → optional `mcp-default`. Later
durable modules `replace` the in-memory bindings before the context is sealed.
Only the HTTP boundary uses Spring/Reactor; foundation modules reject it in source
and in transitive dependencies. `./bin/check-module-layout.sh` and
`python3 bin/check-native-boundaries.py` enforce these rules in CI.

Each domain is a **deep module**: the interface is small (one domain SPI plus a few
value types) while the implementation is thick — idempotency CAS progression, the
approval state machine, billing reservations/tails and MCP admission all live
inside. Callers and tests cross the same seam; internal seams stay private. A seam
is only introduced when something actually varies across it (two engines, several
provider dialects, in-memory vs Redis).

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

# customer idempotency: the second identical call returns 409 with the original
# call identity (no SSE replay, no double charge)
curl -sS -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer sk-demo' \
  -H 'Idempotency-Key: order-2026-11-20-0001' \
  -d '{"model":"qwen-plus","messages":[{"role":"user","content":"hi"}],"stream":false}'

# MCP gateway (disabled by default; needs gateway.mcp.enabled=true and declared
# servers/rules; tools are denied unless authorized)
curl -sS -X POST http://127.0.0.1:8080/mcp/demo \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer sk-demo' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

Publish or roll back routes through a platform-admin `/control` session with CSRF and `expectedVersion`. SQLite usage restores automatically; replacing the authoritative ledger via a JSON snapshot returns 409.

To enable tenant prepaid billing, the control-plane order is: create tenant/project → grant the project token quota → set customer prices (`POST /control/prices/{model}`) → recharge (`POST /control/billing/recharge`) → issue keys carrying `llmgw.tenantId` / `llmgw.projectId`. Inspect pending items with `GET /control/billing/reservations/{tenantId}/pending` and reconcile with `POST /control/billing/reverse`; a call without an applicable price is rejected with 402 rather than recorded as zero. See [CONFIGURATION](docs/CONFIGURATION.md) and [ADR-0007](docs/adr/0007-tenant-prepaid-billing-with-estimated-reservations.md). Configure the production legacy token to leave the unauthenticated demo exception, keep token mode disabled, replace demo keys and isolate management traffic. See [SECURITY.md](SECURITY.md).

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
the governance virtual-thread side path, never on the hot path. With `-Potel`,
`gen_ai.*` span attributes bridge the gateway judgment to the observation API.

## Hot configuration & distributed state

- `RouteConfigSource` SPI + immutable `RouteTable` behind one
  `AtomicReference`. Validation happens before the table is built, then a
  successful SQLite transaction publishes it through `/control/routes/publish`. In-flight requests keep their already-resolved
  `Route`; nothing is drained or re-routed. `POST /admin/governance` also updates the supported rate/burst/cache/circuit
  settings. Other startup configuration still requires a restart.
- `llmgw-governance/llmgw-governance-redis` is **opt-in**: built with `-Predis` and activated
  with `gateway.redis.enabled=true`. Lua scripts provide rate limiting, circuit breaking and cache governance, with a
  fail-closed policy (`gateway.redis.failure-policy`) that answers 503 + `Retry-After`
  when governance is unavailable. The application still uses SQLite for keys, token
  and billing ledgers, idempotency/approval state and published routes; multi-replica
  application deployment is outside this release scope. The default jar contains no
  Redis dependency.

## Performance and measurement limits

The pre-v0.2.0 frozen performance review measured these changes: the SSE parser copies payload text once; WebFlux header copying avoids multi-value collections. Local allocations dropped by 22%–48% for SSE and 32% for headers. This does not imply equal end-to-end speedups.

The latest frozen-artifact A/B contains 64 samples and 4,217,770 requests, all error-free with matching upstream counts. WebFlux SSE median throughput increased 7.3% / 5.2% in separate governance-on/off batches, with overlapping ranges. JSON results change direction and some p99 values worsen; there is no evidence for a general stable throughput gain. A 4-thread experiment produced an SSE truncation, so the default remains 32.

See the [full report](docs/verification/perf/2026-11-10/perf-review/README.md) for JFR, ranges, raw evidence and limitations. Historical cross-gateway results describe older fixed artifacts, not a current ranking. Native performance, public TLS and remote governance latency were not remeasured for v0.5.0; the billing/idempotency/approval hot path is not benchmarked, so no performance claim is made for it.

## Regression suite

| Script | Assertions |
|---|---|
| `mvn -B clean verify` | 1230 tests (module summaries), includes spring-javaformat validate |
| `./bin/smoke-stream-errors.sh` | JVM 67 / each native image 34 |
| `./bin/smoke-multi-protocol.sh` | 56 (both engines) |
| `./bin/smoke-v13-extensions.sh` | 54 |
| `./bin/smoke-v13-hardening.sh` | 46 |
| `./bin/smoke-v13-platform.sh` | 54 |
| `./bin/smoke-v13-provider.sh` | 24 |
| `./bin/smoke-observability.sh` | 30 (both engines) |
| `./bin/smoke-hot-reload.sh webflux` / `vt` | 10 each |
| `./bin/smoke-redis-cluster.sh` | 7 (shared quota, two replicas) |
| `python3 bin/smoke-redis-recovery.py --jar <redis-jar>` | 5 (restart, outage, reconnect) |
| `python3 bin/smoke-control-plane.py` | 10 scenarios (both engines) |
| `python3 bin/smoke-tenant-isolation.py` | tenant boundaries (both engines) |
| `python3 bin/smoke-billing.py` / `smoke-billing-recovery.py` / `smoke-gemini.py` | billing 24 / recovery 8 / Gemini 18 |
| `python3 bin/smoke-admin-auth.py` / `smoke-jwt-auth.py` | administrative and JWT boundaries |
| `python3 bin/smoke-heartbeat-deadline.py` / `smoke-sse-line-limit.py` | heartbeat/timeout and SSE line limits |
| `python3 bin/smoke-sdk-compatibility.py --out DIR` | official OpenAI/Anthropic SDKs (see `bin/requirements-sdk.txt`) |

If the host already binds the embedded mock default port 9090, pass
`MOCK_PORT=9093` to the shell suites. Each suite owns its control-plane database;
none reads the project-root `data/` directory. New capabilities get their own
script; the historical 67/34 counts stay comparable across versions.

## Contributing & license

- [CONTRIBUTING.md](CONTRIBUTING.md) — environment, SPI conventions, PR checklist
- [SECURITY.md](SECURITY.md) — private vulnerability reporting and deployment warnings
- [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)
- [CHANGELOG.md](CHANGELOG.md)

Licensed under the **Apache License 2.0** — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
