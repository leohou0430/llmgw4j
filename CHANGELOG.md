# Changelog

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循语义化版本。
**性能数字的解释口径以 `bench/README.md` 的数据质量红线为准**：宿主过载期间的数据只作方向参考，
不得据此声称性能提升或退化。

## [Unreleased]

> 下方 `[0.1.0]` 历史条目中的模块名按当时结构记录；当前模块结构以
> `README.md` 的「模块布局规范」与 `CONTRIBUTING.md` 第 2 节为准。

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
