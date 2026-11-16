# DeepSeek 实际供应商验收（2026-11-11）

用户指定 DeepSeek、`deepseek-flash` 并提供凭据。通过隐藏输入读取，随后仅向子进程环境传入；未把真实凭据放入命令参数、源码、回归报告或 Git。本目录做过凭据格式泄露扫描，零命中。生成请求上限为 2，单次 `max_tokens=32`，SDK 无自动重试、网关 attempts=1、无 fallback；不上传文件、不调用工具。

[官方模型文档](https://api-docs.deepseek.com/quick_start/pricing)确认此模型名有效；按[官方思考模式配置](https://api-docs.deepseek.com/guides/thinking_mode)显式关闭思考，降低本次最小验收的消耗。未自动替换用户选择的模型。

| 场景 | 结果 | usage.total_tokens | 本次调用耗时 |
|---|---|---:|---:|
| WebFlux / JSON | 完整回答，finish_reason=stop | 10 | 2.24s |
| VT / SSE | 完整回答、协议正常结束、usage 可读取 | 10 | 1.81s |

两次调用均返回 `deepseek-flash`，合计 20 tokens。冻结 JVM 的 SHA、启动参数、实际 usage 与脱敏结果见 [live-results.json](live-results.json)。耗时仅是这两次功能验证的观测值，不能用来建立生产 SLO 或跨引擎性能结论；期间 native 编译仍在运行。

没有验证持续负载、供应商异常/限流、工具调用、真实多模态、账单金额、native 的公网 TLS 或其他供应商。没有追加生成请求来补齐这些场景。

`verify-deepseek.py` 为人工运行的有费用探针，不加入自动 CI；每次运行最多两次生成，且每次重新要求隐藏凭据输入。原来本地 Mock 的验收记录保持不变，本记录只补充实际 DeepSeek 的两个场景。
