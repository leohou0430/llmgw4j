# 第 3 步：接入兼容性审查（2026-11-11，已完成）

在第 2 步完成十项门槛后启动。使用本地受控 HTTP 上游和真实官方 Python SDK；没有调用付费供应商，没有提交、推送或发布。

后续已完成[当前本机WebFlux native补验](../../native-compatibility/2026-11-11/README.md)和[指定DeepSeek模型实测](../../live-provider/2026-11-11/README.md)。以下保持第3步原始验收范围与失败记录；新结果单独绑定其产物。

## 已修复的三个现有缺陷

| 缺陷 | 真实失败与修复 |
|---|---|
| 文件/转写上传正文仍在，multipart boundary 的 Content-Type 却被公共头策略删除 | `sdk-second/` 两引擎实际收到的上游头均无 Content-Type；`multipart-red.log` 两项失败。共享策略为 FILES/TRANSCRIPTIONS 保留结构性 Content-Type，即使可选透传白名单为空；JSON/响应媒体类型和凭据仍受保护。`multipart-green.log` 和官方 SDK 上传回归转绿。 |
| 全协议路由设 target-model 后，批任务创建正文被额外加入 model 字段 | `sdk-third/` 严格上游拒绝；`batch-model-red.log` 精确复现 JSON 字段增加。UpstreamRequest 在批任务信封上跳过模型重写，原样保留 input_file_id/endpoint/completion_window；普通文本模型别名仍生效。 |
| Anthropic 上游收到两个 anthropic-version，默认版本和 SDK 版本同时出现 | `sdk-header-red/` 记录 `['2023-06-01','2026-10-21']`；JDK builder 对已过滤的透传头使用 setHeader，白名单允许的版本替换默认值。最终逐请求验证只出现一个 SDK 版本头，x-api-key 为路由凭据且无调用方 Authorization。未提供版本时由 provider 使用默认值，另有 24 项 provider 契约。 |

`sdk-first/` 的两类失败含验收夹具问题：固定旧版本预期，以及 Mock 对所有正文均按 JSON 解码。第二轮修正夹具后才确认缺失 multipart 头；不能把夹具失败计入产品缺陷。所有失败记录均保留。最终脚本保存自身副本和摘要，避免只凭后续脚本解释旧结果。

## 实际 SDK 验收矩阵

