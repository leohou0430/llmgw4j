# spring-javaformat 引入验证（2026-11-20，已完成）

引入 [spring-javaformat](https://github.com/spring-io/spring-javaformat) `0.0.48` 并对 Maven 模块内全部 Java 源码执行一次性格式化（Tab 缩进、120 列）。本记录只覆盖该引入本身的行为等价性与随之完成的回归验证，不产生任何性能结论。

## 变更与产物摘要

- 根 `pom.xml` 声明 `io.spring.javaformat:spring-javaformat-maven-plugin:0.0.48`，`validate` 目标绑定 validate 阶段，全部 57 个 Maven 模块继承；`mvn spring-javaformat:apply` 供提交前格式化。
- Maven 模块内 539 个 Java 文件全部经格式化器处理，其中 444 个已跟踪文件产生重排 diff（4 空格 → Tab、行宽 120、javadoc `<p>` 换行风格）。`bench/` 下 34 个微基准源文件不在 Maven 构建内，不受约束。
- 文档：`CONTRIBUTING.md` §5 新增格式化约定；`CHANGELOG.md` [Unreleased] 记录。经代码评审后修正：CHANGELOG 的文件计数改为实测口径（模块内 539 个 Java 文件、444 个产生重排 diff），`CONTRIBUTING.md` 对 `-Dspring-javaformat.skip=true` 的措辞收紧为仅限一次性只读构建。

## 行为等价性证据（格式化本身）

格式化器只调整空白。针对「工作区测试失败是否由格式化引入」的怀疑，做了一次二分取证：以 `git worktree` 检出 HEAD（不含任何未提交改动）并对其执行同一 `apply`，在该状态下 `llmgw-governance-default` 全模块 65 项测试通过；而主工作区（= HEAD + 之前轮次行为改动 + 新增文件 + 格式化）同模块存在 1 项失败（见下文 §9.5.2 条目）。失败只能来自 HEAD 与工作区之间的**之前轮次行为修改**（精确 diff 证实：该模块唯一语义差异文件是 `DefaultGovernanceChain.java`，其中含之前轮次新增的主体归属规则），而非格式化——worktree 的 `HEAD+apply` 同样被格式化且测试通过，在该模块上证明该变换未引入行为差异。

注意：初次全树 diff 的检索输出被截断，一度误读为「两树共有文件完全一致」；随后按模块精确 `diff -rq` 证实共有文件间存在上述语义差异。此段按精确结果表述。

## 验证矩阵（本轮会话实际执行）

时序说明：全量 `mvn test` 在 `GovernanceSafetyTest` 修复之后执行；其后会话仅改动冒烟脚本与文档（不触及 Java 源码与测试），该结论对当前工作区仍有效。

| 验证 | 结果 |
|---|---|
| `mvn spring-javaformat:validate`（全 reactor） | EXIT=0，无违规 |
| 全量 `mvn test`（58 模块，含 validate 阶段） | BUILD SUCCESS |
| `bin/check-module-layout.sh` | PASS=219 FAIL=0 |
| `bin/check-native-boundaries.py` | 539 模块文件 / 57 模块 / 37 命名扩展 PASS |
| `python3 -m unittest discover -s bin -p 'test_native_*.py'` | 6 tests OK |
| Shell 冒烟 ×8（双引擎全量口径，`MOCK_PORT=9093`） | stream-errors 67、multi-protocol 56、hardening 46、extensions 54、hot-reload 10、observability 30、platform 50、provider 24 — 共 337 PASS / 0 FAIL |
| Python 冒烟 ×11 | tenant-isolation、jwt-auth、control-plane、heartbeat-deadline、sse-line-limit、conversion-sdk、billing 24/24、billing-recovery 8/8、gemini 18/18（以上双引擎）、admin-auth（vt + read-only/demo 两变体）、sdk-compatibility（官方 SDK venv，vt） |
| `mvn -B -Predis test`（独立实例 6399） | BUILD SUCCESS（RedisConsistency 20 + RedisTokenLimiter 6） |
| `mvn -B -Predis,jtokkit,otel test`（同一独立实例） | BUILD SUCCESS |
| `smoke-redis-recovery.py`（`-Predis` 构建的 jar） | 5 PASS |
| `smoke-redis-cluster.sh`（备份默认 jar + 新 Redis jar） | 7 PASS（含“默认 jar 无 redis 类”边界检查） |
| Native webflux（GraalVM 25，Serial GC，webflux 单引擎口径） | extensions 27、stream-errors 34、hardening 23 — 共 84 PASS / 0 FAIL |
| Native vt（GraalVM 25，Serial GC） | 见下文 vt 段 |

## 随引入一并修复的失败（根因均非格式化引入，已逐一取证）

1. `GovernanceSafetyTest.runtimeMoneyBudgetCannotBypassProductionStartupRestriction`：之前轮次新增的 §9.5.2 主体归属规则（production 下 key 须携带 `llmgw.tenantId/llmgw.projectId` 元数据）先于被测的 production 金额预算限制返回 401。HEAD worktree 交叉验证排除格式化因素后，为测试 key 补归属元数据，测试原意不变。
2. 7 个冒烟脚本的环境隔离/合同缺陷：
   - `smoke-multi-protocol.sh`/`smoke-stream-errors.sh`：start() 未把 `MOCK_PORT` 传给网关（内嵌 mock 绑 application.yml 默认 9090）；补三件套注入并处理 Spring 命令行参数重复 key 逗号合并（非覆盖）的陷阱（GW2 覆盖场景）。
   - `smoke-hot-reload.sh`/`smoke-observability.sh`/`smoke-redis-cluster.sh`：未设 `LLMGW_CONTROL_DB`，读到项目根 `data/llmgw-control.db` 运行时残留（其中 route_version 含 `env:` 凭据引用）导致网关启动失败（`unknown environment credential reference`）；补独立 DB 隔离。
   - `smoke-billing-recovery.py`：补 §9.5.2 归属元数据与 `token_quota` 项目授权（`SqliteUsageLedger` 合同：加 key 不能凭空铸额度）；删除恒真假断言（archive watermark）并更正错标的耗时断言名。
   - `smoke-redis-recovery.py`：补 `LLMGW_ROOT_KEY`（provider secrets 加密必需）且双副本共享同一 key（文件快照跨副本解密）；接入 `LLMGW_TEST_JAR` 约定。
3. 过时合同断言按 v0.4.0 实现更新（均以发布说明为依据）：
   - `smoke-redis-cluster.sh`：v10 合同断言 Redis usage hash 记账；v0.4.0 起控制面 `control-sqlite` 模块在 `redis` 模块之后加载，其 `UsageLedger` replace 为 SQLite 权威账本，Redis hash 不再写入。防超卖语义改为「每副本 charged ≤ 自身 quota + 共享 Redis token bucket 封顶总准入（429 正确触发）」，两副本快照一致性保留。
   - `smoke-redis-recovery.py`：文件/批列表在 v0.4.0 已接入持久目录（501 → 200，且跨副本共享）；旧 `/admin` 默认禁用（key CRUD 断言从显式 501 改为非成功响应拒绝）。

## 环境约束（复现本记录所需）

- 宿主 9090 被外部容器（OrbStack `my-agent-ragent-stub-1`）占用：脚本修复后默认端口仍为 9090，在本宿主上运行依赖内嵌 mock 的 shell 冒烟需带 `MOCK_PORT=9093`（9090 空闲的宿主可不带）；python 冒烟自管随机端口不受影响。
- 项目根 `data/llmgw-control.db` 为运行时残留（git 未跟踪）；控制面不隔离 DB 的启动会读取其中路由版本并因 `env:` 引用解析失败而退出。
- Redis 单测与冒烟使用本次任务独立实例（6399），验证后已 `shutdown nosave` 清理；`smoke-redis-cluster.sh`/`smoke-redis-recovery.py` 自管实例生命周期。

## Native vt 补充段

`build-native.sh vt`（GraalVM 25，Serial GC）构建成功；`BINARY=` 直跑该 AOT 二进制（单引擎口径，webflux native 的对应单项结果一致）：

- stream-errors vt：PASS=34 FAIL=0
- hardening vt：PASS=23 FAIL=0

## 补充修复（评审后追加）

### 旧 `/admin` 创建 key 返回 500

原记录把它列为“已知瑕疵、未修”。追加复现后拿到精确根因并修复：

- **根因**：`POST /admin/keys` 请求体不带 `group` 时，`SqliteApiKeyRegistry.create` 把 `null` 直接交给 `SqliteKeyStore.write`，插入 `key_store.group_name`（`NOT NULL`）触发 `SQLITE_CONSTRAINT_NOTNULL`，异常未捕获 → 500。`admin-token-mode` 默认 `disabled` 且未配置凭据时 `requireAdmin` 按设计放行（local demo mode），因此请求确实到达 create 路径。
- **修复**：`SqliteApiKeyRegistry.create` 将缺失/空白 group 归一化为 `""`（与 config 路径 `ApiKeyDef.group` 默认值、`"" = ungrouped` 语义一致），不改变显式 group 的行为。
- **复验**：同一请求由 500 变为 200，响应 `group:""` 正确持久化；回归 `smoke-admin-auth.py` PASS、`smoke-v13-platform.sh` 50/0、`smoke-billing-recovery.py` 8/8。

### `HeartbeatBackpressureTest` 全量下的时序 flaky

- **现象**：全量 `mvn test` 首次出现 `slowDownstreamDropsHeartbeatTicksInsteadOfFailingTheStream` 失败（`expected: <1> but was: <0>`），单独重跑该测试 2/2 通过。
- **根因**：断言 `assertEquals(1, cancelled.get())` 在 `consumer.cancel()` 返回后同步读取上游取消计数；取消沿心跳 merge 链异步传播，在全量负载下尚未落地（与仓库既有提交 “test: await upstream cancellation across async admission” 修复的是同一类问题；该测试文件为之前轮次改动）。
- **修复**：改为有界轮询（2 秒上限）等待取消计数落地，超时以实际计数失败。
- **复验**：修复后连续两次全量 `mvn test` 均 BUILD SUCCESS（每次模块汇总合计 1230 项测试，0 失败、0 错误）；三项静态检查复跑仍为 PASS=219 / 539 文件边界 / 6 tests OK。

### 管理面登录的启动竞态（发布验证中发现）

- **现象**：`smoke-billing.py` 一次出现 `/control/login` 401，立即重跑 24/24 通过。
- **根因**：首个管理员账号由 `ApplicationRunner` 创建，在 Web 服务器已通过 `/actuator/health` 之后才执行；脚本以健康检查作为就绪信号后立即登录，存在窗口。
- **修复**：`smoke-billing.py` 与 `smoke-control-plane.py` 的登录改为 10 秒有界重试（仅对 401 重试），超时仍失败。
- **复验**：`smoke-billing.py` 24/24、`smoke-control-plane.py` 双引擎 PASS（artifact sha256 `76b1d9ef…`）。

### `LocalRecordingStoreTest` 满队列断言的跨平台 flaky（发布验证中发现）

- **现象**：本地 macOS 通过；Linux CI 首次发布构建失败于 `fullQueueDropsEventsAndDegradesTheOutcome`（`droppedEvents() > 0` 实得 0）。
- **根因**：队列实际容量为 `Math.max(16, capacity)`，测试仅灌 50 条；写入线程使用缓冲写，在 Linux 上可能在生产者循环期间全部排空，导致无丢弃（负载相关）。
- **修复**：突发量提高到 100,000 条，使生产者必然跑赢磁盘写入；终态行本身不会丢失（队列满时同步落盘并降级 INCOMPLETE），断言意图不变。
- **复验**：该测试 6/6 通过；模块格式校验通过。

## 发布执行记录（v0.5.0）

- 提交：`7cda8f8`（发布内容）+ `5380705`（录制满队列 flaky 修复）；tag `v0.5.0` 指向 `5380705`。
- 工作流：`release` run 44653863472 —— build 13m20s、native 15m50s、publish 45s，全部成功。
- 首次 run 44653377289 在 build 阶段失败于 `LocalRecordingStoreTest` 的跨平台 flaky（见上），修复后重打 tag 通过。
- 发布资产：`llmgw-app-0.5.0.jar`、`llmgw-app-0.5.0-redis.jar`、`llmgw-docs-0.5.0.tar.gz`、`llmgw-webflux-0.5.0-linux-amd64`、`SHA256SUMS`。
- 修正：工作流原先只替换了 Redis 变体的源路径，目标名仍为 `llmgw-app-0.4.0-redis.jar`；已修正 `release.yml`，并重命名该资产、重建 `SHA256SUMS`（哈希值不变，仅文件名更正）。

## 未覆盖范围

- vt native 的 extensions 套件与 python 冒烟未以 AOT 二进制重跑（webflux native 已覆盖 extensions/stream-errors/hardening 三套件；本轮无 AOT 相关源码变更）。
- `bench/` 微基准不在 Maven 构建内，未格式化、未验证。
- 补充修复（旧 `/admin` 500、心跳测试 flaky）之后未重新构建 native image：两者都不改变 AOT 可达类型或反射配置；若需以 native 复验这两项，需重建二进制。
- `LLMGW_CONTROL_DB` 注入逻辑在 4 个冒烟脚本中重复，可后续提取共享库；本轮保持就地修复以最小化变更面。
