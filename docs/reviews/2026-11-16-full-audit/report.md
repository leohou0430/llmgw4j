# 全盘审查：团队试点交付判定

日期：2026-11-16。对象：llmgw4j 当前工作区，包含审查开始前已有的未提交修改。按内部 Java 团队、单副本 Java＋SQLite、整体 2CPU/2GiB 的试点目标审查；不是逐文件形式检查，也不把组件测试数量视为产品验收。

**结论：暂不放行团队试点。** 原生 SPI、模块边界和已有数据面契约有可保留的基础，但身份、配置、资源和账本没有形成统一运行链路。已复现跨项目可见、管理操作不生效、跨请求事务回滚和重启丢用量。

本轮只增加审查报告与证据，不修改业务代码，不提交、推送或发布。未使用真实供应商凭据，所有请求针对本机 mock；数据库故障注入、账号角色夹具和进程强杀仅作用于本次独立测试实例。


审查产物截面（源码持续被并行修改，不是最终工作区全量签字）：最新固定 JAR 的 SHA-256 为 `3d0ca2be37265776d02e5e34035567dd807166ac03b0cc7fc98a9b2ceac7c980`。审查期间其他工作持续更新文件，具体摘要以 verification.json 为准；之后新增的磁盘安全余量配置尚未纳入该 JAR，且未改变主文件容量检查遗漏 WAL 的判断。

## 验收合同

- 所有开放功能满足权限隔离与操作闭环；未完成功能可明确禁用。
- SQLite 已发布版本是路由唯一权威；发布、回滚和重启恢复必须改变实际数据面。
- 普通 key 吊销拒绝新请求，在途请求继续完成并结算。
- token 配额跨重启有效；项目共享总额、key 可有独立上限，两级原子预留；未知提交保守占额。
- 累计额度仅管理员显式调整并审计，不重置在途预留；新增或轮换 key 不增加项目额度。
- 结构问题按实际风险分级，不因代码长度或重复数量要求重写。

持久化项目/key 两级配额是本次访谈新增的明确要求，下面标为需求缺口，不误称为旧功能回归。未要求金额硬预算、企业 SSO 或跨副本控制面。

## 必须修复或明确禁用

### 1. [P1] 授权范围与实际查询脱节，项目账号能读取其他项目的数据

**复现：** 隔离库中建立 t1/p1、t2/p2，t1/p1 viewer 的租户/项目列表返回两边全部记录；将该测试账号角色改为 PROJECT_ADMIN 后，key 列表包含 key-p1 和 key-p2。viewer 的 usage 也返回全局 key 用量。两引擎均复现。角色更改仅是本次隔离库的测试夹具，不是利用入口。

**原因：** 多个接口固定用 t1/p1 授权，却读取全库。ManagedKeyService 只检查传入 target，不保存 key 的项目归属，list 直接遍历全部存储。相反，正常 t2/p2 账号登录后登出返回 403，因为登出也固定检查 t1/p1。目录、审计等同样被固定项目限制。

**建议：** 统一解析真实主体与目标 scope，并让数据查询本身带租户/项目条件；key 归属必须持久化。登出只依赖当前会话与 CSRF。补两租户、每租户两项目、同项目两 key 的交叉访问矩阵。

**影响：** 隔离边界不成立，新项目也无法正常使用控制面。定位：[ControlOrgController.java:44](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-app/src/main/java/io/github/leohou0430/llmgw/app/ControlOrgController.java:44)、[ManagedKeyService.java:44](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-identity/llmgw-identity-default/src/main/java/io/github/leohou0430/llmgw/identity/ManagedKeyService.java:44)、[ControlUsageController.java:46](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-app/src/main/java/io/github/leohou0430/llmgw/app/ControlUsageController.java:46)、[ControlLoginController.java:84](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-app/src/main/java/io/github/leohou0430/llmgw/app/ControlLoginController.java:84)。

### 2. [P1] 控制面新建 key 不被数据面接受，生命周期没有闭环

**复现：** /control/keys 创建成功并返回一次性凭据；该凭据调用 /v1/chat/completions 返回 401，同进程配置 key 的同一请求返回 200。两引擎一致。

