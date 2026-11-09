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

## 核心能力（对照主流网关差距分析补齐，详见 [docs/gap-analysis.md](docs/gap-analysis.md)）

- **转发治理**：同路由重试+指数退避、三段式超时（connect/首字节/空闲读）、双向 header
  白名单透传、SSE 心跳、加权负载均衡 + failover + 窗口熔断、路由表与治理参数双热更新
- **协议面（8 协议字节透传）**：chat / responses / messages / images / videos /
  embeddings / rerank / audio-speech——协议是**路由维度而非转换维度**
- **密钥与预算**：API key 运行时管理（创建/吊销/TTL/metadata，**SHA-256 哈希存储**，
  重启恢复）、**key 轮换**（overlap 窗口）、**HS256 JWT 入口认证**（`TokenValidator` SPI，
  可换 RS256/OIDC）、token 配额（在途预留+退款）、**$ 硬预算**（模型价目 → 微美元记账
  → 到额即拒）、**per-model 限流**与 **per-group 硬预算**、按组花费聚合
- **provider 适配**：`UpstreamAuthenticator` SPI——OpenAI 兼容 Bearer 与 Anthropic
  原生（x-api-key + anthropic-version）方言随路由选择
- **可观测**：Micrometer/Prometheus 指标、TTFT/上游耗时/网关开销、审计环、
  请求日志（`/admin/requests`）、trace 上下文传播（`-Potel` 全量 span）、管理控制台
- **内容与缓存**：Guardrail SPI（本地正则 + 外接审核引擎，均带决策/向量缓存）、
  **语义缓存**（Embedder SPI + 分桶键 + **余弦相似度向量检索**，实测同义改写命中）、
  精确缓存、`DELETE /admin/cache` 失效 + `X-LLMGW-No-Cache` 旁路
- **多副本**：`-Predis`（Lua 原子限流/配额/熔断 + 跨副本 key 表与响应缓存）
- **转发韧性**：同路由重试 + 指数退避、**上游 `Retry-After` 感知**、context-overflow
  识别（跳过同路由重试直达 fallback）、三段式超时、加权 LB + failover + 熔断
- **管理面**：审计导出（`/admin/audit/export`）、**只读 admin token**、请求日志、
  指标、路由与治理参数双热更新、控制台
- **工程化**：6 模块 45 个 JUnit + 10 个 shell 契约套件（378 断言）+ Docker/compose
  + TLS 前置部署文档（[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)）+ 可选 tokenizer
  （`-Pjtokkit`）与 OTel（`-Potel`）

## 定位与不做清单

llmgw4j 是**模型数据面网关**：字节保真透传 + LLM 语义治理。以下能力**有意不做**，
属前置代理（Envoy/Nginx）或上层平台（LiteLLM/Higress 等）的职责：

- WebSocket / gRPC 入口、HTTP/2 上游（南北向流量治理交给前置代理）
- Prompt 模板管理、SSE 流内容改写（与字节透传契约冲突）
- MCP / A2A Agent 协议、内置 PII 改写（PII 走外接 Guardrail 引擎）
- DB-first 持久化（默认零依赖 JSON/内存，SQLite/Redis 为可选 SPI 实现）

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
| `llmgw-core` | 纯 SPI + 协议薄 DTO、Jackson 3 编解码、路由、请求管线。**不依赖任何具体实现组件**（外部依赖仅 Spring 配置注解、Jackson、Reactor 类型） |
| `llmgw-governance` | **治理子系统**：聚合 POM 兼子模块父 POM（`packaging=pom`，本身不产出 jar），下挂 1 个 API 模块 + 2 个实现包 |
| └ `llmgw-governance-api` | 实现包公共契约（框架中立）：`GovernanceLimits`（配置归一化）、`QuotaMath`（配额预留/结算）、`UsageSummaries`、`GovernancePacks`（自动装配与 key 前缀常量）。**只依赖 core**，不引入 Caffeine / Micrometer / Jackson / Redis |
| └ `llmgw-governance-default` | 默认全内存实现包（自动装配）：Caffeine 缓存、原子 token 桶、用量账本、JSON 快照、虚拟线程旁路、Micrometer。所有 bean 带 `@ConditionalOnMissingBean`，**声明自己的实现即自动替换默认实现** |
| └ `llmgw-governance-redis` | **可选**（`-Predis` 才链入）：Redis 版 `TokenLimiter` / `UsageLedger` / `CircuitBreaker`，全部用 Lua 脚本保证跨副本原子性；默认 fat jar 不含该模块与任何 Redis 依赖 |
| `llmgw-engine` | **数据面子系统**：聚合 POM 兼子模块父 POM（`packaging=pom`，本身不产出 jar），下挂 1 个 API 模块 + 2 个实现模块 |
| └ `llmgw-engine-api` | 数据面公共契约（框架中立）：`EngineKind`、`AbstractEngineConfiguration`、`EngineHeaders`、`EngineReasons`、`EngineRoutes`、`SseChunkAccounting`、`EngineTimings`、`EngineProfiles`。**只依赖 core**，不引入 WebFlux / Servlet |
| └ `llmgw-engine-webflux` | 反应式数据面（`ServerHttpResponse.writeWith` 裸写 SSE）。依赖 `engine-api` + core + webflux starter |
| └ `llmgw-engine-vt` | 虚拟线程数据面（Servlet 流 + JDK HttpClient 阻塞读）。依赖 `engine-api` + core + web starter |
| `llmgw-provider-openai` | `openai-java` 4.100.0 适配：请求校验 + 可选真实上游连通性验证，**不进转发链** |
| `llmgw-app` | 组合根：启动类、profile 配置、mock 上游、管理 API、native hints |

