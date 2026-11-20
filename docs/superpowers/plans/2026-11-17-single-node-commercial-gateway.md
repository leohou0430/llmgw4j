# 单机团队与商业网关实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在单机预算内交付已确认的租户商业计费、正文记录、Gemini双向HTTP与可演练恢复，统一验收。

**Architecture:** 金额、正文采用独立原生能力域，共用现有权威SQLite元数据与显式ExtensionContext。正文按块写本地文件；双引擎共用调用/尝试生命周期，Gemini沿用codec和provider SPI。

**Tech Stack:** JDK 25、当前Maven/Spring Boot应用、SQLite JDBC、JDK Flow/CompletableFuture、现有JUnit与Python/shell合同工具。

**Spec:** `docs/superpowers/specs/2026-11-17-single-node-commercial-gateway.md`

**状态：** 产品范围及Spec §8 D1–D6均已确认；本计划完成设计自检，供整体审阅和选择执行方式。尚未实施，不把计划中的测试写作已通过。本轮不自动提交、推送或发布。

**交付方式：** 计费、记录、协议/恢复分组实施与复核，下一版本统一验收；依赖任务完成后才接入下游，不同时改同一个控制器。每任务使用独立行为测试验证，不用测试同步探针或mock成绩替代真实端到端结果。

## Global Constraints

- 单Java进程、SQLite、2CPU/2GiB；无集群、OIDC、客户门户、自助注册、在线支付、Gemini Live。
- 基础模块不得依赖Spring、Boot、Reactor；HTTP只在app、engine-webflux、engine-vt。
- 新能力域使用聚合parent、唯一api和独立实现child；根POM只列聚合目录；基础jar启用`llmgw.foundation.enforcer.skip=false`。
- 所有状态/资源属于ExtensionContext，seal前显式绑定/替换；未知扩展失败；上游公开契约为Flow/Future。
- 金额与token合同独立；零价必须显式，未知结果保留预留；最终一次尝试向客户收费，失败尝试成本由平台承担。
- 正文7天可配置，不保存附件原件与凭据；正文存储故障拒绝新调用，在途继续且不完整显式可见。
- 恢复目标最多回退1小时、4小时内核对并安全重开；必须有独立故障域备份证据。
- 不移动已发布v0.4.0 tag，不重写历史测量为新证据。

## Review Focus

1. 客户重复X-Request-Id、重复充值和异步重复终态不得导致重复收费或串租户；由任务2/4覆盖。
2. token预留成功而金额准入失败、或终态回调被取消时，不泄漏可安全释放的额度，不释放未知上游结果；由任务3覆盖。
3. Base64跨块、工具参数转义、SSE拆字节时，不把附件/凭据落盘，不改客户端收到的字节；由任务5覆盖。
4. Gemini无candidate、安全拦截、usage-only尾帧及EOF不得伪造正常完成或重复累加usage；由任务7覆盖。
5. 历史明细归档后重复请求、恢复旧备份后已吊销Key，不得绕过幂等或直接恢复通流；由任务9覆盖。

## 执行与复核规则

任务按计费、记录、协议/恢复三个子项目组织，可分别审阅，最终统一发行。每任务以行为测试红→最小实现→绿→diff审阅收尾；无提交授权时仅完成本地变更，不执行技能模板中的自动commit步骤。D1–D6已确认，按下列合同和算例实施；具体模型售价与估算参数由显式配置给出，不静默编造生产默认价。

### 任务1：固定已确认合同并修正当前能力文档

**Files:** 修改`docs/superpowers/specs/2026-11-17-single-node-commercial-gateway.md`、`CONTEXT.md`、`docs/gap-analysis.md`、`README.md`、`README.en.md`。

**Interfaces:** 输入为Spec §4和§8已确认合同；产出当前能力矩阵，以及用于后续测试的固定费用/单位/估算fixtures。

- [x] D1–D6已逐项确认并记录到Spec及ADR-0007；后续不重新询问已确认选择。
- [x] 将已确认合同及两次0.0000006元的累计算例写入Spec，领域词汇更新至CONTEXT.md。
- [ ] 将旧gap-analysis保留为历史证据，当前入口另列v0.4.0事实与本版计划，不能把计划列为已实现。
- [ ] 校验相对链接和`git diff --check`；审阅无未经确认的免费、自动退款、价格覆盖默认值。

### 任务2：租户账户、价格版本与追加账务