**原因：** ManagedKeyService 写独立 SQLite KeyStore，数据面原生图仍使用 ManagedApiKeyRegistry＋JsonKeyStore，没有共享注册表或同步协议。新控制面 HTTP 只有创建/列表，吊销/轮换仍没有对应入口；旧 admin 生命周期属于另一注册表。

**建议：** 在 seal 前显式组装一个共享持久凭据服务，管理面与数据面使用同一能力；补创建→真实调用→轮换→吊销→重启测试，普通吊销遵守在途保留语义。

**影响：** 管理页发出的 key 不能使用，实际运行 key 又不受新控制面完整管理。定位：[ControlPlaneManagementConfiguration.java:69](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-app/src/main/java/io/github/leohou0430/llmgw/app/ControlPlaneManagementConfiguration.java:69)、[DefaultGovernanceModule.java:25](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-governance/llmgw-governance-default/src/main/java/io/github/leohou0430/llmgw/governance/inmemory/DefaultGovernanceModule.java:25)。

### 3. [P1] 路由发布接受任意字符串，报告激活成功却不改变实际路由

**复现：** config 传 `not even JSON`，省略可选 upstream，publish 返回 version=1、activeVersion=1；/control/status 仍报 activeRouteVersion=0，实际路由仍是 audit-mock。两引擎一致。

**原因：** activate 只给自身字段赋值，没有接入 RouteConfigSource；校验只针对可选 upstream 对象，并不解析或验证 config，也不保证候选与配置相同。状态控制器自行 new 了另一套内存版本服务。

**建议：** 同一结构化配置贯穿解析、能力/扩展名/地址校验、持久发布与数据面激活；status 注入真实服务，删除假状态对象。用实际转发目的地验证发布、回滚和重启恢复，不只断言版本号。

**影响：** 管理员以为已切换或回滚，流量仍走旧配置。定位：[ControlRouteController.java:65](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-app/src/main/java/io/github/leohou0430/llmgw/app/ControlRouteController.java:65)、[RouteVersionService.java:66](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-control/llmgw-control-default/src/main/java/io/github/leohou0430/llmgw/control/RouteVersionService.java:66)、[ControlPlaneController.java:34](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-app/src/main/java/io/github/leohou0430/llmgw/app/ControlPlaneController.java:34)。

### 4. [P1] 发布请求共享全局可变草稿，交错执行会发布别人的内容

**复现：** A.saveDraft(A) → B.saveDraft(B) → A.publish(0)，结果为 request-B。控制器确实分两步调用，各方法分别 synchronized 不能保护整个发布操作。

**原因：** 共享 draft、activeVersion 和 route_version 表没有请求/项目归属。HTTP 对 body 的项目授权后，仍写同一个全局版本服务。SqliteRouteVersionStore 使用 INSERT OR REPLACE，也没有追加不可变版本的保护。

**建议：** 发布原子接收候选内容及 expectedVersion；全局配置只能由平台角色发布，项目配置则真正按 scope 分区。版本重复必须冲突，不能覆盖历史。

**影响：** 并发发布内容互串；接通数据面后，还可能把项目权限扩大为全局配置修改。定位：[ControlRouteController.java:73](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-app/src/main/java/io/github/leohou0430/llmgw/app/ControlRouteController.java:73)、[RouteVersionService.java:19](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-control/llmgw-control-default/src/main/java/io/github/leohou0430/llmgw/control/RouteVersionService.java:19)、[SqliteRouteVersionStore.java:62](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-app/src/main/java/io/github/leohou0430/llmgw/app/SqliteRouteVersionStore.java:62)。

### 5. [P1] 一个请求的数据库回滚可撤销另一个已成功返回的写入

**复现：** 真实 SqliteKeyStore 开始事务，在 INSERT 处注入可控 SQLException；事务期间另一线程调用真实 OrgStore.createTenant，正常返回。前一事务回滚后，租户记录消失。探针输出 org_write_returned_success=true、org_present_after_other_request_rollback=0。

**原因：** SqliteContext 为全部存储暴露同一个 JDBC 连接；各存储各自改 autoCommit。部分类局部 synchronized(connection)，OrgStore、key、会话、审计并不共同遵守完整的事务所有权规则。

