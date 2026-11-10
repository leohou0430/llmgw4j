# Changelog

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循语义化版本。
**性能数字的解释口径以 `bench/README.md` 的数据质量红线为准**：宿主过载期间的数据只作方向参考，
不得据此声称性能提升或退化。

## [0.3.0] - 2026-11-10

### Native SPI and modules

- 基础能力使用真实 Dubbo SPI 和显式 Java 构造/实例生命周期，移除 core/provider/治理/tokenizer 的 Spring/Reactor 依赖与自动配置。
- 上游契约改为 JDK Flow/Future；JDK HTTP 独立模块，保留背压、取消和正常完成保护，Reactor/心跳适配留在 WebFlux。
- Provider 统一 api/http-jdk/openai/openai-sdk/anthropic；tokenizer 统一 api/heuristic/jtokkit，配置选择实际作用于准入估算。
- Redis 改为 Jedis 原生池，保留六段原子 Lua；Spring 配置别名仅在 app 兼容，推荐 gateway.redis.*。
- 新增源码/命名扩展/未来平铺实现规则、传递依赖 Enforcer、架构与生命周期测试；AOT 按描述文件注册元数据。
- 内部 API/扩展方式有兼容变化，详见 [迁移说明](docs/EXTENSIONS.md)。完整资产与升级边界见 [v0.3.0 发布说明](docs/releases/v0.3.0.md)。

## [0.2.0] - 2026-11-10

此历史版本的准确范围、兼容变化、资产与限制见 [v0.2.0 发布说明](docs/releases/v0.2.0.md)。

### Fixed

- 修复租户缓存、文件/任务归属与文件引用隔离，未知归属 404，列表能力缺失明确返回 501。
- 修复管理审计凭据缺失的错误状态、只读配置意外开放写接口、生产读写凭据相同；补齐 JWT audience、时间声明与 compact 格式边界。
- 修复 Redis 模型桶参数/退款及 key/model 结算预留，强制审核失败关闭，付费请求未知提交状态不盲目重试。
- 限制全生命周期并发和非流式响应体，阻塞治理移出 WebFlux 事件循环。

### Performance and verification

- SSE 一次截取 payload、WebFlux 直接读取 header 第一值；局部分配下降约 22%–48% 和 32%。
- 64 个 A/B 样本、4,217,770 请求零错误；SSE 中位数有正向线索，范围重叠且 JSON 波动，不认定全面稳定提速。缩小线程池发生一次截断，默认保留 32。
- 117 个 JUnit（含真实 Redis 验证）、双引擎契约及隔离/鉴权回归。统一文档、版本与 tag 发布资产、校验和。历史测量不追溯改写为当前性能。

### v13 能力建设历史（已包含在 0.2.0）

以下保留能力开发时的阶段性记录和测试计数；与当前修复冲突时，以以上发布说明、当前配置和性能报告为准。

> 下方 `[0.1.0]` 历史条目中的模块名按当时结构记录；当前模块结构以
> `README.md` 的「模块布局规范」与 `CONTRIBUTING.md` 第 2 节为准。

### Added — v13 能力补齐（对照主流网关差距分析，见 `docs/gap-analysis.md`）

按四波次补齐主流 LLM 网关能力公约数；每项默认配置保持 v12 行为与性能基线不变，
新能力全部可配置开启。功能证据：`docs/verification/`（最终记录 `final-all-green.log`，
8 个冒烟套件 + 5 个模块单元测试全绿）。

**Wave 1 — P0 快赢**

- 同路由重试 + 指数退避（新 `ForwardingPolicy` 深模块，双数据面尝试循环归一；
  `gateway.upstream.retry.*`，默认关闭即 v12 语义；5xx/408/429/传输错误可重试）。
- 三段式超时：connect / 首字节 / 空闲读（`gateway.upstream.*-timeout-ms`，默认 0=关；
  首字节在 200 提交前失败保住 fallback 窗口；空闲读中止流内静默悬挂）。
- 双向 header 透传（新 `HeaderPolicy` SPI，双向白名单 + 通配；受保护头永不透传）。
- SSE 心跳（`gateway.upstream.heartbeat-interval-ms`，默认 0；comment 帧是网关唯一
  注入字节，计账/TTFT/首块守卫全部豁免）。