两个能力域的依赖方向均由 Maven 强制为**单向无环**：

```
llmgw-core   领域核心：SPI、协议 DTO、路由、请求管线（engine / governance / provider / app 共用）
    ├──> llmgw-engine-api ───────> llmgw-engine-webflux
    │                       └────> llmgw-engine-vt
    └──> llmgw-governance-api ───> llmgw-governance-default
                            └────> llmgw-governance-redis（仅 -Predis 链入）

llmgw-app   组合根：同一 fat jar 装两个数据面实现（profile 二选一）+ 一个治理实现包
```

同域实现之间**互不依赖**，跨域也**不互相引用**（数据面只看 `llmgw-core` 的 SPI，
治理实现包不出现在数据面的 POM 里）。

**边界判断**：`GatewayPipeline` / `UpstreamProvider` / `ChunkSink` 以及
`GovernanceChain` / `TokenLimiter` / `UsageLedger` 等 SPI 都留在 `llmgw-core`，
因为它们被 engine、governance、provider、app 共同依赖；搬进任一子系统都会让
下层模块反向依赖上层，形成环。两个 `-api` 承载的是「**只有本域实现需要、
且多个实现逐字重复**」的部分，不是 SPI 定义本身。

### 模块布局规范（强制）

凡有多实现的能力域，一律 `聚合层 + 一个 -api + 每个实现一个 module`：
聚合 POM 为 `packaging=pom` 且兼作子模块 parent；`api` 只依赖 `llmgw-core`；
实现模块依赖本域 `api`、彼此不依赖、Java 包必须是 api 包的子包（防 split package）；
根 `pom.xml` 只列聚合目录。领域 SPI 留在 `llmgw-core` 不动。

`./bin/check-module-layout.sh` 会**自动发现**所有能力域，并在 CI 每次 PR 校验这些
规则（新增域无需改脚本）。新增实现 / 新能力域的完整步骤见 `CONTRIBUTING.md` 第 2 节。
当前两个域为 `llmgw-engine/` 与 `llmgw-governance/`；`llmgw-provider-openai`
尚只有一个验证器，不构成多实现域，出现第二个 provider 时按同一模板拆分。

### SPI 扩展点

核心逻辑全部是 SPI 接口，core 不 import 任何实现类；引擎与实现之间只通过下面的接口对话。
默认实现由 `GovernanceDefaultsConfiguration`（Spring Boot 自动装配）提供，每个 bean 都有
`@ConditionalOnMissingBean(SPI类型)` 守卫——**在自己的配置里声明一个实现 bean，默认实现自动退位**：

