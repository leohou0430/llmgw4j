# 第 9 章「AI 网关与统一流量治理」设计深度审查报告

- 核查日期：2026-11-20
- 审查基准：《AI Agent Handbook》运行篇第 9 章（含 9.8 实现说明及子页面），基线为页面正文所述 MCP 2026-07-28 版本语义
- 审查范围决策（用户确认）：全章全量对标、发现问题全部修到代码、全矩阵验证、手册原则绝对优先
- 事实来源：docs/ 文档盘点 + 代码盘点（本目录同日记录），关键结论均经源码复核
- 严重级：P0 = 正确性/资损/安全；P1 = 治理语义偏差；P2 = 边界确认与文档

## 一、发现清单

| ID | 章节 | 发现 | 级别 | 状态 |
| --- | --- | --- | --- | --- |
| F1 ✅已修复 | 9.2.6 | 严格预算不变量缺失：预留基于估算而非「最大可计费消耗」，无输出上限约束，重试不单独占额 | P0 | 待修 |
| F2 ✅已修复 | 9.2.6 | 金额预留 UNKNOWN→0：估算未知时金额预留为 0，在途预留不构成任何约束 | P0 | 待修 |
| F3 ✅已修复 | 9.2.4 | fallback 切换无能力兼容检查：fallback 链只按 id 解析，协议/能力不兼容也会切换 | P0 | 待修 |
| F4 ✅已修复 | 9.2.4/9.2.5 | 无跨 attempt 总截止时间：每个 attempt 独享完整 timeout-ms，计划长度是唯一上限 | P0 | 待修 |
| F5 ✅已修复 | 9.2.5 | SSE 无累计字节上限：仅单行 1MiB，整条流可无限消耗内存 | P0 | 待修 |
| F6 ✅已修复 | 9.2.7 | Redis 故障分支未定义：异常冒泡成 5xx，读/写/超时/恢复分支无显式语义与测试 | P0 | 待修 |
| F7 ✅已修复 | 9.5.2 | 主体映射退化：tenant/project 缺失时回退 keyId，账务归属可被 key 元数据缺失隐式改写 | P0 | 待修 |
| F8 ✅已修复 | 9.5.3/9.5.5 | policy_version 缺失；allow 判定不落盘（只有 deny 记录），审计事件无结构化归因字段 | P1 | 待修 |
| F9 ✅已修复 | 9.4.5 | 归因链缺 session/turn 层级：只有 request/attempt/task，无会话与轮次关联，审计与账本均不可下钻 | P1 | 待修 |
| F10 ✅已修复 | 9.2.4/9.5.4 | 客户幂等键未实现：CONTEXT.md 与 ADR-0007 已承诺（同键异请求拒绝、结果未知不重发），代码无 IdempotencyStore | P1 | 待修 |
| F11 ✅已修复 | 9.5.4 | 审批工作流缺失：挂起→批准→重新准入→超时/撤销状态机不存在 | P1 | 待修 |
| F12 ✅已修复 | 9.3 | MCP Gateway 语义整体缺失：无协议代理、无四步准入、无镜像头校验、无工具授权、无 Modern/Legacy 版本声明 | P1 | 待修 |
| F13 ✅已修复 | 9.4 | Agent Gateway 语义部分缺失：入口/执行并发边界无说明、预算压力信号缺失、请求成功率与任务状态未分离统计 | P1 | 待修 |
| F14 ✅已修复 | 9.6.3 | 可观测性缺口：无 gen_ai.* 语义约定、无 usage 来源标记（reported/estimated/unknown）、无流内错误/取消/结果未知指标 | P1 | 待修 |
| F15 →改判P2（边界确认：阻塞 servlet 无静默断开信号，写失败即中止；WebFlux 反应式取消即时——文档化而非代码改动） | 9.2.5 | VT 流式路径客户端静默断开时，上游要等下一次写失败才被中止（取消滞后） | P1 | 待修 |
| F16 ✅已修复 | 9.2.5 | 透传路径不注入 stream_options.include_usage，usage 只能靠上游自发发送（计量盲区） | P1 | 待修 |
| F17 | 9.2.4 | 无主动健康检查 | P2 | 合规确认（被动熔断+半开探针符合手册「冷却或健康检查恢复」，且规避真实探活费用放大） |
| F18 | 9.4.4 | 入口并发与执行并发共用单闸门 | P2 | 边界确认（执行并发属 Runtime 责任，文档声明） |
| F19 | 9.4.7 | 请求成功率与业务 Outcome 边界 | P2 | 合规确认（网关不拥有 Outcome，红线遵守） |
| F20 | 9.5.5 | 审计 ≠ Evidence、Trace ≠ 账本边界 | P2 | 文档确认 |
| F21 ✅已修复 | — | docs/gap-analysis.md（2026-11-10 口径）与 v0.4.0+ 现状冲突 | P2 | 待修 |
| F22 ✅已修复 | — | README「不做清单」（MCP/A2A、财务硬预算）与本次全量补齐决策冲突 | P2 | 待修 |
| F23 ✅已修复 | — | llmgw-app/ 下约 200 个 SQLite 测试残留 .db/-wal/-shm 文件 | P2 | 待修 |

