# 协议映射矩阵与第一阶段技术准入判定（T1.0）

状态：技术准入研究产出，作为后续编解码实现（T1.4–T1.9）的唯一规范。日期：2026-11-12。
本文只做官方文档核对、钉住版本 SDK 类型核对与本地受控推理证明，**未发起任何真实模型生成调用**，不需要凭据。
依据：[四阶段设计 §3](../superpowers/specs/2026-11-12-gateway-four-stage-design.md)、[实施计划 T1.0](2026-11-12-gateway-four-stage.md)、[协议资料核对](2026-11-12-protocol-convergence-source-notes.md)。

> 实现者注意：本文所有字段名/事件名均来自下方"来源登记"中钉住的 SDK 类型或官方文档原文，未证实项一律标 **待受控验证** 或 **阻塞**，不得据此猜测。分类标记见 §3 图例。

---

## 1. 合同版本固定

| 合同 | 钉住版本 | 核对方式 | 结果 |
|---|---|---|---|
| OpenAI Python SDK | `openai==3.49.0` | `bin/requirements-sdk.txt` 第 10 行；/tmp venv 安装后 `openai.__version__` | 一致，3.49.0 |
| Anthropic Python SDK | `anthropic==2.8.0` | `bin/requirements-sdk.txt` 第 2 行；/tmp venv 安装后 `anthropic.__version__` | 一致，2.8.0 |
| 传输依赖 | `httpx2==2.16.0` / `httpcore2==2.16.0` / `pydantic==2.14.3` | 同 requirements；SDK 实际 `import httpx2` | 一致 |
| 上游模型 | `deepseek-flash`（= DeepSeek-V4.1-Flash-1017） | DeepSeek Models & Pricing 页 | 文档证实 |
| DeepSeek 文档快照 | 2026-11-12 抓取 | 见 §2 来源登记 | — |

- SDK 安装于 `/tmp/llmgw-t10-sdk`（venv），**未改动仓库任何依赖文件**。
- 类型核对脚本与受控往返证明：`/tmp/llmgw-t10-docs/roundtrip_test.py`，结果 `roundtrip-results.json`（20/20 PASS，见 §11）。这些是 /tmp 临时研究产物，不入仓库。
- 关键 SDK 行为：openai 响应解析走 `construct_type`→`model_construct`（**非严格校验**），故 `ChatCompletionMessage` 等 `extra="allow"` 模型保留未知字段（如 `reasoning_content`），且 `finish_reason` 超出 Literal 枚举（如 `aborted`）仍可被官方客户端解析。直接 `pydantic.model_validate` 则严格拒绝——实现/测试须走真实客户端解析路径，不能用严格 `model_validate` 断言 SDK 行为。

### 1.1 钉住 SDK 暴露的请求字段全集（权威合同边界）

- **OpenAI Chat**（`openai.types.chat.CompletionCreateParams`，3.49.0 已把 `ChatCompletionCreateParams` 改名为 `CompletionCreateParams`）：
  `audio, frequency_penalty, function_call, functions, logit_bias, logprobs, max_completion_tokens, max_tokens, messages, metadata, modalities, model, moderation, n, parallel_tool_calls, prediction, presence_penalty, prompt_cache_key, prompt_cache_options, prompt_cache_retention, reasoning_effort, response_format, safety_identifier, seed, service_tier, stop, store, stream, stream_options, temperature, tool_choice, tools, top_logprobs, top_p, user, verbosity, web_search_options`。
  `messages` 角色：`developer / system / user / assistant / tool / function`。
- **OpenAI Responses**（`openai.types.responses.ResponseCreateParams`）：
  `access_programs, background, context_management, conversation, image, include, input, instructions, max_output_tokens, max_tool_calls, metadata, model, moderation, parallel_tool_calls, previous_response_id, prompt, prompt_cache_key, prompt_cache_options, prompt_cache_retention, reasoning, safety_identifier, service_tier, store, stream, stream_options, temperature, text, tool_choice, tools, top_logprobs, top_p, truncation, user`。
- **Anthropic Messages**（`anthropic.types.MessageCreateParams`）：
  `cache_control, container, diagnostics, inference_geo, max_tokens(必填), messages, metadata, model, output_config, service_tier, speed, stop_sequences, stream, system, thinking, tool_choice, tools, user_profile_id, workspace_id`。
  **重要：2.8.0 的 Messages 类型已不含 `temperature / top_k / top_p`**（全包 grep 证实）。`messages` 角色含 `user / assistant / system`（支持对话中插入 system）。`thinking` 形态：`enabled{budget_tokens(必填),display?} / disabled / adaptive{display?} / between_tools`。`output_config{effort: low|medium|high|xhigh|max, format:{type:json_schema,schema}}`。

> 凡未在上表出现的请求字段，一律按 **未知字段拒绝**（`capability_unknown_field`，400）。raw HTTP 客户端可能发送 SDK 未暴露的字段（如 Messages 的 `temperature`），同样按未知字段拒绝，除非后续显式扩充合同。

---

## 2. 来源登记（URL + 访问日期，均为 2026-11-12）

| 编号 | 对象 | URL |
|---|---|---|
| S1 | DeepSeek Chat Completions API（请求/响应/流式 schema） | https://api-docs.deepseek.com/api/create-chat-completion/ |
| S2 | DeepSeek Thinking Mode（开关/effort/续轮规则） | https://api-docs.deepseek.com/guides/thinking_mode |
| S3 | DeepSeek Vision（图片内联/URL/Files、限制） | https://api-docs.deepseek.com/guides/vision |
| S4 | DeepSeek Tool Calls（思考工具、mid-conversation、strict） | https://api-docs.deepseek.com/guides/tool_calls |
| S5 | DeepSeek Multi-round Conversation（无状态拼接） | https://api-docs.deepseek.com/guides/multi_round_chat |
| S6 | DeepSeek Models & Pricing（deepseek-flash 能力/上下文） | https://api-docs.deepseek.com/quick_start/pricing |
| S7 | DeepSeek Your First API Call（模型 id/legacy 名） | https://api-docs.deepseek.com/ |
| S8 | DeepSeek Files API（上传/列表/删除、file_id） | https://api-docs.deepseek.com/guides/files_api |
| S9 | DeepSeek Error Codes | https://api-docs.deepseek.com/quick_start/error_codes |
| S10 | DeepSeek Using the Anthropic API（兼容差异参考） | https://api-docs.deepseek.com/guides/anthropic_api |
| S11 | DeepSeek Token & Token Usage | https://api-docs.deepseek.com/quick_start/token_usage |
| S12 | OpenAI Chat API Reference | https://developers.openai.com/api/reference/resources/chat |
| S13 | OpenAI Responses Create Reference | https://developers.openai.com/api/reference/resources/responses/methods/create |
| S14 | Anthropic Messages API Reference | https://platform.claude.com/docs/en/api/messages |
| S15 | Anthropic Messages Streaming | https://platform.claude.com/docs/en/build-with-claude/streaming |
| S16 | Anthropic Thinking Overview（thinking 块/签名/加密） | https://platform.claude.com/docs/en/build-with-claude/thinking |
| S17 | Anthropic Thinking in tool & multi-turn workflows | https://platform.claude.com/docs/en/build-with-claude/thinking-tool-workflows |
| S18 | Anthropic Extended thinking (legacy) | https://platform.claude.com/docs/en/build-with-claude/extended-thinking |
| S19 | 钉住 SDK 类型 | 本机 `/tmp/llmgw-t10-sdk` 内 `openai==3.49.0` / `anthropic==2.8.0` 源码（`bin/requirements-sdk.txt`） |

