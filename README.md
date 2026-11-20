# llmgw4j — Java 大模型 API 网关

[![CI](https://github.com/leohou0430/llmgw4j/actions/workflows/ci.yml/badge.svg)](https://github.com/leohou0430/llmgw4j/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-25-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.2-brightgreen.svg)](https://spring.io/projects/spring-boot)

[English README](README.en.md) · [贡献指南](CONTRIBUTING.md) · [安全策略](SECURITY.md) · [行为准则](CODE_OF_CONDUCT.md) · [更新日志](CHANGELOG.md)

Spring Boot 4.1.2 + JDK 25 + GraalVM 25 实现的大模型 API 网关。
核心特色：**同一套治理层，两套可切换的数据面（WebFlux / 虚拟线程），八种协议透传对等（chat / responses / messages / images / videos / embeddings / rerank / audio-speech），配可复现的对照压测**。
Java 包名 `io.github.leohou0430.llmgw`，采用 [Apache-2.0](LICENSE) 许可，
仓库地址 `https://github.com/leohou0430/llmgw4j`。
命名取 `llmgw`（LLM Gateway）+ `4j`（for Java，沿用 log4j / dom4j 的命名习惯）。

> 这是给 Java 团队看的网关：不引入 Python/Go 组件，不改现有微服务治理习惯。
> 它是**可复现的参考实现与测量台**，不是 Kong / Higress / Envoy 的即插即用替代品；
> 部署前的安全边界见 [SECURITY.md](SECURITY.md)。
> 生产必须启用 `gateway.production=true`；默认配置仅用于本地演示。金额准入有两条独立路径：**租户预付费计费**（`gateway.billing.enabled=true`，ADR-0007：SQLite 持久账户、充值/透支、独立售价版本、估算预留与核实结算、未知结果跨重启待对账）与**遗留演示预算**（key/group 的 `budgetMicroUsd`；生产在无持久账本时直接拒绝）。需要可证明金额上界时启用严格预算档位（`gateway.billing.strict-budget`，ADR-0009）——无显式输出上界的调用会被拒绝，这是承诺可证明性的代价。计费不宣称最终欠款的硬上限。

> 当前发布：**v0.5.0**。详见[发布与升级说明](docs/releases/v0.5.0.md)。应用面向单进程 Java + SQLite（ADR-0006）：密钥、项目/key累计token账本、租户计费账本、幂等与审批状态、资源目录和已发布路由统一持久化；SQLite 迁移至 V13。Redis 为可选治理后端，不代表支持多网关副本共享此数据库。
>
> `/control/` 提供基于账号、会话、CSRF和角色范围的管理闭环，覆盖租户/项目/key、额度与金额对账、售价、审批、录制、目录、用量、审计与路由发布回滚。首次设置 `LLMGW_ADMIN_USERNAME`、`LLMGW_ADMIN_PASSWORD`，凭据加密另需 `LLMGW_ROOT_KEY`，详见[控制面操作](docs/control-plane-operations.md)。旧`/admin`无凭据本地演示例外不可暴露；生产配置legacy凭据后默认禁用token访问，迁移需显式受限模式。
>
> v0.5.0 的计费/治理证据见[第 9 章治理审查](docs/reviews/2026-11-20-chapter9-governance-review/README.md)；源码风格与修复验证见[spring-javaformat 引入验证](docs/verification/spring-javaformat-introduction/2026-11-20/README.md)。早期[16项审查修复](docs/reviews/2026-11-16-remediation/README.md)保持原产物归属；历史性能与真实供应商数字仍属原始冻结产物，本版不声明性能提升或退化。

## 核心能力（对照主流网关差距分析补齐，详见 [docs/gap-analysis.md](docs/gap-analysis.md)）

- **转发治理**：同路由重试+指数退避、三段式超时（connect/首字节/空闲读）、双向 header
  白名单透传、SSE 心跳、加权负载均衡 + failover + 窗口熔断、路由表与治理参数双热更新
- **协议面（8 协议字节透传）**：chat / responses / messages / images / videos /
  embeddings / rerank / audio-speech；默认字节透传，可显式启用chat/responses/messages到DeepSeek Chat的受控转换；Gemini 以 `provider: gemini` 双向 HTTP 接入，新增转换限于 OpenAI Chat（ADR-0008）
- **密钥与配额**：API key 运行时管理（创建/吊销/TTL/metadata，**SHA-256 哈希存储**，
  重启恢复）、**key 轮换**（overlap 窗口）、**HS256 JWT 入口认证**（issuer/audience 与时间声明校验；
  其他算法可用 `TokenValidator` SPI 自行扩展）、项目/key两级持久累计 token 配额（原子在途预留、已知结果结算、未知结果保守占额）、**per-model 限流**、**跨 attempt 截止预算**（`total-deadline-ms`/`X-LLMGW-Deadline`）、**归因关联头**（`X-LLMGW-Session-Id`/`X-LLMGW-Turn-Id` 绑定主体入审计）
- **租户预付费计费**（`gateway.billing.enabled`，ADR-0007）：SQLite 持久账户与充值/透支、项目子限额、客户售价版本按调用冻结、估算预留与权威用量结算、未知结果跨重启待对账、人民币 0.000001 元尾数按租户累计、缓存命中零价、重试仅按最终尝试计费；缺售价准入即 402，不静默记零。**严格预算档位**（`gateway.billing.strict-budget`，ADR-0009）要求显式输出上界，否则 400 `strict_budget_requires_output_bound`。遗留 key/group `budgetMicroUsd` 演示预算保留，但生产无持久账本时拒绝
- **客户幂等键**（`Idempotency-Key`）：认证后、金额预留前判读；同键同摘要返回 409 原调用标识（不重放 SSE、不重复计费），同键异摘要同样 409，结果未知附 `EXISTING_UNKNOWN` 不自动重发
- **审批与 MCP**：审批工作流 `PENDING → APPROVED/REJECTED/EXPIRED → REVOKED`，批准≠放行，执行前按参数摘要重校验（ADR-0011）；MCP 网关 `/mcp/{server}` 四步准入、默认拒绝工具授权、Modern 下游与单向 Legacy 桥接（ADR-0010）；默认关闭，关闭时两引擎均返回 JSON-RPC 404
- **provider 适配**：`UpstreamAuthenticator` SPI——OpenAI 兼容 Bearer、Anthropic
  原生（x-api-key + anthropic-version）与 Gemini 方言随路由选择
- **可观测**：Micrometer/Prometheus 指标、TTFT/上游耗时/网关开销、审计环、
  请求日志（`/admin/requests`）、trace 上下文传播（`-Potel` 全量 span）、`gen_ai.*` span 属性桥、管理控制台
- **内容与缓存**：Guardrail SPI（本地正则 + 外接审核引擎，均带决策/向量缓存）、
  **语义缓存**（Embedder SPI + 请求分区 + **进程内余弦相似度检索**，实测同义改写命中）、
  精确缓存、`DELETE /admin/cache` 失效 + `X-LLMGW-No-Cache` 旁路
- **可选 Redis**：`-Predis` 提供限流、熔断、缓存等治理实现，并新增故障策略（默认 fail-closed，治理不可用返回 503 + `Retry-After`）；应用密钥、token/计费账本与路由仍由 SQLite 统一管理，部署范围为单副本
- **转发韧性**：同路由重试 + 指数退避、**上游 `Retry-After` 感知**、context-overflow
  识别（跳过同路由重试直达 fallback）、三段式超时、加权 LB + failover + 熔断
- **管理面**：审计导出（`/admin/audit/export`）、**审计导出只读 token**、请求日志、
  指标、路由与治理参数双热更新、控制台；`/control` 覆盖计费、售价、审批、录制与载荷
- **工程化**：`mvn clean verify` 覆盖 1230 项测试（含真实 Redis 与双引擎契约）+ 隔离/鉴权回归 + Docker/compose
  + TLS 前置部署文档（[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)）+ 可选 tokenizer
  （`-Pjtokkit`）与 OTel（`-Potel`）；源码风格由 spring-javaformat 在 `validate` 阶段强制

## 定位与不做清单

llmgw4j 是**模型数据面网关**：字节保真透传 + LLM 语义治理。以下能力**有意不做**，
属前置代理（Envoy/Nginx）或上层平台（LiteLLM/Higress 等）的职责：

- WebSocket / gRPC 入口、HTTP/2 上游（南北向流量治理交给前置代理）
- Prompt 模板管理、SSE 流内容改写（与字节透传契约冲突）
- A2A Agent 协议、MCP 订阅/Tasks 扩展与服务端主动流式（MCP Gateway 治理语义首版已交付：四步准入、默认拒绝工具授权、Modern→Legacy 单向桥接，见 ADR-0010）；内置 PII 改写（PII 走外接 Guardrail 引擎）
- 多网关副本共享SQLite、分布式配置发布（单机交付边界见 ADR-0006）；企业 OIDC/JWKS/SSO 身份联邦不在范围。**租户预付费计费**（ADR-0007）与**严格预算档位**（ADR-0009）已内建，但计费不宣称最终欠款的硬上限——余额加透支额度减在途预留只约束准入，不构成财务封顶

## 一、架构

```
                    ┌──────────────────────────────────────────────┐
  POST /v1/{chat/   │  llmgw-app                                  │
  completions,      │  ┌────────────┐  或  ┌────────────────────┐  │
  responses,        │  │ WebFlux    │      │ Servlet + 虚拟线程 │  │
  messages,         │  │ Netty      │      │ Tomcat 11          │  │
  images, videos} ─►│  └─────┬──────┘      └─────────┬──────────┘  │
                    │        └──────────┬────────────┘              │
                    │                   ▼                           │
                    │  ┌────────────────────────────────────────┐  │
                    │  │ llmgw-governance/ (共享，只写一份)     │  │
                    │  │ 鉴权 / 配额 / token 限流 / 缓存(热路径) │  │
                    │  │ 账单 / 审计 / 指标(虚拟线程旁路)        │  │
                    │  └────────────────────────────────────────┘  │
                    │                   ▼                           │
                    │  ┌────────────────────────────────────────┐  │
                    │  │ JdkHttpUpstream (两引擎共用同一客户端) │  │
                    │  │ WebFlux: sendAsync (流式 + 非流式)     │  │
                    │  │ VT: send (阻塞在虚拟线程)             │  │
                    │  └──────────────────┬─────────────────────┘  │
                    │                     ▼                         │
                    │  MockUpstreamServer:9090 或 DashScope 兼容端点 │
                    └──────────────────────────────────────────────┘
```

模块：

| 模块 | 职责 |
|---|---|
| `llmgw-core` | 普通配置 POJO、协议 DTO/解析、路由与治理契约、项目内原生扩展上下文；**无 Spring/Reactor 依赖** |
| `llmgw-governance` | **治理子系统**：聚合 POM 兼子模块父 POM（`packaging=pom`，本身不产出 jar），下挂 1 个 API 模块 + 2 个实现包 |
| └ `llmgw-governance-api` | 实现包公共契约（框架中立）：`GovernanceLimits`（配置归一化）、`QuotaMath`（配额预留/结算）、`UsageSummaries`、`GovernancePacks`（命名扩展与 key 前缀常量）。**只依赖 core**，不引入 Caffeine / Micrometer / Jackson / Redis |
| └ `llmgw-governance-default` | 默认全内存实现包（原生命名 module）：Caffeine 缓存、原子 token 桶、用量账本、JSON 快照、虚拟线程旁路、Micrometer。显式 Java 构造和实例级资源所有权，使用命名模块的 `replace` 替换实现 |
| └ `llmgw-governance-redis` | **可选**（`-Predis` 才链入）：Redis 版 `TokenLimiter` / `CircuitBreaker` / 缓存治理，全部用 Lua 脚本保证跨副本原子性；应用内 token/计费账本仍由控制面 SQLite 接管。默认 fat jar 不含该模块与任何 Redis 依赖 |
| `llmgw-engine` | **数据面子系统**：聚合 POM 兼子模块父 POM（`packaging=pom`，本身不产出 jar），下挂 1 个 API 模块 + 2 个实现模块 |
| └ `llmgw-engine-api` | 数据面公共契约（框架中立）：`EngineKind`、`AbstractEngineConfiguration`、`EngineHeaders`、`EngineReasons`、`EngineRoutes`、`SseChunkAccounting`、`EngineTimings`、`EngineProfiles`。**只依赖 core**，不引入 WebFlux / Servlet |
| └ `llmgw-engine-webflux` | 反应式数据面（`ServerHttpResponse.writeWith` 裸写 SSE）。依赖 `engine-api` + core + webflux starter |
| └ `llmgw-engine-vt` | 虚拟线程数据面（Servlet 流 + JDK HttpClient 阻塞读）。依赖 `engine-api` + core + web starter |
| `llmgw-provider` | provider-api + http-jdk / openai / openai-sdk / anthropic / gemini，统一多模块域 |
| └ `llmgw-provider-api` | 原生 Flow/Future、认证与验证契约；不依赖 Reactor/Spring |
| └ `llmgw-provider-http-jdk` | 独立 JDK HTTP 转发、需求/取消/超时与资源关闭 |
| └ `llmgw-provider-openai` / `anthropic` | 无框架认证方言（Bearer / x-api-key + anthropic-version），经路由 `provider` 按名称选择 |
| └ `llmgw-provider-gemini` | Gemini 方言（`x-goog-api-key`），调用方密钥永不透传上游 |
| └ `llmgw-provider-openai-sdk` | SDK 校验/显式连通性检查，与轻量认证模块分离 |
| `llmgw-tokenizer` | tokenizer-api + heuristic / 可选 jtokkit，经项目命名扩展选择准入估算器 |
| `llmgw-identity` | identity-api + default：账号、会话、RBAC、凭据签发和登录治理 |
| `llmgw-control` | control-api + default：路由版本、平台策略、根密钥与加密契约 |
| `llmgw-persistence` | persistence-api + sqlite：连接/事务、迁移（V13）、持久 key/secret、token 与计费账本、载荷存储、备份和空间保护 |
| `llmgw-billing` | **计费子系统**：billing-api（账户/充值/售价/预留/结算契约）+ billing-sqlite（权威账本、价格版本冻结、待对账、微元尾数） |
| `llmgw-idempotency` | idempotency-api + sqlite：客户幂等键（scope、摘要、一次性推进令牌、TTL 与重启恢复） |
| `llmgw-approval` | approval-api + sqlite：审批状态机（持久 CAS、挂起-重新准入、超时不降级） |
| `llmgw-mcp` | mcp-api + default：MCP 网关语义（出口白名单、默认拒绝、Modern/Legacy 桥接、工具过滤与再鉴权） |
| `llmgw-recording` | recording-api + local：调用录制（事件/终态、有界队列丢弃与降级、载荷加密存储） |
| `llmgw-catalog` | catalog-api + default：项目资源生命周期、固定来源和分页目录 |
| `llmgw-conversion` | conversion-api、chat/messages/responses/gemini 入口、OpenAI Chat 与 DeepSeek 适配、显式组合模块 |
| `llmgw-app` | 组合根：`control-sqlite` 权威实例，按开关装配 `billing-sqlite`/`recording-local`/`idempotency-sqlite`/`approval-sqlite`/`mcp-default`，HTTP 管理桥、mock 与 native hints |

各能力域的依赖方向均由 Maven 强制为**单向无环**：

```
llmgw-core   领域核心：SPI、协议 DTO、路由、请求管线（engine / governance / provider / app 共用）
    ├──> llmgw-engine-api ───────> llmgw-engine-webflux
    │                       └────> llmgw-engine-vt
    ├──> llmgw-governance-api ───> llmgw-governance-default
    │                       └────> llmgw-governance-redis（仅 -Predis 链入）
    ├──> llmgw-billing-api ──────> llmgw-billing-sqlite
    ├──> llmgw-idempotency-api ──> llmgw-idempotency-sqlite
    ├──> llmgw-approval-api ─────> llmgw-approval-sqlite
    ├──> llmgw-mcp-api ──────────> llmgw-mcp-default
    ├──> llmgw-recording-api ────> llmgw-recording-local
    ├──> llmgw-persistence-api ──> llmgw-persistence-sqlite
    ├──> llmgw-catalog-api ──────> llmgw-catalog-default
    ├──> llmgw-identity-api ─────> llmgw-identity-default
    ├──> llmgw-control-api ──────> llmgw-control-default
    ├──> llmgw-provider-api ─────> http-jdk / openai / openai-sdk / anthropic / gemini
    ├──> llmgw-tokenizer-api ────> heuristic / jtokkit（可选）
    └──> llmgw-conversion-api ───> chat / responses / messages / gemini / openai-chat / deepseek

llmgw-app   组合根：同一 fat jar 装两个数据面实现（profile 二选一），按开关显式装配计费/录制/幂等/审批/MCP/control-sqlite
```

同域实现之间互不依赖。跨域通过core或对应api契约连接：数据面使用catalog/conversion等契约，不能依赖同域其他引擎实现或扫描治理实现。基础模块不引入Spring/Reactor，HTTP适配留在app与两引擎实现。

**边界判断**：`GatewayPipeline` / `UpstreamProvider` / `ChunkSink` 以及
`GovernanceChain` / `TokenLimiter` / `UsageLedger` 等 SPI 都留在 `llmgw-core`，
因为它们被 engine、governance、provider、app 共同依赖；搬进任一子系统都会让
下层模块反向依赖上层，形成环。各域`-api`承载的是「**只有本域实现需要、
且多个实现逐字重复**」的部分，不是 SPI 定义本身。

### 模块布局与 SPI 扩展

所有多实现能力域统一为「聚合 parent + 一个 api + 每个实现一个 child module」。当前共 14 个能力域（engine、governance、provider、tokenizer、conversion、persistence、catalog、identity、control、billing、idempotency、approval、mcp、recording）均遵循此规则；未来新增域或实现也由 CI 自动检查，禁止重新在根目录平铺实现。

设计上每个能力域都是一个**深模块**：接口很小（一个本域 SPI 加少量值类型），实现很厚（幂等键的 CAS 推进、审批状态机、计费的预留/结算/尾数、MCP 的四步准入都在实现内部）。调用方与测试都只穿过同一个接口；实现内部的私有 seam（例如计费账本的重启恢复、录制的有界队列）不扩大接口。两个实现才引入真正的 seam——例如 engine 的 WebFlux/VT、provider 的多方言、governance 的内存/Redis——只有一个实现时不提前抽象。

基础能力参考 Dubbo 的命名与惰性机制，采用项目内 `@ExtensionPoint` / `META-INF/llmgw/extensions` / `Extensions`，不直接依赖 Dubbo。治理和有构造依赖的客户端由命名 `ExtensionModule` 使用普通 Java 构造，状态与资源归各自 `ExtensionContext`。Spring 仅在 app 桥接配置和 HTTP 引擎；基础模块的源码与传递依赖均禁止 Spring/Reactor。数据面装配顺序固定为 `in-memory` → 可选 `redis` → `jdk-http` → `conversion` → `control-sqlite` → 可选 `billing-sqlite`/`recording-local` → `idempotency-sqlite` → `approval-sqlite` → 可选 `mcp-default`；后装配的持久实现通过显式 `replace` 覆盖内存绑定的账本。

自定义实现需在独立模块提供命名 factory，并在 seal 前显式替换类型绑定；原来的 `@Component` / `@ConditionalOnMissingBean` 覆盖方式已移除。Provider 的认证、验证、上游契约在 provider-api；TokenCounter 在 tokenizer-api；通用领域契约继续留 core，避免依赖环。详细示例、旧新 API 对照、资源关闭与 native 边界见 [扩展说明](docs/EXTENSIONS.md)及 [贡献规范](CONTRIBUTING.md)。

**检查**：`bin/check-module-layout.sh`、`bin/check-native-boundaries.py` 与 Maven Enforcer。app 可向原生图显式提供 Micrometer Registry，基础度量不再查找 Spring ObjectProvider。

## 二、关键设计判断（面试可讲）

1. **为什么同时要 WebFlux 和虚拟线程**：不是叠加，是做单变量对照实验。两个引擎共用同一治理层和同一个上游客户端，唯一变量就是并发模型。两条数据面都是各自并发模型的「正统实现」：WebFlux 网络转发走 `sendAsync`，阻塞治理通过虚拟线程移出事件循环；VT 两条路径都直接阻塞自己的虚拟线程。
2. **为什么热点链路不让 SDK 碰**：SDK 会反序列化再序列化，厂商扩展字段（`reasoning_content` 等）会丢。网关的价值是协议保真，所以网关按协议各解析少量字段（model / stream / prompt 类文本）用于路由和计量，原始 chunk 与响应体原样透传，网关不做任何协议翻译。
3. **为什么治理分热路径/旁路**：鉴权、配额、限流是纯内存原子操作，留在热路径；SSE 计量、账单、审计、快照交给公共 `VirtualThreadPerTaskExecutor`，避免阻塞事件循环，也让两个引擎的对照公平。
4. **为什么不用 Spring AI / LangChain4j**：它们是应用框架（`ChatModel` / `AiServices`），目标是帮应用「调模型」；网关要的是「原样转发字节流」。选官方 `openai-java` 只做校验与连通性验证，是折中且克制的选择。
5. **状态权威**：基础模块保持框架中立，应用由命名 `control-sqlite` 模块统一装配持久密钥、账本、路由和目录；HTTP只导出同一个原生实例图，JSON快照不能覆盖累计账本。
6. **推理延迟与代理开销应分场景测量**：历史内嵌 mock 的 8 组结果只适用于当时配置，不能推广到所有运行时或竞品。当前统一容器测试分别保留低延迟与模拟推理场景；当代理开销相对较小时，端到端差距会缩小，但不能据此假定网关永远不是瓶颈。当前结果和范围见 [对比报告](docs/gap-analysis.md)。
7. **流式失败要按「首字节是否已出」分层处理**：HTTP 状态在第一个字节写出后才不可逆。网关在首 chunk 前保持响应未提交（VT 不再提前 `flushBuffer()`），上游失败就返回真正的 **502 + JSON**；一旦已发 chunk，只能带内报错 `event: error` 再补 `[DONE]`。**fallback 只允许在首 chunk 前发生**，否则两个上游的 chunk 会拼成一段语义错乱的回答。`bin/smoke-stream-errors.sh` 用 mock 的 `mock-fail-after-N` / `mock-eof-after-N` 标记覆盖这些边界，v5 时计数为 JVM 双引擎 55 项、单个 native 28 项（当前 67/34，见回归表）。
8. **请求体只解析一次**：`GatewayPipeline.enter` 解析出的 `ParsedRequest` 挂在 `GatewayDecision` 上，两个 controller 直接复用，不再各 parse 一遍同一个 JSON。准入与转发共用一次反序列化结果，是网关热路径最基本的节约。
9. **传输层 EOF ≠ 协议层完成**：上游干净关闭连接时 HTTP 客户端不会抛异常，代理若把「没报错」当成功、甚至替上游补一帧终止帧，就会把被截断的回答伪装成完整回答——客户端不报错、账单不记失败、上游故障被静默吞掉。本项目把「上游是否显式发出**该协议自己的终止帧**」作为唯一完成判据（chat=`data: [DONE]`、Responses=`response.completed`、Messages=`message_stop`；images / videos 无流式），两个引擎共用 `ProtocolCodec.truncatedMessage(kind)`；网关**永远不伪造终止帧**——带内错误后的 `[DONE]` 只对 CHAT 追加，responses / messages 的错误事件之后直接干净断流，绝不替上游补另一种协议的终止帧。这是透明代理与「自作主张的中间件」的分界线。

10. **透明代理要同时守住三条资源契约**：上游非 2xx 在 BodyHandler 收到响应头时判定；下游 demand 透传给 JDK Flow.Subscription；正常结束不取消 transport，只有取消/超时等终止路径才中止实际 HTTP future/订阅。v0.3.1 将该逻辑移入无 Reactor 的 provider-http-jdk。账单和审计覆盖取消；未知上游结果保留预留；单元测试与双引擎「40 路并发 SSE」契约覆盖连接复用竞态。

11. **计量以 usage 为准，准入以原子预留为准**：SSE 的 `usage.prompt_tokens` 和 `usage.completion_tokens` 是权威值，必须**覆盖**而非叠加逐 delta 估算（否则 8 个 chunk 记成 16）；非流式要从 `usage` 取真实值，不能拿整个 JSON 信封（id/时间戳/object）估算。配额检查 `used()+estimate>quota` 是读-改-写竞态，并发下会把额度超卖 N 倍，改成含在途请求的 CAS 预留、`finish` 时释放；令牌桶按实际用量结算，失败 / 取消 / 缓存命中退还预扣；refill 的时间戳只在真的铸出整数 token 时推进，否则高频调用会把不足 1 token 的时间片永久丢掉。
12. **缓存键必须是完整请求，不是 prompt**：`model + 文本 prompt` 会让 `temperature` / `max_tokens` / `tools` / 多模态 content 不同但文本相同的两个请求互串答案，并让截断答案污染后续 300 秒。改成完整请求体的 SHA-256（保守精确缓存：宁可 miss 不可错答），且只缓存校验过 `choices` 的合法响应。
13. **管理面按默认最小暴露**：`/admin/routes` 原本直接序列化 `Route` record，里面的 `apiKey` 就是 `${DASHSCOPE_API_KEY}` —— 一个未鉴权 GET 会把上游厂商密钥交出去。现在只回显 `apiKeyConfigured` 布尔值，并支持 `LLMGW_ADMIN_TOKEN` 网关管理接口（本地演示默认空）。SQLite账本禁止快照覆盖、SDK 客户端用后关闭、请求体有 4MB 上限。
14. **计费是「预留—核实—结算」，不是「事后记账」**：准入时必须原子预留估算费用（余额 + 透支 − 在途预留），否则并发请求会集体越过余额；上游结果未知时**保留**预留跨重启待对账，而不是记零或自动重发。客户售价按调用冻结版本，路由回退仍按客户请求的对外模型计费，平台承担供应商差异；人民币尾数按租户持久累计，不逐次丢弃。这些规则都收在 `BillingGate` 的窄接口后面——数据面只需「开始、结算、标记未知」三个动作，不感知账本与价格表。
15. **幂等与审批都放在准入 seam，而不是业务代码里**：`IdempotencyGate` 在认证之后、金额预留之前判读，重复请求既不占配额也不预留，避免借幂等键绕过授权；审批的「批准」不等于「放行」，执行前必须用参数摘要重校验，审批超时不降级为只读。两者都是小接口、厚实现（CAS 推进、TTL 窗口、重启恢复），由 SQLite 持久化。

## 三、跑起来

环境要求：JDK 25（推荐 Oracle GraalVM 25）、Maven 3.9+。

```bash
cd llmgw4j
chmod +x bin/*.sh bench/*.sh
./bin/build.sh

# WebFlux 引擎（Netty，默认）
./bin/run-webflux.sh

# 虚拟线程引擎（Tomcat 11）
./bin/run-vt.sh
```

两个引擎都自带 mock 上游（端口 9090，延迟/chunk 数可配）。
运行脚本会显式传 `--server.port=8080`：宿主环境如果注入了 `SERVER__PORT` 之类的变量，
Spring Boot 的宽松绑定会把端口抢走（本机实测被抢到 58458），不钉死就会对着空气打请求。

```bash
# 非流式
curl -sS -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer sk-demo' \
  -d '{"model":"qwen-plus","messages":[{"role":"user","content":"hi"}],"stream":false}'

# 流式 SSE
curl -N -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer sk-demo' \
  -d '{"model":"qwen-plus","messages":[{"role":"user","content":"hi"}],"stream":true}'

# 客户幂等键：同键同请求第二次返回 409（原 callId/requestId），不重放 SSE、不重复计费
curl -sS -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer sk-demo' \
  -H 'Idempotency-Key: order-2026-11-20-0001' \
  -d '{"model":"qwen-plus","messages":[{"role":"user","content":"hi"}],"stream":false}'

# MCP 网关（默认关闭；需 gateway.mcp.enabled=true 与 servers[rules] 声明，默认拒绝工具）
curl -sS -X POST http://127.0.0.1:8080/mcp/demo \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer sk-demo' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# 默认透传路由指向同协议上游；受控转换须显式设置upstreamProtocol=chat
# OpenAI Responses（流式终止帧是 response.completed，没有 [DONE]）
curl -N -X POST http://127.0.0.1:8080/v1/responses \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer sk-demo' \
  -d '{"model":"qwen-plus","input":"hi","stream":true}'

# Anthropic Messages（流式终止帧是 message_stop）
curl -N -X POST http://127.0.0.1:8080/v1/messages \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer sk-demo' \
  -d '{"model":"qwen-plus","max_tokens":32,"messages":[{"role":"user","content":"hi"}],"stream":true}'

# 图片生成（n 个请求单元计量；大 base64 响应永不进缓存）
curl -sS -X POST http://127.0.0.1:8080/v1/images/generations \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer sk-demo' \
  -d '{"model":"wan","prompt":"a cat","n":2}'

# 视频生成是异步任务：create → poll → content（OpenAI Sora 风格）
VID=$(curl -sS -X POST http://127.0.0.1:8080/v1/videos \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer sk-demo' \
  -d '{"model":"sora","prompt":"a cat runs"}' | jq -r .id)
curl -sS -H 'Authorization: Bearer sk-demo' http://127.0.0.1:8080/v1/videos/$VID   # queued → in_progress → completed
curl -sS -H 'Authorization: Bearer sk-demo' http://127.0.0.1:8080/v1/videos/$VID/content -o out.mp4

# 管理接口（默认本地演示、无鉴权；共享环境务必设置 LLMGW_ADMIN_TOKEN）
curl -sS http://127.0.0.1:8080/admin/snapshot
curl -sS http://127.0.0.1:8080/admin/routes   # 只回显 apiKeyConfigured，不回显密钥
curl -sS http://127.0.0.1:8080/admin/keys
curl -X POST http://127.0.0.1:8080/admin/snapshot/save

```

运行时路由由平台管理员通过`/control/routes/publish`发布完整JSON，使用当前会话CSRF与`expectedVersion`；回滚追加新版本，在途SSE不受影响。Key、项目额度和未知用量通过同一控制面管理，见[操作指南](docs/control-plane-operations.md)。

启用租户预付费计费时，控制面顺序是：建租户/项目 → 授权项目 token 额度 → 设置客户售价（`POST /control/prices/{model}`）→ 充值（`POST /control/billing/recharge`）→ 发放携带 `llmgw.tenantId`/`llmgw.projectId` 的 Key。待对账项用 `GET /control/billing/reservations/{tenantId}/pending` 查看、`POST /control/billing/reverse` 核实；缺售价的调用准入即 402，不会静默记零。详细字段与货币单位（人民币微元）见[配置说明](docs/CONFIGURATION.md)与 [ADR-0007](docs/adr/0007-tenant-prepaid-billing-with-estimated-reservations.md)。

旧`/admin`仅作为本地演示/受限迁移入口：未配置legacy凭据时不可暴露到共享网络；配置token后默认disabled，需要显式迁移模式才能使用。SQLite用量自动恢复，`/admin/snapshot/load`不能覆盖权威账本。请求体默认4MiB，超限413。

**v6 能力**：

- **关联 ID**：客户端传 `X-Request-Id` 就原样回显，否则生成 `gw-<16hex>`。
  同一个 ID 出现在响应头、审计事件（`/admin/snapshot` 的 `recentAudit`）里，两个引擎行为一致，
  一条请求可以端到端串起来。
- **`/admin/metrics`**：聚合全部 `llmgw.*` Micrometer 计量——拒绝原因
  （`llmgw.denied` reason=invalid_key/model_not_allowed/rate_limited/quota_exceeded）、
  缓存 hit/miss、**带 route 标签的请求计数**（加权路由的可观测证据）、熔断 open/reopened/closed 事件、
  延迟 Timer 的 p50/p99/max。读 Micrometer 而不是另记账本，换掉治理实现也喂同一张视图。
- **熔断器**（SPI，默认 `WindowedCircuitBreaker`）：同一 route 连续 `circuit-failure-threshold`
  （默认 5，<=0 关闭）次「未交付任何 chunk 的失败」后开路，冷却 `circuit-cooldown-ms`（默认 30s）后
  放**一个**半开探测（CAS 保证不并发踩踏），探测成功关闭、失败重开。开路时引擎**零网络 I/O** 直接
  走 fallback；`CircuitOpenException` 永不出网。
- **加权路由**：同 model 多路由按 `weight` **加权随机**分流（weight 是负载比例，不只是优先级）；
  精确 model 仍优先于 `*` 通配；单路由行为与之前完全一致。

接真实上游（阿里百炼 OpenAI 兼容端点，读环境变量 `DASHSCOPE_API_KEY`）：

```bash
java -jar llmgw-app/target/llmgw-app-0.5.0.jar \
  --spring.profiles.active=webflux,real
curl -sS http://127.0.0.1:8080/admin/verify   # SDK 连通性验证
```

关闭治理（纯透传对照用）：`--gateway.governance-enabled=false`

**全量回归**（`mvn -B clean verify` 1230 项测试；默认 profile 双引擎 shell 冒烟 341 项、Python 冒烟 11 套；`-Predis`/`-Predis,jtokkit,otel` 各一轮；native webflux 84 项、vt 57 项。覆盖正常路径、401/400/402/409/413、上游不可达、非 2xx、fallback、首 chunk 后断流、静默截断、`[DONE]` 后断连、禁止拼接、缓存键、精确计量、计费预留与对账、幂等键、审批状态机、MCP 准入、管理接口脱敏与鉴权、快照重复恢复、断连结算、40 路并发 SSE、关联 ID、熔断开路、加权路由）：

```bash
# 结构校验：能力域布局（CI 每次 PR 也跑，新增域自动纳入）
./bin/check-module-layout.sh                 # 依赖方向、实现互不依赖、无 split package
python3 bin/check-native-boundaries.py       # 基础模块无 Spring/Reactor/HTTP

# 默认 profile 全量测试（含 spring-javaformat validate 阶段）
mvn -B clean verify

./bin/smoke-stream-errors.sh                                             # JVM，双引擎 67 项
BINARY="$PWD/bin/native/llmgw-webflux" ./bin/smoke-stream-errors.sh webflux  # native 34 项
BINARY="$PWD/bin/native/llmgw-vt"      ./bin/smoke-stream-errors.sh vt

# 多协议回归（chat/responses/messages/images/videos/embeddings/rerank/speech × 双引擎）
./bin/smoke-multi-protocol.sh                # 56 项：协议路由 / 计量 / 各协议终止帧契约 / sticky 视频
BINARY="$PWD/bin/native/llmgw-webflux" ./bin/smoke-multi-protocol.sh webflux

# 扩展、加固、平台、provider 方言
./bin/smoke-v13-extensions.sh                # 54 项：语义缓存 + embeddings/rerank/speech
./bin/smoke-v13-hardening.sh                 # 46 项：重试/超时/心跳/取消
./bin/smoke-v13-platform.sh                  # 54 项：$ 预算、热更新、Key 生命周期、控制台、MCP 禁用语义
./bin/smoke-v13-provider.sh                  # 24 项：provider 凭据方言

# 管理面与隔离/鉴权/契约
python3 bin/smoke-control-plane.py           # 10 场景（双引擎）
python3 bin/smoke-tenant-isolation.py        # 租户边界（双引擎）
python3 bin/smoke-admin-auth.py              # 管理凭据边界
python3 bin/smoke-jwt-auth.py                # JWT 声明/audience/expiry（双引擎）
python3 bin/smoke-heartbeat-deadline.py      # 心跳与首字节/空闲超时（双引擎）
python3 bin/smoke-sse-line-limit.py          # SSE 单行上限与恢复（双引擎）
python3 bin/smoke-billing.py                 # 计费 24 项（双引擎）
python3 bin/smoke-billing-recovery.py        # 备份/恢复演练 8 项
python3 bin/smoke-gemini.py                  # Gemini 18 项
python3 bin/smoke-conversion-sdk.py --out DIR
python3 bin/smoke-sdk-compatibility.py --out DIR   # 官方 SDK（需 bin/requirements-sdk.txt）

# 变更监听、可观测、Redis 双副本
./bin/smoke-hot-reload.sh webflux           # 10 项：40 路在途 SSE 零中断 + 权重即时生效
./bin/smoke-hot-reload.sh vt
./bin/smoke-observability.sh                # 30 项：TTFT / 上游 / 开销头 + 指标 + 审计
./bin/smoke-redis-cluster.sh                # 7 项：双实例共享配额（需 -Predis 产物 + 本地 Redis）
python3 bin/smoke-redis-recovery.py --jar <redis-jar>   # 5 项：重启/故障/跨副本恢复
```

> 宿主若已有服务占用 9090（内嵌 mock 默认端口），shell 冒烟统一加 `MOCK_PORT=9093`；脚本自管控制面数据库，不读取项目根 `data/`。

mock 上游支持受控故障注入（对**全部流式协议**生效，images / videos 用 `mock-status-N`；对全部压测 payload 均不生效，压测口径不受影响）：

| 标记 | 上游行为 | 覆盖的边界 |
|---|---|---|
| `mock-fail-after-N` | 发 N 个 chunk 后抛 `IOException`，连接异常中断 | 抛异常的错误传播、首 chunk 后带内报错、禁止 fallback 拼接 |
| `mock-eof-after-0` | 一帧不发就干净关闭 | 空流截断，首 chunk 前必须 502 |
| `mock-eof-after-N` | 发 N 个 chunk 后干净关闭，不抛异常、不发终止帧 | **静默截断**：EOF 不能被当成成功 |
| `mock-status-N` | 上游返回状态 N + JSON 错误体（流式/非流式均可） | 非 2xx 不能变成 SSE `data:` 行，必须网关 502 |
| `mock-done-then-fail` | 发完协议终止帧后再抛 `IOException` 断连 | 协议已完成就不能补 `event: error`、不能记失败 |
| `mock-echo-hash` | 非流式回答回显完整请求体的哈希前缀 | 缓存键若只看 prompt，同文本不同采样参数会互串答案 |

## 四、Native 编译

> **GC 说明（实测）**：native-image 的 G1 GC 仅支持 Linux AMD64/AArch64，macOS 上 `--gc=G1` 直接报错
> 「only supported on Linux AMD64 and AArch64」，本机（macOS）构建默认使用 Serial GC。不能仅凭 GC 类型不同归因 SSE 延迟；当前 Linux 数据见[稳态补测](docs/verification/native-sse-steady/2026-11-12/README.md)。
> `NATIVE_GC=g1 ./bin/build-native.sh webflux` 保留给 Linux 构建（profile `native-g1`）。
> **坑**：① `--gc=G1` 大小写敏感（小写 `g1` 报 invalid value）；② spring-boot-maven-plugin 与
> native-maven-plugin 的 AOT 自动装配只认名字**恰好叫 `native`** 的 profile，任何其他 profile id
> （如 `native-g1`）必须显式声明 `process-aot`、`add-reachability-metadata` 与 `<classesDirectory>`，否则
> native-image 在 fat jar 上找不到主类/AOT 类。

**关键坑**：Spring AOT 在**构建期**求值 `@Profile`，native 镜像运行时不做组件扫描。
所以一个 native 二进制只能装一个引擎，两个引擎要各编一个：

```bash
./bin/build-native.sh webflux    # -> bin/native/llmgw-webflux
./bin/build-native.sh vt         # -> bin/native/llmgw-vt

bin/native/llmgw-webflux --spring.profiles.active=webflux --server.port=8080
```

`bin/build-native.sh` 会显式检查 `$JAVA_HOME/bin/native-image`（本机它没进 PATH），
并通过 `-Dllmgw.native.profile=...` 把 profile 传给 `spring-boot:process-aot`。
另配 `GatewayRuntimeHints` 注册 DTO 反射与 `application*.yml` 资源。

**第二个 AOT 坑**：Jackson 3 序列化 record 会调 `Class.getRecordComponents()`；
native image 里若该 record 的组件访问器未注册反射，就直接抛
`UnsupportedFeatureError: Record components not available ...` → HTTP 500。
`AuditSink.AuditEvent` 曾让 `/admin/snapshot` 在 native 下 500，而 JVM 完全正常；
`/v1` 转发链路不受影响，所以只在测管理接口时才暴露。现在 `GatewayRuntimeHints`
把 `/admin/*` 响应可达的 record（`UsageSummary`、`AuditEvent`、`ApiKey` 等）全部注册，
回归脚本也加了「native 快照必须 200 + 含 `usage`」的断言。
**教训：native 冒烟必须覆盖管理接口，不能只测数据面。**

本机实测（GraalVM 25 / JDK 25）：首次单个二进制编译约 **8 分 43 秒**、产物 **282.64MB**、
构建峰值 RSS **10.73GB**；v5 机器负载较轻时两次重编各 **6 分 08 秒 / 6 分 42 秒**，
峰值 RSS 10.65–12.16GB（编译时间强依赖机器负载，不要当固定值）。
JVM 冷启动 **3.538–6.946s**、native **0.387–0.560s**（矩阵实测，见 5.1）。
早期单次空载手测曾记录 native 0.082s，机器负载不同、不可直接横比。
代码区 Top1 是 `openai-java-core-4.100.0.jar`（46MB），SDK 是 native 体积的主要来源。


## 五、性能

后续 [过载拒绝成本审查](docs/verification/perf/2026-11-11/overload/README.md)将 WebFlux 准入移到参数解析前，局部拒绝分配减少约 18%；两引擎的容量拒绝新增 `Retry-After: 1`。合作式客户端实验与忽略提示的压力场景分开报告，不将退避收益当成服务器容量提升。

心跳开启场景另完成[背压与超时隔离修复](docs/verification/backpressure/2026-11-11/README.md)：避免提前提交 200、VT 心跳拖住共享超时，以及 WebFlux 慢消费者触发心跳溢出。

上游 SSE 已补齐[单行字节边界](docs/verification/sse/2026-11-11/README.md)：默认 1 MiB，可配置；在解码缓冲前拒绝超长无换行正文，不限制合法长流累计大小。

历史[生命周期与大帧补审](docs/verification/lifecycle/2026-11-11/README.md)修复线性行解码、WebFlux大行出口预取OOM、VT断开误记熔断，以及未完成行的空闲误判；211项三profile与固定JAR契约通过，64慢读/45秒及2C2G容量已复验，当前WebFlux native构建与功能契约也已通过。性能数据与边界见[本轮复验](docs/verification/lifecycle/2026-11-11/capacity-results.md)，不宣称全面提速。

已完成历史 [2C2G 单副本容量与能力审查](docs/verification/capacity/2026-11-11/README.md)，已修复动态 URI 缓存无界、VT 错误体绕过大小限制、流式空闲计时和正文整体超时；这些修复包含在v0.4.0；本批报告仍按原产物归属。

历史冻结性能审查减少 SSE 字符串复制和 WebFlux header 集合创建，局部分配分别下降约 22%–48% 和 32%。64 个 A/B 样本、约 422 万请求零错误。WebFlux SSE 吞吐中位数在治理开/关两批分别 +7.3% / +5.2%，但范围重叠；JSON 一正一负，不能声称全面稳定提速。

[最新性能说明](docs/PERFORMANCE.md)提供完整结果、JFR、失败的线程实验和剩余风险。跨网关对照是旧固定产物的历史批次，不能作为本次发布的最新排名；v0.3.1 的 SPI/Flow 重构没有重新执行网关或 native 性能 A/B。

## 六、风险清单（当前）

| 风险 | 现状 / 预案 |
|---|---|
| **官方 SDK 很重** | `com.openai:openai-java` 4.100.0 SDK 对产物体积有明显影响；旧构建的大小不能代替 v0.3.1 资产测量。SDK 只在校验/连通性路径，轻量认证已独立拆包 |
| **应用单进程SQLite，可选`-Predis`** | Redis提供限流/熔断/缓存与 fail-closed 故障策略；应用Key、token/计费账本、幂等/审批状态与发布路由始终归SQLite；无多网关副本部署承诺（ADR-0006） |
| **配额与余额不是最终用量硬上限** | 准入按估算做含在途的原子预留，成功后按实际用量结算；并发下最终 used 或消费可能略超额度（预留+结算语义，非超卖竞态）。严格预算档位把 token 调用约束在「输入估算 + 显式输出上界」内；金额侧仍不宣称最终欠款封顶 |
| **计费依赖客户售价与上游成本分离** | 缺客户售价即 402；路由回退按客户请求的对外模型计费，平台承担供应商差异。成本异币种未换算不参与利润计算，详见 ADR-0007 |
| **Redis 模式是延迟换一致性** | 每请求 2–4 次 Redis 往返，未做流水线合并；治理不可用时默认 503 + `Retry-After`（fail-closed），按治理需要启用；默认不链接Redis |
| **Native 需按引擎分编** | AOT 构建期求值 `@Profile`，两个引擎各一个二进制 |
| **token 计量在无 usage 帧时是估算** | `estimateTokens` 启发式（len/4 + CJK）；可用 `-Pjtokkit` 接入可选 tokenizer，模型覆盖需验证 |
| **压测口径要一致** | 单机 directional；A/B 结论以同 worktree 基线为准，数据质量红线见 `bench/README.md` |
| **剩余能力差距** | 见 [docs/gap-analysis.md](docs/gap-analysis.md)：OIDC/JWKS/SSO 身份联邦、分布式控制面、MCP 订阅/Tasks 扩展、跨平台 native 资产 |
## 七、构建依赖版本（以 pom.xml 为准）

- Spring Boot `4.1.2`
- `com.openai:openai-java` `4.100.0`
- Caffeine `3.3.1`
- `sqlite-jdbc` `3.53.4.0`；可选 Redis 客户端 Jedis `7.0.0`（仅 `-Predis`）
- `native-maven-plugin` `1.1.18`
- `spring-javaformat-maven-plugin` `0.0.48`（`validate` 阶段强制；`mvn spring-javaformat:apply` 格式化）
- Spring Boot 4 要求 Java 17+，兼容至 Java 25；GraalVM Native Image 要求 GraalVM 25+
- Jackson 3：databind 包名 `tools.jackson.*`，注解仍在 `com.fasterxml.jackson.annotation`

## 八、开源与社区

| 项目 | 说明 |
|---|---|
| 许可证 | [Apache-2.0](LICENSE)，版权与第三方声明见 [NOTICE](NOTICE) |
| 贡献 | [CONTRIBUTING.md](CONTRIBUTING.md)：环境、SPI 约定、双引擎对称与回归清单 |
| 行为准则 | [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) |
| 安全 | [SECURITY.md](SECURITY.md)：漏洞请走 GitHub Security Advisory 私下报告，**不要公开提 issue** |
| 更新日志 | [CHANGELOG.md](CHANGELOG.md) |

**提 issue 的规矩**：`.github/ISSUE_TEMPLATE` 提供缺陷报告、功能建议、性能数据三种表单，
不允许空白 issue。性能类必须附 15s 粒度宿主负载时间线、逐轮原始 CSV 与逐轮一致性分析 ——
本项目历史上多次因宿主过载（`vm.loadavg` 80–110）得到互相矛盾的数字，
这类样本只留档、不作结论（见 `bench/README.md`）。

**提交 PR**：模板要求逐条确认流式契约（`[DONE]` 唯一完成判据、不伪造终止帧、
首 chunk 前后两套错误语义、`onCancel` 取消结算）与双引擎行为一致，
并贴出相关回归脚本的 PASS/FAIL；`mvn spring-javaformat:apply` 后再提交。

**CI**：JDK 25 下执行默认、`-Predis` 和 `-Predis,jtokkit,otel` 的 `mvn clean verify`（含 spring-javaformat `validate` 阶段）、模块布局、原生边界、OpenAPI 严格 YAML/操作结构校验，以及同一 jar 的双引擎流式错误契约。tag 发布构建默认/Redis JAR、Linux amd64 WebFlux native、文档和 SHA256；全部构建及 native 流契约通过后统一发布。


## 九、文档索引

| 文档 | 内容 |
|---|---|
| [docs/EXTENSIONS.md](docs/EXTENSIONS.md) | 原生 SPI、多模块、配置与内部 API 迁移 |
| [docs/control-plane-operations.md](docs/control-plane-operations.md) | 控制面部署、权限矩阵、升级与固定 JAR 验证 |
| [docs/PERFORMANCE.md](docs/PERFORMANCE.md) | 历史性能结果、当前容量审查及证据归档 |
| [docs/gap-analysis.md](docs/gap-analysis.md) | 当前能力边界、优先级与历史统一容器对照 |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | 部署拓扑 / TLS 立场 / 单机范围 |
| [docs/USER_GUIDE.md](docs/USER_GUIDE.md) / [docs/CONFIGURATION.md](docs/CONFIGURATION.md) | 接口与配置全典（含计费、幂等、审批、MCP） |
| [docs/adr/](docs/adr/) | 架构决策：单机交付、租户预付费计费、严格预算、Gemini、MCP、审批、幂等 |
| [docs/releases/v0.5.0.md](docs/releases/v0.5.0.md) | 当前发布说明、升级步骤与兼容变化 |
| [docs/reviews/2026-11-20-chapter9-governance-review/README.md](docs/reviews/2026-11-20-chapter9-governance-review/README.md) | 第 9 章治理轮的验证产物与未覆盖范围 |
| [CHANGELOG.md](CHANGELOG.md) | 版本变更史 |
| [docs/archive/](docs/archive/) | 历史归档：逐版本压测演进、第一轮差距分析、执行看板 |

### 持久控制面

浏览器访问 `/control/`。首次启动须显式设置管理员环境凭据；建立租户/项目、设置项目累计 token 额度后再发放项目 Key。网页支持项目选择、分级账号创建、Key 轮换/吊销、额度调整、计费账户与售价、待对账核销、审批、录制、用量/目录/审计和平台全局路由发布/回滚。会话通过 `/control/session` 恢复 CSRF；无默认账号。部署、升级、权限矩阵和固定 JAR 验证见 [控制面操作说明](docs/control-plane-operations.md)，接口见 [OpenAPI](docs/openapi.yaml)。