## 二、P0/P1 发现的证据与修复方案

### F1+F2 严格预算不变量缺失（9.2.6）

**证据**：
- `BillableCall.open`（llmgw-engine-api）按 `meter.estimate(frozen, input)` 预留金额，`estimate.quality()==UNKNOWN` 时 `estimateNano=0` —— 未知估算时**预留为 0**，在途预留不构成约束；
- `DefaultGovernanceChain.enter` 用 prompt 估算预留 token 与 micro-USD 预算，输出按「completion 与 prompt 等长」假设，无 max_tokens 约束，不可证明最大消耗；
- `BillingLedger.reserve` 语义为「估算向上取整」，CONTEXT.md 明示「计费可用金额不代表最终欠款的严格上界」——当前产品是**软预算**且从未声明具备严格模式。

**手册要求**（9.2.6）：严格预算必须满足 `可用额度 = 已授权额度 − 已结算消耗 − 在途预留`、`准入条件 = 本次最大可计费消耗 ≤ 可用额度`；预留覆盖输入、**受约束的最大输出**及其他计费项；重试单独占用额度；实际消耗可能超过预留时只能声明软预算。

**修复方案**：
1. 引入 `budgetMode = soft | strict`（租户级开关，soft 为默认，保持 ADR-0007 兼容）。
2. strict 模式下准入预留 = `max(输入估算, 单位下限) + 输出上限`，其中输出上限取请求 `max_tokens`/`max_output_tokens`/`max_completion_tokens` 与模型目录/路由配置 cap 的较小值；**两者均不存在时 strict 拒绝**（可解释错误），绝不静默回软。
3. UNKNOWN 估算不再预留 0：strict 拒绝或按单位上限预留（如 images units × 单价上限）。
4. 重试占额：strict 模式准入预留乘以转发计划最大 attempt 数（`ForwardingPolicy.plan` 大小），仅最终 attempt 按实际结算，其余预留随终态释放——总消耗上界为 `worstCase × attempts`，满足「重试单独占用额度」。
5. token 配额预留同步采用 worst-case（输入估算 + 输出上限）。
6. 未知执行 → `markPendingVerify` 保留预留（已有，回归覆盖）。

### F3 fallback 无能力兼容检查（9.2.4）

**证据**：`ForwardingPolicy.plan` 对 `router.fallbacks(primary)` 只按 id 追加；`RouteDef.upstreamProtocol` 存在但 fallback 解析不校验；转换能力检查（`conversion.prepare`）只覆盖首路由。

**修复**：`plan()` 中过滤 fallback：仅保留「与 primary 的上游协议一致」或「转换域声明支持该协议对」的 fallback；被剔除的 fallback 记录原因进请求日志。路由配置发布时同步校验（llmgw-control 候选校验）。