注：`platform.openai.com` 已跳转 `developers.openai.com`；`docs.anthropic.com` 已跳转 `platform.claude.com`（与资料核对文档一致）。DeepSeek 旧 `guides/reasoning_model` 已并入 `guides/thinking_mode`。

---

## 3. 分类图例与内部语义合同

请求/响应字段映射分类（每行必标其一）：

- **E**＝可等价映射（语义无损）。
- **I**＝无语义影响可忽略（上游已声明无效/网关固定行为，透传或丢弃不改变可观察语义）。
- **R**＝不可表达，默认拒绝（400 + 稳定能力码）。
- **D**＝可授权降级（仅凭 `X-LLMGW-Allow-Degradation` 显式授权，语义损失见 §9；未授权→拒绝）。
- 未知字段＝一律 **R**（`capability_unknown_field`）。

> 条件分支拆行：同一字段在不同条件（思考/非思考模式、取值分支）下各成一行、各标一类。表中 `R/D` 不是并列双类，仅表示**授权条件分支**：未授权→R（拒绝），显式授权对应降级 id→D（见 §11）。

内部语义合同沿用实施计划 T1.2（`SemanticRequest / SemanticMessage / ContentPart{TextPart,ImagePart,ToolCallPart,ToolResultPart,ThinkingPart} / Usage(null=未知) / StopReason`）。本文增补：`ThinkingPart(reasoning, continuationToken)` 的 `continuationToken` 即 §7 网关命名空间续轮信封；Chat 入口可为 null（原生 `reasoning_content` 直接回传）。

---

## 4. DeepSeek 上游事实核对（deepseek-flash）

以下均来自 S1–S11 原文，未证实项显式标注。

### 4.1 模型与能力（S6/S7）
- 模型 id：`deepseek-flash`（当前由 DeepSeek-V4.1-Flash-1017 服务）；另有 `deepseek-v4-pro`（**不支持 Vision**）。legacy 名 `deepseek-v4-flash`、`deepseek-v4-flash-vision-exp` 仍接受但已退役，按 Flash 计费。
- deepseek-flash：上下文 1M，最大输出 384K；Thinking（默认开）/非 Thinking 双模；Vision ✓；Tool Calls ✓；JSON Output ✓；Responses/Anthropic 兼容入口 ✓。
- 本网关固定 `model=deepseek-flash`，路由 `target-model` 改写。

### 4.2 思考开关与推理输出（S1/S2）
- 开关：`thinking: {type: "enabled"|"disabled"}`（nullable）。省略/null 时跟随 `reasoning_effort`；两者都省略→**默认 enabled**。提供 `thinking` 时 `type` 优先于 `reasoning_effort`。
- effort：`reasoning_effort: none|low|high|max`，默认 `high`。`none` 关思考；`low/high/max` 开思考。兼容映射：`minimal→low`，`medium/xhigh→high`，`ultra→max`。
- 推理输出字段：`reasoning_content`（string|null），与 `content` 同级；流式在 `delta.reasoning_content`。仅思考模式产生。
- 思考模式**不支持** `temperature/presence_penalty/frequency_penalty`（设置不报错但无效）；`top_p`：flash 思考模式有效区间 0.95–1.0（<0.95 按 0.95），非思考固定 1.0（忽略传入值）。
- 思考模式支持工具调用（auto）；`tool_choice=required` 或具名在思考模式→**400**（须先关思考）。

### 4.3 思考续轮规则（S2，关键）
- 请求**携带 `tools`**：所有历史轮的 `reasoning_content` **必须完整回传**并拼入上下文；未正确回传→**400**。assistant 消息回传形态 `{role, content, reasoning_content, tool_calls}`。
- 请求**不携带 `tools`**：`reasoning_content` 无需回传；即使回传也被**忽略**，不拼入上下文。
- 多轮无状态（S5）：每次请求拼接全部历史。

### 4.4 图片（S3/S8，关键）
- deepseek-flash 接受图片，格式 JPEG/PNG/GIF/WebP（**按实际文件内容判定**，非 MIME/文件名）。
- **内联 base64 data URL：支持**。`image_url.url = "data:image/jpeg;base64,..."`；计入 48 MiB 请求体限制。→ 与"第一阶段用内联图片"**相容，无阻塞**。
- 外部 http(s) URL：≤8192 字符，单图 ≤32 MiB，下载 ≤60s。
- Files 引用：`file` 内容块 `{file_id: "file-api-..."}`（XOR `file_data` base64 data URL，`filename` 仅随 `file_data`）；file_id 单图 ≤64 MiB。
- `detail: low(降到512×512)|high|original|auto`（high/original/auto 均保留原图）。
- **限制：图片仅允许在 user 消息**；system/assistant 消息含图→400。tool 消息含图：S1 schema 允许 tool content 为 parts，但 S3 明文"images in user messages only"——**tool_result 图片经 Chat 上游是否接受＝待受控验证**（S10 称 deepseek-flash 的 Anthropic 入口支持 tool_result 图片，但本网关走 Chat 上游，须以 Chat 实测为准）。
- 限额：请求体 48 MiB；内联单图 32 MiB；每请求 ≤600 图；总图 ≤64 MiB（无 file_id）/200 MiB（含 file_id）；单边 ≤8192px（≥15 图时 4096px）；每图 resize 后 ≤1024 tokens。

### 4.5 工具调用与增量（S1/S4）
- 工具定义：`tools:[{type:"function", function:{name, description?, parameters?(JSON Schema), strict?(Beta)}}]`；name 唯一，`a-zA-Z0-9_-`，≤128。
- `tool_choice: none|auto|required|{type:"function",function:{name}}`；无 tools 默认 none，有 tools 默认 auto；required/具名思考模式→400。
- 流式工具增量：每个 tool call 首 chunk 带 `id/type/function`，后续 chunk 仅带 `function.arguments`；按 `index` 拼装。
- mid-conversation 插入**非模型生成**的 tool call：Chat **不支持**（Anthropic/Responses 入口支持）；Chat 支持对话中插入 system 消息。→ 客户端伪造 tool call 注入历史经 Chat 上游的行为＝**待受控验证**（必测场景用模型生成的 tool call，不受影响）。
- strict 模式：Beta，需 `base_url=https://api.deepseek.com/beta`，所有 function `strict=true`，JSON Schema 类型受限。第一阶段不启用。

