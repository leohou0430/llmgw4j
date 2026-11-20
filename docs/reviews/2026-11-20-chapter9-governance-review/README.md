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

## 三、Redis profile

- Redis 双副本共享配额契约（`smoke-redis-cluster.sh`）与跨副本故障恢复（`smoke-redis-recovery.py`）：**本轮未能收口——既有 WIP 回归，非本轮引入**。
  - 证据 A：`git stash`（含未跟踪）后以 HEAD 构建的 jar 复现同一启动失败 `unknown environment credential reference`（ControlRuntimeModule 路由环境凭据引导，与本轮改动无关；本轮 stash 前后均失败）。
  - 证据 B：`smoke-redis-recovery.py` 断言 `GET /v1/files → 501`（v0.4.0 的显式不可用合同），而 WIP 已实现租户列表 → 断言过期；冻结 v10 jar 与现行 schema 不兼容无法对照。
  - 本轮新增的 Redis 故障分支（`gateway.redis.failure-policy` fail-closed/fail-open-limits、`GovernanceUnavailableException → 503+Retry-After`）已由单元级接线验证；**双副本端到端故障注入回归未覆盖**（待 WIP 启动回归修复后补跑）。
- Redis 模式 jar 可启动且健康（手工验证）；`bin/check-module-layout.sh` 含 mcp/idempotency/approval 后仍 PASS。

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

## 六、未覆盖范围（如实声明）

1. Redis 双副本端到端（见三，既有 WIP 回归）。
2. `smoke-billing-recovery.py`（RPO≤1h 恢复演练）本轮未重跑（属 v0.5 计费任务 9 范围，本轮未触碰归档/恢复代码路径）。
3. WebFlux 异步路径的 gen_ai span 属性（sidecar 线程无当前 observation，降级为指标）。
4. MCP 订阅/Tasks 扩展、服务端主动流式（ADR-0010 显式声明默认关闭/首版不含）。
5. 性能基准（bench-*）未重跑——本轮热路径新增仅 fail-closed 包装与一次性令牌校验，未做测量，不做性能声明。

## 七、产物清单

- `llmgw-app/target/llmgw-app-0.4.0.jar`（默认，本轮构建，无 redis 类）
- `llmgw-app/target/llmgw-app-0.4.0-redis.jar`（-Predis，本轮构建）
- `llmgw-app/target/llmgw-app-0.4.0-otel.jar`（-Potel，本轮构建）
- `llmgw-app/target/llmgw-app-0.4.0-{webflux,vt}` native 二进制（本轮构建）