### F4 无跨 attempt 总截止时间（9.2.4/9.2.5）

**证据**：`GatewayProperties.Upstream` 有 connect/first-byte/idle/整体 timeout，但每个 attempt 各自获得完整预算；`ForwardingPolicy` 无 deadline 概念。

**修复**：
1. 新增 `gateway.forwarding.total-deadline-ms`（0 = 关闭，兼容默认）。
2. 引擎在每次 attempt 前计算剩余预算；耗尽 → 停止计划，返回 504 + 可解释错误（`deadline_exceeded`），不进入下一个 attempt。
3. attempt 的上游超时取 `min(自身配置, 剩余预算)`。
4. 支持客户端头 `X-LLMGW-Deadline`（绝对 epoch-ms，可选；非白名单头不透传，仅作为准入预算输入，缺失时用配置值）——实现「尝试预算跨层传递」。

### F5 SSE 无累计字节上限（9.2.5）

**证据**：`SseLineLimitSubscriber`（单行 1MiB）与 JSON `maxResponseBytes` 存在；SSE 全流无累计上限。

**修复**：新增 `gateway.upstream.max-stream-bytes`（默认 256MiB，可配置，0=off），SSE 累计超限 → cancel 上游 + 带内错误终止；双引擎回归。

### F6 Redis 故障分支未定义（9.2.7）

**证据**：`RedisScripts` 仅捕获 NoScriptException；其余异常沿 `chain.enter` 冒泡成 5xx——事实上 fail-closed，但无声明、无分支区分、无恢复对账测试。

**修复**：
1. 显式 `gateway.redis.failure-policy = fail-closed（默认）| fail-open-limits`：fail-closed 下任何 Redis 读/写/超时 → 503 + `Retry-After: 1`（与容量语义一致）；`fail-open-limits` 仅限「令牌桶读」失败放行（配额/账本类永远 fail-closed）。
2. 每类操作（限流读/配额读/配额写/熔断/粘性路由）的分支行为写入 CONFIGURATION.md。
3. `smoke-redis-recovery.py` 增加故障注入断言（读失败、写失败、超时、恢复后计数对账）。

### F7 主体映射退化（9.5.2）

**证据**：`DefaultGovernanceChain.enter` 与两引擎的 scope 取值均为 `metadata.getOrDefault("llmgw.tenantId", keyId)` / `getOrDefault("llmgw.projectId", keyId)`（含 TODO stage 3）。

**修复**：认证后 key 必须携带 tenant/project 元数据，缺失 → 401 `unattributed_subject` 拒绝；删除 keyId 回退。JWT 虚拟 key 与管理面创建的 key 均已强制归属，仅 legacy JSON key 需迁移校验。

### F8 policy_version 与判定记录（9.5.3/9.5.5）

**修复**：
1. `DynamicGovernanceSettings` 增加单调 `policyVersion`（启动 1，每次热更 +1）；`GatewayDecision` 与 finish 审计携带。
2. 审计事件结构化：`admission.allowed` / `admission.denied` / `request.completed` / `request.failed` 统一字段：`subject_ref`、`tenant/project`、`call_id`（billingCallId）、`attempt_id`、`policy_version`、`decision`、`execution_status`、`usage_status`（settled/pending-verify/released/estimated）。
3. allow 判定落盘（进入审计/requestLog），与 deny 同构。

### F9 session/turn 归因（9.4.5）

**修复**：
1. 接受关联头 `X-LLMGW-Session-Id`、`X-LLMGW-Turn-Id`（及既有 task 语义）；进入 `GatewayContext`。
2. 关联头绑定已认证主体写入审计/请求日志/账本元数据——session/turn 不参与准入与配额（权威来源是执行系统），伪造仅能污染自己主体的关联记录，无法跨主体。
3. 缺失关联 → `attribution: unattributed`，不编造。
4. 归因链 `task_id → session_id → turn_id → call_id → attempt_id` 完整写入审计事件。