- Dockerfile + docker-compose + 快照启动自动恢复（`snapshot-auto-restore`，默认关）。
- 视频 GET 未钉住路径补熔断（钉住路径保持「任务只在创建上游」设计并文档化）。
- JUnit 测试基建（5 个模块；此前全库无 `src/test/`）。

**Wave 2 — 结构性**

- **Protocol SPI**：四处闭式 switch 收编为 `Protocol` 注册表 + 5 个 adapter；
  Spring bean 可覆盖任意协议行为；新协议 = 枚举项 + 一个实现类。
- **ModelCatalog SPI**：模型目录（provider / 上下文窗口 / micro-USD 每百万 token
  价格），`gateway.models[]` 配置驱动，`*` 兜底条目。
- **API key 运行时管理**：`KeyAdminService`（创建/吊销/TTL 过期）+
  `POST/DELETE /admin/keys` + JSON key store（0600，重启恢复）。
- **llmgw-provider-anthropic**：`UpstreamAuthenticator` SPI + `Route.provider`
  路由维度——第一个真上游 adapter（x-api-key + anthropic-version 方言）；
  协议仍是路由维度而非转换维度，字节依旧逐字透传。

**Wave 3 — 平台化**

- **$ 硬预算**：`BudgetLedger` SPI（原子 committed+reserved 准入）+
  `QuotaMath.costMicroUsd`（整数微美元）+ 到额即拒 429；快照按 key 与按组聚合。
- 身份层级：ApiKey `group` 归属 + 组级 spend 汇总。
- 请求日志：`RequestLogSink` SPI（含拒绝路径）+ `GET /admin/requests`。
- **治理参数热更新**：`DynamicGovernanceSettings` CAS 换表驱动限流/缓存/熔断，
  `POST /admin/governance` 运行时换参免重启。
- Redis 覆盖补全：跨副本 key 表 + 跨副本响应缓存（`-Predis`）。
- 最小管理控制台：`GET /admin/console`（单页只读，喂既有 /admin JSON）。
- OTel：traceparent/tracestate/baggage 默认透传上游；完整 span 导出走 `-Potel`。

**Wave 4 — 生产化/差异化**

- **Guardrail SPI**：本地正则拒答 + 外接审核引擎适配器（fail-open），
  认证后、计量前拦截，`gateway.guardrails.*`。
- **语义缓存**：`Embedder` SPI + OpenAI 兼容 /embeddings 适配器 + 分桶向量签名键
  （相似度阈值 = 分桶粒度；embedder 失败降级精确键）。
- **协议面扩张（最终 11 协议）**：`/v1/embeddings`、`/v1/rerank`、`/v1/audio/speech`
  （二进制透传，字节不经 String）、`/v1/audio/transcriptions` 与 `/v1/files`
  （multipart 字节透传，含 boundary 的 Content-Type 原样转发）、`/v1/batches`
  （异步任务，粘性路由）——经 Protocol SPI 每协议零核心改动，治理全覆盖。
  文件上传/批量任务的跨副本轮询由 Redis sticky 支撑。
- 部署文档：`docs/DEPLOYMENT.md`（TLS 立场 / nginx 前置 / 多副本 Redis / 生产清单）。
- **发布链路（作品级 #23）**：GitHub Release workflow（tag → fat jar + native 产物），
  Maven Central 走 `central-publishing-maven-plugin`（secrets 就绪即启用，配置文档化）。
- **任务粘性 SPI（多副本 #21 收尾）**：`TaskRouteSticky` + InMemory 默认实现 +
  `RedisTaskRouteSticky`（SETEX/GET 跨副本），视频/批量/文件任务轮询跨副本可达。
- **性能 A/B 与优化**：v12 基线 worktree 同口径 k6 对比发现 JSON 热路径回归
  （3734→1937/s），定位并修复（mock 探针 join 门控、HeaderPolicy 预编译匹配、
  ForwardingPolicy 单路由快路径、RetryConfig 构造期缓存）后复测：**6107/s、
  p95 13.8ms（基线 3734/s、22.0ms）**，SSE 持平略优（460 vs 457/s），双负载故障率 0%。
  证据：`docs/verification/perf/k6-ab-v12-vs-v13.txt`。

