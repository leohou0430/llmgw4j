# 协议收敛资料核对（2026-11-12）

状态：调研事实与待解决问题，不是已批准的映射设计或兼容性声明。用户已确认三种对外入口需要语义转换；首期供应商为DeepSeek，模型固定deepseek-flash，三种对外入口统一到上游Chat；文本、工具、SSE、usage、思考和图片均纳入验收。具体转换实现仍待设计批准。

## 当前代码边界

`ProtocolKind`明确描述同协议透传；`RouteTable`按协议和模型选择路由，`UpstreamRequest`支持模型字段改写和认证方言，但没有形成三种协议之间请求/响应/SSE的转换合同。现有SDK与流式通过记录不能当作新转换能力的验收证据。

## 官方资料核对

| 对象 | 已核对事实 | 资料 |
|---|---|---|
| Chat Completions | 流式对象使用`chat.completion.chunk`，响应包含choices及finish_reason等字段 | [OpenAI Chat API](https://developers.openai.com/api/reference/resources/chat) |
| Responses | 使用独立的输出条目结构；文档包含response.created事件、工具调用条目及previous_response_id字段 | [OpenAI Create response](https://developers.openai.com/api/reference/resources/responses/methods/create) |
| Messages streaming | 内容块有start/delta/stop事件；工具输入可通过input_json_delta/partial_json分片传送，消息另有message_delta和message_stop | [Anthropic Messages streaming](https://platform.claude.com/docs/en/build-with-claude/streaming) |

上述链接为本次实际查阅后的官方地址；原platform.openai.com及docs.anthropic.com地址已跳转。资料字段可能演进，实施前须固定所采用的合同及SDK版本。

## 对后续设计的影响（分析，未选方案）

不能仅替换URL、认证头或事件名称来声称实现语义收敛。后续能力矩阵至少需要区分文本、工具调用/结果、增量工具参数、usage、正常终止、错误/截断，以及各协议专有字段。

特别需要明确无法表达的能力如何处理；官方支持某能力，不代表所选另一上游也支持。此前的严格完成判据、取消与背压要求继续保留，不能为兼容输出伪造成功完成。

本记录不指定供应商、模型、费用或转换实现架构；未发起模型生成调用。

## DeepSeek补充核对

用户确认DeepSeek后，进一步核对其官方资料：

- 提供[Chat Completions接口](https://api-docs.deepseek.com/api/create-chat-completion/)。
- 提供[Responses格式接口](https://api-docs.deepseek.com/guides/responses_api/)，有独立流式终止事件，不使用Chat的DONE帧。
- 提供[Anthropic兼容入口](https://api-docs.deepseek.com/guides/anthropic_api/)，文档同时列出兼容差异，例如忽略thinking.budget_tokens、把tool_choice.any作为auto处理。

因此三种对外入口不一定都必须转换才能调用DeepSeek；但用户已经要求网关自身具备语义转换，后续必须显式测试异协议组合，不能用DeepSeek原生兼容入口成功替代网关转换验收。此处仍是官方文档核对，没有新增模型生成请求，也没有确认具体模型、费用预算或全部兼容能力。

## DeepSeek文件资料（2026-11-12）

[官方Files API](https://api-docs.deepseek.com/guides/files_api/)描述图片上传、分页列表、元数据查询与删除，单文件最高64MiB，格式为JPEG/PNG/GIF/WebP；上传图片可供deepseek-flash引用。已查阅页面未列出文件内容下载端点，因此不能仅根据网关现有通用下载入口承诺DeepSeek支持该操作。供应商文件账户共享范围不能代替网关已确认的租户/项目授权边界。
