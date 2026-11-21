# 2026-11-20 第 9 章治理审查轮 · 验证产物摘要

本轮修复按「全章全量对标、全部修到代码、全矩阵验证、手册绝对优先」口径执行（决策记录见对话与 report.md）。本摘要记录实际产物、场景与未覆盖范围；不复制历史测量作为本轮证据。

## 一、单元与模块级

| 检查 | 产物 | 结果 |
| --- | --- | --- |
| `bin/check-module-layout.sh` | 219 项断言（新增 mcp/idempotency/approval 域后） | PASS=219 FAIL=0 |
| `bin/check-native-boundaries.py` | 539 文件 / 57 模块 / 24 descriptors / 37 named extensions | PASS |
| `python3 -m unittest discover -s bin -p 'test_native_*.py'` | 6 tests | OK |
| llmgw-engine（api/vt/webflux）`mvn test` | 115 tests（含 StreamDisconnectTest 适配新签名） | 0 失败 |
| llmgw-idempotency（api+sqlite）`mvn test` | 12 tests（FRESH/重入/MISMATCH/scope 隔离/unknown 吸收/TTL/推进令牌/并发单 FRESH/重启持久） | 0 失败 |
| llmgw-approval（api+sqlite）`mvn test` | 8 tests（CAS 单终态/重校验/过期不降级/撤销/重启持久） | 0 失败 |
| llmgw-mcp（api+default）`mvn test` | 19 tests（镜像矩阵/默认拒绝/工具过滤/call 再鉴权/审批挂起/Legacy 单次交换桥接/大小与 Origin 边界/SSE 提取） | 0 失败 |
| llmgw-app 全模块编译 | — | 通过 |

## 二、默认 profile 双引擎回归（jar: llmgw-app-0.4.0.jar，本轮构建）

| 套件 | 结果 |
| --- | --- |
| smoke-stream-errors.sh（流式错误合同，双引擎） | PASS=67 FAIL=0 |
| smoke-multi-protocol.sh | PASS=56 FAIL=0 |
| smoke-v13-extensions.sh | PASS=54 FAIL=0 |
| smoke-v13-hardening.sh | PASS=46 FAIL=0 |
| smoke-v13-platform.sh | PASS=50 FAIL=0（修复一次：严格主体映射不再拦截演示级 budgetMicroUsd——生产/持久账本路径仍强制） |
| smoke-v13-provider.sh | PASS=24 FAIL=0 |
| smoke-tenant-isolation.py | PASS（双引擎） |
| smoke-admin-auth.py | PASS（protected/read-only/demo） |
| smoke-jwt-auth.py | PASS（双引擎） |
| smoke-heartbeat-deadline.py | PASS（双引擎） |
| smoke-sse-line-limit.py | PASS（双引擎） |
| smoke-hot-reload.sh | PASS=10 FAIL=0 |
| smoke-observability.sh | PASS=30 FAIL=0 |
| smoke-control-plane.py | PASS（webflux+vt，10 场景含审计回滚/重启恢复） |
| smoke-billing.py | PASS=24/24（场景配置按新合同补 key 归属元数据与项目额度授权种子——见「合同演进」） |
| smoke-gemini.py | PASS=18/18（同上） |
| smoke-conversion-sdk.py / smoke-sdk-compatibility.py（vt） | PASS |

### 本轮合同演进导致的冒烟配置更新（不改产品语义）

1. `smoke-billing.py` / `smoke-gemini.py`：静态 key 补 `llmgw.tenantId/llmgw.projectId` 元数据 + 种子项目额度授权（`token_quota`）。依据 Chapter-9 §9.5.2：billing.enabled 下认证主体必须携带财务归属；SqliteUsageLedger 的既有规则「加 key 不能凭空铸项目额度」要求显式授权。
2. `smoke-v13-platform.sh` 无改动——产品修正：演示级 `budgetMicroUsd`（生产本就禁用）不触发归属强制，`gateway.billing.enabled` 与 production 才强制。

## 三、Redis profile（2026-11-21 补跑收口，基于 v0.5.0 jar）

- 根因结论（昨日记录的启动失败）：`smoke-redis-cluster.sh` mem pair 失败 = **仓库 `./data/llmgw-control.db` 的陈旧路由版本跨运行激活**（副本控制库落在 CWD 相对路径）+ Spring binder 的命令行 indexed 路由**整体替换** yml 列表（文档行为）→ 持久版本里的 `env:mock-primary` 引用在当前绑定下无对应路由。v0.5.0 已在冒烟中引入独立 `LLMGW_CONTROL_DB`，启动回归随之消除；`ControlRuntimeModule` 的错误消息已增强为携带待解析 id 与已知路由清单（可操作诊断）。
- `smoke-redis-cluster.sh`：**PASS=7 FAIL=0**（mem/redis 双 pair 对比：共享配额 200=56、429=144、双副本快照一致、无副本超额度）。
- `smoke-redis-recovery.py`（带 LLMGW_ROOT_KEY）：**5 项 PASS**，含「**Redis outage: both replicas fail closed with no upstream calls**」——本轮新增的 fail-closed 合同（`gateway.redis.failure-policy` 默认 + `GovernanceUnavailableException → 503+Retry-After`）首次获得双副本端到端故障注入验证。
- recovery 断言按 v0.5.0 装配语义对齐（`bin/smoke-redis-recovery.py` 本轮修改）：文件/批次列表 200 共享（v0.4.0 的 501 不可用合同已被实现取代）；key 权威断言升级为「共享 SQLite 控制库——经 legacy /admin CRUD 创建的运行时 key 在双副本均可认证，key 状态永不落 Redis」。