固定版本：OpenAI Python **3.49.0**、Anthropic Python **2.8.0**、Python **3.14**；完整依赖见 [requirements.txt](requirements.txt)，常规回归入口为 [bin/requirements-sdk.txt](../../../../bin/requirements-sdk.txt)。调用方式参照 [OpenAI 官方 SDK](https://github.com/openai/openai-python)和 [Anthropic 官方 SDK](https://github.com/anthropics/anthropic-sdk-python)；网络文档工具暂不可用时直接读取官方仓库 README，未依赖第三方教程。

| 能力 | WebFlux / VT 证据 |
|---|---|
| Chat JSON、SSE、最终 usage | 两引擎官方 OpenAI SDK 通过 |
| Chat 工具调用及流式参数分片 | 工具名、JSON arguments 拼合、finish_reason 通过；厂商扩展字段仍在上游正文中 |
| Responses JSON / SSE | output_text、类型化 delta/completed 事件、usage 通过；此处测试事件迭代器，不覆盖所有 response stream 辅助聚合 API |
| Messages JSON / SSE | Anthropic 原生 x-api-key 入口、text_stream、get_final_message 的 usage 通过 |
| Anthropic 工具调用 | tool_use JSON input 及 input_json_delta 聚合通过 |
| 模型路由和 provider 认证 | JSON 顶层模型别名、原生协议路径、单一版本头及路由凭据通过；客户端凭据不发送上游 |
| SDK 错误与重试 | 两 SDK 能识别网关 502；OpenAI 明确设置一次重试后恢复；非法 key 401 |
| 完成与截断 | 三种协议正常结束通过；缺少 [DONE] / response.completed / message_stop 时 SDK 明确抛 APIError，不静默成功 |
| 同步/异步客户端 | 两个官方 SDK 均实测异步 JSON 与 SSE；客户端和流使用上下文管理器关闭 |
| 文件 | multipart boundary 与完整字节、上传后 SDK 下载通过；外租户 404 且不进入上游；列表明确 501 |
| 批任务创建 | 先上传同租户输入文件，再由 SDK 创建批任务；不注入顶层 model |
| 转写与语音 | 转写原始 multipart（含 model）不重写，SDK 读取 text；语音响应含非 UTF-8 字节仍完整 |
| Embeddings | SDK 读取浮点向量通过 |
| images / videos / rerank / 批任务生命周期 | 双引擎既有 HTTP 多协议契约覆盖；本轮没有额外声称这些路径经过官方 Python SDK 全流程验收 |

最终 [sdk-ci/sdk-results.json](sdk-ci/sdk-results.json) 共 **52 项检查全部通过**，源脚本副本、JAR SHA 和启动命令同时记录。脚本为 [bin/smoke-sdk-compatibility.py](../../../../bin/smoke-sdk-compatibility.py)，已加入 CI 的默认产物阶段；远端 CI 未触发，本地使用相同依赖与命令验证。

```bash
python3 -m venv /tmp/llmgw-sdk-venv
/tmp/llmgw-sdk-venv/bin/pip install -r bin/requirements-sdk.txt
/tmp/llmgw-sdk-venv/bin/python bin/smoke-sdk-compatibility.py \
  --jar /absolute/path/to/llmgw-app.jar --out /tmp/llmgw-sdk-evidence
```

## 最终产物与回归

- 默认包 `bench/results/compatibility-2026-11-11/sdk-final-default.jar`，Redis 包同目录 `sdk-final-redis.jar`；摘要与源码复查写入收尾记录，不把早期候选作为当前产物。
- `final-profiles/final-verification.json`：default / Redis / Redis,jtokkit,otel 各 **246 项**，0 失败/错误/跳过；共同及 Redis 模块 class 摘要匹配固定产物。模块布局、基础依赖边界、Python native 规则与 OpenAPI 均通过。
- `additional-contracts.json`：最终产物的 24 项双引擎 provider 契约与独立 Redis 双副本恢复/资源归属通过。
- `contracts-final/final-verification.json`：固定默认产物的心跳、单行、流式错误、扩展、加固、多协议、租户、管理及 JWT 九套契约；九套均已通过，流式完整重跑 67/67；[第3步收尾门槛](phase-3-completion.json)全部通过。
- CI YAML / OpenAPI / compose 校验和 SDK 依赖一致性检查另存日志；没有远端 workflow 运行结果。
- 首轮 `contracts/` 保留一次 VT 128 字节上限场景的进程未就绪（66/67）。该轮没有保留被覆盖的启动日志，原因未知，不声称已定位或修复生产启动问题。`vt-413-recheck.json` 的三次独立复验均约 4.5–4.7 秒健康且返回 413。流式脚本现于启动失败时立即打印日志并退出；最终重跑流式及后续套件，最初两个成功套件因 JAR SHA 完全相同而保留复用，未虚构新执行。

## 保留的兼容边界

1. 协议透传按路由选择，provider 只选择上游认证方言，不把 Chat 转成 Messages/Responses；未实测真实 OpenAI、Anthropic、DashScope 等供应商、其 TLS/限流/账号权限或真实模型。
2. 上游非成功状态在首 chunk 前归一为网关 **502**，不保持供应商的 401/429/503 错误类型；SDK 的错误分型和默认重试会因此变化。可显式设 `max_retries=0`，避免客户端与网关的重试叠加。首 chunk 后只能用带内错误终止，不能切换 fallback。
3. `target-model` 不改 multipart 内的 model，也不改已上传 JSONL 的逐行模型；上传/批任务中的模型应由调用方按真实上游填写。JSON 模型别名通过测试，不代表上传内容能自动转换。
4. 文件/批任务列表及 Redis 运行时 key CRUD 为 501；跨租户或无归属资源为 404。资源绑定有 TTL，不提供跨供应商资源迁移。
5. SDK 版本固定在本报告，未测其他语言/旧 SDK/未来版本、OpenAI realtime/WebSocket、完整视频 SDK 上传协议、所有 Responses 辅助方法与所有供应商扩展。
6. 上传、下载仍整包缓冲，本轮只测功能，没有上传/下载的 2C2G 容量结论。第 2 步性能对照和第 1 步 native 都属于各自冻结产物；本轮没有新 native/AOT 构建，也不把旧 native 的成功算作新修复已验证。
