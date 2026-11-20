---
status: accepted
---

# Gemini双向HTTP接入，新增转换限于OpenAI Chat

用户于2026-11-17选择在完善现有OpenAI兼容与Anthropic原生适配之外，新增Gemini对外原生入口与上游原生接入。首版仅覆盖HTTP生成与流式调用，不包含Live/WebSocket；支持范围内的文本、多模态、工具调用、流式事件与用量须列矩阵验证。

Gemini新增跨协议转换限于Gemini与OpenAI Chat双向转换，原生透传独立验收，已有转换能力继续保留。不将新增范围扩大为Gemini与Responses、Messages全部双向互转，也不据此承诺每个上游模型支持全部能力。语义不支持时明确拒绝或取得显式降级授权，延续ADR-0002。

该决定接受首版转换矩阵受限，以控制语义兼容及双引擎验证成本；同协议透传、认证适配和跨协议转换必须在公开文档中分别说明。