### F10 客户幂等键（CONTEXT.md/ADR-0007 已承诺）

**修复**：新能力域 `llmgw-idempotency/`（api + sqlite）：
- 数据面 chat/messages/responses 接受 `Idempotency-Key`（可选头）；
- 请求摘要 = 方法+路径+模型+参数（非仅正文）；同键同摘要且终态 → 返回原 `billingCallId`/`X-Request-Id`（流式不重放 SSE，仅返回原调用标识）；同键异摘要 → 409 `idempotent_request_exists`；
- TTL 24h（可配置），执行中不失效；结果未知（pendingVerify）不自动重发；key 吊销后不能借幂等键绕过授权（命中时先过认证）。

### F11 审批工作流（9.5.4）

**修复**：新能力域 `llmgw-approval/`（api + sqlite）：
- 状态机 `pending → approved | rejected | expired | revoked`；执行结果未知单独记 `execution_unknown`；
- 审批记录绑定：subject、目标（MCP server/tool 或模型操作）、工具版本、参数摘要（SHA-256）、风险标签、有效期、幂等键；
- HTTP 不长挂：创建审批即返回 202 + `approval_id` 与查询路径；等待与通知由业务侧承接；
- 批准 ≠ 放行：执行前重新准入（认证、委托、参数摘要一致、预算重预留）；重复回调防重放（幂等键约束）；
- 超时/撤销 → 终止，不降级为只读，不隐式替换工具/参数；
- 控制面 API：list/approve/reject（RBAC `APPROVAL_DECIDE`，审计原因必填）。
- 首版触发点：MCP 高风险工具策略（`approval: required`）；数据面模型调用不强制接入。

### F12 MCP Gateway 语义（9.3）

**修复**：新能力域 `llmgw-mcp/`（parent + api + default-proxy），遵循 AGENTS.md 模块规则（enforcer.skip=false）：
- 端点：`/mcp`（Modern，MCP 2026-07-28 streamable HTTP）+ `/mcp/legacy`（Legacy initialize 握手）；支持的版本集合显式声明于配置与文档。
- 四步准入（9.3.3）：认证（复用 API key/JWT，映射稳定主体）→ 协议校验（`params._meta` protocolVersion 必填、`MCP-Protocol-Version` 头镜像一致、`Mcp-Method` 头与 JSON-RPC method 一致、`Mcp-Name` 仅对 tools/call、resources/read、prompts/get 校验镜像 params.name/uri；报文大小限制；Content-Type/Accept 校验；Origin 可配置校验）→ 授权（**默认拒绝**；`subject × server × tool` ACL；`tools/list` 按 ACL 过滤、`tools/call` 再次鉴权；schema 校验只是类型合规，不替代授权）→ 转发（按上游 RPC 重构出口请求；剥离 Cookie/会话标识/内部路由头；上游凭证仅来自路由 secret 注入，不透传下游凭证）。
- 桥接：Modern 下游 → Legacy 上游（initialize 隔离在单次交换内）；Legacy 下游 → Modern 上游显式不支持（4xx 声明）。
- 订阅与 Tasks 扩展默认关闭，显式配置开启。
- 出口目标只能来自路由配置（模板不承载可变主机 → 无 SSRF 面）。
- 归因/计量：每次工具调用记 call_id/attempt_id；`tool_calls`（模型意图）与工具实际执行结果用可区分事件类型；审批策略 `approval: required` 的高风险工具挂起进 F11 工作流。
- 健康：连接可达（转发失败自然观测）/协议发现（server/discover 透传）/业务探测不做（避免副作用探测）。

### F13 Agent Gateway 语义（9.4）