### 4.6 usage（S1）
`usage: {prompt_tokens, completion_tokens, total_tokens, prompt_tokens_details:{cached_tokens}, prompt_cache_hit_tokens, prompt_cache_miss_tokens, completion_tokens_details:{reasoning_tokens}}`。
- `prompt_tokens = prompt_cache_hit_tokens + prompt_cache_miss_tokens`；`prompt_tokens_details.cached_tokens = prompt_cache_hit_tokens`。
- `completion_tokens_details.reasoning_tokens`：推理 token（思考模式）。`completion_tokens_details` 整体可选。
- `stream_options.include_usage`：支持；须与 `stream:true` 同用否则 400。**DeepSeek 把 usage 放在最后一个内容 chunk**（choices 恰 1 元素、无新内容、finish_reason 非空），**不发独立 usage-only chunk**；其后 `data:[DONE]`。

### 4.7 finish_reason（S1）
`stop | length | content_filter | tool_calls | insufficient_system_resource | aborted`。
- `insufficient_system_resource`＝推理系统资源不足中断；`aborted`＝生成被中断。二者为**异常终止**，非自然完成。
- 钉住 openai SDK 的 `finish_reason` Literal 仅 `stop/length/tool_calls/content_filter/function_call`，但客户端 `construct` 解析容忍扩展值（§11 实测）。网关仍须按语义把异常终止映射为错误/不完整终态，**不得伪造成功**（见 §6/§8）。

### 4.8 错误码（S9）
`400 Invalid Format / 401 Authentication Fails / 402 Insufficient Balance / 422 Invalid Parameters / 429 Rate Limit Reached / 500 Server Error / 503 Server Overloaded`。错误体为 OpenAI 风格 `{"error":{"message","type","code","param"}}`（受控验证确认具体 type/code 取值）。

---

## 5. 逐字段请求映射表

目标列统一为 DeepSeek Chat（S1）。`messages` 顺序保持；system/developer 归一为 system 优先级。

### 5.1 OpenAI Chat 入口 → DeepSeek Chat

| 入口字段 | 内部语义 | DeepSeek 字段 | 类 | 说明/损失 |
|---|---|---|---|---|
| `model` | model | `model=deepseek-flash` | E | 路由 target-model 固定改写 |
| `messages[system].content` | SystemPart | `messages[system].content` | E | 顺序保持 |
| `messages[developer].content` | SystemPart | `messages[system].content` | E | developer→system，保留指令优先级 |
| `messages[user].content`(str) | TextPart | `messages[user].content`(str) | E | |
| `messages[user].content[text]` | TextPart | `content[text]` | E | 分段顺序保持 |
| `messages[user].content[image_url]` | ImagePart | `content[image_url]{url,detail}` | E | data URL 内联；detail low/high/auto/original；仅 user |
| `messages[user].content[input_audio]` | — | — | R | 音频不支持 |
| `messages[assistant].content` | TextPart | `messages[assistant].content` | E | |
| `messages[assistant].tool_calls` | ToolCallPart | `messages[assistant].tool_calls` | E | id/name/arguments |
| `messages[assistant].reasoning_content`(extra) | ThinkingPart | `messages[assistant].reasoning_content` | E | 原生回传；tools+thinking 续轮必需（§4.3） |
| `messages[assistant].refusal` | — | — | R | 拒绝文本不可表达 |
| `messages[assistant].audio` | — | — | R | 音频 |
| `messages[assistant].function_call`(legacy) | ToolCallPart | `tool_calls` | D | legacy 函数调用→tool_calls；损失：legacy 形态；降级 `tool_call.legacy_function`（§11） |
| `messages[tool].content` | ToolResultPart | `messages[tool].content` | E | |
| `messages[tool].tool_call_id` | ToolResultPart.toolCallId | `messages[tool].tool_call_id` | E | 关联续轮 |
| `messages[function]`(legacy role) | — | — | R | legacy function 角色 |
| `max_tokens` / `max_completion_tokens` | maxOutputTokens | `max_tokens` | E | 1..393216；二者同现取一致值否则 R |
| `temperature`（非思考模式） | sampling | `temperature` | E | 非思考模式有效，直接映射（§4.2） |
| `temperature`（思考模式） | — | — | I | 思考模式上游忽略（设置不报错），透传不改变生成语义 |
| `top_p`（思考模式） | sampling | `top_p` | E | flash 思考有效区间 0.95–1.0；<0.95 上游钳制为 0.95（注记，§4.2） |
| `top_p`（非思考模式） | — | — | I | 非思考上游固定 1.0，忽略传入值（§4.2） |
| `frequency_penalty`/`presence_penalty` | — | — | I | DeepSeek 已废弃无效（S1） |
| `n`(=1) | — | — | I | 上游单 choice，`n=1` 与默认等价 |
| `n`(>1) | — | — | R | 上游单 choice，多候选不可表达 |
| `stop` | stop | `stop` | E | ≤16 |
| `stream` | stream | `stream` | E | |
| `stream_options.include_usage` | usage | `stream_options.include_usage` | E | 网关流式固定注入 true |
| `tools[function]` | ToolSpec | `tools[function]` | E | name/description/parameters |
| `tools[custom]` | — | — | R | 自定义（非 function）工具不支持 |
| `function_call`/`functions`(legacy 顶层) | ToolSpec / toolChoice | `tools[function]` / `tool_choice` | D | legacy 函数调用→tool_calls；损失：legacy 形态；降级 `tool_call.legacy_function`（§11，与 assistant 级行一致） |
| `tool_choice` none/auto | toolChoice | `tool_choice` | E | |
| `tool_choice` required/具名（非思考模式） | toolChoice | `tool_choice` | E | 非思考模式 required/具名可用，直接映射（S1） |
| `tool_choice` required/具名（思考模式） | toolChoice | — | R/D | 思考模式上游 400：未授权→R；授权 `tool_choice.force_to_auto`→D（损失：不强制，退化为 auto） |
| `tool_choice` allowed_tools | — | — | R | 工具子集约束不可表达 |
| `parallel_tool_calls` | — | — | I | 上游无显式开关；透传不保证（注记） |
| `response_format` text | — | `response_format{text}` | E | |
| `response_format` json_object | — | `response_format{json_object}` | E | 须提示模型输出 JSON |
| `response_format` json_schema | — | — | R/D | 上游仅 json_object；未授权→R；授权 `response_format.schema_to_object`→D（损失：不强制 schema） |
| `reasoning_effort` | thinkingEffort | `reasoning_effort` | E | none/minimal/low/medium/high/xhigh/max→上游映射（§4.2） |
| `logprobs`/`top_logprobs` | — | `logprobs`/`top_logprobs` | I | 网关第一阶段不还原 logprobs 输出，透传不改变生成语义（透传或忽略） |
| `seed` | — | — | R | 上游无 seed |
| `user` | userId | `user_id` | E | ≤512B UTF-8 |
| `safety_identifier` | userId | `user_id` | E | 与 user 同义归一 |
| `prompt_cache_key`/`prompt_cache_retention` | — | — | I | 上游自动上下文缓存，无 key |
| `prompt_cache_options` | — | — | R | 不可表达 |
| `store` | — | — | R | 不引入对话存储 |
| `metadata` | — | — | I | 上游无对应；丢弃不改变生成语义 |
| `service_tier` | — | — | I | 上游无对应 |
| `modalities`/`audio`/`prediction`/`web_search_options`/`moderation`/`verbosity`/`logit_bias` | — | — | R | 音频/预测/服务端搜索/审核等不可表达（legacy `function_call`/`functions` 见上方 D 行，不在此重复分类） |