**建议：** 数据库上下文拥有统一事务入口，完整事务和同连接访问遵循同一串行化规则；单副本可以采用有界写执行器，无需因此引入独立数据库。不能只给某一个存储加锁。

**影响：** 管理接口承诺成功的数据可被另一个失败请求撤销。定位：[SqliteContext.java:38](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-persistence/llmgw-persistence-sqlite/src/main/java/io/github/leohou0430/llmgw/persistence/sqlite/SqliteContext.java:38)、[SqliteKeyStore.java:232](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-persistence/llmgw-persistence-sqlite/src/main/java/io/github/leohou0430/llmgw/persistence/sqlite/SqliteKeyStore.java:232)、[OrgStore.java:29](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-app/src/main/java/io/github/leohou0430/llmgw/app/OrgStore.java:29)。证据：structural-extended.log。

### 6. [P1] 业务变更与审计分两次提交，审计失败仍留下变更

**复现：** 本次隔离库给 audit_event 添加故障触发器，创建租户返回 500，但 tenant 行仍存在。两引擎一致。

**原因：** 控制器先提交变更，再 audit.record。已有 AuditedChange 回滚单测没有接入这些真实入口。审计记录还没有操作者字段，仅有项目、动作和详情。

**建议：** 变更与审计通过第 5 项统一事务入口提交，记录真实主体；对配置另外明确持久提交和运行激活状态，避免普通失败响应诱发盲重试。

**影响：** 请求失败与实际状态不一致，无法完整追查操作者。定位：[ControlOrgController.java:58](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-app/src/main/java/io/github/leohou0430/llmgw/app/ControlOrgController.java:58)、[ControlKeyController.java:72](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-app/src/main/java/io/github/leohou0430/llmgw/app/ControlKeyController.java:72)、[AuditLog.java:28](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-app/src/main/java/io/github/leohou0430/llmgw/app/AuditLog.java:28)。

### 7. [P1，新增合同缺口] 用量不跨重启恢复，也没有项目/key 两级额度

**复现：** 同一配置 key 完成真实本地转发，snapshot 中有用量；同库重启后变为空。两引擎一致。默认 InMemoryUsageLedger 的快照也不包含在途预留。

**原因：** 准入只按 key.id 预留；没有项目总额、持久请求预留、幂等结算和崩溃未知结果核对。仅将内存计数另存文件不能满足新合同。

**建议：** 在已有账本边界实现可恢复的两级原子预留和幂等结算，累计额度调整同事务审计，未知提交保守占额。测试强杀、重复结算、额度调整与在途并发、新增/轮换 key。

**影响：** 重启或多发 key 会突破管理员以为有效的累计额度。定位：[DefaultGovernanceModule.java:27](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-governance/llmgw-governance-default/src/main/java/io/github/leohou0430/llmgw/governance/inmemory/DefaultGovernanceModule.java:27)、[DefaultGovernanceChain.java:360](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-governance/llmgw-governance-default/src/main/java/io/github/leohou0430/llmgw/governance/inmemory/DefaultGovernanceChain.java:360)、[InMemoryUsageLedger.java:13](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-governance/llmgw-governance-default/src/main/java/io/github/leohou0430/llmgw/governance/inmemory/InMemoryUsageLedger.java:13)。

### 8. [P1] 账本忽略上游报告的输入 token，持续使用准入估算

**复现：** mock 返回 prompt_tokens=1、completion_tokens=1，账本却记录 promptTokens=3、completionTokens=1。两引擎真实 HTTP 复现。

**原因：** GatewayContext.promptTokens 是构造时写入的 final 估算值；非流式和 SSE 仅更新 completion。工具定义、图片等场景会进一步放大偏差。

**建议：** 输入、输出都区分估算和上游报告；有权威 usage 时覆盖，没有时才回退估算，分别验证 JSON、SSE 和转换请求。