**修复**：
1. F9 的 session/turn 归因落地；异步任务（videos/batches/files）已绑定持久 billingCallId（回归确认）。
2. 指标分离：`llmgw.tasks{state=pending|confirmed|unknown}`（来自任务目录），请求成功率（llmgw.requests）与任务状态分开统计；文档声明网关不拥有 Outcome。
3. 预算压力信号：402/429 响应附 `X-LLMGW-Budget-Pressure: soft|hard` 提示头（soft=接近阈值告警语义，Harness 决策）。
4. 入口并发 vs 执行并发边界写入 README/CONFIGURATION（执行并发属 Runtime 账本）。

### F14 可观测性（9.6.3）

**修复**：
1. OTel profile（`-Potel`）span 增加生成式 AI 语义约定属性，版本固定声明（`gen_ai.system`、`gen_ai.request.model`、`gen_ai.usage.input_tokens`、`gen_ai.usage.output_tokens`、`gen_ai.response.finish_reasons`），并在文档标注所用约定版本与 Development 状态。
2. 指标增加 `usage_source` 维度：`reported | estimated | unknown`（依据 `reportedPromptTokensRaw()/reportedCompletionTokensRaw()` 与挂账状态），基数受控（无用户输入入 tag）。
3. 新增 `llmgw.stream{result=completed|in-band-error|truncated|client-cancelled|unknown}` 计数（双引擎）。
4. TTFT 语义已在文档声明（首个数据 chunk ≠ HTTP 首字节），回归保留。

### F15 VT 客户端静默断开中止滞后（9.2.5）

**修复**：`StreamSink` 注册 servlet 异步 `onError/onTimeout` 回调 → 立即 `abortTransport`（不等下一次写失败）；客户端取消后上游已提交的用量照旧走 `billOnStreamFailure` 挂账（不因取消视为免费）。

### F16 include_usage 注入（9.2.5）

**修复**：路由级配置 `streamUsageInjection = never（默认）| auto`；`auto` 仅对「上游协议 = openai-chat 且请求 stream 且请求未含 stream_options」注入 `include_usage=true`。默认 never 保持字节保真合同；开启即文档声明「该路由不再字节对等」（ADR-0008 同类显式声明模式）。

## 三、明确不改的边界（合规确认）

1. **无主动健康检查**（F17）：被动熔断 + 半开探针即手册允许的恢复路径，且避免「真实模型探活产生费用、多副本重复放大」——有意选择，写入文档。
2. **网关不拥有 Outcome**（F19）：手册红线，现有实现未越界；任务目录状态 ≠ 业务成功标准。
3. **审计 ≠ Evidence**（F20）：正文记录与审计提供材料，业务判定仍属应用/Verifier——文档确认。
4. **入口并发即网关职责**（F18）：执行并发槽位属 Runtime；网关不假装判断执行槽释放。
5. **X-Request-Id 不是幂等键**：CONTEXT.md 已有定义，保持。

## 四、验证口径（用户确认：全矩阵）

- `bash bin/check-module-layout.sh`、`python3 bin/check-native-boundaries.py`、`python3 -m unittest discover -s bin -p 'test_native_*.py'`
- 默认 profile 双引擎（vt/webflux）流式、扩展、加固、租户隔离、管理、JWT 契约
- Redis 独立实例 profile（含新故障注入断言）+ jtokkit/otel profile
- AOT 变更：native 验证（新增模块含 reflection 配置则必须）
- 新增覆盖：严格预算不变量（并发准入不超授权、重试占额、UNKNOWN 拒绝）、幂等键（同键同/异请求、吊销后）、审批状态机、MCP 四步准入与镜像头、deadline 预算、SSE 累计上限
- 验证产物摘要记入本目录 README（实际产物、场景、未覆盖范围），不改写历史测量

## 五、术语与决策记录

- 新增术语（CONTEXT.md）：会话标识（Session Id）、轮次标识（Turn Id）、审批记录、严格预算、预算模式（预算模式 soft/strict）、MCP 工具授权、关联头绑定、用量来源（usage_source）
- 新增 ADR：0009 严格预算模式（有界预留）、0010 MCP 网关语义首版（Modern 下游 + Legacy 上游桥接单向）、0011 审批工作流（挂起-重新准入）