### 5.2 OpenAI Responses 入口 → DeepSeek Chat

| 入口字段 | 内部语义 | DeepSeek 字段 | 类 | 说明/损失 |
|---|---|---|---|---|
| `model` | model | `model=deepseek-flash` | E | |
| `instructions` | SystemPart | `messages[system]`（前置） | E | |
| `input`(str) | TextPart(user) | `messages[user].content` | E | |
| `input[message role=user/developer/system]` | System/UserPart | `messages[system/user]` | E | developer/system→system |
| `input[message].content[input_text]` | TextPart | `content[text]` | E | |
| `input[message].content[input_image]{image_url,detail}` | ImagePart | `content[image_url]` | E | data URL 内联；file_id 阶段 2/3 |
| `input[message].content[input_image]{file_id}` | ImagePart.fileRef | — | R | 阶段 1 仅内联；file 引用阶段 2/3 |
| `input[message].content[input_file]` | — | — | R | 文件输入阶段 2/3 |
| `input[function_call]{call_id,name,arguments}` | ToolCallPart | `messages[assistant].tool_calls` | E | call_id→tool_call id |
| `input[function_call_output]{call_id,output}` | ToolResultPart | `messages[tool]{tool_call_id,content}` | E | |
| `input[reasoning]{content[reasoning_text],encrypted_content}` | ThinkingPart | `messages[assistant].reasoning_content` | E | 信封校验后取 reasoning_text（§7） |
| `input[item_reference]` | — | — | R | 需服务端存储 |
| `input[内建工具调用条目*]` | — | — | R | file_search/web_search/code_interpreter/computer/mcp/shell/apply_patch/image_gen 等 |
| `max_output_tokens` | maxOutputTokens | `max_tokens` | E | |
| `max_tool_calls` | — | — | R | 上游无工具次数上限 |
| `temperature`（非思考模式） | sampling | `temperature` | E | 同 §5.1：非思考有效，直接映射 |
| `temperature`（思考模式） | — | — | I | 同 §5.1：思考模式上游忽略，透传不改变生成语义 |
| `top_p`（思考模式） | sampling | `top_p` | E | 同 §5.1：钳制 0.95–1.0（注记） |
| `top_p`（非思考模式） | — | — | I | 同 §5.1：上游固定 1.0，忽略传入值 |
| `reasoning.effort` | thinkingEffort | `reasoning_effort` | E | none/minimal/low/medium/high/xhigh/max |
| `reasoning.summary`/`generate_summary` | — | — | R/D | 上游无摘要模型；未授权→R；授权 `thinking.display_raw`→D（损失：固定原文推理，非摘要） |
| `reasoning.context`/`mode` | — | — | R | 不可表达 |
| `text.format` text | — | `response_format{text}` | E | |
| `text.format` json_object | — | `response_format{json_object}` | E | |
| `text.format` json_schema | — | — | R/D | 未授权→R；授权 `response_format.schema_to_object`→D（损失：不强制 schema） |
| `tools[function]` | ToolSpec | `tools[function]` | E | |
| `tools[内建*]` | — | — | R | 服务端工具一律拒绝 |
| `tool_choice` none/auto | toolChoice | `tool_choice` | E | |
| `tool_choice` required/具名（非思考模式） | toolChoice | `tool_choice` | E | 非思考模式 required/具名可用，直接映射（S1） |
| `tool_choice` required/具名（思考模式） | toolChoice | — | R/D | 思考模式上游 400：未授权→R；授权 `tool_choice.force_to_auto`→D（损失：不强制，退化为 auto） |
| `tool_choice` 内建/mcp/custom/allowed | — | — | R | |
| `parallel_tool_calls` | — | — | I | 注记 |
| `include`含`reasoning.encrypted_content` | — | — | I | 网关固定输出信封，无需 include 门控 |
| `include`其他值 | — | — | R | file_search/web_search/computer/code_interpreter/logprobs 等结果展开不可表达 |
| `previous_response_id` | — | — | R | 不引入对话存储（设计明令） |
| `store` | — | — | R | 同上 |
| `background` | — | — | R | 后台执行不支持 |
| `conversation` | — | — | R | 对话对象存储 |
| `context_management` | — | — | R | 上下文管理不可表达（`compaction` 非钉住 SDK 字段，按未知字段拒绝） |
| `prompt`（模板） | — | — | R | prompt 模板不可表达 |
| `prompt_cache_key`/`prompt_cache_retention` | — | — | I | 上游自动上下文缓存，无 key（同 §5.1） |
| `prompt_cache_options` | — | — | R | 不可表达（同 §5.1） |
| `truncation` | — | — | R | 上游无自动截断策略 |
| `stream`/`stream_options` | stream/usage | `stream`/`stream_options.include_usage` | E | |
| `user`/`safety_identifier` | userId | `user_id` | E | |
| `metadata`/`service_tier` | — | — | I | 上游无对应；丢弃不改变生成语义 |
| `moderation`/`image`/`access_programs` | — | — | R | 审核/图片生成/访问计划不可表达 |
| `top_logprobs` | — | — | I | 网关第一阶段不还原 logprobs 输出，透传不改变生成语义 |

### 5.3 Anthropic Messages 入口 → DeepSeek Chat