**影响：** 配额与价格统计不准确，持久化也只会保存错误计量。定位：[GatewayContext.java:29](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-core/src/main/java/io/github/leohou0430/llmgw/core/governance/GatewayContext.java:29)、[SseChunkAccounting.java:35](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-engine/llmgw-engine-api/src/main/java/io/github/leohou0430/llmgw/engine/SseChunkAccounting.java:35)、[WebfluxGatewayController.java:457](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-engine/llmgw-engine-webflux/src/main/java/io/github/leohou0430/llmgw/engine/webflux/WebfluxGatewayController.java:457)。

### 9. [P1] 资源目录未接入真实文件/任务生命周期，项目共享与重启恢复仍不成立

**具体场景：** 上传只写 TaskRouteSticky；/control/catalog 是手工录入的另一套目录。GET /v1/files、/v1/batches 仍返回 501。归属使用 keyId，不能实现同项目多 key 共享；默认 sticky 重启丢失。现有隔离 smoke 正在断言列表 501，不能拿它证明新目录已经交付。

**原因：** 数据面没有使用 ResourceCatalog、CreationService、DeletionService、TaskCatalog；读取资源按 routeId 再查当前路由，也没有保留来源配置快照。

**建议：** 从统一凭据解析 tenant/project，接通上传、列表、引用、读取、删除、任务操作和来源快照；提供供应商格式的分页与归属验证。保留“不长期保存文件原文”的责任边界。

**影响：** 文件/任务在项目共享、列表、重启和来源变更时不能完成合同。定位：[WebfluxGatewayController.java:935](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-engine/llmgw-engine-webflux/src/main/java/io/github/leohou0430/llmgw/engine/webflux/WebfluxGatewayController.java:935)、[WebfluxGatewayController.java:940](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-engine/llmgw-engine-webflux/src/main/java/io/github/leohou0430/llmgw/engine/webflux/WebfluxGatewayController.java:940)、[ControlCatalogController.java:68](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-app/src/main/java/io/github/leohou0430/llmgw/app/ControlCatalogController.java:68)。

### 10. [P1] 强杀后的上传暂存文件不会在下次启动回收

**复现：** WebFlux 上传中观察到 223232 字节 spool，强杀本次进程，再启动同配置；旧文件仍存在且大小不变。测试结束已删除本次暂存原文。

**原因：** 两引擎每次 createTempDirectory，SpoolManager 启动清理的只是全新的空目录。组件单测复用同一目录，与实际装配不同。

**建议：** 使用实例拥有的稳定暂存根和进程所有权机制，启动回收自己的遗留文件，避免误删其他运行实例。增加真实 kill -9→重启测试。

**影响：** 上传原文和磁盘占用跨重启遗留。VT 存在相同装配方式，但本次强杀实测只覆盖 WebFlux。定位：[WebfluxGatewayController.java:1147](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-engine/llmgw-engine-webflux/src/main/java/io/github/leohou0430/llmgw/engine/webflux/WebfluxGatewayController.java:1147)、[VirtualThreadGatewayController.java:613](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-engine/llmgw-engine-vt/src/main/java/io/github/leohou0430/llmgw/engine/vt/VirtualThreadGatewayController.java:613)、[SpoolManager.java:42](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-core/src/main/java/io/github/leohou0430/llmgw/core/multipart/SpoolManager.java:42)。

### 11. [P1] 登录限速只有独立组件，未接入真实 HTTP 入口

**复现：** 两引擎连续 15 次错误密码均返回 401；实际调用链只有账号查询、密码校验和会话创建，没有 LoginRateLimiter。

**建议：** 在昂贵密码计算前接入有界、可清理的登录限速；以真实 HTTP 验证限速及恢复，不能只测试 FixedWindowRateLimiter 本身。

**影响：** 暴力尝试及 PBKDF2 消耗缺少承诺的应用内保护。定位：[ControlLoginController.java:61](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-app/src/main/java/io/github/leohou0430/llmgw/app/ControlLoginController.java:61)、[SessionService.java:28](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-identity/llmgw-identity-default/src/main/java/io/github/leohou0430/llmgw/identity/SessionService.java:28)。审查期间外部已增加 Secure cookie 配置和测试，因此最初发现的 secure(false) 不再列为当前缺陷。

### 12. [P1] WebFlux 管理链路在事件循环执行同步 JDBC 和密码计算