### Added — 20 项剩余差距处理方向落地

对照 [docs/gap-analysis.md](docs/gap-analysis.md) 第二节（代码事实反向验证产出的 20 项）：

**性能打磨（A，3/3）**

- **A1** 语义缓存 embedder 向量缓存（Caffeine 2000 条，TTL 跟随缓存 TTL）——同 prompt 零重复网络调用。
- **A2** Guardrail HTTP 引擎决策缓存（60s / 1000 条）——重复与回放流量零网络调用。
- **A3** 上传体保持 4MB 全量缓冲（流式 multipart 与收益不成比例，P3 排期）。
- **性能 bug 修复**：语义缓存 `lookupCache` 用签名键、`cache()` 用指纹键，两键不一致导致**语义命中永远 miss**——改为「主键恒为指纹 + 向量库独立相似查找层」，实测同义改写命中 `cacheSize=1`。

**产品功能（B，12/12）**

- **B1 入口认证深度**：`KeyStore` SPI + SHA-256 哈希存储（明文不落盘，默认开启）+ key 轮换
  （`POST /admin/keys/{id}/rotate` + overlap 窗口，旧 token 窗口内仍可用）+ `TokenValidator`
  SPI + HS256 `JwtTokenValidator`（签名/exp/nbf/iss 校验，registry miss 回退，7 测试）。
- **B2 真向量语义缓存**：`InMemoryVectorStore`（线性余弦 + 有界淘汰）+
  `gateway.cache.semantic-threshold`；分桶键作为 fallback。
- **B3** 流式响应治理按定位不做（README 写明）。
- **B4 per-model / per-group 限流与预算**：`gateway.model-limits[]`（exact > prefix > `*`，
  独立 token 桶）+ `gateway.group-budgets[]`（组级 $ 硬预算），429 原因分别
  `model rate limit exceeded` / `group spend budget exceeded`；`TokenLimiter` 新增
  速率感知重载。
- **B5 管理面**：`GET /admin/audit/export`（全量审计环）+ 只读 admin token
  （`gateway.admin-read-token` / `LLMGW_ADMIN_READ_TOKEN`，可观测 GET 放行、变更拒绝）。
- **B6 真 tokenizer**：`TokenCounter` SPI + 可选 **llmgw-tokenizer-jtokkit** 模块
  （tiktoken cl100k_base，`-Pjtokkit`），4 测试；启发式默认不变。
- **B7 虚拟 key 元数据**：`metadata` 自由标签贯穿 create / JSON 存储 / Redis / admin 响应。
- **B8 缓存失效**：`DELETE /admin/cache`（Caffeine + Redis SCAN）+ `X-LLMGW-No-Cache: 1` 旁路。
- **B9 Retry-After**：上游头解析 → 同路由重试延迟取 `max(backoff, Retry-After)`，
  四个重试循环全部接入。
- **B10 context-overflow**：6 种溢出消息识别 → 跳过同路由重试直达 fallback；
  完整「自动换大窗口模型」P3 排期。
- **B11 端点补齐**：`GET /v1/models`、`POST /v1/batches/{id}/cancel`、
  `GET /v1/files/{id}/content`——数据面端点 15 → 18。
- **B12 SQLite adapter**：`KeyStore` SPI 已就绪（P3 排期）。

**不做清单（C，3/3）**：README 新增「定位与不做清单」——WS/gRPC 入口与 HTTP/2 上游、
Prompt 管理/SSE 改写、MCP/A2A 与内置 PII 改写，均声明为定位外。

**验证**：10 个 shell 契约套件（378 断言）+ 6 模块 45 个 JUnit 全绿
（`docs/verification/final-all-green.log`）。

### Changed — v13

- `Route` 新增 `provider` 维度（认证方言，非协议转换）；模型匹配升级为
  精确 > 前缀（`claude-*`）> 通配三级特异性（canary/模型族分流的地基）。
- `UpstreamRequest` / `GatewayDecision` / `ApiKey` 记录扩展新字段（均有兼容构造器）。
- Mock 上游新增故障注入标记：`mock-fail-once-status-N`（重试合约）、
  `mock-stall-headers-N` / `mock-stall-mid-N`（三段超时）、`mock-chunk-interval-N`
  （心跳），认证方言/收到 header/语义向量探针端点。