| SPI 接口（llmgw-core） | 职责 | 默认实现（llmgw-governance-default） | 换成自己的典型场景 |
|---|---|---|---|
| `GovernanceChain` | 准入管线（鉴权→配额→限流→缓存） | `DefaultGovernanceChain` | 分布式准入（Redis/Sidecar 决策） |
| `ApiKeyRegistry` | API Key 鉴权与元数据 | `InMemoryApiKeyRegistry` | 数据库/OIDC 签发的密钥 |
| `TokenLimiter` | 按Key的 token 速率限制 | `AtomicTokenLimiter` | Redis 令牌桶 / 多实例共享限流 |
| `UsageLedger` | 用量账本 + 配额预留 | `InMemoryUsageLedger` | 计费系统落库 |
| `SemanticCache` | 语义/精确缓存 | `CaffeineSemanticCache` | Redis、向量检索语义缓存 |
| `AuditSink` | 审计事件 | `InMemoryAuditSink` | Kafka / ELK 审计管道 |
| `SnapshotStore` | 用量快照持久化 | `JsonSnapshotStore` | JDBC / 对象存储 |
| `Router` | 模型路由 + fallback 链 | `ConfigRouter` | 动态路由 / 权重中心化配置 |
| `UpstreamProvider` | 上游转发（流式+非流式） | `JdkHttpUpstream`（app） | gRPC 上游、多协议适配 |
| `CircuitBreaker` | 上游健康门（开路/半开探测） | `WindowedCircuitBreaker` | 共享 Redis 熔断状态、更细粒度策略 |
| `RouteConfigSource` | 路由表不可变快照 + 原子替换 | `InMemoryRouteConfigSource` | 配置中心监听、GitOps 拉取、DB 路由 |

替换示例（一个类 + 一个注解，无需改网关任何代码）：

```java
@Component
public class RedisTokenLimiter implements TokenLimiter {
    // ...
}
```

启动日志与压测口径不变；`AtomicTokenLimiter` 自动退位，其余默认实现继续装配。
依赖方向由 Maven 强制：`llmgw-engine/*` 与 `llmgw-provider-*` 的 POM **不依赖任何治理实现包**；
两个数据面实现之间互不依赖，只共同依赖 `llmgw-engine-api`；两个治理实现包之间也互不依赖，
只共同依赖 `llmgw-governance-api`。
想换掉整套治理，把 app 里的 `llmgw-governance-default` 依赖替换成你自己的实现模块即可。

**监控**：治理链路通过 `GovernanceMetrics` 接 Micrometer（`ObjectProvider<MeterRegistry>`，无 Registry 时静默降级），
计数器/计时器带 `key`、`model`、`engine` 标签；`/admin/snapshot`、`/admin/keys` 提供业务侧视图，
`/actuator/*` 提供标准运维视图。

## 二、关键设计判断（面试可讲）