**证据：** 本次故障日志明确记录 reactor-http-nio-11 执行 ControlOrgController→AuditLog→JDBC；登录同步调用 PBKDF2 与数据库。管理链路未像数据面一样隔离阻塞工作。

**建议：** HTTP 适配层将完整阻塞管理事务移到有界执行器或虚拟线程，配合统一事务所有权；基础模块继续不依赖 Reactor。验证数据库忙等待和登录负载期间的数据面健康/SSE 进展。

**影响：** 数据库等待、磁盘延迟和密码校验占用承载模型流量的事件循环。本次确认线程及调用路径，没有据此推算生产吞吐损失。定位：[ControlOrgController.java:49](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-app/src/main/java/io/github/leohou0430/llmgw/app/ControlOrgController.java:49)、[ControlLoginController.java:62](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-app/src/main/java/io/github/leohou0430/llmgw/app/ControlLoginController.java:62)。

### 13. [P1] 数据库容量保护遗漏 WAL，不能约束实际磁盘占用

**复现：** 主文件为 4096 字节、WAL 为 2125952 字节，1MiB StorageGuard 仍通过。探针在可控条件关闭自动 checkpoint；实际长读事务也可能阻止 WAL 回收，不能依赖恰好及时 checkpoint。

**建议：** 将数据库本体、WAL 和受管空间计入容量策略，并在统一写入口执行；定义超限后的登录/会话及恢复行为，接入实际保留清理流程。

**影响：** 配置的磁盘上限不限制实际占用。定位：[StorageGuard.java:27](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-persistence/llmgw-persistence-sqlite/src/main/java/io/github/leohou0430/llmgw/persistence/sqlite/StorageGuard.java:27)、[SqliteContext.java:26](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-persistence/llmgw-persistence-sqlite/src/main/java/io/github/leohou0430/llmgw/persistence/sqlite/SqliteContext.java:26)。

## 应补齐

### 14. [P2] 会话跨重启保留，CSRF 却无法延续或主动刷新

**复现：** 两引擎重启后同 cookie 读取 200，旧 CSRF 写入 403。网页刷新也会清空只存在于 JS 变量中的 csrf，没有会话恢复或 token 刷新入口。

**建议：** 增加基于有效会话的 CSRF 恢复流程，或明确让重启使会话统一失效并引导重登，避免已登录状态下写操作持续失败。定位：[ControlPlaneManagementConfiguration.java:55](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-app/src/main/java/io/github/leohou0430/llmgw/app/ControlPlaneManagementConfiguration.java:55)、静态 control/index.html 的 csrf 变量。

### 15. [P2] SQLite rowid 可复用，旧游标会包含第一页后新增的资源

**复现：** 创建 file-1/2/3，读取第一页 limit=1；删除最大 rowid 的 file-3，再创建 new-after-first-page；旧游标的下一页包含新记录。探针输出 catalog_old_cursor_includes_new_rows=true。

**原因与建议：** 普通 rowid 不是严格递增持久序列，删除最大行后可复用；用独立单调序列建立高水位，并覆盖删除最高行、替换尾行、新增交错测试。该组件分页尚未接入 HTTP，并不消除其合同错误。定位：[SqliteResourceCatalog.java:151](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-catalog/llmgw-catalog-default/src/main/java/io/github/leohou0430/llmgw/catalog/local/SqliteResourceCatalog.java:151)、[SqliteResourceCatalog.java:58](/Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway/llmgw-catalog/llmgw-catalog-default/src/main/java/io/github/leohou0430/llmgw/catalog/local/SqliteResourceCatalog.java:58)。

### 16. [P2] 网页、公开合同与自动化门没有覆盖真实业务闭环

**具体场景：** 网页 key/路由固定 t1/p1，发布默认内容 routes-A；没有项目选择、key 轮换/吊销、路由回滚等完整操作。OpenAPI 没有 /control 操作，check-openapi 只验证已声明操作形状，不检查漏掉的整个控制面。README/安全部署说明仍以透传和旧 token 管理为主，未与当前默认迁移策略统一。

**建议：** 建立“建项目/账号→发 key→真实调用→查用量/归属→发布→回滚→吊销→重启”端到端合同，每角色覆盖跨项目反例。同步部署、升级与公开 API；未交付菜单明确隐藏/禁用。现有数据面/原生扩展/Redis 测试继续保留。