- 新增冒烟套件：`smoke-v13-hardening.sh`（42）、`smoke-v13-provider.sh`（24）、
  `smoke-v13-platform.sh`（34）、`smoke-v13-extensions.sh`（36，含 multipart/files/batches 闭环后）。

### Changed

- Java 包名 `com.leo.llmgw` → **`io.github.leohou0430.llmgw`**，Maven groupId
  `com.leo` → **`io.github.leohou0430`**（52 个 Java 文件、7 个模块 POM、
  `AutoConfiguration.imports`、native 反射提示、回归脚本静态检查同步迁移）。
  模块 artifactId 仍保持 `llmgw-*` 前缀不变。
- 项目展示名与仓库定为 **`llmgw4j`**（`llmgw` = LLM Gateway + `4j` = for Java，
  沿用 log4j / dom4j 命名习惯），目标仓库 `leohou0430/llmgw4j`。
- **数据面独立成子系统**：新增 `llmgw-engine/` 聚合兼父 POM，下挂 `llmgw-engine-api`
  与 `llmgw-engine-webflux` / `llmgw-engine-vt` 两个实现模块。依赖方向
  `core → engine-api → 实现`，实现之间互不依赖。`engine-api` 收敛了原先两个
  Controller 中逐字重复的 8 类契约（引擎标识 / profile 常量 / wire 头 / 错误原因 /
  路由候选 / UpstreamRequest 构造 / SSE 用量计量 / 计时头），共 41 处等价替换，
  **协议时序与控制流未变**。领域核心（`GatewayPipeline` / `UpstreamProvider` /
  `ChunkSink`）仍留在 `llmgw-core`，避免 governance / provider / redis 反向依赖数据面。
- **治理层独立成子系统**：新增 `llmgw-governance/` 聚合兼父 POM，下挂
  `llmgw-governance-api` 与 `llmgw-governance-default` / `llmgw-governance-redis`
  两个实现包。依赖方向 `core → governance-api → 实现`，实现之间互不依赖，
  数据面 POM 不引用任何治理实现包。`governance-api` 收敛了原先两个实现包
  逐字重复的 4 类契约：`GovernanceLimits`（8 个治理配置统一归一化，
  顺带修复 Redis 熔断器 cooldown 未 clamp 的漂移）、`QuotaMath`（配额预留/结算）、
  `UsageSummaries`（DTO 构造与容错数值读回）、`GovernancePacks`（自动装配 FQN /
  Redis 属性 / key 前缀常量）。默认实现包名改为 `...llmgw.governance.inmemory`，
  Redis 保持 `...governance.redis`，避免跨 jar split package；
  `AutoConfiguration.imports` 与 `@AutoConfigureBefore` 同步。领域 SPI 仍留在
  `llmgw-core`（engine / app 共用，搬走会形成环）。原 artifactId
  `llmgw-governance` 现为聚合 POM，默认实现包更名 `llmgw-governance-default`。

### Added

- Apache-2.0 许可证（`LICENSE` / `NOTICE`）与根 POM 的 licenses / developers /
  scm / issueManagement 元数据。
- `.github` 下缺陷报告、功能建议、性能数据三类 issue 表单与 PR 模板；
  性能表单把「宿主负载时间线 + 逐轮一致性 + wire 剖面」做成必填项。
- `CONTRIBUTING.md`、`SECURITY.md`、`CODE_OF_CONDUCT.md`、英文 `README.en.md`。
- `.gitignore` 补充对本地 JDK/GraalVM 工具链、密钥文件与本地运行时数据的忽略；
  新增 `.gitattributes`。
- `bin/check-module-layout.sh`：**自动发现**能力域并校验「聚合层 + api + 多实现」
  布局（api 只依赖 core、实现互不依赖、无 split package、根 POM 只列聚合目录），
  已接入 CI 每次 PR；`CONTRIBUTING.md` 新增模块布局规范，明确后续新扩展模块
  一律采用该模板（新增域无需改校验脚本）。

### Fixed