| 入口字段 | 内部语义 | DeepSeek 字段 | 类 | 说明/损失 |
|---|---|---|---|---|
| `model` | model | `model=deepseek-flash` | E | |
| `system`(str\|text blocks) | SystemPart | `messages[system]`（前置） | E | |
| `messages[system]`(对话中) | SystemPart | `messages[system]` | E | Chat 支持 mid-conversation system（S4） |
| `messages[user].content`(str) | TextPart | `messages[user].content` | E | |
| `messages[user].content[text]` | TextPart | `content[text]` | E | |
| `messages[user].content[image]{source:base64,media_type,data}` | ImagePart | `content[image_url]{url:data URL}` | E | media_type∈jpeg/png/gif/webp |
| `messages[user].content[image]{source:url}` | — | — | R | 阶段1仅内联；外部 URL 支持延后阶段2/3（入口即 `capability_not_supported`，同 Chat/Responses；绝不把 URL 塞进 `ImagePart.fileRef`） |
| `messages[user].content[image]{source:file,file_id}` | ImagePart.fileRef | — | R | 阶段 1 仅内联 |
| `messages[assistant].content[text]` | TextPart | `messages[assistant].content` | E | |
| `messages[assistant].content[thinking]{thinking,signature}` | ThinkingPart | `messages[assistant].reasoning_content` | E | 信封校验后取 thinking 文本（§7）；不伪造供应商签名 |
| `messages[assistant].content[tool_use]{id,name,input}` | ToolCallPart | `messages[assistant].tool_calls` | E | input(JSON)→arguments(str) |
| `messages[user].content[tool_result]{tool_use_id,content,is_error}` | ToolResultPart | `messages[tool]{tool_call_id,content}` | E | is_error 上游无对应→注记或降级；content 仅文本（字符串/text 块），图片等非文本块第一阶段保守拒绝（R，待 §13.2 T1.14 受控验证） |
| `messages[*].content[redacted_thinking]` | — | — | R | 私有加密块 |
| `messages[*].content[server_tool_use/web_search_tool_result/code_execution_*/mcp_*/container_upload/document/search_result/*_tool_result]` | — | — | R | 私有/服务端块一律拒绝 |
| `max_tokens` | maxOutputTokens | `max_tokens` | E | 必填 |
| `thinking{type:disabled}` | thinkingEnabled=false | `thinking{disabled}` | E | |
| `thinking{type:enabled,budget_tokens}` | thinkingEnabled=true+effort | `thinking{enabled}`+`reasoning_effort` | D | 预算→effort 档；未授权→R；授权 `thinking.budget_to_effort`→D（损失：精确 token 预算→粗档） |
| `thinking{type:adaptive}` | thinkingEnabled=true | `thinking{enabled}`+`reasoning_effort=high` | E | 开思考等价；adaptive"模型自决是否思考"细微差别注记 |
| `thinking{type:between_tools}` | — | — | R | 工具间思考形态不可表达 |
| `thinking.display`(summarized/omitted) | — | — | R/D | 网关固定原文推理；未授权→R；授权 `thinking.display_raw`→D（损失：无法隐藏/摘要） |
| `output_config.effort` | thinkingEffort | `reasoning_effort` | E | low/medium/high/xhigh/max→上游映射 |
| `output_config.format{json_schema}` | — | — | R/D | 未授权→R；授权 `response_format.schema_to_object`→D（损失：不强制 schema） |
| `stop_sequences` | stop | `stop` | E | ≤16 |
| `stream` | stream | `stream` | E | |
| `tools[custom]{name,input_schema,description}` | ToolSpec | `tools[function]{name,parameters,description}` | E | input_schema→parameters |
| `tools[服务端*]`(bash/code_execution/web_search/web_fetch/computer/memory/text_editor/tool_search/browser) | — | — | R | 服务端工具拒绝 |
| `tool_choice{auto}` | toolChoice | `tool_choice=auto` | E | |
| `tool_choice.disable_parallel_tool_use` | — | — | I | 上游无显式开关，忽略（注记，同 §5.1 `parallel_tool_calls`） |
| `tool_choice{none}` | toolChoice | `tool_choice=none` | E | |
| `tool_choice{any}`（非思考模式） | toolChoice | `tool_choice=required` | E | 非思考模式 any→required 直接映射（S1） |
| `tool_choice{any}`（思考模式） | toolChoice | — | R/D | 思考模式上游 400：未授权→R；授权 `tool_choice.force_to_auto`→D（损失：不强制，退化为 auto） |
| `tool_choice{tool,name}`（非思考模式） | toolChoice | `tool_choice`具名 | E | 非思考模式具名可用，直接映射（S1） |
| `tool_choice{tool,name}`（思考模式） | toolChoice | — | R/D | 思考模式上游 400：未授权→R；授权 `tool_choice.force_to_auto`→D（损失：不强制指定工具） |
| `metadata.user_id` | userId | `user_id` | E | |
| `metadata`其他 | — | — | I | |
| `service_tier`/`speed` | — | — | I | 上游无对应；丢弃不改变生成语义 |
| `inference_geo`/`container`/`cache_control`/`diagnostics`/`user_profile_id`/`workspace_id` | — | — | R | 地域/容器/缓存控制/诊断/profile/workspace 不可表达 |
| `temperature`/`top_k`/`top_p` | — | — | R | **2.8.0 SDK 未暴露**；raw 出现按未知字段拒绝 |

---

## 6. 逐事件 SSE 映射表

上游统一为 DeepSeek Chat SSE（S1）：`chat.completion.chunk` 序列 + `data:[DONE]`。usage 在最后一个内容 chunk（§4.6）。网关须还原**调用方协议**的合法事件序、块/条目生命周期、**一次终态**；上游截断/缺终态/非法 JSON→入口协议错误事件并终止，不伪造成功。

### 6.1 上游 chunk 语义分解
- `delta.role`：起始角色（assistant）。
- `delta.reasoning_content`：思考增量 → 内部 ThinkingDelta。
- `delta.content`：文本增量 → 内部 TextDelta。
- `delta.tool_calls[{index,id?,type?,function:{name?,arguments?}}]`：工具增量 → 内部 ToolCallDelta（首片带 id/name，后续仅 arguments）。
- `choices[].finish_reason`（非空）+ `usage`（最后 chunk）：终态 + usage。
- `data:[DONE]`：上游正常结束符。

### 6.2 → OpenAI Chat 入口 SSE
对象 `chat.completion.chunk`，`data:` 帧，`data:[DONE]` 终止。
- 文本：`choices[0].delta.content` 增量；首帧 `delta.role="assistant"`。
- 思考：`choices[0].delta.reasoning_content` 增量（extra 字段，SDK 保留，§11）。
- 工具：`choices[0].delta.tool_calls[{index,id,type,function:{name,arguments}}]`，按 index 增量。
- 终态：最后内容 chunk `choices[0].finish_reason=<映射>`、`delta={}`；**若 `stream_options.include_usage=true`，随后发独立 usage-only chunk（`choices=[]`，`usage={...}`）以还原 OpenAI 合同**（S12；DeepSeek 原生是 usage 随最后内容 chunk，网关须转换）。`usage` 缺失→不发 usage chunk（未知≠0）。
- 结束：`data:[DONE]` 一次。
- finish_reason 映射：stop→stop；length→length；tool_calls→tool_calls；content_filter→content_filter；`insufficient_system_resource`/`aborted`→**错误事件/异常终止**（§8），不得映射为 stop。
- 错误（头已发送）：发 `data:{"error":{message,type,code,param}}` 后终止，不再发 [DONE] 伪造成功。