**影响：** 组件全绿和展示页容易被误当成可用产品。另：admin/JWT Python smoke 默认读取可变 target JAR，并非都消费 LLMGW_TEST_JAR；本次 admin 批次实际读到外部重建中的 JAR，出现类加载失败，不能记作产品失败或固定产物通过。定位：static/control/index.html、docs/openapi.yaml、bin/check-openapi.py、bin/smoke-admin-auth.py、bin/smoke-jwt-auth.py、CI。

## 架构结论与处理顺序

根因是同一能力存在相互分离的状态和装配入口：原生 ExtensionContext 的 key/路由/账本，与 Spring 控制面的 SQLite 对象分别运行，资源目录又独立于真实请求。模块层次和禁 Spring/Reactor 的方向无需推倒；应统一服务实例、身份模型和事务所有权。

1. 统一身份、scope、持久凭据与事务入口，关闭并行的无 scope 管理旁路。
2. 使结构化路由发布真正激活数据面，修复并发草稿、审计、回滚和启动恢复。
3. 补持久两级配额与权威输入 usage，接通资源目录和来源快照。
4. 补强杀恢复、磁盘保护、WebFlux 阻塞隔离，再完善网页、OpenAPI 和整体验收。

不把“大控制器拆分”作为独立阻断项。先删除假状态对象、复用已有能力边界；没有证据承诺可删除多少行或依赖。

## 验证与限制

### 本轮实测摘要

| 检查 | 结果与范围 |
|---|---|
| 模块布局 / native 边界 / 边界单测 | 142 项布局检查、边界检查、6 项 Python 单测通过 |
| 默认 JUnit 复测 | 1054 tests，0 failures，0 errors，26 skipped（Redis 条件跳过） |
| Redis/jtokkit/otel 组合 JUnit | 1050 tests，0 failures，0 errors，0 skipped；本次独立 Redis |
| 新固定 JAR 管理反例 | 双引擎完成；关键问题仍可复现 |
| 新固定 JAR 管理鉴权 / JWT / 租户隔离 | 双引擎通过；租户隔离使用非临时端口区重跑，仅替换测试端口选择函数 |
| 初始固定 JAR 多协议 / 扩展 / 加固 | 分别 56 / 54 / 46 项通过；不与新 JAR 混为一次验收 |
| 初始固定 JAR 官方 SDK 转换 | 36 场景通过，本地受控上游，无真实供应商调用 |
| 完整流式错误脚本 | 900 秒超时；观察到 43 条 PASS、0 条 FAIL，未完成整套，不计通过 |
| OpenAPI/YAML / 验收夹具 / 基准解析 | 5 个 YAML、77 项夹具、8 项解析测试通过；OpenAPI 检查不证明 /control 完整 |



实际命令、结果和产物摘要见 verification.json；脚本、机器可读复现结果及选定日志见 evidence/。组合 profile 使用本次独立 Redis，结束后仅清理自己的进程。审查期间存在外部源码更新，初次 RestrictedNetworks 失败已被后续更新与复测覆盖，不列为当前遗留问题；Secure cookie 的外部更新也已排除。

已覆盖基础边界、默认/组合 JUnit、双引擎管理反例及多项数据面契约；确切状态逐批记录，环境无效和未完成批次不计通过。

**未验证：** 当前产物 native/AOT 重建、Linux native、真实 DeepSeek 语义验收、全部协议字段逐项等价、公网 TLS、2C2G 整体容器验收、长期负载、全依赖漏洞扫描、浏览器逐菜单操作。历史报告不替代本轮证据，本机测试不构成容量/SLO 或财务保证。本报告是按风险深入关键链路的全仓审查，并非对每一行代码无缺陷的保证。

补充：source-hashes 是复制产物时的源码观察值，不是原子构建快照。已明确检测到安全磁盘余量参数的源码/产物差异；其他后续新增或修改记录于 verification.json，不自动继承之前的测试结果。报告中的关键反例使用固定产物复验，后续工作没有被本轮审查修改或覆盖。