- **两个 native 二进制已重编并全量回归**（此前为已知限制）：覆盖包名迁移、
  `llmgw-engine/` 与 `llmgw-governance/` 两次子系统分包及 `engine` 标签修复。
  Oracle GraalVM 25（`bin/build-native.sh webflux|vt`），AOT 反射元数据确认
  `EngineKindEnvironmentPostProcessor` 已被收录。回归：两个二进制各
  **34/34**（`smoke-stream-errors.sh`），各 **15/15**（`smoke-observability.sh`
  已支持 `BINARY=`，engine 标签断言在 AOT 镜像里同样通过）。
- **Micrometer 的 `engine` 标签恒为 `unknown`**（既有缺陷，此前已用基线 jar 确认
  非重构引入）：`DefaultGovernanceChain` 在构造时读取
  `System.getProperty("llmgw.engine")` 并永久缓存，而该属性由引擎的
  `@Configuration` 构造函数写入，治理自动配置先创建。现由 `llmgw-app` 的
  `EngineKindEnvironmentPostProcessor`（Spring Boot 4 的
  `org.springframework.boot.EnvironmentPostProcessor`，`META-INF/spring.factories`
  注册，`Ordered.LOWEST_PRECEDENCE` 保证在 ConfigData 解析
  `spring.profiles.active` 之后）在**环境准备阶段**调用 `EngineKind#activate()`；
  引擎配置类的激活保留为幂等兜底。`EngineKind` 新增 `fromProfile()` 反查；
  治理链改为首次使用时解析且**只缓存已解析的标签**，任何未来的装配时序变化
  都不会把 `unknown` 固化到进程生命周期。`bin/smoke-observability.sh` 双引擎
  各新增 2 条断言（标签 = 当前引擎、不得出现 `engine=unknown`），26/26 → **30/30**。

### 验证状态（治理子系统重构后）

- `mvn -B -DskipTests clean package`：**12 个 reactor 项** BUILD SUCCESS；
  `-Predis clean package` 亦通过，随后已恢复默认 jar（不含 `spring-data-redis` / `lettuce`）。
- 双引擎启动冒烟：health 200、JSON/SSE 响应头、1 帧 `[DONE]`、管理面版本头均正常
  （证明改名后的 `AutoConfiguration.imports` FQN 与 `@AutoConfigureBefore` 常量一致）。
- `./bin/check-module-layout.sh`：`PASS=27 FAIL=0`；已做负向测试，能拦住兄弟实现依赖。
- `./bin/smoke-observability.sh`：`PASS=30 FAIL=0`（2026-11-08 起双引擎各 +2 条 `engine` 标签断言）
- `./bin/smoke-hot-reload.sh webflux` / `vt`：各 `PASS=10 FAIL=0`（40 路在途 SSE 零中断）
- `./bin/smoke-redis-cluster.sh`：`PASS=7 FAIL=0`（共享账本 72 < 两个独立账本 120，
  两实例 `/admin/snapshot` 一致；实验前已用新 `-Predis` 产物刷新 `bench/results/v10-redis.jar`）
- `./bin/smoke-stream-errors.sh`：`PASS=67 FAIL=0`
- `dependency:tree`：`governance-api` 只依赖 `llmgw-core`；default / Redis 各只多自己的技术栈；
  engine / app / core **零治理实现 import**。
- **2026-11-08 遗留项清零后全量重验**：`smoke-observability.sh` JVM 30/30、
  native webflux / vt 各 15/15（`BINARY=` 支持，engine 标签断言在 AOT 镜像
  同样通过）；`smoke-stream-errors.sh` JVM 67/67、native 各 34/34；
  `smoke-hot-reload.sh` 双引擎各 10/10；`smoke-redis-cluster.sh` 7/7；
  `check-module-layout.sh` 27/27；新增 `spring.factories` 已确认进入构建输出
  与 AOT 反射元数据；CI 启动检查扩展为「每引擎一个请求 + `engine` 标签断言」。