1. **为什么同时要 WebFlux 和虚拟线程**：不是叠加，是做单变量对照实验。两个引擎共用同一治理层和同一个上游客户端，唯一变量就是并发模型。两条数据面都是各自并发模型的「正统实现」：WebFlux 流式 + 非流式全走 `sendAsync`，请求路径没有任何阻塞 offload；VT 两条路径都直接阻塞自己的虚拟线程。
2. **为什么热点链路不让 SDK 碰**：SDK 会反序列化再序列化，厂商扩展字段（`reasoning_content` 等）会丢。网关的价值是协议保真，所以网关按协议各解析少量字段（model / stream / prompt 类文本）用于路由和计量，原始 chunk 与响应体原样透传，网关不做任何协议翻译。
3. **为什么治理分热路径/旁路**：鉴权、配额、限流是纯内存原子操作，留在热路径；SSE 计量、账单、审计、快照交给公共 `VirtualThreadPerTaskExecutor`，避免阻塞事件循环，也让两个引擎的对照公平。
4. **为什么不用 Spring AI / LangChain4j**：它们是应用框架（`ChatModel` / `AiServices`），目标是帮应用「调模型」；网关要的是「原样转发字节流」。选官方 `openai-java` 只做校验与连通性验证，是折中且克制的选择。
5. **为什么全内存**：零外部依赖、native 编译最干净。持久化通过 `SnapshotStore` SPI + JSON 快照导出，README 明说这是单机演示。
6. **实测印证了「瓶颈不在网关」**：50 VU 下 SSE 场景 p50 的 8 组变异系数只有 2.3%，而 mock 上游固定占 100ms。当上游是秒级推理时，选 WebFlux 还是虚拟线程、JVM 还是 Native，对端到端延迟几乎无影响；真正值得花钱优化的是 Native 的冷启动（10.5×）与尾延迟（见 5.1 / 风险清单）。这个结论比「哪个框架 RPS 更高」更能指导选型。
7. **流式失败要按「首字节是否已出」分层处理**：HTTP 状态在第一个字节写出后才不可逆。网关在首 chunk 前保持响应未提交（VT 不再提前 `flushBuffer()`），上游失败就返回真正的 **502 + JSON**；一旦已发 chunk，只能带内报错 `event: error` 再补 `[DONE]`。**fallback 只允许在首 chunk 前发生**，否则两个上游的 chunk 会拼成一段语义错乱的回答。`bin/smoke-stream-errors.sh` 用 mock 的 `mock-fail-after-N` / `mock-eof-after-N` 标记覆盖这些边界，v5 扩充为 JVM 双引擎 55 项、单个 native 28 项断言。
8. **请求体只解析一次**：`GatewayPipeline.enter` 解析出的 `ParsedRequest` 挂在 `GatewayDecision` 上，两个 controller 直接复用，不再各 parse 一遍同一个 JSON。准入与转发共用一次反序列化结果，是网关热路径最基本的节约。
9. **传输层 EOF ≠ 协议层完成**：上游干净关闭连接时 HTTP 客户端不会抛异常，代理若把「没报错」当成功、甚至替上游补一帧终止帧，就会把被截断的回答伪装成完整回答——客户端不报错、账单不记失败、上游故障被静默吞掉。本项目把「上游是否显式发出**该协议自己的终止帧**」作为唯一完成判据（chat=`data: [DONE]`、Responses=`response.completed`、Messages=`message_stop`；images / videos 无流式），两个引擎共用 `ProtocolCodec.truncatedMessage(kind)`；网关**永远不伪造终止帧**——带内错误后的 `[DONE]` 只对 CHAT 追加，responses / messages 的错误事件之后直接干净断流，绝不替上游补另一种协议的终止帧。这是透明代理与「自作主张的中间件」的分界线。

10. **透明代理要同时守住三条资源契约**：① 上游状态必须在 `BodyHandler.apply(ResponseInfo)` 阶段判定，晚于 body 订阅的 `whenComplete` 检查会被已发出的 `onComplete` 吞掉，4xx/5xx 的 JSON 错误体会被当成 `data:` 行发给客户端；② 下游 demand 要桥接到 JDK `Flow.Subscription`，`request(Long.MAX_VALUE)` + 无界 `Flux.create` 缓冲会让慢客户端把堆吃光；③ 只有**下游取消**才应该取消 `sendAsync` future 和 body 订阅。这里有个实测过的反例：把取消钩子同时挂到 `onDispose` 上，Reactor 在**正常完成**时也会触发它，HTTP/1.1 keep-alive 连接被提前拆掉，同连接上其他在途请求全部报 `Request cancelled`，50 VU 下 SSE 失败率 **16%**；改成只挂 `onCancel` 后归零。取消本身也不是 Reactor 的 terminal 信号，只挂 `doOnComplete`/`doOnError` 会**永久漏掉账单并泄漏配额预留** —— 两个引擎统一改 `doFinally`。回归里专门加了「40 路并发 SSE 零 502」断言，因为串行 curl 永远暴露不了连接复用竞态。
11. **计量以 usage 为准，准入以原子预留为准**：SSE 的 `usage.completion_tokens` 是权威值，必须**覆盖**而非叠加逐 delta 估算（否则 8 个 chunk 记成 16）；非流式要从 `usage` 取真实值，不能拿整个 JSON 信封（id/时间戳/object）估算。配额检查 `used()+estimate>quota` 是读-改-写竞态，并发下会把额度超卖 N 倍，改成含在途请求的 CAS 预留、`finish` 时释放；令牌桶按实际用量结算，失败 / 取消 / 缓存命中退还预扣；refill 的时间戳只在真的铸出整数 token 时推进，否则高频调用会把不足 1 token 的时间片永久丢掉。
12. **缓存键必须是完整请求，不是 prompt**：`model + 文本 prompt` 会让 `temperature` / `max_tokens` / `tools` / 多模态 content 不同但文本相同的两个请求互串答案，并让截断答案污染后续 300 秒。改成完整请求体的 SHA-256（保守精确缓存：宁可 miss 不可错答），且只缓存校验过 `choices` 的合法响应。
13. **管理面按默认最小暴露**：`/admin/routes` 原本直接序列化 `Route` record，里面的 `apiKey` 就是 `${DASHSCOPE_API_KEY}` —— 一个未鉴权 GET 会把上游厂商密钥交出去。现在只回显 `apiKeyConfigured` 布尔值，并支持 `LLMGW_ADMIN_TOKEN` 网关管理接口（本地演示默认空）。快照恢复改替换语义、SDK 客户端用后关闭、请求体有 4MB 上限。

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