## 四、jtokkit / otel profile

- `TokenizerSelectionTest`（`-Pjtokkit`）：PASS。
- `smoke-conversion-sdk.py` / `smoke-sdk-compatibility.py`：PASS（vt）。
- `-Potel` 构建：网关健康、数据面 200、`GenAiSpanBridge` 装配无错（gen_ai.* 属性随 server observation 导出；WebFlux 异步 finish 侧车无当前 observation 时降级为指标维度——声明未覆盖）。

## 五、native（AOT，本轮构建 GraalVM 25）

- `bin/build-native.sh webflux` / `bin/build-native.sh vt`：双引擎二进制构建成功（`bin/native/llmgw-webflux`、`bin/native/llmgw-vt`，各约 250MB）。
- native 运行冒烟（直接对二进制）：
  - webflux：health 200；chat 200；`Idempotency-Key` 重复 → **409**（幂等键合同在 AOT 下生效）；SSE 流式 200；`smoke-conversion-sdk.py --binary --engine webflux` PASS（含 thinking/image/cancel-recover/截断检测）。
  - vt：health 200；SSE 流式 200；`smoke-sdk-compatibility.py --binary --engine vt` PASS（双客户端 JSON+SSE、401 合同）。
  - 启动耗时：native ~1.2s（对比 JVM ~13s，仅现场观察，不作 SLO 声明）。

## 六、未覆盖范围（2026-11-21 更新）

1. ~~Redis 双副本端到端~~ → **已收口**（见三：cluster 7/0、recovery 5 PASS，含 fail-closed 端到端故障注入）。
2. ~~`smoke-billing-recovery.py` 恢复演练~~ → **已收口**（2026-11-21：8/8 PASS——RTO 7.6s（预算 4h）、备份跨存储域、篡改拒绝（digest mismatch 闸门关）、恢复余额一致）。
3. WebFlux 异步路径的 gen_ai span 属性（sidecar 线程无当前 observation，降级为指标维度）——边界声明，非缺陷。
4. MCP 订阅/Tasks 扩展、服务端主动流式（ADR-0010 显式声明默认关闭/首版不含）。
5. 性能基准（bench-*）未重跑——本轮热路径新增仅 fail-closed 包装与一次性令牌校验，未做测量，不做性能声明。

## 八、v0.5.0 终态抽查（2026-11-21，发布提交后的复核）

- `smoke-billing.py`：24/24；`smoke-stream-errors.sh`：67/0（javaformat 强制重排 + WebFlux MCP 绑定修复提交后无回归）。
- `llmgw-mcp` 单测：全绿（提交 8347ed6 修订 `McpGatewayController` 装配后复核）。
- **F24 子页面审读修复**（heartbeat/空行刷新 idle-read + 错误体语义保留）：`smoke-heartbeat-deadline.py` 双引擎 PASS（新增 heartbeat-stall 场景：纯心跳流 ~300ms 触发 idle，早于整体期限与 EOF）；回归 `smoke-stream-errors.sh` 67/0、`smoke-sse-line-limit.py` 双引擎 PASS、`smoke-multi-protocol.sh` 56/0。
- **F25 servedModel 对账契约**（2026-11-21）：`GatewayContext.servedModel` + `EngineRoutes.servedModel(route, requested)`，四引擎成功提交点无条件记录（与计费句柄无关）；finish 审计 `servedModel` 与客户别名 `model` 分别可读；单测 `ServedModelAuditTest`（重写记录 + 缓存命中不产字段）；端到端 target-model 重写场景 model=claude-alias / servedModel=deepseek-chat-rewritten；回归 billing 24/24、gemini 18/18、stream-errors 67/0。

## 七、产物清单

- `llmgw-app/target/llmgw-app-0.4.0.jar`（默认，本轮构建，无 redis 类）
- `llmgw-app/target/llmgw-app-0.4.0-redis.jar`（-Predis，本轮构建）
- `llmgw-app/target/llmgw-app-0.4.0-otel.jar`（-Potel，本轮构建）
- `llmgw-app/target/llmgw-app-0.4.0-{webflux,vt}` native 二进制（本轮构建）