### 已知限制（发布前请如实保留）
- **默认热路径的端到端零回归裁决仍缺有效样本**：2026-11-07 首轮与
  2026-11-08 三轮 ABBA 配对 A/B 均撞上宿主过载（load1 13–50 / 4→145 /
  6.8→118 / 4.5→60），逐轮方向不一致，前两轮中位数方向互相反转。
  新发现是**压测自身**在这台 16 核宿主上 1 分钟内把 load1 推到 100+
  （50 VU）或 50+（20 VU），ABBA 交错无法抵消单调漂移（`pgrep` 确认
  无残留 java/k6 进程）。按数据质量红线全部判为**无效样本，不作零回归
  结论**，仅留档供复现；可确认的只有错误率 ≤0.005%、p50 3.0–11.5ms、
  p99 5.3–36ms、RPS 3589–7795 处于同一数量级，无数量级级退化；
  第三轮 JSON 两臂打平、SSE 三轮同向 A 占优（幅度 7–48% 不稳），指向
  B 的 SSE 可能存在小幅真实开销（与 v10 每帧计时设计一致），需专用宿主
  复测。详见 `bench/README.md` v10 段与 `paired-ab-20261108-*.csv` /
  `v11-load-*.log`。

## [v12] - 2026-11-08

多协议透传对等：同一套治理层与双数据面，覆盖 OpenAI Chat Completions / OpenAI Responses /
Anthropic Messages / Images / Videos 五种协议。网关不做协议翻译、不重序列化 payload，
每个端点只路由到说同一种协议的上游。

### Added

- **5 个新端点**（双引擎对等，`WebfluxGatewayController` 与
  `VirtualThreadGatewayController` 共用按 `ProtocolKind` 参数化的同一套处理逻辑）：
  `POST /v1/responses`、`POST /v1/messages`、`POST /v1/images/generations`、
  `POST /v1/videos`（create）、`GET /v1/videos/{id}`（poll）、
  `GET /v1/videos/{id}/content`（mp4 下载）。
- **协议抽象**（`llmgw-core/protocol`）：`ProtocolKind`（路径 / wire 名 / 是否流式 /
  终止帧）、按协议的请求解析器（`ResponsesProtocol` / `MessagesProtocol` /
  `ImagesProtocol` / `VideosProtocol`）、按协议的 SSE chunk 解析（`ChunkParser`）与
  完整性判据（`ProtocolCodec.truncatedMessage(kind)`）。
- **协议感知计量与缓存**：completion tokens 按协议从各自 usage 位置取权威值；
  images 按 n 计请求单元、tokens 恒 0 且永不入缓存；videos create 计 1 单元、永不缓存；
  `llmgw.requests` 指标与审计事件新增 `protocol` / `units` 维度（账本聚合键不变，
  配额数学不受影响）。
- **协议路由**：`Route` 新增 `protocol` 字段（缺省 `*` 兼容旧配置），三级优先
  （精确 protocol+model > 通配 protocol+精确 model > 通配 model）。
- **视频异步任务粘性路由**：`StickyTaskRoutes` 把任务 id 钉到创建它的路由
  （TTL 24h、容量 10000，可配 `gateway.videos.*`）；poll / content 零 fallback、
  零熔断——任务只存在于创建它的上游。
- **mock 上游说全部 5 种协议**：各发各的 delta / usage / 终止帧；异步视频任务
  elapsed 状态机（queued → in_progress → completed）+ mp4 内容下载；故障注入标记
  对全部流式协议生效。
- **`bin/smoke-multi-protocol.sh`**：双引擎各 28 项断言（协议路由五路分发、
  跨协议精确计量、每协议截断 / 断连 / 状态注入契约、images/videos 不入缓存、
  视频 create→poll→content、跨实例粘性钉死）。
- **API 契约文档** `docs/openapi.yaml`（5 端点 + 视频轮询/下载 + 错误模型）；
  Apifox MCP 写回接口本次不可用（createEndpoint 被参数校验拦截、importData
  静默零导入），采用预案手动导入：Apifox「项目设置 → 导入数据 →
  OpenAPI/Swagger」选择该文件即可。

### Changed

- 终止帧契约按协议收紧：完成判据 = 各协议自己的终止帧（chat `[DONE]` /
  responses `response.completed` / messages `message_stop`）；截断 502 原因词带
  各自终止帧名；带内错误后的 `[DONE]` 只对 CHAT 追加，网关绝不伪造其他协议的终止帧。
- `ParsedRequest` / `GatewayContext` / `UpstreamRequest` / `Route` 增加 kind /
  units / protocol / path 维度，旧构造器保留兼容委托；bench 微基准同步迁移。