### 6.3 → OpenAI Responses 入口 SSE
事件名＝JSON `type`，每事件带单调 `sequence_number`。合法序（文本+思考+工具）：
1. `response.created`（response.status=in_progress，output=[]）
2. `response.in_progress`
3. 思考条目（若有 reasoning_content）：`response.output_item.added`(item=reasoning,status=in_progress,output_index) → `response.content_part.added`(part=reasoning_text,content_index) → `response.reasoning_text.delta`* → `response.reasoning_text.done` → `response.content_part.done` → `response.output_item.done`(item=reasoning，含 content[reasoning_text]+encrypted_content 信封)
4. 文本条目：`response.output_item.added`(item=message) → `response.content_part.added`(part=output_text) → `response.output_text.delta`* → `response.output_text.done` → `response.content_part.done` → `response.output_item.done`(item=message)
5. 工具条目（每个 function_call）：`response.output_item.added`(item=function_call) → `response.function_call_arguments.delta`* → `response.function_call_arguments.done` → `response.output_item.done`(item=function_call,call_id/name/arguments)
6. 终态：`response.completed`（response.status=completed，output=全部条目，usage）；长度截断→`response.incomplete`(incomplete_details)；上游异常→`response.failed`(error)。
- usage 仅在终态事件的 `response.usage`（§10）；不发独立 usage 事件。
- 一次终态：completed/failed/incomplete 三选一，恰一次。
- 错误（头已发送）：`error` 事件 `{type:"error",code,message,param,sequence_number}` 后终止。
- 注：`response.reasoning_summary_text.*` 不使用（网关不伪造摘要，§7）；用 `response.reasoning_text.*` 承载原文推理。

### 6.4 → Anthropic Messages 入口 SSE
`event:` 名 + `data:` JSON（带 `type`）。合法序：
1. `message_start`（message：role=assistant,content=[],stop_reason=null,usage 初始）
2. 思考块（若有）：`content_block_start`(index,content_block={type:thinking,thinking:"",signature:""}) → `content_block_delta`(thinking_delta)* → `content_block_delta`(signature_delta=网关信封) → `content_block_stop`
3. 文本块：`content_block_start`(index,{type:text,text:""}) → `content_block_delta`(text_delta)* → `content_block_stop`
4. 工具块（每个 tool_use）：`content_block_start`(index,{type:tool_use,id,name,input:{}}) → `content_block_delta`(input_json_delta.partial_json)* → `content_block_stop`
5. `message_delta`（delta.stop_reason/stop_sequence，usage.output_tokens 累计；可带 output_tokens_details.thinking_tokens）
6. `message_stop`
- 块索引连续；每块 start→delta*→stop 闭合；一次终态（message_stop 恰一次）。
- stop_reason 映射：stop→end_turn；length→max_tokens；tool_calls→tool_use；content_filter→refusal（注记）；`insufficient_system_resource`/`aborted`→错误事件（§8）。
- usage：`message_start.usage` 初始（input_tokens 等），`message_delta.usage` 为最终（output_tokens）；**不要把两者相加**（S10）。缺失→未知≠0。
- 可穿插 `ping`。错误（头已发送）：`event: error`，`data:{"type":"error","error":{"type","message"}}` 后终止；连接在 message_stop 前关闭＝不完整。
- 不发 `data:[DONE]`（Messages 无此帧）。

---

## 7. 思考输出与续轮准入判定（关键）

### 7.1 上游可表达性（S2，已证实）
- DeepSeek 思考输出＝`reasoning_content`（与 content 同级，流式 delta）。
- 续轮：**带 tools 必须完整回传历史 reasoning_content（否则 400）；不带 tools 忽略**。→ 网关必须能从各入口续轮请求中**恢复推理文本**并写入上游 assistant `reasoning_content`。

### 7.2 三入口续轮编码与 SDK 往返证明（§11 实测 20/20 PASS）

| 入口 | 推理输出编码 | 续轮状态载体 | 网关信封 | SDK 往返 |
|---|---|---|---|---|
| Chat | `message.reasoning_content`（extra，SDK 保留） | 客户端原样回传 assistant `reasoning_content` | 不需要（原生明文字段，直接透传上游） | PASS（非流式+流式拼装回传） |
| Responses | reasoning 条目 `content:[{type:reasoning_text,text}]`（**原文，非 summary**），`summary:[]` | 客户端回传 reasoning 条目 | `encrypted_content="llmgw1:<payload>.<hmac>"` | PASS（非流式+流式 final.output 回传） |
| Messages | thinking 块 `{type:thinking,thinking:<原文推理>,signature}` | 客户端回传完整 content 数组（含 thinking 块） | `signature="llmgw1:<payload>.<hmac>"` | PASS（非流式+流式 accumulator 重建 thinking+signature_delta） |

### 7.3 网关命名空间续轮信封（设计建议，现已 SDK 级证实可行）
- 形态：`llmgw1:` 前缀 + base64url(payload) + `.` + HMAC。payload 含项目绑定（projectId）、turn/条目标识、对明文推理文本的完整性摘要；HMAC 密钥归 `ExtensionContext`（禁静态全局）。
- 明文推理文本放在各入口的**明文推理字段**（Chat `reasoning_content` / Responses `content[].text` / Messages `thinking`），信封只承载完整性+项目绑定，**无需把全文塞进签名**，避免膨胀。
- 续轮校验：验 HMAC + 项目绑定 + 推理文本完整性；篡改/跨项目/缺失→`capability_continuation_invalid`（400）。校验通过后取明文推理→上游 `reasoning_content`。
- **不伪造供应商签名**：信封带显式 `llmgw1:` 命名空间，不冒充 Anthropic 加密签名或 OpenAI encrypted_content。Anthropic 真实 API 会用供应商密钥验签（S16），网关信封**不可移植到真实 Anthropic API**——这是预期限制（网关是独立服务端），不影响本网关客户端往返。
- 客户端往返可用性：钉住 SDK 对 signature/encrypted_content 仅按字符串处理，不校验格式；流式 accumulator 从 `signature_delta`/条目重建；§11 受控证明三入口均能原样回传。→ **判定：可行**。

### 7.4 诚实性约束
- 不把原文推理伪称摘要：Responses 用 `reasoning_text`（非 `summary_text`），`summary=[]`；Messages `thinking` 字段承载原文推理（网关合同明示，非 Anthropic 式 summarized 显示）。`display`/`reasoning.summary` 的 Anthropic 式摘要/隐藏语义不可等价→默认拒绝或降级 `thinking.display_raw`。
- 精确预算（Messages `budget_tokens`）不可等价→默认拒绝或降级 `thinking.budget_to_effort`。
- 思考模式 + `tool_choice` required/具名→上游 400→默认拒绝或降级 `tool_choice.force_to_auto`。