# 每个端点只路由到说同一种协议的上游，网关不做协议翻译
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

# 热配置：整表替换（先校验后原子换引用，在途 SSE 不中断）
curl -sS -X POST http://127.0.0.1:8080/admin/routes/reload \
  -H 'Content-Type: application/json' \
  -d '[{"id":"a","model":"*","targetUrl":"http://127.0.0.1:9090/mock/v1","weight":1},
       {"id":"b","model":"*","targetUrl":"http://127.0.0.1:9090/mock/v1","weight":3}]'
# GET /admin/routes 的响应头 X-LLMGW-Config-Version 会随之递增
```

设置管理令牌后，所有 `/admin/*` 都要带 `X-Admin-Token`，否则 401：

```bash
LLMGW_ADMIN_TOKEN=change-me ./bin/run-webflux.sh
curl -sS -H 'X-Admin-Token: change-me' http://127.0.0.1:8080/admin/routes
```

请求体默认上限 4MB（`gateway.max-request-bytes`），超限返回 413。
快照恢复是**手动**的（`POST /admin/snapshot/load`，替换语义、可重复调用）；
不做启动时自动恢复，因为压测每组都单独起进程，一份残留快照会把配额变成不可控变量。

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
java -jar llmgw-app/target/llmgw-app-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=webflux,real
curl -sS http://127.0.0.1:8080/admin/verify   # SDK 连通性验证
```

关闭治理（纯透传对照用）：`--gateway.governance-enabled=false`

接真实上游（阿里百炼 OpenAI 兼容端点，读环境变量 `DASHSCOPE_API_KEY`）：

```bash
java -jar llmgw-app/target/llmgw-app-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=webflux,real
curl -sS http://127.0.0.1:8080/admin/verify   # SDK 连通性验证
```

关闭治理（纯透传对照用）：`--gateway.governance-enabled=false`

**全量回归**（正常路径、401/400/413、上游不可达、非 2xx、fallback、首 chunk 后断流、静默截断、`[DONE]` 后断连、禁止拼接、缓存键、精确计量、管理接口脱敏与鉴权、快照重复恢复、断连结算、40 路并发 SSE、关联 ID、熔断开路、加权路由；JVM 双引擎 67 项断言、单个 native 34 项）：

```bash
# 结构校验：能力域布局（CI 每次 PR 也跑，新增域自动纳入）
./bin/check-module-layout.sh                 # 27 项：依赖方向、实现互不依赖、无 split package

./bin/smoke-stream-errors.sh                                             # JVM，双引擎
BINARY="$PWD/bin/native/llmgw-webflux" ./bin/smoke-stream-errors.sh webflux  # native 产物
BINARY="$PWD/bin/native/llmgw-vt"      ./bin/smoke-stream-errors.sh vt

# 多协议回归（responses / messages / images / videos × 双引擎）
./bin/smoke-multi-protocol.sh                # 28 项 × 双引擎：协议路由 / 计量 / 各协议终止帧契约 / sticky 视频
BINARY="$PWD/bin/native/llmgw-webflux" ./bin/smoke-multi-protocol.sh webflux  # native 产物
BINARY="$PWD/bin/native/llmgw-vt"      ./bin/smoke-multi-protocol.sh vt

# 三个独立回归
./bin/smoke-observability.sh                 # 26 项：TTFT / 上游 / 开销头 + 指标 + 审计
./bin/smoke-hot-reload.sh webflux           # 10 项：40 路在途 SSE 零中断 + 权重即时生效
./bin/smoke-hot-reload.sh vt
./bin/smoke-redis-cluster.sh                # 7 项：双实例共享配额（需 -Predis 产物 + 本地 Redis）
```

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
> 「only supported on Linux AMD64 and AArch64」，本机（macOS）只能用默认 Serial GC——native SSE p99 高于 JVM 的主因之一即在此。
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

50 VU、内嵌 mock 上游、同 `bench/k6-*.js` 脚本：

| 场景 | 吞吐 | p95 |
|---|---|---|
| JSON | 6107/s | 13.8ms |
| SSE | 460/s | 114ms |

跨网关同口径对标（standalone mock：llmgw4j 1949/s vs LiteLLM 90/s，JSON）与
全部优化迭代数字见 **[docs/PERFORMANCE.md](docs/PERFORMANCE.md)**；横向排行与
剩余差距见 [docs/gap-analysis.md](docs/gap-analysis.md)。

## 六、风险清单（当前）

| 风险 | 现状 / 预案 |
|---|---|
| **官方 SDK 很重** | `com.openai:openai-java` 4.100.0 core jar 约 **71MB**，fat jar 因此 127MB、native 代码区 46MB。它只在校验/连通性路径，可按需摘除 |
| **默认全内存，多副本用 `-Predis`** | `-Predis` 覆盖 TokenLimiter/UsageLedger/CircuitBreaker/**ApiKeyRegistry/SemanticCache/TaskRouteSticky**；仍是本地实现的 SPI：`BudgetLedger` / `RequestLogSink` / `SnapshotStore`（生产替换为 JDBC/Redis） |
| **配额不是最终用量硬上限** | 准入按估算做含在途的原子预留，成功后按实际用量结算；并发下最终 used 可能略超 quota（预留+结算语义，非超卖竞态）。需要硬上限改「按估算上限结算」 |
| **Redis 模式是延迟换一致性** | 每请求 2–4 次 Redis 往返，未做流水线合并；只需跨副本一致时启用，默认全内存零影响 |
| **Native 需按引擎分编** | AOT 构建期求值 `@Profile`，两个引擎各一个二进制 |
| **token 计量在无 usage 帧时是估算** | `estimateTokens` 启发式（len/4 + CJK）；接真 tokenizer 在 P2 清单（docs/gap-analysis.md B6） |
| **压测口径要一致** | 单机 directional；A/B 结论以同 worktree 基线为准，数据质量红线见 `bench/README.md` |
| **剩余能力差距** | 见 [docs/gap-analysis.md](docs/gap-analysis.md) 第二节 20 项清单与处理方向（入口认证深度/真向量缓存/流式治理/per-model 限流等） |
## 七、版本事实（Maven Central 实查）

- Spring Boot `4.1.2`（GA）；`4.2.0-RC2` 为候选版，未采用
- `com.openai:openai-java` `4.100.0`
- Caffeine `3.3.1`
- `native-maven-plugin` `1.1.18`
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
这类样本只留档、不作结论（见 5.10 与 `bench/README.md`）。

**提交 PR**：模板要求逐条确认流式契约（`[DONE]` 唯一完成判据、不伪造终止帧、
首 chunk 前后两套错误语义、`onCancel` 取消结算）与双引擎行为一致，
并贴出四个回归脚本的 PASS/FAIL。

**CI**：`.github/workflows/ci.yml` 用 JDK 25 跑默认 fat jar 构建、两个引擎的启动健康检查，
以及 `-Predis` 可选模块编译。完整 67/34 契约回归依赖本机辅助命令与较长耗时，
native 编译又需按引擎分编，两者按 `CONTRIBUTING.md` 在本地执行，暂不进 CI。


## 九、文档索引

| 文档 | 内容 |
|---|---|
| [docs/PERFORMANCE.md](docs/PERFORMANCE.md) | 全程性能优化记录（含折线图，每次迭代的具体数字） |
| [docs/gap-analysis.md](docs/gap-analysis.md) | 最新差距反向验证 + 处理方向 + 跨网关性能排行 |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | 部署拓扑 / TLS 立场 / 多副本 |
| [docs/USER_GUIDE.md](docs/USER_GUIDE.md) / [docs/CONFIGURATION.md](docs/CONFIGURATION.md) | 接口与配置全典 |
| [CHANGELOG.md](CHANGELOG.md) | 版本变更史 |
| [docs/archive/](docs/archive/) | 历史归档：逐版本压测演进、第一轮差距分析、执行看板 |