### 验证状态

- `./bin/smoke-multi-protocol.sh`：webflux **28/28**、vt **28/28**
- `./bin/smoke-stream-errors.sh`（chat 全量契约回归）：**67/67** 不变
- `./bin/check-module-layout.sh`：**27/27** 不变
- `mvn -B -DskipTests clean package`：BUILD SUCCESS
- `./bin/smoke-hot-reload.sh`：webflux **10/10**、vt **10/10**——40 路在途 SSE
  跨配置热更零中断，v12 三级协议路由未破坏该行为
- `./bin/smoke-redis-cluster.sh`：**7/7**（`-Predis` 产物重打包含 v12 代码后复跑）——
  双副本共享 Redis 账本 200=12 / 429=188、账本不双花；跑前默认 fat jar 须重建为
  无 Redis 类版本，否则 actuator 健康聚合 Redis 指示器、6379 不可达即 DOWN
- **native 双二进制重编并全量回归**（Oracle GraalVM 25，`bin/build-native.sh
  webflux|vt`；`smoke-multi-protocol.sh` 已支持 `BINARY=`）：webflux / vt 各
  `smoke-stream-errors.sh` **34/34**、`smoke-observability.sh` **15/15**、
  `smoke-multi-protocol.sh` **28/28**——5 端点协议路由 / 计量 / 截断契约在
  AOT 镜像同样成立；GitHub Actions CI（run 42441985260）绿。

## [0.1.0] - 2026-11-07

首个可发布快照。核心能力与设计取舍详见 `README.md` 5.1–5.11 节。

### Added

- **双数据面**：Spring Boot 4.1.2 + JDK 25，WebFlux/Netty 与
  Servlet + 虚拟线程/Tomcat 两套数据面，共用一套 `llmgw-governance` 治理层，
  通过 `--spring.profiles.active=webflux|vt` 单变量切换。
- **全 SPI 治理**：`Router`、`UpstreamProvider`、`TokenLimiter`、`UsageLedger`、
  `CircuitBreaker`、`SemanticCache`、`ApiKeyRegistry`、`AuditSink`、
  `SnapshotStore`、`RouteConfigSource` 均为接口 + `@ConditionalOnMissingBean` 默认实现。
- **协议保真**：`data: [DONE]` 为唯一完成判据，网关不伪造终止帧；
  首 chunk 前失败返回 `502 + JSON`，之后返回 `event: error` + `[DONE]` 并记账失败；
  下游取消只挂 `onCancel`，账单与审计经 `doFinally` 结算。
- **AI 可观测**：`llmgw.ttft` / `llmgw.upstream` / `llmgw.overhead` 指标，
  `X-LLMGW-Overhead-Ms` / `X-LLMGW-Upstream-Ms` / `X-Request-Id` 响应头，
  审计事件新增 `ttftMs` / `upstreamMs` / `gatewayOverheadMs`。
- **路由热配置**：不可变 `RouteTable` + `AtomicReference` 原子替换，
  `POST /admin/routes/reload` 受 `LLMGW_ADMIN_TOKEN` 保护，在途请求零中断，
  `X-LLMGW-Config-Version` 版本头，不回显 `apiKey`。
- **可选分布式状态**：`llmgw-governance-redis` 仅在 `-Predis` 时链入，
  Lua 脚本保证共享限流 / 配额 / 熔断的跨副本原子性；默认 fat jar 零 Redis 依赖。
- **可复现实验**：k6 压测矩阵、ABBA 交错配对 A/B、JMH 微基准、
  JVM 67 项 / native 各 34 项回归，以及三个 v10 独立回归脚本。

### 验证状态

- JVM 双引擎 `smoke-stream-errors.sh`：`PASS=67 FAIL=0`
- `smoke-observability.sh`：`PASS=26 FAIL=0`
- `smoke-hot-reload.sh webflux` / `vt`：各 `PASS=10 FAIL=0`
- `smoke-redis-cluster.sh`：`PASS=7 FAIL=0`
- v9 两项 I/O 优化（kqueue 原生传输、池化 direct buffer）因数据不成立已回退，
  回退后产物与 `v8.1-final.jar` 逐字节一致。