**Files:** 新建`llmgw-billing/pom.xml`、`llmgw-billing/llmgw-billing-api/pom.xml`、`llmgw-billing/llmgw-billing-sqlite/pom.xml`；api源码目录`src/main/java/io/github/leohou0430/llmgw/billing/`下新增`Money.java`、`PriceVersion.java`、`BillingLedger.java`、`BillingReservation.java`、`UsageEvidence.java`、`BillingSettlement.java`；实现目录`llmgw-billing/llmgw-billing-sqlite/src/main/java/io/github/leohou0430/llmgw/billing/sqlite/`新增`SqliteBillingLedger.java`、`BillingModule.java`。修改根POM及`llmgw-persistence/llmgw-persistence-sqlite/src/main/java/io/github/leohou0430/llmgw/persistence/sqlite/Migrations.java`。

**Interfaces:** `BillingLedger.reserve(BillingReservation): boolean`、`settle(BillingSettlement): void`、`markUnknown(String callId, String reason): void`、`releaseUnsubmitted(String callId, String reason): void`；管理命令为独立`RechargeCommand`、`ReversalCommand`、`ReconciliationCommand`类型，须包含actor/reason/operationId与真实scope。新增类型均置于billing-api，不把这些合同塞进core。

- [ ] 新增`SqliteBillingLedgerTest`：同一租户并发请求不能超出估算可用额；同一operationId重放不重复加款，改金额重放拒绝；冲正引用原流水且不能重复；冻结旧价目不被更新。
- [ ] 执行`mvn -pl llmgw-billing/llmgw-billing-sqlite -am test`确认缺实现失败，再实现最小迁移、金额运算与账务事务。
- [ ] 增加负费用、算术溢出、错币种、错scope、MISSING与显式FREE区别、审计拒绝整事务回滚测试。
- [ ] 重跑模块测试和布局/原生边界脚本；核验关闭后重开账户余额、held和幂等状态一致。

### 任务3：双引擎逻辑调用与上游尝试计费

**Files:** `llmgw-engine/llmgw-engine-api/src/main/java/io/github/leohou0430/llmgw/engine/`新增`BillableCall.java`、`BillableAttempt.java`；修改`EngineRoutes.java`、`ForwardingPolicy.java`及两引擎的`WebfluxGatewayController.java`、`VirtualThreadGatewayController.java`；app新增`BillingRuntimeModule.java`并修改`GatewayRuntimeConfiguration.java`、app POM。

**Interfaces:** BillableCall包装任务2账本，为每次真实attempt分配内部标识、绑定冻结售价和最终服务attempt；不以客户端requestId或HTTP success作为计费幂等/已知用量依据。两个引擎消费同一生命周期合同。

- [ ] 新增`BillingLifecycleTest`，先证明token通过/金额拒绝时不会发上游、前序安全预留能撤回；金额通过/token失败同理。
- [ ] 增加请求已提交后强杀/重启仍保留金额预留，finish提交失败仍可恢复；不依赖sideCar入队即当持久成功。
- [ ] 新增两引擎参数化HTTP合同：失败attempt不向客户扣费、最终attempt仅结算一次；取消已知usage收费、未知usage挂账；价格更新后在途仍用旧价。
- [ ] 最小接入后执行引擎/app定向测试，检查正常完成不取消transport、取消仍传递、首字节前后错误语义一致。

### 任务4：八类计量与异步任务结算

**Files:** billing-api新增`BillingMeter.java`、`MeteredQuantity.java`及按任务1确认规则的类型；billing-sqlite新增`SqliteTaskBillingLink.java`；修改`EngineRoutes.java`、两引擎文件/任务处理入口及`SqliteResourceCatalog.java`相关来源绑定；新增`bin/smoke-billing.py`。

**Interfaces:** `BillingMeter.estimate(PriceVersion, BillingInput): MeteredQuantity`与`extract(UsageEvidence): MeteredQuantity`；BillingInput是协议无关已分类数据，MeteredQuantity包含单位及证据质量。任务关联唯一键为tenant/project/provider-source/upstream-task-id，值为billingCallId。

- [ ] 为八类协议各写单位及计价fixtures，测试缺规则拒绝且未发上游、明确免费可调用；D3所列附加端点按确认规则测试。
- [ ] 视频创建→来源固定→轮询→完成→重复终态→重启只结算一次；跨项目轮询不得读到结果或推进他人费用。
- [ ] 增加缓存输入/思考等重叠计数不双算、usage-only累计值不逐帧累加、非文本缺usage待对账测试。
- [ ] 执行`mvn -pl llmgw-app -am test`及固定JAR的`python3 bin/smoke-billing.py`；输出逐协议实际覆盖清单，不以总数替代矩阵。

