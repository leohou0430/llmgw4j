# llmgw4j — Java 大模型 API 网关

[![CI](https://github.com/leohou0430/llmgw4j/actions/workflows/ci.yml/badge.svg)](https://github.com/leohou0430/llmgw4j/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-25-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.2-brightgreen.svg)](https://spring.io/projects/spring-boot)

[English README](README.en.md) · [贡献指南](CONTRIBUTING.md) · [安全策略](SECURITY.md) · [行为准则](CODE_OF_CONDUCT.md) · [更新日志](CHANGELOG.md)

Spring Boot 4.1.2 + JDK 25 + GraalVM 25 实现的大模型 API 网关。
核心特色：**同一套治理层，两套可切换的数据面（WebFlux / 虚拟线程），配可复现的对照压测**。
Java 包名 `io.github.leohou0430.llmgw`，采用 [Apache-2.0](LICENSE) 许可，
仓库地址 `https://github.com/leohou0430/llmgw4j`。
命名取 `llmgw`（LLM Gateway）+ `4j`（for Java，沿用 log4j / dom4j 的命名习惯）。

> 这是给 Java 团队看的网关：不引入 Python/Go 组件，不改现有微服务治理习惯。
> 它是**可复现的参考实现与测量台**，不是 Kong / Higress / Envoy 的即插即用替代品；
> 部署前的安全边界见 [SECURITY.md](SECURITY.md)。

## 一、架构

```
                    ┌──────────────────────────────────────────────┐
  POST /v1/chat/    │  llmgw-app                                  │
  completions  ───► │  ┌────────────┐  或  ┌────────────────────┐  │
                    │  │ WebFlux    │      │ Servlet + 虚拟线程 │  │
                    │  │ Netty      │      │ Tomcat 11          │  │
                    │  └─────┬──────┘      └─────────┬──────────┘  │
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

1. **为什么同时要 WebFlux 和虚拟线程**：不是叠加，是做单变量对照实验。两个引擎共用同一治理层和同一个上游客户端，唯一变量就是并发模型。v2 起两条数据面都是各自并发模型的「正统实现」：WebFlux 流式 + 非流式全走 `sendAsync`，请求路径没有任何阻塞 offload；VT 两条路径都直接阻塞自己的虚拟线程。v1 曾把非流式 `send` 塞给 `boundedElastic`，那是个隐藏变量，其数据保留为对照基线（5.1 vs 5.2）。
2. **为什么热点链路不让 SDK 碰**：SDK 会反序列化再序列化，厂商扩展字段（`reasoning_content` 等）会丢。网关的价值是协议保真，所以只解析 `model`/`messages`/`stream` 三个字段用于路由和计量，原始 chunk 原样透传。
3. **为什么治理分热路径/旁路**：鉴权、配额、限流是纯内存原子操作，留在热路径；SSE 计量、账单、审计、快照交给公共 `VirtualThreadPerTaskExecutor`，避免阻塞事件循环，也让两个引擎的对照公平。
4. **为什么不用 Spring AI / LangChain4j**：它们是应用框架（`ChatModel` / `AiServices`），目标是帮应用「调模型」；网关要的是「原样转发字节流」。选官方 `openai-java` 只做校验与连通性验证，是折中且克制的选择。
5. **为什么全内存**：零外部依赖、native 编译最干净。持久化通过 `SnapshotStore` SPI + JSON 快照导出，README 明说这是单机演示。
6. **实测印证了「瓶颈不在网关」**：50 VU 下 SSE 场景 p50 的 8 组变异系数只有 2.3%，而 mock 上游固定占 100ms。当上游是秒级推理时，选 WebFlux 还是虚拟线程、JVM 还是 Native，对端到端延迟几乎无影响；真正值得花钱优化的是 Native 的冷启动（10.5×）与尾延迟（见 5.1 / 风险清单）。这个结论比「哪个框架 RPS 更高」更能指导选型。
7. **流式失败要按「首字节是否已出」分层处理**：HTTP 状态在第一个字节写出后才不可逆。网关在首 chunk 前保持响应未提交（VT 不再提前 `flushBuffer()`），上游失败就返回真正的 **502 + JSON**；一旦已发 chunk，只能带内报错 `event: error` 再补 `[DONE]`。**fallback 只允许在首 chunk 前发生**，否则两个上游的 chunk 会拼成一段语义错乱的回答。`bin/smoke-stream-errors.sh` 用 mock 的 `mock-fail-after-N` / `mock-eof-after-N` 标记覆盖这些边界，v5 扩充为 JVM 双引擎 55 项、单个 native 28 项断言。
8. **请求体只解析一次**：`GatewayPipeline.enter` 解析出的 `ParsedRequest` 挂在 `GatewayDecision` 上，两个 controller 直接复用，不再各 parse 一遍同一个 JSON。准入与转发共用一次反序列化结果，是网关热路径最基本的节约。
9. **传输层 EOF ≠ 协议层完成**：上游干净关闭连接时 HTTP 客户端不会抛异常，代理若把「没报错」当成功、甚至替上游补一帧 `[DONE]`，就会把被截断的回答伪装成完整回答——客户端不报错、账单不记失败、上游故障被静默吞掉。本项目把「上游是否显式发出 `data: [DONE]`」作为唯一完成判据，两个引擎共用 `ProtocolCodec.TRUNCATED_MESSAGE`；网关**永远不伪造终止帧**，只在已提交响应后补一个带内错误帧。这是透明代理与「自作主张的中间件」的分界线。

10. **透明代理要同时守住三条资源契约**（v5）：① 上游状态必须在 `BodyHandler.apply(ResponseInfo)` 阶段判定，晚于 body 订阅的 `whenComplete` 检查会被已发出的 `onComplete` 吞掉，4xx/5xx 的 JSON 错误体会被当成 `data:` 行发给客户端；② 下游 demand 要桥接到 JDK `Flow.Subscription`，`request(Long.MAX_VALUE)` + 无界 `Flux.create` 缓冲会让慢客户端把堆吃光；③ 只有**下游取消**才应该取消 `sendAsync` future 和 body 订阅。这里有个实测过的反例：把取消钩子同时挂到 `onDispose` 上，Reactor 在**正常完成**时也会触发它，HTTP/1.1 keep-alive 连接被提前拆掉，同连接上其他在途请求全部报 `Request cancelled`，50 VU 下 SSE 失败率 **16%**；改成只挂 `onCancel` 后归零。取消本身也不是 Reactor 的 terminal 信号，只挂 `doOnComplete`/`doOnError` 会**永久漏掉账单并泄漏配额预留** —— 两个引擎统一改 `doFinally`。回归里专门加了「40 路并发 SSE 零 502」断言，因为串行 curl 永远暴露不了连接复用竞态。
11. **计量以 usage 为准，准入以原子预留为准**（v5）：SSE 的 `usage.completion_tokens` 是权威值，必须**覆盖**而非叠加逐 delta 估算（否则 8 个 chunk 记成 16）；非流式要从 `usage` 取真实值，不能拿整个 JSON 信封（id/时间戳/object）估算。配额检查 `used()+estimate>quota` 是读-改-写竞态，并发下会把额度超卖 N 倍，改成含在途请求的 CAS 预留、`finish` 时释放；令牌桶按实际用量结算，失败 / 取消 / 缓存命中退还预扣；refill 的时间戳只在真的铸出整数 token 时推进，否则高频调用会把不足 1 token 的时间片永久丢掉。
12. **缓存键必须是完整请求，不是 prompt**（v5）：`model + 文本 prompt` 会让 `temperature` / `max_tokens` / `tools` / 多模态 content 不同但文本相同的两个请求互串答案，并让截断答案污染后续 300 秒。改成完整请求体的 SHA-256（保守精确缓存：宁可 miss 不可错答），且只缓存校验过 `choices` 的合法响应。
13. **管理面按默认最小暴露**（v5）：`/admin/routes` 原本直接序列化 `Route` record，里面的 `apiKey` 就是 `${DASHSCOPE_API_KEY}` —— 一个未鉴权 GET 会把上游厂商密钥交出去。现在只回显 `apiKeyConfigured` 布尔值，并支持 `LLMGW_ADMIN_TOKEN` 网关管理接口（本地演示默认空）。快照恢复改替换语义、SDK 客户端用后关闭、请求体有 4MB 上限。

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

# 管理接口（默认本地演示、无鉴权；共享环境务必设置 LLMGW_ADMIN_TOKEN）
curl -sS http://127.0.0.1:8080/admin/snapshot
curl -sS http://127.0.0.1:8080/admin/routes   # 只回显 apiKeyConfigured，不回显密钥
curl -sS http://127.0.0.1:8080/admin/keys
curl -X POST http://127.0.0.1:8080/admin/snapshot/save

# v10 热配置：整表替换（先校验后原子换引用，在途 SSE 不中断）
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

# v10 三个独立回归（不改动上面 67/34 的断言数）
./bin/smoke-observability.sh                 # 26 项：TTFT / 上游 / 开销头 + 指标 + 审计
./bin/smoke-hot-reload.sh webflux           # 10 项：40 路在途 SSE 零中断 + 权重即时生效
./bin/smoke-hot-reload.sh vt
./bin/smoke-redis-cluster.sh                # 7 项：双实例共享配额（需 -Predis 产物 + 本地 Redis）
```

mock 上游支持两类受控故障注入（对全部压测 payload 均不生效，压测口径不受影响）：

| 标记 | 上游行为 | 覆盖的边界 |
|---|---|---|
| `mock-fail-after-N` | 发 N 个 chunk 后抛 `IOException`，连接异常中断 | 抛异常的错误传播、首 chunk 后带内报错、禁止 fallback 拼接 |
| `mock-eof-after-0` | 一帧不发就干净关闭 | 空流截断，首 chunk 前必须 502 |
| `mock-eof-after-N` | 发 N 个 chunk 后干净关闭，不抛异常、不发 `[DONE]` | **静默截断**：EOF 不能被当成成功 |
| `mock-status-N` | 上游返回状态 N + JSON 错误体（流式/非流式均可） | 非 2xx 不能变成 SSE `data:` 行，必须网关 502 |
| `mock-done-then-fail` | 发完 `[DONE]` 后再抛 `IOException` 断连 | 协议已完成就不能补 `event: error`、不能记失败 |
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

**第二个 AOT 坑（v4 实测）**：Jackson 3 序列化 record 会调 `Class.getRecordComponents()`；
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

## 五、对照压测

```bash
./bin/bench-matrix.sh                    # 完整 8 组矩阵，默认每轮 30s × 3 轮
DURATION=10s ROUNDS=1 ./bin/bench-matrix.sh   # 快速冒烟
```

矩阵：`2 引擎 × 治理开/关 × JVM/Native = 8` 组合，每组跑 `json` 与 `sse` 两种场景，
预热 1 轮 + 正式 3 轮取中位数，全程打可控延迟的 mock 上游（20ms + 8×10ms）、每组单独起进程。
native 组分别使用 `bin/native/llmgw-webflux` 与 `bin/native/llmgw-vt`；
未编译的组会在 CSV 里标 `SKIPPED`，绝不拿 JVM 数据冒充。
结果写入 `bench/results/summary-*.csv`，k6 原始 JSON 导出与日志同目录保留。
v5 起脚本显式传入 `MOCK_DELAY_MS` / `MOCK_CHUNK_INTERVAL_MS`（默认 20/10，与 v1–v4 一致），
并把值写进 CSV 末尾两列；两者都设为 0 即独立的 **wire 实验**（上游零延迟，用来测网关自身开销）。
**不同上游剖面的 CSV 不可逐行相减**，详见 `bench/README.md`。

**准入阈值必须覆盖**：`bin/bench-matrix.sh` 对每组统一传入极大的 quota / rate-limit，
让治理判定在整个矩阵期间永不拒绝。默认的小配额会在几秒内打满，把 `governance=on`
变成「测 429 拒绝延迟」（p50 0.25ms、RPS 虚高 2.4 万、`error_rate=1`）。
`summary-20261106-072224.csv`、`summary-20261106-075524.csv` 均为修复前的无效数据。
详见 `bench/README.md`。

### 5.1 v1 基线结果（非流式仍用 boundedElastic offload）

`bench/results/summary-20261106-081741.csv`：50 VU、每轮 30s、3 轮中位数、8 组全跑完、
**16 条场景 error_rate 全为 0**、SSE `[DONE]` 检查全通过。单位 ms / rps / s。

| 引擎 | 治理 | 运行时 | json p50 | json p99 | json rps | sse p50 | sse p99 | sse rps | 冷启动 |
|---|---|---|---|---|---|---|---|---|---|
| webflux | on | jvm | 5.77 | 21.28 | 7224 | 106.48 | 128.06 | 463 | 4.190 |
| webflux | on | native | 8.77 | **117.70** | 3538 | 108.70 | **308.33** | 427 | 0.560 |
| webflux | off | jvm | 7.82 | 34.52 | 5198 | 106.31 | 129.11 | 462 | 6.946 |
| webflux | off | native | 4.96 | 36.36 | 7336 | 112.78 | 135.63 | 434 | 0.387 |
| vt | on | jvm | 5.60 | 15.67 | 7952 | 107.81 | 144.97 | 458 | 3.538 |
| vt | on | native | 4.99 | 33.70 | 6245 | 111.41 | 157.31 | 436 | 0.455 |
| vt | off | jvm | 9.03 | 38.13 | 4557 | 106.64 | 138.10 | 461 | 4.795 |
| vt | off | native | 5.35 | 42.21 | 6289 | 111.76 | 150.84 | 436 | 0.447 |

**可以下的结论**

1. **冷启动是唯一稳定且显著的差异**：JVM 3.538–6.946s（均值 4.867s），
   Native 0.387–0.560s（均值 0.462s），约 **10.5×**。Native 的价值是弹性扩缩容 / Serverless，
   代价是 282MB 二进制、单引擎约 8m43s 编译、且必须按引擎分编。
2. **SSE 场景（LLM 网关主流负载）四种组合几乎无差别**：p50 106.31–112.78ms，
   变异系数仅 **2.3%**；RPS 427–463，CV **3.1%**；WebFlux vs VT 的 p50 差异在 −0.9%～+2.5%。
   100ms 由 mock 上游固定延迟构成，网关自身开销被完全淹没 ——
   这正是「网关层语言/框架微秒级差异不重要」的实证。
3. **治理层的开销低于本次测量噪声地板**：SSE 的 on/off 差异在 ±3.6% 内；
   JSON 的 on/off 配对差异方向从 −37.9% 到 +76.6% 反转，说明是噪声而非治理成本。
   准确表述是「当前负载下不可分辨」，**不是**「零开销」或「开治理更快」。

**不能下的结论 / 待查项**

4. **JSON 场景不足以排引擎座次**：p50 CV 24.5%、p99 CV 69.8%、RPS CV 23.4%。
   非流式没有上游延迟做锚，单机调度、JIT 预热、GC 抖动主导了 4–9ms 的观测值
   （冷启动 3.5–6.9s 的波动本身就是机器负载不稳的证据）。
5. **`webflux + native + governance=on` 存在稳定的尾延迟退化（v2 已消失，见 5.3）**：
   json p99 三轮分别 73.7 / 122.4 / 117.7ms（对照 `webflux-off-native` 36.4ms），
   sse p99 207–392ms；rps 逐轮从 4909 掉到 3535，是逐轮恶化而非单次抖动。
   当时怀疑 Native 下 `boundedElastic` offload、旁路虚拟线程调度或 Serial GC。
6. **方法论限制**：50 VU 下 SSE 仅 ~447 rps、JSON ~6042 rps，两场景并发压力不同，
   不可跨场景比吞吐；mock 上游与网关同进程共享 CPU；v1/v2 的 `rss_kb` 全为 `NA`
   （沙箱屏蔽 `ps`），**那两版不给内存结论**；v3 起脚本改用 footprint 兜底并标注 `rss_source`，见 5.4。

固定指标口径（适用于 v1/v2/v3/v4）：网关自身开销 p50/p99、RPS、错误率、进程内存峰值、冷启动时间。
内存采样优先 `ps -o rss=`（真实 RSS）；该沙箱禁用 `ps -p` 时回落到 macOS
`footprint -f bytes --noCategories` 的 `phys_footprint`（换算 KB）。CSV 新增 `rss_source`
列标明来源，`ps` 与 `footprint` **不等价**（后者还计入压缩页），不可混在同一比较里；
两者都失败才写 `NA`，绝不编造数字。`wrk` 用 `bench/wrk-baseline.sh` 做裸吞吐交叉验证。
详见 `bench/README.md`。

### 5.2 v2 正式结果（全异步，公平对照）

代码变更：`UpstreamProvider.completeRawAsync` → `JdkHttpUpstream` 用
`Mono.defer` + `HttpClient.sendAsync` + `Mono.fromFuture(..., false)`；
WebFlux 非流式去掉 `boundedElastic`，fallback 改惰性 `onErrorResume` 链；
异常契约与阻塞路径对齐；缓存命中双计费修复。两个 native 二进制重编
（webflux 272MB / vt 236MB），并用 `build.sh` 重建干净 JVM jar 后才跑矩阵。

`bench/results/summary-20261106-092107.csv`：同口径 50 VU / 30s × 3 轮，
8 组全跑完、16 条场景 `error_rate=0`、SSE `[DONE]` 8/8 通过。

| 引擎 | 治理 | 运行时 | json p50 | json p99 | json rps | sse p50 | sse p99 | sse rps | 冷启动 |
|---|---|---|---|---|---|---|---|---|---|
| webflux | on | jvm | 10.10 | 50.51 | 3966 | 106.95 | 133.38 | 460 | 6.268 |
| webflux | on | native | 5.00 | 36.12 | 7334 | 112.24 | 142.46 | 434 | 0.399 |
| webflux | off | jvm | 4.41 | 10.77 | 9722 | 106.60 | 124.84 | 464 | 3.310 |
| webflux | off | native | 6.72 | 53.66 | 5910 | 112.48 | 139.12 | 435 | 0.093 |
| vt | on | jvm | 5.37 | 15.39 | 8115 | 109.04 | 115.06 | 457 | 3.723 |
| vt | on | native | 4.67 | 36.51 | 7780 | 112.54 | 136.70 | 434 | 0.101 |
| vt | off | jvm | 4.83 | 13.66 | 9069 | 108.97 | 126.02 | 454 | 4.103 |
| vt | off | native | 6.67 | 49.56 | 5331 | 110.73 | 149.32 | 438 | 0.460 |

**v2 能站住的结论**

1. **引擎之间仍无统计显著差异**：SSE p50 106.60–112.54ms（CV **2.1%**）、
   RPS 434–464（CV 2.6%）；JSON 场景除 `webflux-on-jvm` 外，WebFlux vs VT 的
   RPS 差异在 −5.7%～+10.9%。修完隐藏变量后结论不变：瓶颈在上游，不在并发模型。
2. **SSE 尾延迟分布收敛**：p99 CV 从 v1 的 34.8% 降到 **7.7%**，最大 p99
   从 308.33ms 降到 149.32ms。但 **SSE 路径代码一行没改**，这个改善不能归因于
   异步化，更可能来自「干净 JVM jar」与机器负载变化，如实标注。
3. **冷启动仍是唯一稳定显著差异**：v2 JVM 均值 4.351s（3.310–6.268）、
   native 0.263s（0.093–0.460），约 16.5×；结合 v1 的 10.5×，稳妥口径是
   **快一个数量级**，不写死倍数。
4. `webflux-on-jvm` 变慢是噪声不是退化：三轮 p50 16.26 / 9.95 / 10.10ms、
   rps 2273 / 4259 / 3966，第一轮明显是 JIT 预热，且该组 startup 6.268s 为全场最慢。

### 5.3 v1 → v2：尾延迟异常消失的三轮证据

`webflux + native + governance=on`，非流式 json，逐轮原始值：

| 版本 | r1 p99 | r2 p99 | r3 p99 | r1 max | r2 max | r3 max | r1 rps | r2 rps | r3 rps |
|---|---|---|---|---|---|---|---|---|---|
| v1（boundedElastic） | 73.69 | 122.41 | 117.70 | 182 | 606 | 532 | 4909 | 3538 | 3535 |
| v2（全异步） | 36.12 | 36.69 | 35.81 | 97 | 91 | 137 | 9056 | 7334 | 7187 |

v1 是**逐轮恶化**（p99 翻倍、max 冲到 606ms、吞吐持续下滑），符合线程池排队积压；
v2 的 p99 三轮几乎是一条直线。结合唯一改动的路径就是非流式 offload，
推断根因为 `boundedElastic` 在 Native Image 下的调度/排队，而非语言或 GC。

**归因边界（别拿去写 PPT）**：v1 与 v2 相隔 1 小时、机器负载不同，且 v2 修正了
构建顺序（v1 的 JVM jar 可能是 AOT 固化过的）。VT 引擎代码一行没改，却在
v1→v2 出现 +99% / −15% 的剧烈波动。所以 **v1→v2 的绝对数值差不能作为
「异步化提升 X%」的证据**；v2 的价值是「实验现在可归因、尾延迟异常消失」，
不是「吞吐变快了」。JSON 场景 v2 的 p50 CV 仍有 29.5%、RPS CV 25.5%，依旧不能排座次。

### 5.4 v3 正式结果（流式错误契约 + 单次解析 + 内存补齐）

代码变更：流式失败按首 chunk 分两阶段处理（见设计判断 7）、fallback 只在首 chunk 前、
`GatewayDecision` 携带 `ParsedRequest` 消除热路径第二次 JSON 解析、
mock 增加 `mock-fail-after-N` 断流注入、内存采样增加 footprint 兜底与 `rss_source` 列。
两个 native 二进制重编（272MB / 236MB）并按正确顺序重建干净 JVM jar 后才跑矩阵。

`bench/results/summary-20261106-111405.csv`：同口径 50 VU / 30s × 3 轮，
8 组全跑完、16 条场景 `error_rate=0`、SSE `[DONE]` 全通过、内存 **16/16 采到**（来源均为 footprint）。
内存列取该组截至该场景的累计峰值，所以同组两行可能相同。

| 引擎 | 治理 | 运行时 | json p50 | json p99 | json rps | sse p50 | sse p99 | sse rps | rss 峰值 KB | 冷启动 |
|---|---|---|---|---|---|---|---|---|---|---|
| webflux | on | jvm | 9.42 | 37.91 | 4272 | 109.65 | 138.05 | 451 | 741912 | 5.059 |
| webflux | on | native | 7.69 | 53.99 | 4762 | 116.43 | 171.52 | 416 | 178764 | 0.118 |
| webflux | off | jvm | 6.91 | 20.06 | 6227 | 109.41 | 135.74 | 452 | 702832 | 5.285 |
| webflux | off | native | 7.25 | 61.29 | 5409 | 112.95 | 214.93 | 424 | 341368 | 0.117 |
| vt | on | jvm | 8.31 | 27.59 | 5112 | 109.99 | 136.19 | 452 | 728788 | 5.722 |
| vt | on | native | 7.22 | 50.05 | 5325 | 117.64 | 145.38 | 420 | 480040 | 0.119 |
| vt | off | jvm | 5.14 | 12.89 | 8777 | 110.42 | 116.09 | 452 | 686576 | 3.926 |
| vt | off | native | 4.74 | 33.79 | 7684 | 118.13 | 137.99 | 418 | 142364 | 0.102 |

**能站住的结论**

1. **正确性修复覆盖全部产物**：JVM 双引擎 21 项 + 两个 native 各 11 项回归断言全通过；
   账单/审计对齐（`requests=3 / errors=1`、`completed=2 / failed=1`、completionTokens 两引擎同为 58）。
   这是本轮的主要价值，不是性能数字。
2. **SSE 依旧没有引擎差异**：p50 109.41–118.13ms（CV **3.1%**）、RPS 415.9–452.1（CV **3.7%**）；
   100ms mock 上游延迟继续主导端到端延迟。
3. **冷启动仍是唯一稳定显著差异**：JVM 3.926–5.722s（均 4.998s）vs native 0.102–0.119s（均 0.114s）。
   但本轮比值 43.8× 与 v1 的 10.5×、v2 的 16.5× 相差很大，说明倍数本身强烈依赖机器负载，
   稳妥口径仍是**快一个数量级**，不写死倍数。
4. **内存第一次有数，但只给数量级**：JVM 峰值 686,576–741,912KB，native 142,364–480,040KB，
   native 大致是 JVM 的 **1/3 量级**。同一运行时内部波动就有 3.4×，且 footprint ≠ 严格 RSS，
   所以不给精确倍数、不跨来源比较（见「固定指标口径」）。
5. **JSON 仍不能排座次**：p50 CV 20.4%、p99 CV 42.7%、RPS CV 24.3%。
   本轮 gov on/off 配对在 8 组里方向一致地表现为「开治理更慢」（RPS −12%～−42%），
   比 v2 的方向反转更像真实开销，但依然是单机、单轮次、负载偏重，**不作治理成本的定量结论**。

**归因边界**：v2→v3 的 JSON RPS 在 −37%～+44% 之间乱跳，SSE 普遍 −0.5%～−4.7%
（而 SSE 正常路径代码一行没改）。两轮相隔约 2 小时、机器负载明显更重
（JVM 冷启动从 4.351s 升到 4.998s）、native 全部重编过。
**v3 的价值是正确性、口径与内存数据，不是性能提升**；任何「少解析一次 JSON 提速 X%」的说法都不成立。
可用 `python3 bench/analyze-matrix.py --compare old.csv new.csv` 复现这些差值与提示。

### 5.5 v4 正式结果（SSE 完成判定 + native 管理接口 AOT 修复）

代码变更：唯一完成判据改为「上游是否显式发出 `data: [DONE]`」（见设计判断 9），
网关不再伪造终止帧；mock 增加 `mock-eof-after-0/N` 静默截断注入；
`GatewayRuntimeHints` 补齐 `/admin/*` 可达 record 的反射注册（见第四章第二个 AOT 坑）；
k6 SSE 增加「含真实 `data: [DONE]` 行 + 无 `event: error`」两条 check。
两个 native 二进制重编（272MB / 236MB）并按正确顺序重建干净 JVM jar 后才跑矩阵。

`bench/results/summary-20261106-132434.csv`：同口径 50 VU / 30s × 3 轮，
8 组全跑完、**16 条场景 `error_rate=0`**、SSE 新 check 全通过、内存 16/16 采到（footprint）。

| 引擎 | 治理 | 运行时 | json p50 | json p99 | json rps | sse p50 | sse p99 | sse rps | rss 峰值 KB | 冷启动 |
|---|---|---|---|---|---|---|---|---|---|---|
| webflux | on | jvm | 4.95 | 10.37 | 9213 | 111.98 | 119.20 | 444 | 723396 | 3.381 |
| webflux | on | native | 4.46 | 29.82 | 7893 | 120.88 | 148.08 | 409 | 135908 | 0.306 |
| webflux | off | jvm | 4.00 | 8.67 | 10786 | 113.49 | 121.04 | 439 | 703416 | 3.360 |
| webflux | off | native | 3.86 | 27.13 | 9242 | 119.06 | 146.01 | 414 | 116584 | 0.082 |
| vt | on | jvm | 4.32 | 10.70 | 10216 | 112.39 | 118.95 | 444 | 759584 | 3.534 |
| vt | on | native | 3.99 | 27.14 | 8091 | 119.97 | 142.77 | 412 | 103176 | 0.094 |
| vt | off | jvm | 4.03 | 9.83 | 11068 | 112.01 | 119.47 | 444 | 687740 | 4.145 |
| vt | off | native | 3.60 | 26.41 | 9229 | 112.80 | 137.75 | 438 | 179040 | 0.095 |

**能站住的结论**

1. **完成判定修复在压测规模下成立**：16/16 场景在「必须含真实 `data: [DONE]` 行、且不含 `event: error`」的新 check 下 `error_rate=0`。这比回归脚本的 31 项断言更有说服力——它证明正常路径没有被误判为截断，也没有靠补帧蒙混过关。
2. **正确性覆盖全部产物**：JVM 双引擎 31/31、两个 native 各 16/16 回归通过；native 的 `/admin/snapshot`、`/admin/routes`、`/admin/keys` 全部 200（v3 的 native 快照是 500）。这是本轮主要价值。
3. **SSE 依旧没有引擎差异**：p50 111.98–120.88ms（CV **3.2%**）、RPS 409–444（CV **3.5%**）；100ms mock 上游延迟继续主导端到端延迟。
4. **治理开销第一次在同一轮内方向一致**：JSON 场景 8 组配对全部表现为「开治理更慢」（p50 +7.2%～+23.9%、RPS −7.7%～−14.6%），SSE 场景配对差异在 −5.9%～+1.1%。本轮 JSON RPS 的 CV 只有 11.5%（v3 是 24.3%），噪声比历次都低，所以这个方向比 v1/v2 的反转更可信；但仍是**单机、单轮次、同一负载窗口**，只说「本轮内可分辨」，不给治理成本的定量结论、不跨版本套用。
5. **冷启动与内存量级不变**：JVM 3.360–4.145s（均 3.605s）vs native 0.082–0.306s（均 0.144s），四版比值 10.5× / 16.5× / 43.8× / **25.0×**，继续只说**快一个数量级**。内存 JVM 687,740–759,584KB、native 101,232–179,040KB，本轮 native 约为 JVM 的 **1/5 量级**且组内波动收敛到 1.8×（v3 是 3.4×）；footprint ≠ 严格 RSS，仍只给数量级。

**归因边界**：v3→v4 的 JSON RPS 在 **+20%～+116%** 之间整体上跳，而 SSE 普遍 −1.5%～−2.8%；两轮相隔约 2 小时，v4 的 JVM 冷启动从 4.998s 降到 3.605s，说明机器负载明显更轻。**SSE 正常路径代码几乎没改（只多一次布尔判断）却照样跟着动，这再次证明跨轮次绝对差值不是性能证据**。v4 的价值是正确性、AOT 兼容性与更严格的有效性口径，不是「提速」。用 `python3 bench/analyze-matrix.py --compare` 可复现这些差值及提示。

### 5.6 v5 正式结果（功能 / 安全 / 资源加固，不是性能优化）

代码变更：上游非 2xx 在 `BodyHandler` 阶段判定、流式 demand 桥接与取消传播、WebFlux `doFinally` 结算、`[DONE]` 后断连不算失败；配额原子预留 + 令牌桶按实际用量结算 + 失败/取消/缓存命中退还；usage chunk 覆盖估算、非流式取真实 usage；缓存键改完整请求体哈希；管理接口脱敏与可选令牌、请求体上限、快照替换语义；审计无锁、Timer 缓存。两个 native 重编并按正确顺序重建干净 JVM jar。

`bench/results/summary-20261106-155049.csv`：同口径 50 VU / 30s × 3 轮，8 组全跑完、**16 条场景 `error_rate=0`**、内存 16/16 采到（footprint）、SSE 完成 check 全通过。CSV 新增 `mock_delay_ms` / `mock_chunk_interval_ms` 两列，本轮为 **20 / 10**（与 v1–v4 相同）。

| 引擎 | 治理 | 运行时 | json p50 | json p99 | json rps | sse p50 | sse p99 | sse rps | rss 峰值 KB | 冷启动 |
|---|---|---|---|---|---|---|---|---|---|---|
| webflux | on | jvm | 4.08 | 8.59 | 11221 | 111.97 | 119.63 | 444 | 803464 | 3.125 |
| webflux | on | native | 3.69 | 25.12 | 8826 | 120.02 | 152.59 | 408 | 109384 | 0.329 |
| webflux | off | jvm | 3.46 | 6.85 | 13281 | 114.46 | 126.78 | 435 | 728912 | 3.669 |
| webflux | off | native | 3.42 | 30.44 | 11250 | 119.12 | 145.64 | 413 | 180108 | 0.103 |
| vt | on | jvm | 3.75 | 8.93 | 12147 | 112.40 | 147.89 | 441 | 928236 | 4.032 |
| vt | on | native | 3.83 | 30.60 | 8595 | 116.79 | 154.02 | 419 | 131028 | 0.116 |
| vt | off | jvm | 4.30 | 9.78 | 10482 | 113.09 | 131.39 | 438 | 613836 | 5.601 |
| vt | off | native | 3.65 | 25.43 | 9313 | 119.22 | 144.43 | 413 | 117984 | 0.514 |

**能站住的结论**

1. **并发取消缺陷在压测规模下确实被修掉**：修复前 50 VU 短跑 SSE `error_rate=16%`（`Request cancelled`），修复后 50 VU × 30s × 3 轮、16/16 场景 `error_rate=0`，且回归里的「40 路并发 120 请求零 502」在 JVM 双引擎与两个 native 上全通过。这是本轮最有价值的验证 —— 串行功能测试永远发现不了连接复用竞态。
2. **SSE 依旧没有引擎差异**：p50 111.97–120.02ms（CV **2.7%**）、RPS 408–444（CV **3.2%**）；100ms 上游延迟继续主导端到端延迟，与 v1–v4 一致。
3. **治理开销仍不能定量，v4 的「方向一致」被 v5 推翻**：JSON 的 on/off 配对在 v5 是 6 负 1 正（webflux-jvm −15.5%、webflux-native −21.5%、vt-jvm **+15.9%**、vt-native −7.7%），SSE 配对全在 ±2% 内。v4 那轮 8 组方向一致更像是低噪声窗口里的巧合，不能外推成「治理成本 X%」。本轮 JSON RPS CV 14.6%、p99 CV 54.3%（native p99 25–31ms vs JVM 7–10ms），噪声仍大于信号。
4. **冷启动与内存量级不变**：JVM 3.125–5.601s（均 4.107s）vs native 0.103–0.514s（均 0.266s），五版比值 10.5× / 16.5× / 43.8× / 25.0× / **15.5×**，继续只说**快一个数量级**。内存 JVM 613,836–928,236KB、native 109,384–180,108KB，约 **1/5 量级**；footprint ≠ 严格 RSS，只给数量级。
5. JSON p50 CV **7.4%** 是五版最低（v1 24.5%、v2 29.5%、v3 20.4%），但仍不足以排引擎座次 —— p99 CV 54.3%、RPS CV 14.6% 说明尾部与调度噪声没有消失。

**归因边界**：v4→v5 的 JSON RPS 均值上跳约 13%，SSE 几乎不变；两轮相隔约 2 小时、v5 机器负载更重（JVM 冷启动 3.605s → 4.107s）、治理层代码大改、期间还修了一次并发取消缺陷。**v5 的价值是正确性、安全、资源与测量口径，不是提速**；「v5 比 v4 快 X%」不成立。用 `python3 bench/analyze-matrix.py --compare` 可复现差值及提示。

### 5.7 wire 实验（零上游延迟，单独口径）

见 `bench/results/summary-*.csv` 中 `mock_delay_ms=0, mock_chunk_interval_ms=0` 的那一份；
主矩阵把 100ms 上游延迟当作「真实推理」的锚，代价是网关自身开销被完全淹没。
wire 实验把两个延迟都设成 0，只用于**同一份 wire CSV 内部**比较引擎 / 运行时 / 治理配对，
**不与 5.1–5.6 的任何一行横比**。结论与归因边界见 `bench/README.md` 的正式结果表。

v5 wire 实测（`summary-20261106-161852.csv`，16/16 error_rate=0）：网关自身开销首次可分辨——
JSON p50 ≈ **3.9–5.4ms**（均值 4.6ms）、RPS 均值 ≈ **9212**（CV 6.1%）；SSE p50 ≈ 5.4–6.8ms
（`webflux/native/off` 一组 p50=11.9ms 是单点离群，不归因）。治理 on/off 配对除该离群组外
差异全部在 ±12% 内，多数在 ±5%（噪声量级）。冷启动比在本剖面为 18.3×。
两个剖面（100ms 锚定 / 0ms wire）仍然禁止逐行相减。

**JVM GC 矩阵（wire 剖面，webflux/on，3 轮中位数，`bench-gc-matrix.sh`，汇总 `gc-experiment-summary*.csv`）**：
Oracle GraalVM 25 上可用的收集器全部实测（Shenandoah 被 JVMCI 拒绝：`GraalError: Shenandoah garbage
collector is not supported by Graal`，只能换发行版，见下）。每臂开 `-Xlog:gc` 统计暂停；
ZGC 暂停行打在 `{gc,phases}` 标签组，**必须 `-Xlog:gc*`**，裸 `gc` 会静默统计成 0。

| 变体 | JSON p50/p99 ms / RPS | SSE p50/p99 ms / RPS | 峰值 footprint | GC 总暂停 / max |
|---|---|---|---|---|
| g1（默认） | 5.57 / 18.84 / 7594 | 6.08 / 14.08 / 7318 | 0.9–1.0GB | 1.85s / 11.1ms |
| zgc（分代，两样本） | 4.56/9.19/10131 · 4.36/8.66/10548 | 6.79/17.54/6489 · 5.82/12.76/7728 | **7.5GB** | ~3.5s / **0.065ms** |
| g1-coh（紧凑对象头） | 4.42 / 9.45 / 10020 | 5.98 / 13.43 / 7499 | 0.86–1.0GB | 1.88s / 9.3ms |
| g1-pause10 | 5.15 / 12.04 / 8694 | 6.21 / 13.92 / 7199 | ~1.1GB | 1.85s / **6.6ms** |
| parallel | 4.69 / 9.67 / 9775 | 6.21 / 13.97 / 7150 | 1.1–1.5GB | 1.98s / 32.3ms |
| serial（= native macOS 所用） | 4.53 / 13.30 / 9698 | 5.86 / 16.17 / 7360 | **0.62GB** | **9.6s / 72.3ms** |

同批内 ZGC 延迟/吞吐小幅领先、Serial 内存减半，但都付各自代价（ZGC 7.5GB footprint ≈ 默认
Xmx=8GB 整堆提交；Serial 暂停灾难化）。批间噪声显著（run1 的 G1 JSON 7594 RPS vs 更早一批
11153），**跨批不横比**，结论只取自同批配对。

**Shenandoah 专项（`bench-shenandoah.sh`，Temurin 25.0.5，同 C2 JIT 内 A/B，`shen-experiment-summary.csv`）**：
Oracle GraalVM 不带 Shenandoah，因此用 Eclipse Temurin 25 做「同 JVM 内 G1 vs Shenandoah」，
归因干净（若拿 Temurin 直接对比 Oracle GraalVM 的数字会把 Graal JIT vs C2 的差异混进来）。

| 对比（vu50 / vu100） | Temurin G1 | Temurin Shenandoah |
|---|---|---|
| JSON p50/p99 / RPS | 4.35/8.99/10491 · 10.76/23.22/9087 | 4.37/8.58/10517 · 10.94/23.11/9054 |
| SSE RPS | 7754 · 6624 | 7735 · 6788 |
| GC 总 STW 暂停 | 1832ms · 1886ms | **14.3ms · 12.8ms（≈130× 低）** |
| GC max 暂停 | 6.4ms · 12.6ms | **0.85ms · 0.51ms** |
| 峰值 footprint | 0.84–1.0GB | 7.7–7.8GB（默认整堆预提交，与 ZGC 同病） |

**`-Xmx2g` 钉堆后（`summary-20261107-082604/082932`）**：Shenandoah JSON 4.65/10.32ms、9766 RPS、
footprint **2.25GB**、总暂停 60.5ms / max **0.84ms**；G1 JSON 4.29/9.24ms、**10600 RPS**、
footprint **0.49–0.59GB**、总暂停 3.62s / max 8.4ms（小堆令 G1 GC 更频但吞吐反而同批最高）。

**最终结论（用数据说话）**：① 该负载下各收集器吞吐差异都在噪声内，G1 仍是默认正解——延迟
不输、内存最优、且是 native G1（Linux）与 JVM 的统一选择；② Shenandoah/ZGC 的价值在**严格
尾延迟 SLO**：把 max STW 从 6–12ms 压到亚毫秒，吞吐零损失，但必须显式 `-Xmx` 预算
（Shenandoah 2g ≈ 2.25GB footprint，仍是 G1 的 ~4×）；③ `MaxGCPauseMillis=10` 是接近免费的
G1 调优（max 暂停 11→6.6ms）；④ 紧凑对象头（JDK 24+）在此负载无可测收益，不建议为它单独
切换；⑤ Serial 的 9.6s 总暂停/72ms max 证实 **macOS native 镜像吃的是 Serial GC 的真实性能
负债**，Linux 上 native 应使用 `--gc=G1`。bench 脚本支持 `JVM_OPTS` 与 `JAVA_BIN`（跨发行版
GC 实验）注入。

### 5.8 v7 热路径优化（三臂 A/B + executor 池扫描 + 终局确认）

v7 的每项优化都先审代码找真实开销，再由同批 A/B 数据裁决，不拍脑袋优化：

**优化清单**：
1. **HttpClient 专用线程池**（`gateway.upstream.executor-threads`，默认 32）——JDK 默认
   sendAsync 执行器是 wire 剖面最大的黑盒瓶颈
2. **非流式响应体 3 次 JSON 解析 → 1 次**：`tryParse` 单次建树，token 计数与缓存判定共用；
   SPI 加 4 参 `cache(keyId, request, response, cacheable)` 重载（注意：默认方法实现若不
   重载会退回 3 参重新解析，v7.1 已修 `DefaultGovernanceChain`）
3. **ChunkParser 流式解析**：SSE 每行不再建 `JsonNode` 树（50 VU 下 ≈14 万行/秒的树构建），
   改 Jackson 3 流式 `JsonParser` 提取 `delta.content` / `usage`。踩坑记录：Jackson 3 的
   `FIELD_NAME`→`PROPERTY_NAME`、`JsonFactory` 在 `core.json`、`getFactory()`→
   `tokenStreamFactory()`
4. **requestId 去 SecureRandom 化**：`UUID.randomUUID()` 底层全局同步是争用点；关联 ID 不需要
   密码学强度，改 `ThreadLocalRandom` 定长 16 hex
5. `estimateTokens` 去 Stream 化；run 脚本默认 `MaxGCPauseMillis=10`（`GATEWAY_GC_OPTS` 可覆盖）

**三臂 A/B（wire、webflux/on、5 轮中位数，`summary-20261107-0941*.csv`）**：

| 臂 | JSON p50/p99/RPS | SSE p50/p99/RPS |
|---|---|---|
| ARM1 v6 基线 | 4.67 / 10.06 / 9704 | 6.40 / 14.78 / 6955 |
| ARM2 v7（默认 executor） | 5.00 / 10.74 / 9069 | 7.04 / 17.62 / 6261 |
| ARM3 v7 + executor=16 | **3.80 / 8.83 / 11342** | **4.87 / 10.41 / 9213** |

ARM2 反而变差——单次解析被 SPI 默认方法的回退路径吃掉（见优化 2），修复后进 v7.1。

**executor 池扫描（v7.1，3 轮中位数）**：8 线程 JSON 9974 / SSE 8514 RPS；16 → 11342 / 9213；
**32 → 12538 / 9606（拐点）**；64 → 12882 / 9461（无实质收益）。默认值定为 32。

**终局确认 A/B（v7.2 终版，5 轮中位数，`summary-20261107-103046/103618`）**：

| 臂 | JSON p50/p99/RPS | SSE p50/p99/RPS |
|---|---|---|
| v6 基线 | 4.65 / 9.37 / 9899 | 6.07 / 13.06 / 7422 |
| **v7.2 终版** | **3.74 / 8.44 / 11751（+18.7%）** | **5.19 / 10.84 / 8738（+17.7%）** |

v7.2 相对 v6：JSON p50 −19.6%、p99 −10.0%；SSE p50 −14.4%、p99 −17.0%；两模式 RPS 均
+18% 左右，每臂 error_rate=0。jar 快照：`bench/results/v6-before-perf.jar`（基线）、
`v7-new.jar`（v7）、`v7.1-jar.jar`、`v7.2-final.jar`（终版），全部可复现。

### 5.9 v8 每请求 CPU 浪费清理（微基准 + 交错配对 A/B）

v7.2 解决的是 I/O 调度瓶颈；v8 转向「每个请求都在重复做、但结果恒定」的 CPU 浪费。
先审代码，再用**网络无关微基准**定位，最后用**交错配对 A/B**验证，不用端到端数字拍脑袋。

**六项改动**：
1. **上游 URL 缓存**：`build()` 原来每请求执行 `base.replaceAll("/+$", "")`（每次编译正则）
   + `URI.create(url)` + 字符串拼接；targetUrl 来自静态路由配置，结果恒定 → 按 URL 缓存 `URI`
2. **流式请求跳过整包 SHA-256**：SSE 响应**永不进语义缓存**（两个缓存入口都对
   `stream=true` 提前返回），指纹算出来是纯浪费；改为流式请求不预计算，
   `cacheKey()` 若真被自定义链调用才按全量请求体惰性计算（比旧的 model+prompt 回退更安全）
3. **入站请求流式解析**：`readTree` 建整棵 `JsonNode` 树 → `JsonParser` 一遍扫描，
   只取 `model` / `stream` / `messages[].content`，其余 `skipChildren()`
4. **SHA-256 digest 线程内复用**：消掉每请求的 JCA provider 查找
5. **治理指标快路径**：`ObjectProvider.getIfAvailable()` 每请求 3–4 次 bean 查找 +
   `computeIfAbsent` 捕获 lambda 分配 → 注册表一次解析、命中走 `get()`
6. `engineLabel` 构造期缓存，消掉每请求 `System.getProperty`

**网络无关微基准**（`bin/run-parse-microbench.sh`，交错轮次、精确复刻 v7.2 逻辑做对照，
`bench/results/v8.1-parse-microbench-final.csv`）：

| 请求体 | v7.2 解析 ns/op | v8.1 解析 ns/op | 提速 | 每次分配字节 | 降幅 |
|---|---|---|---|---|---|
| 158B | 3412 | 2507 | **1.36×** | 2593 → 1408 | **−45.7%** |
| 1182B | 7136 | 6801 | 1.05× | 6632 → 5504 | −17.0% |
| 4254B | 21768 | 21291 | 1.02× | 18920 → 17792 | −6.0% |
| 16542B | 80248 | 78793 | 1.02× | 68072 → 66944 | −1.7% |

成本分解给出下一步方向：4KB 请求 21565ns 里 **SHA-256 独占 14640ns（68%）**、
建树 6634ns、UTF-8 编码 582ns、token 循环 118ns；16KB 时 SHA-256 55014/75772ns（73%）。
所以流式跳过哈希才是大头：

| 流式请求体 | v7.2（总算哈希） | v8.1（跳过） | 提速 |
|---|---|---|---|
| 157B | 2491ns | 1199ns | **2.08×** |
| 4253B | 20751ns | 6269ns | **3.31×** |
| 16541B | 76810ns | 22209ns | **3.46×** |

URL 单项：6207ns → 140ns（**44.3×**），每请求省 ~6µs，是端到端收益的主因。

**交错配对 A/B**（`bin/bench-paired-ab.sh`，ABBA 反序抵消宿主机负载漂移，
各 4 对、每臂 20s、AB 各取中位数；`paired-ab-20261107-150316.csv` /
`-151331.csv`）：

| 载荷 | 臂 | JSON p50/p99/RPS | SSE p50/p99/RPS |
|---|---|---|---|
| 4096B | v7.2 | 11.65 / 131.83 / 2718 | 17.22 / 46.40 / 2572 |
| 4096B | **v8.1** | **10.88 / 29.23 / 3919（+44%）** | **12.11 / 28.03 / 3658（+42%）** |
| 0B | v7.2 | 12.15 / 120.00 / 3067 | 14.73 / 37.87 / 3048 |
| 0B | **v8.1** | 13.07 / **39.43** / 3306 | 15.23 / 36.56 / 2987 |

逐轮一致性：**p99 8/8 全胜、RPS 14/16 胜**；大载荷 JSON p99 −78%、SSE p99 −40%。
小载荷吞吐基本打平（+7.8% / −2%），但尾延迟同样大幅改善——与「分配量下降 → GC 压力下降」
的机理一致。**注意**：这两批的绝对 RPS 只有 ~3000（正常 ~11000），因为宿主
`vm.loadavg` 期间在 90–110；绝对数字不可与干净批次横比，ABBA 的**配对比值**才有效。

**被判作废的批次**：`summary-20261107-1404*.csv` 至 `-1441*.csv`（Netty worker 扫描 +
首轮四臂 A/B）期间宿主负载一度 111、1017 个进程（Chrome / ChatGPT / Metal 编译服务等），
RPS 掉到 2000–5000、p99 剧烈抖动，**只作方向参考，不得作为结论**。
Netty `ioWorkerCount` 4/8/16/24 扫描未给出可复现赢家（4 明显劣化、8/16/24 全在噪声内），
**保持默认 16**。压测前先看 `sysctl -n vm.loadavg` 已写入 bench/README 红线。

**结论**：v8.1 的收益在**尾延迟与大载荷吞吐**，不在干净环境下的峰值 RPS。
v7.2 之后网关自身 Java 代码已不是主要瓶颈，继续压榨需转向 I/O 层
（native 传输、连接复用、零拷贝），而不是继续微优化解析。
jar 快照：`bench/results/v8-new.jar`（首次 v8）、`v8.1-final.jar`（终版）。

**回归**：v8.1 数据面变更后 JVM 双引擎 67/67、重编的两个 native 各 34/34 全绿
（重点验证 native 镜像里 ThreadLocal `MessageDigest` 与 Jackson 3 流式解析）。
注意 `build-native.sh` 会重打包 target jar、仅给 MANIFEST 加
`Spring-Boot-Native-Processed: true`（所有 class 字节不变），因此交付前已把
`llmgw-app/target/llmgw-app-0.1.0-SNAPSHOT.jar` 恢复为经 A/B 与冒烟验证的
`v8.1-final.jar` 快照（两者 SHA-256 一致）。

### 5.10 v9 I/O 层探路：两项候选都未被数据采纳

v8.1 之后网关自身 Java 代码已不是主要瓶颈，本轮转向 I/O 层。**结论是「不采纳」**：
两项候选都没有通过数据检验，v8.1 仍是终版。保留完整实验资产，避免后来者重复踩坑。

**先排除一条伪线索——上游连接复用**。压测中采样 9090 端口：`ESTABLISHED`
稳定在 100、`TIME_WAIT=0`，JDK HttpClient 的 HTTP/1.1 keep-alive 正常工作，
没有连接风暴可挖。

**候选 A：macOS kqueue 原生传输**。依赖树显示 Netty 4.2.19 / reactor-netty 1.3.8
的 Boot BOM 只带 Linux epoll 与 macOS DNS/QUIC native 包，**macOS 上网关跑在
NIO selector**。新增 opt-in profile `-Pkqueue`（`netty-transport-classes-kqueue`
+ `netty-transport-native-kqueue:osx-x86_64`；Apple silicon 用
`-Dllmgw.kqueue.classifier=osx-aarch_64`）。启用与否不是靠猜：`jcmd Thread.print`
显示对照臂是 `reactor-http-nio`、实验臂是 `reactor-http-kqueue`，且日志出现
`System.loadLibrary` 警告，原生库确实加载了。

| kqueue 实验 | 负载 | 中位数 | 逐轮一致性 | 判定 |
|---|---|---|---|---|
| ABBA 4 对（`paired-ab-20261107-165602.csv`） | 3 → 66 | JSON +4.9%、SSE −5.6% | JSON/SSE 均 **2/4** | 噪声，无结论 |
| ABBA 3 对（`paired-ab-20261107-172309.csv`） | 6 → 111 | JSON +42%、SSE +19% | JSON **1/3**、SSE 3/3 | 中位数被漂移扭曲 |

第二轮那个「+42%」是假象：最干净的 r1（负载 6–17）JSON 反而是 **A 赢 23%**
（9067 vs 7361 RPS），r2 里 A 崩到 4672、r3 里 B 崩到 4524，SSE 胜幅在
+1.7%~+72% 之间乱跳。**不设默认**；profile 保留，建议在负载隔离的环境或
生产 Linux（已有 epoll）复测。

**候选 B：池化 direct 响应 buffer**。原路径 `wrap(byte[])` 是 Unpooled **堆内**
ByteBuf，NIO 写出前要 heap→direct 拷贝；改为从 channel 的
`PooledByteBufAllocator` 用 `allocateBuffer(n).write(bytes)` 分配 direct buffer。
新增网络无关微基准（`bin/run-buffer-microbench.sh` + `bench/micro/BufferMicrobench.java`，
ABBA 交错、ThreadMXBean 量分配字节），三次运行：

| 载荷 | wrap → direct ns/op | 提速 | 分配降幅 |
|---|---|---|---|
| 104B（SSE 行） | 202.8→244.9 / 271.7→275.8 / 279.9→265.7 | 0.83× / 0.99× / 1.05× | 0–0.5% |
| 238B（JSON 响应） | 343.9→327.3 / 159.1→252.2 / 154.7→170.6 | 1.05× / 0.63× / 0.91× | 0–1.5% |

底层没有任何可测收益；端到端配对 A/B（`paired-ab-20261107-172857.csv`，负载
80–90 已污染）方向还偏负（JSON −12.5%、SSE −25.8%、SSE p99 28→66ms）。
**已回退**：`WebfluxGatewayController` 恢复 `wrap(byte[])`，回退后全模块 class 与
`v8.1-final.jar` **逐字节一致（0 差异）**、target jar SHA-256 相同、JVM 67/67 全绿。
native 二进制数据面未变，无需重编。

**数据质量红线（本轮新增）**：`bin/bench-v9-io-ab.sh` 带 15s 粒度负载采样
（`bench/results/v9-load-*.log`），支持 `PAIRS` / `DURATION` / `VUS`。两次实验都
撞上宿主负载从个位数飙到 100+（1011 个进程，Chrome / ChatGPT / Metal 编译服务等），
**所有端到端数字只作方向参考，不得作为结论**；看中位数之前必须先看逐轮一致性和
负载时间线。实验快照 `v9-kqueue.jar` / `v9.1-direct.jar` 与全部 CSV 保留供复现。

**工程踩坑**：`mvn dependency:build-classpath -pl <依赖内部 SNAPSHOT 的模块>`
无法解析 `llmgw-core`（`-am` 也只给出不完整 classpath），最终改为从 fat jar 的
`BOOT-INF/lib` 提取依赖，顺带保证微基准与被测产物版本精确一致；
`getThreadAllocatedBytes` 在 `com.sun.management.ThreadMXBean` 扩展接口上，
需先 `setThreadAllocatedMemoryEnabled(true)`。

### 5.11 v10 能力建设：可观测 / 热配置 / 分布式状态

用 Context7 实查 Spring Cloud Gateway、APISIX、Kong、Higress、Envoy Gateway、Traefik、KrakenD、Pingora、TensorZero、SGLang Model Gateway、LiteLLM、New API、MLflow、Portkey、vLLM（ShenYu 在 Context7 未收录，用官方 GitHub README 补齐）后的判断：通用网关不理解 token 语义，AI 网关的公共能力是**按 token 计量/限流 + TTFT 可观测 + 模型路由 + 热配置 + 共享状态**。v10 补齐后三项；裸流保真与双数据面单变量 A/B 仍是本项目独有的。

**① AI 可观测**

- `GatewayContext` 增加三个原子时间戳：上游发起、首 chunk、上游结束。热路径每请求只多几次 CAS/`nanoTime`；字符串格式化、Timer 记录、审计富集全在治理虚拟线程旁路完成。
- 指标：`llmgw.ttft`（engine/route，纳秒精度 + p50/p99）、`llmgw.upstream`（engine/route/mode；非流式=完整上游耗时，SSE=上游首字节）、`llmgw.overhead`（总耗时 − 上游耗时）。`/admin/metrics` 直接可查；审计事件新增 `ttftMs` / `upstreamMs` / `gatewayOverheadMs`。
- 响应头：`X-LLMGW-Overhead-Ms`（两模式都有）、`X-LLMGW-Upstream-Ms`（仅非流式）、`X-Request-Id`。**流式为什么没有 Upstream-Ms**：WebFlux 的 `writeWith` 在订阅 body 之前就提交响应头，首 chunk 到达时头已在线上，只有提交前可算的量能进头；完整 TTFT 走指标。VT 引擎虽然能在首帧前改头，为两引擎口径一致也只给 preflight。
- 计时起点从「准入通过后」前移到「请求进入数据面」，`llmgw.latency` 现在包含读 body + 解析（更准确，但与 v8.1 之前口径不同，不能跨版本比较）。
- `/admin/metrics` 的 p50/p99 不再输出 NaN（Prometheus Timer 无客户端分位数时回退 0.0，保证 JSON 合法）。

**② 热配置**

- 新增 `RouteConfigSource` SPI 与不可变 `RouteTable`（routes / byId / fallback / version 一起冻结）。`ConfigRouter` 每次调用只读一个 `AtomicReference`；`Router` SPI 未变，**两个引擎零改动**。
- 默认 `InMemoryRouteConfigSource` 从 `GatewayProperties` 构造；`replace()` 先建表并校验（id 唯一、model 非空、fallback 引用存在、targetUrl 必须 http(s)），再一次 CAS 换引用。在途请求持有已 resolve 的 `Route` 对象，不会被 drain 或重路由。
- `POST /admin/routes/reload` 受 `LLMGW_ADMIN_TOKEN` 保护，永不回显 `apiKey`（只有 `apiKeyConfigured`）；`GET /admin/routes` 增加 `X-LLMGW-Config-Version` 响应头。只读配置源返回 409。
- 边界：热替换只作用于**路由表**；`quota` / `rate` / `cache` / `circuit` 等治理参数仍构造期固化。在途请求的 fallback 链按当前表解析，旧 id 被删则降级为空链（不报错、不中断），这是有意选择的宽松语义。

**③ 分布式状态（opt-in）**

- 新模块 `llmgw-governance/llmgw-governance-redis`，**只有 `-Predis` 才链入 app**；默认 fat jar 经 `unzip -l` 验证不含 `spring-data-redis` / `lettuce` / 本模块。
- `RedisTokenLimiter` / `RedisUsageLedger` / `RedisCircuitBreaker` 各用一条 Lua 脚本完成 read-modify-write，跨副本原子；`tryReserve` 与 v5「含在途预留」语义一致，熔断半开探测用 `SETNX` 租约保证只放一个副本探测。
- 启用：`mvn -B -DskipTests -Predis clean package` + `--gateway.redis.enabled=true --spring.data.redis.port=...`（实验产物 `bench/results/v10-redis.jar`）。
- 代价：每请求 2–4 次 Redis 往返，是**用延迟换一致性**；默认全内存路径完全不受影响，也未做流水线/本地批量合并，不要拿它跟纯透传比 RPS。

**独立回归（不挤占 67/34 断言数）**

| 脚本 | 结果 | 关键证据 |
|---|---|---|
| `bin/smoke-observability.sh` | **30/30**（双引擎，2026-11-08 起；此前 26/26） | 三种指标注册、三个响应头、审计三字段、流式仍收到 `[DONE]`、metrics 无 NaN，新增双引擎各 2 条 `engine` 标签断言（等于当前引擎、无 `engine=unknown`） |
| `bin/smoke-hot-reload.sh`（webflux + vt） | 各 10/10 | 40 路在途 SSE 全部完成且 0 错误；权重 1:3 → 3:1 即时生效（0.250/0.255 → 0.725/0.755）；4 类非法配置返回 400 且版本不变；无 token 401；响应不回显密钥 |
| `bin/smoke-redis-cluster.sh` | 7/7 | 共享 Redis：成功 12 / 429 188 / 账本扣 72；对照两个独立内存账本：成功 20 / 合计扣 120；两实例 `/admin/snapshot` 完全一致；默认 jar 无 Redis 类 |

**口径提醒**：共享配额下最终 `used=72 > quota=60` 不是分布式超卖——准入按 `estimatedTokens` 预扣、结算按实际 `prompt+completion` 记账，内存版同样如此（单实例 10 次成功也扣到 60）。这组实验验证的是「没有两份配额各自花一遍」，不是「最终使用量的硬上限」。

### 5.12 发布前遗留项清零（2026-11-08）

**① `engine` 指标标签修复（既有缺陷）**

`DefaultGovernanceChain` 构造时读取 `System.getProperty("llmgw.engine")` 并永久缓存，
而该属性由引擎 `@Configuration` 构造函数写入——治理自动配置先创建，所有 meter
被打上 `engine=unknown`。修复分两层：

- **根因**：新增 `llmgw-app` 的 `EngineKindEnvironmentPostProcessor`（Spring Boot 4
  的 `org.springframework.boot.EnvironmentPostProcessor`，经
  `META-INF/spring.factories` 注册，`Ordered.LOWEST_PRECEDENCE` 保证在
  ConfigData 解析 `spring.profiles.active` 之后），在**环境准备阶段**（任何
  bean 创建之前）调用 `EngineKind#activate()`；引擎配置类的激活保留为幂等兜底。
  `EngineKind` 新增 `fromProfile()` 反查。
- **防御**：治理链改为首次使用时解析、且只缓存**已解析**的标签，任何未来的
  装配时序变化都不会把 `unknown` 固化到进程生命周期（一次 volatile 读，远便宜
  于它替代的同步 Properties 查询）。

验证：`smoke-observability.sh` 双引擎各 +2 条断言（标签 = 当前引擎、无
`engine=unknown`），26/26 → **30/30**；native 镜像里同样通过（AOT 反射元数据
已收录该处理器）；CI 启动检查扩展为「每引擎一个请求 + `engine` 标签断言」，
防止回归。

**② 两个 native 二进制重编并全量回归**

包名迁移 + engine/governance 两次分包后首次重编（Oracle GraalVM 25，
`bin/build-native.sh webflux|vt`，分别 11:54 / 13:46 min）。回归：
`smoke-stream-errors.sh` 各 **34/34**、`smoke-observability.sh` 各 **15/15**
（脚本新增 `BINARY=` 支持，与 `smoke-stream-errors.sh` 对齐）。

**③ 默认热路径 A/B：三轮补跑仍无有效样本（诚实结论）**

同口径补跑（`v8.1-final.jar` → `v11-final-default.jar`，wire 剖面 0/0、
`PAIRS=3` / `DURATION=15s`、`PAYLOAD_BYTES=0`）三轮，全程 10–15s 负载采样：

| 轮次 | VUS | load1 起点→峰值 | JSON RPS A/B | SSE RPS A/B | 逐轮方向 |
|---|---|---|---|---|---|
| `paired-ab-20261108-004610` | 50 | 4.1 → 145 | 5198 / 4048 | 4349 / 3876 | JSON 3×A、SSE 2×B |
| `-005533` | 50 | 6.8 → 118 | 4743 / 4385 | 3874 / 4054 | JSON 2×A、SSE 3×B |
| `-021716` | 20 | 4.5 → 60 | 5650 / 5627 | 4932 / 4485 | JSON 打平、SSE 3×A |

按数据质量红线三轮均**不作零回归裁决**。核心新发现：这台 16 核宿主上
**压测自身**在 1 分钟内把 load1 推到 100+（50 VU）/ 50+（20 VU），ABBA 交错
无法抵消单调漂移（`pgrep` 确认实验后无残留 java/k6 进程，不是外部污染）。
可确认的是错误率 ≤0.005%、p50 3.0–11.5ms、p99 5.3–36ms、RPS 3589–7795
处于同一数量级，**没有数量级级退化**；第三轮 JSON 打平、SSE 三轮同向 A 占优
（幅度 7–48% 不稳），方向性指向 B 的 SSE 可能存在小幅真实开销——与 v10 在
SSE 每帧新增计时 CAS 的设计一致——需专用空闲宿主复测后才能定论。
产物 `v11-final-default.jar` 与全部 CSV / 负载日志（`v11-load-*.log`）留档。

## 六、诚实的风险清单

| 风险 | 现状 / 预案 |
|---|---|
| **官方 SDK 很重** | `com.openai:openai-java` 4.100.0 的 core jar 约 **71MB**（Kotlin 实现，内部依赖 Jackson 2），fat jar 因此达 127MB、native 代码区占 46MB。它只在校验/连通性路径，可按需从依赖里摘除；native 构建若卡在反射配置，先排除 `llmgw-provider-openai` 再编译 |
| **全内存不持久** | 默认仍是全内存、重启归零、多实例不共享。v10 起 `-Predis` 提供共享配额/限流/熔断，但只覆盖 `TokenLimiter`/`UsageLedger`/`CircuitBreaker` 三个 SPI；`ApiKeyRegistry`/`SemanticCache`/`SnapshotStore` 仍是本地实现，生产还需各自替换为 JDBC/Redis |
| **配额不是最终用量硬上限** | 准入按 `estimatedTokens` 做含在途的原子预留，成功后按实际 `prompt+completion` 结算；并发下最终 `used` 可能略超 `quota`（实测共享版 72/60、内存单实例 60/60）。这是 v5 起两版一致的既有语义，需要硬上限得改成「按估算上限结算」或引入事后补偿 |
| **热配置只覆盖路由表** | `quota`/`rate`/`cache`/`circuit` 仍构造期固化，要热更需给各实现再加 `ConfigSource` 监听；在途请求的 fallback 链按当前表解析，旧 id 删除后降级为空链 |
| **Redis 模式是延迟换一致性** | 每请求 2–4 次 Redis 往返，未做流水线/批量合并；只在需要跨副本配额一致时启用，默认全内存路径零影响，也没有与该模式配对的 RPS 基线 |
| **Native 需按引擎分编** | 已实测编译成功并通过启动验证；AOT 构建期求值 `@Profile`，因此两个引擎各一个二进制，不能一镜像通吃 |
| **压测数据分版本，别混用** | `summary-20261106-072224.csv` / `-072536.csv` / `-075524.csv` 是准入阈值未覆盖时的废数据（gov-on 被 429 打满、error_rate=1），**不要引用**；`-102748.csv` 是 8s/1 轮的 RSS 采样冒烟（native 仍是 v2 二进制），不作结论。`-081741.csv` 是 v1 有效基线，`-092107.csv` 是 v2 正式口径，`-111405.csv` 是 v3 正式口径，`-132434.csv` 是 v4 正式口径，`-155049.csv` 是 **v5 当前正式口径**（上游取消/状态/计量/缓存键/管理面加固）。`-152012.csv` 是并发取消缺陷修复前的单组短跑（SSE `error_rate=16%`），`-152417.csv` 是修复后单组验证冒烟，均不作结论。v4 起 k6 SSE 的 `error_rate` 额外包含「真实 `[DONE]` 行 / 无带内错误」两条 check；v5 起 CSV 新增 `mock_delay_ms` / `mock_chunk_interval_ms` 两列，**0/0 的 wire 实验是独立口径，绝不能与 20/10 主矩阵逐行相减**。引用前先看脚本的 INVALID 告警，且不要把任何两版逐点相减当性能提升 |
| **JSON 场景噪声大于信号** | p50 CV 24.5%、p99 CV 69.8%，单机短时对照受调度/JIT/GC 影响；只能看数量级，不能排引擎座次。要更稳需多机、更长轮次、固定 CPU 亲和 |
| **v1 的 native 尾延迟退化已在 v2 消失** | v1 `webflux + native + governance=on` 的 p99 三轮 73.7/122.4/117.7ms、max 606ms 且吞吐逐轮下滑；v2 同组 p99 稳定在 35.8–36.7ms、max ≤137ms（见 5.3）。推断根因是 `boundedElastic` offload 在 Native 下的排队，但两次实验相隔 1 小时且构建产物不同，**属于强相关推断而非严格因果证明** |
| **v2 已消除隐藏变量** | 原 WebFlux 非流式 `boundedElastic` offload 改为 `UpstreamProvider.completeRawAsync`（`Mono.defer` 保惰性 + `sendAsync` + `Mono.fromFuture(..., false)` 传播取消），fallback 改为惰性 `onErrorResume` 链，异常契约与阻塞路径对齐（统一包成 `IllegalStateException`，否则 `ConnectException` 会绕过 fallback）。5.1 是修复前基线，5.2 是修复后结果 |
| **缓存命中曾双计费（v2 已修）** | WebFlux 在 `.map(cached)` 与 `.flatMap` 各累加一次 completion tokens；已统一到 `flatMap` 一次，实测两引擎同为 `requests=2 / promptTokens=8 / completionTokens=80` |
| **流式上游失败的错误语义（v3 已修）** | v2 实测：WebFlux 流式 500、VT 流式 **200 空响应**（提前 `flushBuffer()` 提交响应，catch 里 `isCommitted()` 为真便吞掉错误），客户端会误判成功；非流式两引擎均正确返回 502。v3：首 chunk 前保持响应未提交 → 失败返回 **502 + JSON**；已发 chunk 后 → SSE `event: error` + `[DONE]` 并记账失败。回归见 `bin/smoke-stream-errors.sh`（v3 时 21 项，v4 扩充为 JVM 双引擎 31 项 + native 各 16 项） |
| **fallback 拼接（v3 已修）** | 流式已发 chunk 后不再切换上游，避免两个上游的 chunk 拼成一段语义错乱的回答；回归脚本用 `tok0` 出现次数验证未拼接 |
| **内存口径是 footprint 而非严格 RSS** | 该沙箱拒绝 `ps -p` / `top`（`operation not permitted`），脚本回落到 macOS `footprint` 的 `phys_footprint`，CSV 用 `rss_source` 标注来源。两者不等价（后者含压缩页），内存数字只作数量级/趋势参考；要严格 RSS 需换无沙箱环境重跑 |
| **静默截断（v4 已修）** | v3 行为：上游干净 EOF 但没有 `[DONE]` 时，VT 的 `onDone()` 会补一帧并记成功，WebFlux 按 `onComplete` 记成功、不补帧——客户端不报错、账单不记失败，上游故障被静默吞掉。v4 统一为「只有上游显式 `data: [DONE]` 才算完成」：首 chunk 前 → **502 + JSON**，已发 chunk 后 → `event: error` + `[DONE]` 并记账失败；网关不再伪造终止帧。新增 `mock-eof-after-0/N` 注入与 4 条回归断言（含账单 `errors>=2`） |
| **native 管理接口 500（v4 已修）** | 实测 `llmgw-webflux` native 的 `/admin/snapshot` 返回 500，根因是 `AuditSink.AuditEvent` 未注册反射，Jackson 3 调 `getRecordComponents()` 抛 `UnsupportedFeatureError`；JVM 与 `/v1` 链路均正常，所以之前的 native 冒烟没发现。v4 在 `GatewayRuntimeHints` 补齐 `/admin/*` 可达 record，并把「快照 200 + usage」写进回归；`/admin/routes`、`/admin/keys` 一并验证 |
| **`[DONE]` 之后才断连（v5 已修）** | 异常分支先判 `doneSeen` / `doneWritten`：协议终止帧已交付就记成功，不补 `event: error`。回归用 `mock-done-then-fail` 覆盖，并断言账单 `errors=0` |
| **上游非 2xx 被当 SSE 数据（v5 已修）** | 旧实现在 `sendAsync.whenComplete` 里查状态，但 body 订阅已先 `onComplete`，错误检查被吞；4xx/5xx 的 JSON 错误体会被当 `data:` 行透传，且 3xx 也当成功。现在在 `BodyHandler.apply(ResponseInfo)` 阶段判定 `>=300`，错误体只进 502 的 reason，并始终关闭 body stream |
| **客户端断连不结算 / 不释放上游（v5 已修）** | Reactor 的 cancel 不是 terminal 信号，旧代码只挂 `doOnComplete`/`doOnError`，取消后账单与审计永久丢失、配额预留泄漏；`Flux.create` 又没有 backpressure 与 cancel 钩子。现在两引擎 `doFinally` 结算，上游 future + body subscription 只在 `onCancel` 时取消，demand 经加锁的 `DemandBridge` 桥接到 `Flow.Subscription`（避免 onRequest/onSubscribe 竞态丢 demand）。回归用 `curl --max-time 0.06` 断言 `requests=1 errors=1` |
| **并发 SSE 被误取消（v5 实测并修复）** | 第一版把取消钩子同时挂 `onDispose`，正常完成也触发，cancel 掉共享 keep-alive 连接上的在途兄弟请求，50 VU 短跑 SSE `error_rate=16%`、响应是 `upstream: Request cancelled`。改为只挂 `onCancel` 后归零；`bin/smoke-stream-errors.sh` 用 40 路并发 120 请求守住这条边界。短跑 CSV `summary-20261106-152012.csv` 是修复前的无效数据，不要引用 |
| **并发配额超卖与计量双算（v5 已修）** | 配额改为含在途请求的原子 CAS 预留；令牌桶按实际 usage 结算并退还失败/取消/缓存命中；usage chunk 覆盖而非叠加 delta 估算；非流式取真实 `usage.completion_tokens`。回归断言两引擎 `1 + 8 = 9` |
| **管理接口泄露上游密钥（v5 已修）** | `/admin/routes` 不再直接序列化 `Route`（其 `apiKey` 是 `${DASHSCOPE_API_KEY}`），改为布尔 `apiKeyConfigured` + 可选 `LLMGW_ADMIN_TOKEN` 网关；`/admin/snapshot/load` 改替换语义；`/admin/verify` 用后关闭 SDK 客户端 |
| **缓存串答案（v5 已修）** | 缓存键改为完整请求体哈希，并校验响应含 `choices` 才入缓存；`mock-echo-hash` 断言同 prompt 不同 temperature 返回不同内容且缓存确实已写入 |
| **仍待办：厂商扩展请求头 / 响应头** | `UpstreamRequest` 目前只带 rawJson/URL/key/model/stream/timeout，`JdkHttpUpstream.build` 只设置 Content-Type/Accept/Authorization。`OpenAI-Beta` / `OpenAI-Organization` / `anthropic-version` / `x-request-id` 等不透传，`x-ratelimit-*` 等响应头也不回写。要做得给 SPI 加 headers 通道并在两个引擎分别提取白名单，属于协议面扩展而非 bug |
| **仍待办：流式空闲读超时 / 字节保真边界** | `HttpRequest.timeout(60s)` 是整体超时，没有 connect / 首字节 / 空闲读三级超时，半开上游仍要等满 60s。`forRoute` 的 model 改写已由正则换成 Jackson 树改写（修了 `$` 注入与误伤 messages 里的 `"model":`），但会重新序列化 JSON —— 只在配置了 `target-model` 时触发，默认压测路径零影响 |
| **token 计量是估算** | 上游没给 `usage` 时才用 `ProtocolCodec.estimateTokens` 启发式估算；未接真实 tokenizer |

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