### 7.5 判定
**三入口思考输出及续轮：通过（可表达性，SDK 受控证明）。** 无必测入口续轮不可表达。
- 待受控验证（T1.14 受控上游）：网关信封篡改/跨项目拒绝、带 tools 续轮上游 reasoning_content 回传正确性。
- 真实窗口（T1.18）验证：DeepSeek 真实续轮语义连贯性（非 T1.0 阻塞项）。

---

## 8. 图片准入判定（关键）

- **deepseek-flash 接受内联 base64/data URL 图片**（S3 明文，§4.4）。→ 与设计"第一阶段用内联图片"**相容，无阻塞**。
- 三入口内联编码：Chat `image_url.url=data URL`；Responses `input_image.image_url=data URL`；Messages `image.source{type:base64,media_type,data}`→网关拼 data URL。均 E。
- 格式 JPEG/PNG/GIF/WebP（按内容判定）；不支持格式/损坏 base64→`capability_invalid_value`（400），**不静默去图**。
- 仅 user 消息可含图；system/assistant 含图→上游 400→网关前置拒绝。
- 限额（§4.4）：内联单图 32 MiB、请求体 48 MiB、≤600 图等；超限前置拒绝。
- file_id 引用：阶段 1 拒绝（R），阶段 2/3 补回归（设计顺序）。
- **待受控验证**：tool_result 图片经 Chat 上游是否接受（S3 与 S1 schema 表述不一致，§4.4）；必测图片场景用 user 消息内联，不受影响。
- **判定：三入口图片理解（user 内联）通过。** 无阻塞。

---

## 9. usage 映射

缺失＝未知（null），**≠0**，不自行推算为供应商实测。

| 语义 | DeepSeek（S1） | Chat 入口 | Responses 入口 | Messages 入口 |
|---|---|---|---|---|
| 输入 token | `prompt_tokens`(=hit+miss) | `usage.prompt_tokens` | `usage.input_tokens` | `usage.input_tokens`＝`prompt_cache_miss_tokens`（Anthropic 语义不含缓存命中，S10） |
| 缓存命中输入 | `prompt_cache_hit_tokens` | `usage.prompt_tokens_details.cached_tokens` | `usage.input_tokens_details.cached_tokens` | `usage.cache_read_input_tokens` |
| 缓存写入输入 | —（无） | — | `input_tokens_details.cache_write_tokens`=0/未知 | `usage.cache_creation_input_tokens`=0（S10） |
| 输出 token | `completion_tokens` | `usage.completion_tokens` | `usage.output_tokens` | `usage.output_tokens` |
| 推理 token | `completion_tokens_details.reasoning_tokens` | `usage.completion_tokens_details.reasoning_tokens` | `usage.output_tokens_details.reasoning_tokens` | `usage.output_tokens_details.thinking_tokens` |
| 总计 | `total_tokens` | `usage.total_tokens` | `usage.total_tokens` | —（Messages 无 total，按 input+output 语义，不伪造字段） |

- 注意输入语义不对称：Chat/Responses 的输入含缓存命中（cached 为子集）；Messages 的 `input_tokens` 不含缓存命中（命中单列 cache_read）。网关须按各自合同换算，总输入＝miss+hit。
- 推理细分可表达：三入口均有推理 token 字段（上表）。若上游 `completion_tokens_details`/`reasoning_tokens` 缺失→该细分未知：Chat/Responses 省略对应 details；Messages 省略整个 `output_tokens_details`（其 `thinking_tokens` 为必填 int，未知时不得填 0）。
- 流式 usage 位置：Chat 独立 usage-only chunk（include_usage=true）；Responses 终态事件 response.usage；Messages message_delta.usage（§6）。

---

## 10. 错误合同

### 10.1 稳定能力错误码（建议码表，最终以此为准）

| 码 | HTTP | 触发 |
|---|---|---|
| `capability_unknown_field` | 400 | 未知请求字段（含 SDK 未暴露字段） |
| `capability_not_supported` | 400 | 不可表达语义（previous_response_id/store/background/内建工具/私有块/音频/seed/n>1 等） |
| `capability_degradation_not_authorized` | 400 | 需降级但未授权 / 授权了未知降级项 |
| `capability_invalid_value` | 400 | 非法值（损坏 base64、不支持 media_type、越界、max_tokens 冲突等） |
| `capability_continuation_invalid` | 400 | 续轮信封篡改/跨项目/缺失/完整性失败 |
| `resource_not_found` | 404 | 授权失败/不存在统一 404（防枚举） |
| `resource_operation_not_supported` | 501 | 上游不支持的资源操作 |
| `upstream_error` | 502 | 上游 5xx/异常终止（aborted/insufficient_system_resource）/截断 |
| `upstream_rate_limited` | 429 | 上游 429 |
| `upstream_overloaded` | 503 | 上游 503 |
| `internal_error` | 500 | 网关内部错误 |

每个错误带 `fieldPath`（违规字段路径）+ `reason`。上游 401/402/422 等按语义归一（401→网关侧凭据问题按 502/内部处理，不泄露上游凭据；422→capability_invalid_value 或 upstream_error，受控验证确定）。

### 10.2 三入口错误形状

| 入口 | 头未发送（JSON） | 头已发送（SSE 错误事件） |
|---|---|---|
| Chat | `{"error":{"message","type","code","param"}}` + HTTP 状态 | `data:{"error":{message,type,code,param}}` 后终止（不发 [DONE]） |
| Responses | `{"error":{"code","message","param","type"}}` + HTTP 状态 | `event: error`，`{"type":"error","code","message","param","sequence_number"}` 后终止 |
| Messages | `{"type":"error","error":{"type","message"}}` + HTTP 状态 | `event: error`，`{"type":"error","error":{"type","message"}}` 后终止 |

- 头已发送后不得再改 HTTP 状态或伪造成功终态。
- 上游异常终止（aborted/insufficient_system_resource）：非流式→502 错误 JSON；流式→入口协议错误事件 + 终止（Responses 可用 response.failed，Messages/Chat 用错误事件）。

---

## 11. 降级授权清单（X-LLMGW-Allow-Degradation）

默认**空集合**。逐项显式授权方可降级；记录请求授权与实际执行降级；**该头不得透传上游**（HeaderPolicy 之外显式剥离，配正反测试）。未列出的降级一律拒绝；禁止全局开关默许未知字段。

| 降级能力 id | 触发字段 | 语义损失 |
|---|---|---|
| `thinking.budget_to_effort` | Messages `thinking.enabled.budget_tokens` | 精确 token 预算→粗 effort 档（low/high/max），不保证预算上限 |
| `thinking.display_raw` | Messages `thinking.display` / Responses `reasoning.summary`/`generate_summary` | 无法按 Anthropic/OpenAI 语义摘要或隐藏思考；网关固定回传原文推理 |
| `tool_choice.force_to_auto` | Chat/Responses `tool_choice` required/具名、Messages `any`/`tool`——**均限思考模式**（非思考模式直接等价映射，不触发降级） | 不强制调用工具/指定工具，退化为 auto |
| `response_format.schema_to_object` | `response_format`/`text.format`/`output_config.format` json_schema | 不强制 JSON Schema，仅保证 json_object |
| `tool_call.legacy_function` | Chat 顶层 `function_call`/`functions`、`messages[assistant].function_call` | legacy 函数调用形态→tool_calls |