### 任务5：有界正文记录与故障闸门

**Files:** 新建`llmgw-recording/`及`llmgw-recording-api`、`llmgw-recording-local`的POM；api目录`src/main/java/io/github/leohou0430/llmgw/recording/`新增`RecordingStore.java`、`RecordingHandle.java`、`RecordingStart.java`、`RecordedEvent.java`、`RecordingOutcome.java`；local目录新增`LocalRecordingStore.java`、`RecordingSanitizer.java`、`RecordingModule.java`；app新增`RecordingRuntimeModule.java`，两引擎接入事件旁路。

**Interfaces:** `RecordingStore.open(RecordingStart): RecordingHandle`；`RecordingHandle.append(RecordedEvent): void`、`finish(RecordingOutcome): void`，实现AutoCloseable且终态幂等。失败显式健康闸门，不能静默吞异常或无界排队。

- [ ] 新增`RecordingSanitizerTest`：跨块Base64/data URI、带令牌URL、认证字段、二进制片段不写入；合法工具文本可记录；输出转发字节完全一致。
- [ ] 新增`LocalRecordingStoreTest`：慢磁盘与满队列有界；写失败后新调用提交前被拒绝，在途继续且INCOMPLETE；进程重启修复OPEN，不能错误改COMPLETE。
- [ ] 用可控Clock测试7天清理只删正文，不删除账务/未知预留；两个ExtensionContext资源关闭、实例锁与目录隔离。
- [ ] 执行recording模块测试及双引擎慢消费者/取消合同，记录队列和内存上限，不累计完整流。

### 任务6：管理闭环与权限

**Files:** app新增`ControlBillingController.java`、`ControlPriceController.java`、`ControlRecordingController.java`；修改`ControlRuntimeModule.java`、`ControlPlaneManagementConfiguration.java`、`GatewayRuntimeHints.java`、`static/control/index.html`、身份域`Action.java`与授权实现、`docs/openapi.yaml`；扩展`bin/smoke-control-plane.py`。

**Interfaces:** `/control/billing`族提供账户、充值、冲正、预留/对账和账单；`/control/prices`版本发布；`/control/recordings`提供元数据、正文和导出。账务写仅平台管理员；租户/项目角色只能按真实scope读，正文三级管理员可读且审计，观察者仅元数据。该授权细化为技术提案，不能因此给非平台角色增加信用修改权。

- [ ] 先写四角色×四项目范围测试，覆盖伪造scope、legacy token、CSRF失败、正文导出审计失败均不返回内容。
- [ ] 实现分页/流式导出与有界并发，不将完整正文载入控制器内存；拒绝任意文件路径和CSV公式注入。
- [ ] 后台形成创建租户→配置价格→充值→发Key→真实调用→账单→对账/冲正闭环，浏览器实际点击验证。
- [ ] 运行控制面HTTP合同、OpenAPI严格验证、native反射可达检查；UI不展示凭据和内部实现细节。

### 任务7：Gemini原生生成协议与认证

**Files:** 新增`llmgw-conversion/llmgw-conversion-gemini/`、`llmgw-provider/llmgw-provider-gemini/`；修改`ProtocolKind.java`、协议注册、`HeaderPolicy.java`及两引擎路径映射；各新增模块提供命名发现描述符。codec源码为`GeminiEntryCodec.java`、`GeminiUpstreamCodec.java`，认证为`GeminiAuthenticator.java`。

**Interfaces:** 沿用EntryCodec/UpstreamCodec/UpstreamAuthenticator；新增生成协议按路径解析model、区分generateContent与streamGenerateContent；仅允许已配置的上游API版本/操作，不转发任意路径。

- [ ] 官方合同fixtures覆盖非流/流、安全拦截、无candidate、工具终态、usage-only尾帧、多candidate及截断；不以Chat `[DONE]`套用Gemini。
- [ ] 认证测试证明客户Key不会发上游，secretId有效、未知扩展失败、model路径注入被拒绝；重启保持已发布来源。
- [ ] 命名模块最小实现后执行该两模块`-am test`及双引擎HTTP；正常完成不取消transport，超时/取消完整传播。
- [ ] 锁定官方API合同与SDK版本，把滚动文档中的Interactions/Live明示排除。

### 任务8：Gemini与Chat双向转换

