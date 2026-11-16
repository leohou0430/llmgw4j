# 网关四阶段实施计划

> **For implementer:** 全程 TDD：先写失败测试并亲眼看到失败，再实现，再看到通过。每个任务以"测试绿 + 本轮检查通过"结束；**不自动 git 提交/推送/发布**（AGENTS.md 覆盖 superpowers 默认的每任务提交；提交由用户显式发起，偏好单个打包提交）。

**Goal:** 按已批准设计交付四阶段：三协议转换与真实业务验收 → Files 有界流式化 → 项目资源目录与列表 → 可操作控制面。

**Architecture:** 新增原生能力域（转换/持久化/目录/身份），经 `ExtensionPoint`/命名 `ExtensionModule` 显式组装进 `ExtensionContext`；HTTP 桥只导出原生图。协议转换采用"共同语义合同 + 独立协议编解码适配器"，上游固定 DeepSeek Chat（deepseek-flash）。单 Java 进程 + 嵌入 SQLite，全部运行组件合计 2C2G。

**Tech Stack:** Java 25、Maven 多模块、Jackson 3.x（tools.jackson）、JDK Flow/CompletableFuture、JUnit 5；HTTP 仅 app/engine-webflux/engine-vt；SQLite（xerial sqlite-jdbc，版本进入阶段时固定并实测 native）；密码哈希用成熟非 Spring 库。

**设计依据:** [四阶段设计](../superpowers/specs/2026-11-12-gateway-four-stage-design.md)（用户已确认）、[24 项访谈决定](2026-11-12-roadmap-interview.md)、[协议资料核对](2026-11-12-protocol-convergence-source-notes.md)、ADR-0001~0005。产品责任、能力不支持策略、scope、调用上限、资源预算**不是实现细节，不得擅改**；技术准入失败先报阻塞与替代，再请求改变合同。

**计划粒度:** 第一阶段为可执行粒度（精确路径/测试/命令）。第二至四阶段为任务级合同与验收；因阶段门要求前一阶段真实通过，且准入结论会影响后续细节，进入各阶段时先把该阶段细化为同粒度子计划再动工。模块名/schema/依赖版本/UI/线程池数值属实施者自主范围（设计 §7），本文给出建议值。

---

## 0. 现状锚点（已核验，2026-11-12）

| 事实 | 位置 |
|---|---|
| 协议枚举，明示同协议透传 | `llmgw-core/src/main/java/io/github/leohou0430/llmgw/core/protocol/ProtocolKind.java` |
| 入口管线（解析→路由→治理） | `llmgw-core/.../core/governance/GatewayPipeline.java` `enter()` |
| 上游请求构建（路径恒为 `kind.upstreamPath()`，无翻译） | `llmgw-engine/llmgw-engine-api/.../engine/EngineRoutes.java` `request()` |
| 路由记录（protocol=入口协议钉住；provider 仅认证方言） | `llmgw-core/.../core/routing/Route.java`、`RouteTable.java` |
| 上游契约（rawJson/rawBody byte[]；Flow/CompletableFuture） | `llmgw-core/.../core/upstream/UpstreamRequest.java`、`llmgw-provider/llmgw-provider-api/.../UpstreamProvider.java` |
| 唯一真实传输（两引擎共用；取消/首字节/空闲超时已具备） | `llmgw-provider/llmgw-provider-http-jdk/.../JdkHttpUpstream.java` |
| 两引擎入口/SSE 桥 | `llmgw-engine/llmgw-engine-webflux/.../WebfluxGatewayController.java`（handleKind/dispatch/upstreamLines）、`llmgw-engine/llmgw-engine-vt/.../VirtualThreadGatewayController.java`（handleKind/handleStream/streamWithPlan/StreamSink） |
| 扩展机制（命名发现/惰性/replace/seal/逆序关闭） | `llmgw-core/.../core/extension/Extensions.java`、`ExtensionContext.java`、`ExtensionModule.java` |
| 原生图组装与桥导出 | `llmgw-app/.../app/GatewayRuntimeConfiguration.java`（loadModule→seal；@Bean 仅 `c.get`） |
| 受控上游（三协议 handler、body 标记故障注入） | `llmgw-app/src/test/java/.../app/MockUpstreamServer.java` |
| KeyStore SPI 已预留 SQLite 槽位 | `llmgw-core/.../core/governance/KeyStore.java`（现实现 JsonKeyStore） |
| 管理端点（X-Admin-Token，无 RBAC）；只读 console | `llmgw-app/.../app/AdminController.java`、`llmgw-app/src/main/resources/console/index.html` |
| 租户现表达=ApiKey.id；资源隔离=TaskRouteSticky scopedId | `llmgw-core/.../core/routing/TaskRouteSticky.java`、`EngineRoutes.ownsReferencedFiles` |
| 模块布局/边界检查 | `bin/check-module-layout.sh`、`bin/check-native-boundaries.py`、`bin/test_native_boundaries.py` |

