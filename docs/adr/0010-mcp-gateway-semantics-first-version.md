---
status: accepted
---

# MCP 网关语义首版：Modern 下游与单向 Legacy 桥接

用户于2026-11-20依据《AI Agent Handbook》第9章9.3选择在本网关实现MCP Gateway治理语义，作为新能力域llmgw-mcp交付。首版下游仅声明支持Modern（2026-07-28，逐请求元数据）；Legacy（initialize握手）仅作为上游桥接目标，采用单次交换隔离——每次下游交换内完成上游握手、发出目标RPC后丢弃会话标识，不长期持有上游协议会话，代价是重复握手。Legacy下游到Modern上游的反向路径未声明支持。

四步准入顺序固定：认证由统一入口完成并把凭据映射为稳定主体；协议校验执行JSON-RPC结构、Modern必需元数据（_meta协议版本与clientCapabilities，允许空对象）与HTTP镜像头一致性（MCP-Protocol-Version与_meta一致、Mcp-Method与方法一致、具名方法Mcp-Name镜像自身目标参数，其余方法不强制名称镜像）；授权按主体×服务器×工具默认拒绝，tools/list按同一授权谓词过滤可见清单，tools/call独立再次鉴权；转发按上游RPC重构出口请求，不透传下游Cookie、会话标识、内部路由头与调用方凭证，上游凭证仅来自配置的受控引用。

出口目标只能来自配置声明的服务器与URL，模板不承载可变主机，构成无SSRF面。订阅与Tasks扩展默认关闭，需显式开启。能力发现与清单可见不代表已授权、健康或可执行；schema校验不替代授权。

审批策略标记为require-approval的工具调用返回202并挂起，接入审批工作流（ADR-0011）重新准入后才可执行。每次工具调用记call_id/attempt_id并写审计，模型生成的tool_calls意图与工具实际执行结果用可区分事件类型记录。首版返回单次JSON交换，上游SSE响应解析出结果帧后以JSON返回；服务端主动流式不在首版声明范围。