**Files:** 修改conversion-api中的`SemanticRequest.java`、`SemanticResponse.java`、`SemanticEvent.java`、`Usage.java`及必要内容类型；Gemini模块实现两端codec；新增`llmgw-conversion/llmgw-conversion-openai-chat/`上游codec；修改`ConversionModule.java`和路由转换能力校验；新增`bin/smoke-gemini.py`。

**Interfaces:** 沿用语义合同和续接信封；Chat通用上游codec与DeepSeek专用codec分别命名，不修改既有路由选择含义。不自动将Responses/Messages接到Gemini转换。

- [ ] 先写双方向system、多轮、工具call/result、思考续接、图片引用、流式工具参数测试；不支持目标模型能力时拒绝或显式降级。
- [ ] 测错误签名/跨项目续接拒绝、不伪造思考、不重复累计usage；Gemini转换计费仍用准入冻结售价。
- [ ] 使用官方客户端指向本地mock网关执行两引擎SDK合同；无真实供应商凭据明确标为未验收真实供应商。
- [ ] 全量conversion测试和既有18场景SDK回归，确认旧Chat/Responses/Messages→DeepSeek不退化。

### 任务9：账务归档与灾难恢复

**Files:** billing-sqlite新增`BillingArchiveService.java`；persistence-sqlite扩展`OnlineBackup.java`及备份manifest；app新增`RecoveryGate.java`；新增`bin/backup-control.py`、`bin/restore-control.py`、`bin/smoke-billing-recovery.py`，补`docs/control-plane-operations.md`。

**Interfaces:** 归档持久高水位与摘要，保留账户锚点及幂等索引；恢复默认maintenance，仅核验清单及显式记录缺口核对结果后开闸。外部备份目标是操作者配置，不能用同盘副本冒充独立故障域。

- [ ] 归档测试：归档前后余额/累计/held不变，归档前operationId重放仍拒绝，活动/未知记录不能归档，损坏归档恢复拒绝。
- [ ] 恢复测试：独立目标、错误根密钥、损坏manifest、缺口未核对均保持关闸；已吊销Key不因旧备份自动有效。
- [ ] 备份期间并发调用测共享连接等待；只有验证影响后才决定是否改用独立备份连接，保留实例资源所有权。
- [ ] 实际计时故障→恢复→账务/吊销核对→重开，恢复点≤1小时且全程≤4小时；无法取得缺口证据则验收失败。

### 任务10：历史增长、双引擎及发行级验收

**Files:** 新增`bin/smoke-commercial-capacity.py`、`docs/verification/commercial-single-node/`证据目录；修改必要CI步骤与当前指南，不调整已发布tag。

**Interfaces:** 固定commit/JAR摘要、场景参数、profile、引擎、失败与未覆盖清单；所有长期任务由当前执行跟进，不创建一次性进度自动化。

- [ ] 在10万/100万已结算历史下观测金额准入与现有token历史SUM；只有实测必要时才增加token累计投影，迁移前后对账证明一致。
- [ ] 2CPU/2GiB至少24小时混合调用、正文、管理、清理、备份，记录p50/p95/p99、错误率、拒绝率、DB等待、WAL/队列/磁盘/内存；不预填未测SLO。
- [ ] 执行`bash bin/check-module-layout.sh`、`python3 bin/check-native-boundaries.py`、`python3 -m unittest discover -s bin -p 'test_native_*.py'`。
- [ ] 分别执行默认、独立Redis、jtokkit/otel全量验证；LLMGW_TEST_JAR锁定产物，运行流式、扩展、加固、租户、管理、JWT、计费/正文/Gemini合同。
- [ ] 构建双引擎native并运行真实二进制控制面、计费、正文、Gemini及流式/取消合同；记录平台和构建模式。
- [ ] 将当前矩阵与实际证据同步到双语README、配置/部署/用户/扩展/性能文档。统一验收通过后再讨论版本与发布，不自动执行。

## 设计自检

已对照Spec §1–9检查任务覆盖：D1缓存→任务3/4，D2精度→任务2，D3附加操作→任务4，D4客户防重→任务3/4，D5项目限额→任务2/3，D6估算→任务2/4；正文与scope→任务5/6，Gemini→任务7/8，归档及恢复→任务9，容量及native→任务10。下方补充各任务共用合同、边界算例与定向命令。计划中的测试与恢复目标尚未执行，不计为现有证据。

## 共用接口与精确行为

以下为待整体审阅的技术接口设计；不再次变更已确认的商业规则。新增记录类型均为不可变值对象，校验在构造/命令入口完成，不携带运行凭据。