> 必测思考场景（Messages）若用 `thinking.enabled+budget_tokens`，需授权 `thinking.budget_to_effort`；或改用 `thinking.adaptive`（无 budget，无需该降级）。验收 fixture 须固化所选路径与授权头。

---

## 12. 受控往返证明证据（本地，无真实生成）

脚本 `/tmp/llmgw-t10-docs/roundtrip_test.py`（httpx2.MockTransport，钉住 SDK），结果 `roundtrip-results.json`：**20/20 PASS**。覆盖：
- Chat：reasoning_content 经响应模型保留 + 续轮回传；流式 reasoning delta 拼装 + 最后 chunk usage；异常 finish_reason（aborted/insufficient_system_resource）客户端可解析。
- Responses：reasoning 条目 content(reasoning_text)+encrypted_content(llmgw1 信封) 解析 + 回传；流式 reasoning_text.delta、一次 response.completed、usage.output_tokens_details.reasoning_tokens；final.output 续轮回传。
- Messages：thinking 块（llmgw1 签名）解析 + 回传；流式 accumulator 重建 thinking+signature_delta、usage.output_tokens_details.thinking_tokens；input_json_delta 拼装 tool_use.input；tool_result 续轮形态。

这些证明**客户端往返可用性**（T1.0 准入门槛），不证明真实供应商语义连贯性（T1.18）。

---

## 13. 准入结论汇总表

必测能力 × 三入口（通过 / 待受控验证 / 阻塞）。"通过"指可表达性已证实（官方文档 + 钉住 SDK + 受控往返）；真实供应商行为在 T1.18 验证。

| 必测能力 | Chat | Responses | Messages | 依据 |
|---|---|---|---|---|
| 文本/多轮 | 通过 | 通过 | 通过 | S1/S5；system/developer→system，顺序保持，无状态拼接 |
| 客户端工具调用+结果续轮 | 通过 | 通过 | 通过 | S1/S4；tool_call id/参数增量/工具结果映射；auto tool_choice |
| SSE（合法序/一次终态/usage 位置） | 通过 | 通过 | 通过 | S1/S12/S13/S15；§6 事件表；钉住 SDK 事件类型 |
| usage（输入/输出/推理细分，缺失=未知） | 通过 | 通过 | 通过 | S1；§9 映射；SDK usage 类型 |
| 思考输出及续轮 | 通过 | 通过 | 通过 | S2；§7 网关信封 SDK 往返 20/20；带 tools 回传 reasoning_content |
| 图片理解（user 内联） | 通过 | 通过 | 通过 | S3 内联 data URL 证实；§8 |

**总体判定：通过（无阻塞）。** 所有必测能力在三入口均可等价表达或经显式授权降级表达；思考续轮信封与内联图片两大关键门槛均已证实可行。

### 13.1 约束（默认拒绝，非阻塞）
- 思考模式下 tool_choice required/具名/any → 拒绝或降级 `tool_choice.force_to_auto`；非思考模式为等价映射（E，Messages any→required），不触发降级。
- legacy `function_call`/`functions`（Chat 顶层与 assistant 级）→ 拒绝或降级 `tool_call.legacy_function`。
- Messages `budget_tokens` 精确预算 → 拒绝或降级 `thinking.budget_to_effort`。
- `display`/`reasoning.summary` 摘要/隐藏 → 拒绝或降级 `thinking.display_raw`。
- json_schema 强制 → 拒绝或降级 `response_format.schema_to_object`。
- previous_response_id/store/background/conversation/内建工具/私有块/音频/seed/n>1/未知字段 → 拒绝。

### 13.2 待受控验证项清单（T1.14 受控上游，非 T1.0 阻塞）
1. tool_result 图片经 Chat 上游是否接受（S3 与 S1 schema 表述不一致）。
2. 客户端伪造（非模型生成）tool call 注入历史经 Chat 上游的行为（S4 称 Chat 不支持 mid-conversation 插入）。
3. 网关信封篡改/跨项目/缺失拒绝路径；带 tools 续轮上游 reasoning_content 回传正确性。
4. DeepSeek 错误体具体 type/code 取值与 401/402/422 归一。
5. 上游异常终止（aborted/insufficient_system_resource）三入口错误事件/终态还原。
6. Messages `is_error` 工具结果上游无对应字段的处理（注记/降级）。
7. 流式 usage 位置转换（DeepSeek 最后内容 chunk → Chat 独立 usage chunk）SDK 兼容。

### 13.3 真实窗口验证项（T1.18，需用户 go + 凭据）
- DeepSeek 真实思考续轮语义连贯性、真实图片理解结果、真实 usage 数值、真实工具续轮。这些不是 T1.0 阻塞项，但必测场景须至少完整成功一次方称第一阶段通过。

---

## 14. 阻塞项与替代

**无阻塞项。** 两大关键门槛（思考续轮、内联图片）均证实可行：
- 思考续轮：网关命名空间信封（llmgw1: + HMAC + 项目绑定）三入口 SDK 往返可用，不伪造供应商签名（§7，20/20 PASS）。
- 内联图片：deepseek-flash 接受 base64 data URL（S3），与第一阶段内联图片相容（§8）。

若后续受控/真实验证推翻上述任一结论（如上游拒绝 tool_result 图片影响必测、或真实续轮不可用），按设计 §3.1/计划 §6 停下来报阻塞与可行替代，请用户裁决，不擅改范围。可行替代预案（仅备案，未启用）：
- 图片若内联受限：改 Files file_id 引用（但倒置用户阶段顺序，须用户批准）。
- 思考续轮若某入口不可表达：该入口必测失败→阻塞，报用户；不得以"全部拒绝思考"或塞普通文本冒充完成。

---

## 15. 实现交接要点

- 本文是 T1.4–T1.9 编解码的唯一规范；字段/事件名以 §1.1 钉住 SDK 与 §2 来源为准。
- 分类 E/I/R/D 驱动 `CapabilityPolicy`；R→400 稳定码；D→需 §11 授权头；未知字段→R。
- 续轮信封实现见 T1.8（HMAC 密钥归 ExtensionContext，篡改/跨项目拒绝，客户端往返测试）。
- 流式转码须还原入口协议事件序与一次终态；usage 位置按 §6/§9；异常终止不伪造成功。
- 降级授权头剥离 + 正反测试；受控上游 fixture 固化（含授权头路径）。
- 受控验证（§13.2）与真实窗口（§13.3）分离记录；不改写历史测量；不自动提交/推送/发布。