**工作区注意:** 当前分支 `codex/gateway-capacity-audit` 存在大量上一轮未提交修改。执行任何任务前先 `git status` 与用户确认处置；不覆盖、不丢弃、不提交既有变更。

## 1. 全局约束与每轮验证

环境（本机已核验）：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home   # Oracle JDK 25.0.3
export PATH=/Users/leo/work/maven/apache-maven-3.9.16/bin:$PATH
```

- 每轮至少运行：`bash bin/check-module-layout.sh`、`python3 bin/check-native-boundaries.py`、`python3 -m unittest discover -s bin -p 'test_native_*.py'`。
- 单模块测试：`mvn -B -pl <module> -am test -Dtest=<TestClass> -Dsurefire.failIfNoSpecifiedTests=false`。
- 构建：`bash bin/build.sh` → `llmgw-app/target/llmgw-app-0.3.1.jar`；回归用 `LLMGW_TEST_JAR` 固定被测 JAR，防 profile/AOT 重建覆盖。
- profile：默认 / `-Predis`（本次独立 Redis 实例并清理）/ `-Pjtokkit` / `-Potel`；协议/治理变更跑双引擎流式、扩展、加固、租户隔离、管理与 JWT 契约（bin/smoke-*）。
- native：`bash bin/build-native.sh webflux|vt` 需 GraalVM 25+ 的 `native-image`；**本机默认 JDK 无 native-image，未找到 GraalVM**——native 任务先确认工具链（历史 native 证据为 Linux 产物），缺失即报阻塞，不得用 JVM 启动冒充 native 证明。
- 新端点/字段同步 `docs/openapi.yaml` 并跑 `python3 bin/check-openapi.py`；术语落定更新 CONTEXT.md，重大取舍记 ADR。
- 证据写入 `docs/verification/<日期>/<主题>/`：commit/worktree 摘要、产物摘要、运行参数、fixture 摘要、供应商/模型、调用计数、真实/受控分类、错误与未覆盖范围、清理结果。不改写历史测量。
- 基础模块禁 Spring/Boot/Reactor/Dubbo；多实现域=聚合 parent+唯一 api+独立 child；根 POM 只列聚合目录；基础 jar `llmgw.foundation.enforcer.skip=false`；状态归 ExtensionContext，禁静态有状态注册表；seal 前显式 replace，未知模块/扩展名失败。

---

## 2. 第一阶段：三协议转换与真实业务验收

合同：Chat Completions / Responses / Messages 三入口 → DeepSeek Chat（固定 deepseek-flash）；响应与 SSE 还原调用方协议。默认拒绝不可表达语义；降级仅凭网关扩展头显式授权。真实验收=单个 10 分钟窗口、≤60 次生成、并发 1、每请求输出上限 1024 tokens、无自动重试/fallback。

### T1.0 技术准入（硬门，先于一切实现）

**产出:** `docs/plans/2026-11-12-protocol-mapping-matrix.md`

1. 固定合同版本：OpenAI Chat/Responses、Anthropic Messages 官方 schema 与 SDK 版本（`bin/requirements-sdk.txt` 现有 openai/anthropic SDK 版本核对并钉住）、DeepSeek Chat/Files 官方文档快照日期与链接。
2. 逐字段映射表：三入口请求字段 → 语义合同 → DeepSeek Chat 字段；分类=可等价映射 / 无语义影响可忽略 / 不可表达默认拒绝 / 可授权降级（列出语义损失）。未知字段一律拒绝。
3. 逐事件映射表：DeepSeek Chat SSE（`chat.completion.chunk`、`[DONE]`）→ 三入口合法事件序列（Responses：`response.created`/`output_item.*`/`content_part.*`/`response.completed`；Messages：`message_start`/`content_block_*`/`message_delta`/`message_stop`），含块/条目生命周期与一次终态。
4. **思考准入判定**：deepseek-flash 的 thinking 开关参数名、推理输出字段（`reasoning_content`?）、续轮时推理状态的可表达性；三入口各自续轮编码（Responses reasoning 条目 / Messages thinking 块签名 / Chat 消息回传）。判定网关命名空间续轮信封（完整性校验+项目绑定）是否客户端往返可用；**不得伪造 Anthropic 供应商签名**。
5. **图片准入判定**：deepseek-flash 内联图片（data URL/base64）是否被 DeepSeek Chat 接受；若仅支持 Files 引用，则与"第一阶段用内联图片"冲突——**列为阻塞报用户**，不擅自改范围。
6. usage 字段核对：输入/输出/推理细分与 `stream_options.include_usage` 支持；缺失=未知≠0。
7. 明确拒绝清单：Responses `previous_response_id`/`store`/内建工具/`background`；Messages 私有块；精确 thinking 预算等。错误合同：400+稳定能力错误码+字段路径+原因；资源操作不支持 501；授权/不存在统一 404。

**门:** 任一必测能力（文本/多轮、工具、SSE、usage、思考及续轮、图片）无法等价表达且不在可授权降级清单 → 停止，写阻塞报告与可行替代，请用户裁决。**本任务只做文档/SDK 类型核对/受控验证，不发起真实生成调用。**

### T1.1 新域骨架 `llmgw-conversion`

**Files:**
- Create: `llmgw-conversion/pom.xml`（packaging pom，`llmgw.foundation.enforcer.skip=false`，modules: api/chat/responses/messages/deepseek）
- Create: `llmgw-conversion/llmgw-conversion-api/pom.xml`（仅依赖 core + junit test）
- Create: `llmgw-conversion/llmgw-conversion-{chat,responses,messages,deepseek}/pom.xml`（各依赖本域 api + core）
- Modify: 根 `pom.xml` modules 增加 `llmgw-conversion`；dependencyManagement 增加 5 个内部 artifact

**Step 1:** 先跑 `bash bin/check-module-layout.sh` 确认现有通过；建骨架后重跑 + `python3 bin/check-native-boundaries.py` + `mvn -B -pl llmgw-conversion -am test`（空模块编译通过）。
**合同:** api 包 `io.github.leohou0430.llmgw.conversion`；实现包 `.chat/.responses/.messages/.deepseek`，不得共享 api 裸包、不得依赖同域兄弟实现。

### T1.2 语义合同类型（api）

**Files:**
- Create: `llmgw-conversion/llmgw-conversion-api/src/main/java/io/github/leohou0430/llmgw/conversion/SemanticModel.java`（或按类型分文件）
- Test: `llmgw-conversion/llmgw-conversion-api/src/test/java/io/github/leohou0430/llmgw/conversion/SemanticModelTest.java`

**Step 1 失败测试:** 构造含有序混合内容（文本+图片+工具调用+工具结果+思考）的多轮请求，断言：顺序保持；`Usage(null,null,null)` 序列化为"未知"而非 0；`StopReason.UNKNOWN` 不等于 COMPLETED；ThinkingPart 与普通文本类型不同。
**Step 2:** `mvn -B -pl llmgw-conversion/llmgw-conversion-api -am test -Dtest=SemanticModelTest -Dsurefire.failIfNoSpecifiedTests=false` → 编译失败（类型不存在）。
**Step 3 实现（最小合同，准入矩阵可增补字段）:**

```java
public sealed interface ContentPart permits TextPart, ImagePart, ToolCallPart, ToolResultPart, ThinkingPart {}
public record TextPart(String text) implements ContentPart {}
public record ImagePart(String mediaType, String base64Data, String fileRef, String detail) implements ContentPart {} // 阶段1仅 inline；fileRef 阶段2/3；内联或 fileRef 至少其一
public record ToolCallPart(String id, String name, String argumentsJson) implements ContentPart {}
public record ToolResultPart(String toolCallId, String content, boolean isError) implements ContentPart {}
public record ThinkingPart(String reasoning, String continuationToken) implements ContentPart {} // token=网关信封或 null
public record SemanticMessage(Role role, List<ContentPart> parts) {}
public enum Role { SYSTEM, USER, ASSISTANT, TOOL }
public record ToolSpec(String name, String description, String parametersJsonSchema) {}
public record SemanticRequest(String model, List<SemanticMessage> messages, List<ToolSpec> tools,
        String toolChoice, Boolean thinkingEnabled, Integer maxOutputTokens, boolean stream) {}