### 金额与计量（任务2/4）

源码统一放在`llmgw-billing/llmgw-billing-api/src/main/java/io/github/leohou0430/llmgw/billing/`，每个public类型独立同名文件：

```java
record Money(long microCny) {}
record ExactAmount(BigInteger numerator, BigInteger denominator) {}
enum Unit { TOKEN, CHARACTER, DOCUMENT, IMAGE, MILLISECOND, REQUEST }
record Quantity(Unit unit, BigInteger numerator, BigInteger denominator) {}
enum EvidenceQuality { ESTIMATED, AUTHORITATIVE, UNKNOWN }
record MeteredQuantity(Map<String, Quantity> dimensions, EvidenceQuality quality) {}
record Rate(ExactAmount microCny, Quantity perQuantity) {}
record PriceVersion(String id, String publicModel, String protocol,
                    String operation, String meterName, String meterVersion,
                    Map<String, Rate> rates,
                    Map<String, Quantity> defaultEstimates, boolean explicitFree) {}
record BillingInput(String publicModel, String protocol, String operation,
                    Map<String, Quantity> quantities, Map<String, String> attributes) {}
record UsageEvidence(String attemptId, String provider, String actualModel,
                     String sourceRequestId, Instant observedAt,
                     MeteredQuantity usage, String sourceDigest) {}
record BillingReservation(String callId, String tenantId, String projectId,
                          String keyId, String priceVersion, Money estimate) {}
record BillingSettlement(String callId, String attemptId, UsageEvidence evidence,
                         String outcome) {}
```

分母正、数量非负，分数约分；负值仅允许ExactAmount中的冲正变化量。rates与dimensions按声明维度名精确匹配，未知单位拒绝，不以ordinal持久化。模型不同模态/规格使用attributes做已声明分类；属性不得含凭据或任意可执行表达式。新增单位必须显式扩展meter合同和fixtures，不能拿同名单位隐式换算。

BillingMeter消费BillingInput，输出MeteredQuantity；费用由冻结PriceVersion与Quantity/Rate匹配计算，不由客户端提交金额。UsageEvidence的sourceDigest仅验证关联，不代替权威计量证据，原始可核验usage另存尝试记录。

任务2同时定义`RechargeCommand(String operationId, String tenantId, Money amount, String receiptId, String actor, String reason)`、`ReversalCommand(String operationId, String tenantId, String originalEntryId, String actor, String reason)`、`ReconciliationCommand(String operationId, String tenantId, String projectId, String callId, UsageEvidence evidence, boolean release, String actor, String reason)`。充值金额严格正；冲正首版仅整笔，禁止重复或跨租户原流水；释放待对账须提供核实原因且不得处理在途。BillingLedger分别提供`recharge(RechargeCommand): void`、`reverse(ReversalCommand): void`、`reconcile(ReconciliationCommand): void`，补充任务2列出的reserve/settle接口。SQLite实现消费DatabaseContext并由命名BillingModule绑定。

**任务2必须写的红/绿算例：**

- [ ] `tenantAdmissionIncludesInflightAcrossProjects`：租户余额1000微元、透支200微元，两个项目分别预留700和500成功，再预留1失败；所有失败零部分写入。
- [ ] `fractionalChargesAndReversalSurviveRestart`：两次各0.6微元，消费入账1、余数0.2；重启后整笔冲正第一次，净入账0、余数0.6；重复冲正拒绝，免费调用不改变余数。
- [ ] `estimateUsesConfiguredOutputWithoutMutatingRequest`：输入估10token、输出上限100token，售价输入1元/百万token、输出2元/百万token，预留210微元；实际输入10/输出20结算50微元，释放210预留，余额净减50。未给上限且模型配置默认100得到相同预留，但上游正文保持未给上限；两者皆缺则准入拒绝且上游调用0次。
- [ ] `projectCumulativeLimitDoesNotMintTenantCredit`：项目限额500微元、已用400、在途100，再预留1失败；只提高项目限额不能绕过租户可用额；调额不清历史和held。
- [ ] `priceAndMeterVersionFrozenForInflight`：准入后发布新价，旧调用结算仍按旧价/旧meter，新调用才用新版本；明确零价与MISSING分别断言放行/拒绝。

### 调用和客户防重（任务3/4）

`BillableCall.beginAttempt(String routeId, String sourceVersion): BillableAttempt`；attempt提供`markSubmitted(String upstreamRequestId): void`、`complete(UsageEvidence evidence, String outcome): void`、`markUnknown(String reason): void`。call提供`selectFinalAttempt(String attemptId): void`、`releaseUnsubmitted(String reason): void`。任意attempt只能属于一个call，选定最终尝试与终态推进通过账本持久CAS；String outcome使用集中合法值表并校验，不由控制器任意拼写。

新增`IdempotencyStore.java`于billing-api及`SqliteIdempotencyStore.java`于billing-sqlite。`claim(String tenantId, String projectId, String key, String requestDigest, String proposedCallId): ClaimResult`，结果包含`NEW/EXISTING/CONFLICT`及原callId和状态；一次事务占位，只有NEW路径可预留并提交上游。请求头固定`Idempotency-Key`，不复用X-Request-Id；空键、过长键显式400，长度上限配置并在OpenAPI公开。

对重复请求，现有生成入口统一返回HTTP409，错误码`idempotent_request_exists`及原callId/status，不返回成功SSE或偷偷重新订阅上游；不同内容为HTTP409、错误码`idempotency_conflict`。Gemini入口以其错误包装携带相同业务码，协议fixtures固定结构。该返回合同是供审阅的技术选择，不承诺客户端SDK会把它当正常模型结果。已确认的“不重放SSE”保持不变，非流首版也不额外建设原始响应重放库。

- [ ] `sameProjectConcurrentKeyCreatesOneSubmission`：32个并发相同键和请求，只有一次真实上游提交，其余返回原callId；同键不同正文/路径/模型冲突；跨项目不读到对方call。
- [ ] `unknownCallNeverExpiresIntoNewSubmission`：Clock推进25小时并重启，未知状态仍返回原callId；完成状态结束后23h59m有效，达到24h后可形成新callId；旧终态回调不能结算新call。
- [ ] `revokedKeyCannotReadIdempotentResult`：Key吊销后相同幂等键仍401；当前无模型权限也不能借原callId绕过授权。
- [ ] `cacheHitReleasesHoldWithoutChargingAuxiliaryCostToCustomer`：缓存命中客户扣款0、held释放一次、辅助向量费用单独记录；双终态/重复回调不改变结果。

### 正文设施与恢复（任务5/9）

RecordingStart包含callId、真实tenant/project/keyId、protocol、开始时间，不含原始认证头；RecordedEvent包含单调sequence、INPUT/OUTPUT方向、事件类别与已分类净化的UTF-8数据；RecordingOutcome包含COMPLETE/INCOMPLETE、原因与结束时间。索引open/finish写DB，append只进入有界文件队列。资源字节阈值通过显式配置注入并在容量验证后设置生产建议值，不把所有字节塞入共享DB事务。

- [ ] `malformedRequestDoesNotTripGlobalStorageGate`：一个请求非法JSON被拒绝，随后健康调用可准入；模拟真实磁盘写失败才关闭新调用闸门。
- [ ] `streamContentIsNotChangedByRecording`：UTF-8每字节拆分、工具参数跨块、带附件混合消息，客户端字节与关闭记录时一致，记录没有附件数据或凭据；写失败不会取消原在途传输。
- [ ] `expiryNeverDeletesMoneyOrPendingReservations`：Clock推进7天清理正文，账务、尾数、审计和未知预留保持；尚未写完的调用不得误标完整。
- [ ] `restoreCannotOpenBeforeGapReconciliation`：验证备份后仍maintenance，缺口核对未完成不能开闸；错误根密钥、损坏归档、已知撤销证据丢失分别拒绝。计时包含核对步骤，不能只报进程启动耗时。

### 定向命令与证据（所有任务）

先创建目标模块并完成父POM登记，再运行相应红测试；模块尚不存在的Maven定位失败不是行为红测试。每个类的关键断言按上方算例先失败，实际失败原因必须是目标行为缺失。

```sh
mvn -pl llmgw-billing/llmgw-billing-sqlite -am test
mvn -pl llmgw-recording/llmgw-recording-local -am test
mvn -pl llmgw-conversion/llmgw-conversion-gemini -am test
mvn -pl llmgw-provider/llmgw-provider-gemini -am test
mvn -pl llmgw-app -am test
```

定向行为通过后才运行全量`mvn -B clean verify`及profile矩阵，实际运行输出需零失败/错误，所有跳过注明并由独立环境补齐；不以重复直到成功掩盖不稳定性。新增shell/Python合同遵循已有固定JAR参数和独立DB/端口/Redis清理约定。长稳、native及独立目标恢复仅在任务10真实运行后声明通过。