public record Usage(Long inputTokens, Long outputTokens, Long reasoningTokens, Long cachedInputTokens) {} // null=未知；cachedInputTokens=缓存命中子集
public enum StopReason { COMPLETED, LENGTH, TOOL_CALL, CONTENT_FILTER, UNKNOWN }
public record SemanticResponse(String id, List<SemanticMessage> messages, StopReason stop, Usage usage) {}
public sealed interface SemanticEvent permits TextDelta, ThinkingDelta, ToolCallDelta, UsageEvent, TerminalEvent {}
// ToolCallDelta 携带 id/name/arguments 增量；TerminalEvent 携带 StopReason；ErrorEvent 由错误合同单独表达
```

**Step 4:** 同命令 → PASS。**Step 5:** 本轮三检查。不提交。

### T1.3 能力策略与错误合同（api）

**Files:**
- Create: `.../conversion/CapabilityPolicy.java`、`CapabilityException.java`、`DegradationGrant.java`
- Test: `.../conversion/CapabilityPolicyTest.java`

**合同:**
- `CapabilityException(int status, List<Violation>)`，`Violation(String code, String fieldPath, String reason)`；稳定码：`capability_unknown_field`、`capability_not_supported`、`capability_degradation_not_authorized`、`capability_invalid_value`（最终清单以准入矩阵为准）。
- 降级授权头 `X-LLMGW-Allow-Degradation: <逗号分隔能力id>`；默认空集合；只接受矩阵列出的可降级项；记录请求授权与实际执行降级；**该头不得透传上游**（HeaderPolicy 之外显式剥离，配正反测试）。
- 失败测试先行：未知字段→400+字段路径；不可表达约束→400；未授权降级→400；授权降级→放行且留痕；未知降级项→拒绝；一个全局开关默许所有未知字段的设计禁止出现。

### T1.4 上游 DeepSeek Chat 编解码（`llmgw-conversion-deepseek`）

**Files:**
- Create: `.../conversion/deepseek/DeepSeekChatCodec.java`（实现 api `UpstreamCodec`）
- Create: `src/main/resources/META-INF/llmgw/extensions/io.github.leohou0430.llmgw.conversion.UpstreamCodec` = `deepseek-chat=...DeepSeekChatCodec`
- Test: `.../conversion/deepseek/DeepSeekChatCodecTest.java` + fixture 资源

**api SPI（本任务先在 api 定义，配发现/失败测试）:**

```java
@ExtensionPoint public interface UpstreamCodec {
    UpstreamPayload encodeRequest(SemanticRequest req, String targetModel); // rawJson + path("/chat/completions")
    SemanticResponse decodeResponse(String upstreamJson);
    StreamDecoder newStreamDecoder(); // 每请求一个有状态实例：onChunk(json)->List<SemanticEvent>；terminalSeen()
}
```

**测试（fixture 驱动，映射矩阵为准）:** 文本/多轮/system 顺序；工具定义与 call id/参数增量拼装；`reasoning_content`→ThinkingDelta（不混入文本）；usage 缺失→null；`stream_options.include_usage=true` 注入；图片内联引用按准入结论编码；截断/非法 JSON→错误而非成功；未知上游字段不静默丢弃语义。
**验证:** 模块测试 + 命名发现/未知名失败测试（参照 `ExtensionsTest` 手法）。

### T1.5 Chat 入口 codec（`llmgw-conversion-chat`）

**Files:** `.../conversion/chat/ChatEntryCodec.java` + META-INF `EntryCodec` 描述（`chat=...`）+ `ChatEntryCodecTest.java`

**api SPI:**

```java
@ExtensionPoint public interface EntryCodec {
    ProtocolKind kind();
    SemanticRequest decode(String rawJson, CapabilityContext ctx); // 含能力校验，违规抛 CapabilityException
    String encodeResponse(SemanticResponse resp);
    StreamEncoder newStreamEncoder(); // SemanticEvent -> 入口协议 SSE 行（含一次终态）
    String errorJson(CapabilityException e);   // 头未发送
    String errorEvent(CapabilityException e);  // 头已发送的 SSE 错误事件
}
```

**测试:** 请求 decode 字段分类全量（矩阵驱动）；响应 encode 还原 choices/finish_reason/usage；SSE 合法事件序与 `[DONE]` 一次终态；错误 JSON 形状 `{"error":{message,type,code,param}}`；往返（decode→encode→decode）语义不变。

### T1.6 Responses 入口 codec（`llmgw-conversion-responses`）

同 T1.5 结构。**测试:** 输入条目/内容结构 decode；输出条目还原；事件序列 `response.created → response.output_item.added → response.content_part.added → response.output_text.delta → …done → response.completed`（以准入固定的官方事件表为准）；工具调用条目与参数增量；usage 事件；`previous_response_id`/`store`/内建工具/`background` → 400 稳定码；reasoning 条目续轮按 T1.8 结论。

### T1.7 Messages 入口 codec（`llmgw-conversion-messages`）

同 T1.5 结构。**测试:** system 顶层与 content blocks decode；`message_start/content_block_start/content_block_delta(text_delta|input_json_delta)/content_block_stop/message_delta/message_stop` 合法序与一次终态；tool_use/tool_result 块与续轮关联；thinking 块不伪造供应商签名；私有块（如 server_tool_use 等）400；错误形状 `{"type":"error","error":{"type","message"}}`。

### T1.8 思考与续轮封装（按 T1.0 结论；不可行即阻塞）

**Files:** `.../conversion/ContinuationEnvelope.java`（api）+ 各入口 codec 续轮路径 + `ContinuationEnvelopeTest.java`
**合同:** 信封=网关命名空间（如 `llmgw1:` 前缀）+ 完整性校验（HMAC，密钥归 ExtensionContext）+ 项目绑定；客户端往返证明（SDK 级受控测试）；不冒充供应商原生签名；篡改/跨项目使用→拒绝。若任一必测入口续轮不可表达→停，报阻塞。

### T1.9 图片内联与归属接缝

**测试先行:** 支持的 mediaType/大小内联转换正例；不支持格式/损坏 base64→400 不静默去图；跨项目 file 引用→统一 404（阶段 1 内联为主，file 引用校验接缝挂 `EngineRoutes.ownsReferencedFiles` 现有逻辑，阶段 3 换目录授权）；图片 fixture 用自有小图（已知图形/文字，sha256 固化）。

### T1.10 路由与配置扩展

**Files:**
- Modify: `llmgw-core/.../routing/Route.java`（新增 `upstreamProtocol` 组件；旧构造器委托 ""，""=既有透传）
- Modify: `llmgw-core/.../routing/RouteTable.java`（of 校验：仅允许 ""/"chat"，未知值失败）
- Modify: `llmgw-core/.../config/GatewayProperties.java` RouteDef、`InMemoryRouteConfigSource.fromProperties`、`llmgw-app/.../AdminController.java` RouteSpec、`docs/openapi.yaml`
- Test: `RouteTableTest`（增补）、`llmgw-app` 配置绑定测试

**测试先行:** `upstreamProtocol=chat` 路由可解析且三入口均匹配；`upstreamProtocol=bogus`→配置失败；缺省=""时行为与现状逐字节一致（既有测试全绿）；`python3 bin/check-openapi.py` 通过。

### T1.11 ConversionService 与命名模块组装

**Files:**
- Create: api `ConversionService.java`、`PreparedConversion.java`、`StreamTranscoder.java`
- Create: `.../conversion/ConversionModule.java`（命名 ExtensionModule，显式构造各 codec；bindDefault ConversionService）
- Create: META-INF `ExtensionModule` 描述 `conversion=...ConversionModule`
- Modify: `GatewayRuntimeConfiguration.java`（在 seal 前按配置 loadModule("conversion")；@Bean 导出 ConversionService）
- Test: `ConversionModuleTest.java`（无 Spring 建图）

**合同:**

```java
public interface ConversionService {
    boolean applies(Route route); // upstreamProtocol 非空
    PreparedConversion prepare(ParsedRequest parsed, Route route, String tenantId, String projectId, DegradationGrant grant);
}
public interface PreparedConversion extends AutoCloseable {
    String upstreamJson(); String upstreamPath();
    String transcodeResponse(String upstreamJson);       // 非流式
    StreamTranscoder newStreamTranscoder();              // 流式，每请求一个
}
public interface StreamTranscoder extends AutoCloseable {
    List<String> onUpstreamLine(String line);  // 上游 chat SSE 行 -> 0..n 入口协议行
    List<String> onUpstreamComplete();         // 上游正常结束：补终态；缺终态->错误事件，不伪造成功
    List<String> onUpstreamError(Throwable t); // 头后错误：入口协议错误事件
    boolean terminalSeen();
}
```

**测试（参照 NativeGovernanceModuleTest/ExtensionContextTest）:** 命名发现；seal 前 replace 生效、seal 后 replace 失败；未知模块名失败；两个 ExtensionContext 实例互不污染；close 逆序且只关一次；transcoder 有界缓冲、close 释放。

### T1.12 两引擎接缝

**Files:**
- Modify: `WebfluxGatewayController.java`（handleKind/dispatch/upstreamLines）、`VirtualThreadGatewayController.java`（handleKind/handleStream/streamWithPlan/StreamSink）
- Modify: `llmgw-engine/llmgw-engine-api`（EngineRoutes 增补接受 PreparedConversion 的构建重载；engine-api pom 加 conversion-api 依赖）
- Test: 两引擎各自 Conversion*Test（受控上游）

**合同:**
- 治理决策后、构建 UpstreamRequest 前：`applies(route)` → `prepare(...)`；上游路径/JSON 用 PreparedConversion；否则走原路透传（逐字节不变）。
- 流式：provider 行流先经 StreamTranscoder 再进既有 SseChunkAccounting/ChunkParser（记账仍按入口协议终止符）；上游截断/缺终态→入口协议错误事件+终止，不伪造成功；头已发送不得再改 HTTP 状态。
- 头未发送错误：入口协议 errorJson（400/404/501/502 按设计）；取消/首字节/空闲超时语义不变，正常流完成不 cancel transport。
- 降级授权头剥离在此层验证（正反例）。
**回归:** 双引擎流式、smoke-stream-errors、smoke-sse-line-limit、取消后 active 许可归零（既有加固 smoke）。

### T1.13 受控上游与版本化 fixture

**Files:**
- Modify: `llmgw-app/src/test/java/.../MockUpstreamServer.java`（chat handler 增 deepseek 模式：reasoning_content、tool_calls 增量、usage、可注入截断/损坏/慢首字节；沿用 body 标记约定）
- Create: `tools/acceptance/stage1/fixtures/`（三入口×六场景请求 JSON、预期结构断言、自有小图片、可判定计算工具定义；`manifest.json` 含 sha256；无个人信息固定文本）
**合同:** fixture 版本化；改范围/新增付费上限须重新确认。受控上游不算真实验收。

### T1.14 三入口×六场景受控合同（双引擎）

**Files:** `llmgw-app/src/test/java/.../ConversionContractTest.java`（webflux）与 vt 变体
**场景（每入口）:** ①非流式文本+多轮 ②流式文本+usage ③工具调用→客户端执行→结果续轮 ④思考输出+携带状态续轮 ⑤图片理解 ⑥取消后释放并恢复。
**反例:** 未知字段、不可表达约束、未授权降级、损坏/截断 SSE、跨项目资源引用（404）、预期不支持正确拒绝。
**断言:** 结构与语义（不匹配随机措辞）；usage 未知≠0；一次终态；取消后许可归零、无泄漏。

### T1.15 SDK 兼容（受控）

**Files:** Modify `bin/smoke-sdk-compatibility.py`（或新增 `bin/smoke-conversion-sdk.py`）
**合同:** 官方 openai SDK（chat+responses）、anthropic SDK（messages）经网关转换路由打受控上游；重试=0；六场景同 T1.14；`LLMGW_TEST_JAR` 固定被测 JAR。

### T1.16 真实验收工具（先于窗口，纯本地可测）

**Files:**
- Create: `bin/acceptance-stage1.py`、`bin/test_acceptance_stage1.py`（unittest，无网络）
- Create: `tools/acceptance/stage1/scenarios.json`（27 次计划：每入口 文本2/流式1/工具2/思考续轮2/图片1/取消1；60 为上限非必须用满）

**合同（测试先行）:**
- 发送前持久化计数预约（append-only JSONL+fsync）；崩溃后未知提交按已使用；启动时预约数即已用数。
- 同一 run id；600 秒或 60 次任一达到停止新增；并发 1；每请求 max_tokens=1024；SDK 与网关均关重试/fallback（验收路由 fallbacks=[]）。
- 低速轮转分布到窗口；晚结束请求单独记收尾时间，不伪称 10 分钟负载；窗口结束不自动延长。
- 脱敏追加记录：场景 id、时间、请求 sha256、结果分类（成功/预期拒绝/供应商错误/未知）、usage、TTFT/耗时；凭据仅经安全环境变量（如 `LLMGW_ACCEPTANCE_API_KEY`），不进仓库/命令行/报告，仅记变量标识与脱敏供应商。
- 资源观察：网关进程 RSS/CPU 采样（仅观察，不推导 SLO）。
- dry-run 模式（无网络）校验配置/计数/报告路径。
**验证:** `python3 -m unittest bin.test_acceptance_stage1`（或 discover 模式）+ dry-run 实测。

### T1.17 第一阶段全量回归

三检查 + `bash bin/build.sh` + 默认/Redis/jtokkit/otel profile + 双引擎流式/扩展/加固/租户隔离/管理/JWT smoke（LLMGW_TEST_JAR 固定）+ T1.14/T1.15 全绿。Redis 用本次独立实例并清理。证据入 `docs/verification/2026-11-12/stage1-controlled/`。

### T1.18 真实 10 分钟窗口（硬门；需用户显式 go + 凭据）

**前置:** 用户经安全环境提供 DeepSeek 凭据并明确批准窗口；选定单引擎（建议 webflux 默认）；双引擎只做受控合同，真实额度不翻倍。
**执行:** 验收配置（收敛路由、无 fallback、无重试）→ dry-run → 单窗口运行 → 报告 `docs/verification/<日期>/stage1-live/`。
**通过:** 全部必测场景至少完整成功一次；预期不支持正确拒绝；网关错误/静默语义丢失/OOM/取消后泄漏=0；符合 2C2G 与次数边界。供应商错误单列；必测未成功=不通过，不跳阶段、不改术语宣布完成。余量仅用于窗口内预先列明场景，不自动重试失败/未知提交。

---

## 3. 第二阶段：Files 有界流式传输（进入前细化为子计划）

范围：仅 Files 上传 + 供应商确实支持的内容下载；音频/视频字节通路保留原行为。边界：64MiB/文件、66MiB/multipart、64KiB/非文件元数据、每进程 4 并行 Files 传输并计入总并发；普通 JSON 限制独立保留。

| 任务 | 合同要点 | 主要落点 |
|---|---|---|
| T2.1 原生字节流合同 | JDK Flow+CompletableFuture；chunk 所有权、只订阅一次、有限 demand、取消与关闭责任；直接改契约并更新唯一实现，不留 compat shim | `core/upstream` 新字节契约、`UpstreamProvider` |
| T2.2 JDK 传输流式化 | 上传/下载不整包 byte[]；正常完成不 cancel transport；超限取消上游并关输入 | `JdkHttpUpstream` |
| T2.3 WebFlux 桥 | DataBuffer 复制/转移有明确生命周期，禁引用已释放池内存；背压/取消回归 | engine-webflux |
| T2.4 VT 桥 | 有界队列/读写，不退回整包 byte[] | engine-vt |
| T2.5 multipart 流式扫描 | 认证+全局/Files 准入在读取正文前；流式边界扫描、文件计数、metadata 上限；后置字段有界 spool，不要求官方客户端加自定义路由字段 | core 新 multipart 包（纯 JDK 可测）+ 两引擎 |
| T2.6 临时 spool 与磁盘保护 | ≤66MiB/传输、总 ≤264MiB、磁盘安全余量；独立受限目录；结束/取消/异常立即清理+启动清理遗留；配额/空间不足提前拒绝；仅前置校验依赖后置字段时落盘 | 同上 |
| T2.7 限制与错误 | 已知长度超限前置 413；未知长度按累计拒绝；生命周期许可只释放一次；4 并行计入总并发 | 两引擎+传输 |
| T2.8 慢路径回归 | 客户端慢读/慢写、上游慢读、首字节/空闲超时（两引擎） | smoke 增补 |
| T2.9 下载合同 | 用明确支持下载的受控上游验证；DeepSeek 真实仅上传/列表/元数据/删除，不声称下载；超限被发现前上游可能已收部分→报未知/失败 | 受控上游+真实记录 |
| T2.10 2C2G 文件压力 | 容器 2CPU/2GiB：4 路 64MiB 上传+管理并发；RSS/CPU/OOM/取消后许可归零/临时原文清理/恢复 | deploy + docs/verification |

阶段门：设计 §8 第二阶段证据行；全量回归同 T1.17。

## 4. 第三阶段：项目资源目录与列表（进入前细化）

归属：tenant→project→多 key；同项目按授权共享，跨项目隔离。建议新域：`llmgw-persistence`（api+sqlite）、`llmgw-catalog`（api+default）；KeyStore SQLite 实现放 persistence-sqlite（core SPI 不变）。

| 任务 | 合同要点 |
|---|---|
| T3.1 SQLite 基础 | xerial sqlite-jdbc 版本固定；WAL、短事务、有限写队列、busy timeout；读写不在 WebFlux 事件循环；AutoCloseable 归 context；双 context 隔离；native JNI 解压目录/资源包含实测（工具链缺失先报阻塞） |
| T3.2 schema 与迁移 | 版本表+迁移+失败回退指引；表：租户/项目/key 摘要、来源与配置版本、资源目录、审计（阶段 4 复用）；原文不进库 |
| T3.3 KeyStore 槽位替换 | SQLite 实现显式 replace JsonKeyStore；关闭/恢复/实例隔离测试 |
| T3.4 目录模型与授权 | 条目：网关 ID/类型/上游 ID/来源路由/供应商/配置版本/创建时间/可观察状态/元数据/最后同步/删除标志；先查项目授权再访问固定来源；默认路由切换不改旧资源来源；密钥轮换=同来源新凭据，来源撤销=明确不可访问 |
| T3.5 创建一致性 | 上游创建成功→先提交目录再回成功；目录提交失败→脱敏待核对事件+结果未知；不谎称跨系统原子；无幂等合同不自动重发 |
| T3.6 稳定分页 | 本地目录分页；创建序号+ID 稳定排序；签名游标绑定租户/项目/筛选+首屏高水位；新增不混入同次遍历；页大小/过滤/查询时长限制；上游不可用可读目录+陈旧状态注明最后观察时间 |
| T3.7 任务目录 | 仅上游异步资源目录/元数据/状态/分页/已有查询取消；无网关执行器/队列/恢复调度；DeepSeek 未支持任务 API 只做受控上游合同 |
| T3.8 删除/取消状态机 | pending→confirmed/unknown；仅上游确认标完成；非幂等不自动重试；超时 unknown 可人工触发核对；无原文持久化/下载能力明确拒绝原文请求 |
| T3.9 旧资源迁移 | 旧 key 空间独立保留；显式 tenant/project 映射+人工审核记录才共享；缺归属证据不猜测、不扫供应商全账户导入；兼容入口限原 owner；预览/冲突拒绝/审计，不自动合并 |
| T3.10 保留与磁盘保护 | 默认墓碑 30 天/详细审计 30 天（可配）；数据库/审计空间上限+磁盘剩余保护；达阈值拒绝需持久化的新建/变更，不无声丢审计/删活跃资源；强杀恢复/断写/磁盘满实测 |

阶段门：设计 §8 第三阶段证据行；openapi/管理端点同步；全量回归。

## 5. 第四阶段：可操作控制面（进入前细化）

同 Java 进程网页+管理 API；建议新域 `llmgw-identity`（api+default）。角色动作表=设计 §6.1，逐端点服务端校验。

| 任务 | 合同要点 |
|---|---|
| T4.1 账号与角色 | 平台/租户/项目管理员+项目只读；scope 持久化；密码哈希用成熟非 Spring 库（如 favre bcrypt，版本固定+native 实测，不自实现密码学）；首个管理员一次性本地 bootstrap，无默认凭据/公网注册 |
| T4.2 登录与会话 | 随机服务端会话；HttpOnly/Secure/SameSite cookie；CSRF；登录限速；过期/吊销；改密/改权限失效相关会话；控制面 TLS 部署 |
| T4.3 RBAC 执行 | 每接口服务器校验 scope+动作；跨租户/越权统一拒绝；项目管理员不能自授租户/平台角色、不能引入未允许上游地址；原始凭据/密码哈希任何角色不可读 |
| T4.4 管理 API | 租户/项目/key（明文仅创建/轮换显示一次，读取掩码）、来源、目录、用量、脱敏审计 |
| T4.5 路由版本 | 草稿→校验→不可变发布→本进程原子激活；期望版本防并发覆盖；回滚=新版本引用目标；启动恢复已提交版本；响应注明本进程激活状态；进行中请求/既有资源持有来源快照；密钥吊销行为单独定义验证；不宣称跨副本确认 |
| T4.6 配置校验 | 扩展名/模型协议能力/允许上游地址与协议（平台权限，项目不能放宽）/敏感头/超时容量边界；拒地址探测、重定向绕过、DNS 变化绕过 |
| T4.7 密钥静态加密 | 供应商密钥加密保存；根密钥库外安全注入；缺失行为明确；备份说明根密钥恢复前提 |
| T4.8 旧 token 迁移 | 新模式默认禁用 X-Admin-Token 远程管理；显式迁移模式限本机/受限管理网、可审计紧急平台权限；旧只读页迁移；不留无 scope 后门；调用方 JWT 独立继续验证 |
| T4.9 审计与观察 | 配置/资源变更与审计同事务，失败拒绝变更；上游已生效按 unknown 补偿；HTTP 观察有界队列+汇总，丢弃数可见；不当财务账本；磁盘满影响写入成功判断 |
| T4.10 网页 | 同进程静态资源（沿用 console 模式，无 Node）；登录+租户/项目/key/路由发布回滚/目录/用量/审计菜单功能闭环；每角色动作浏览器实测（含越权反例） |
| T4.11 备份恢复 | WAL 一致性在线备份或停写快照；升级前备份+schema 迁移回退；强杀/启动恢复/断写/磁盘满/还原/缺根密钥/旧 schema 实测 |
| T4.12 native 与总预算 | JNI 解压目录、native 资源包含、双引擎 native 启动+SQLite 恢复实测；全部产品进程合计 2C2G 复测（不引用第一阶段资源结果代替） |

阶段门：设计 §8 第四阶段证据行；全量回归 + 整体 2C2G 复测。

---

## 6. 停止条件与诚实报告

- 任一阶段门未过：保留未通过状态，可继续不依赖失败项的工程工作，不得跳过后声称四阶段完成。
- 技术准入失败、真实关键场景失败、整体 2C2G 未过：如实报告阻塞与可行替代，请用户裁决，不以变更术语宣布完成。
- 真实生成调用仅限 T1.18 单窗口（≤60 次）；后续补验需另行确认时限与额度。凭据不进仓库/命令行/报告。
- 不自动提交、推送或发布；每任务结束=测试绿+检查通过+证据记录。

## 7. 执行交接

计划批准后二选一：
1. **子代理驱动（本会话）**：每任务派发实现子代理（携带本计划+准入矩阵+约束），完成后依次派发 spec 审查与代码质量审查，修复再复审，逐任务推进；阶段门处停下报用户。
2. **手动执行**：用户按任务自行实施，本会话提供支持与审查。