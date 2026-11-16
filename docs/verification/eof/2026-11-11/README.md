# 2026-11-11 异步 EOF 末行完整性补查

本轮接续 [心跳与背压审查](../../backpressure/2026-11-11/README.md)。前轮 EOF 兼容处理只放在 BlockingSseBodySubscriber，遗漏了异步 Flow 路径；本轮修正该遗漏，不将旧报告作为新产物证据。

## 复现

JDK 行解码器在有限需求下，清理已耗尽的输入 buffer 时可能跳过 EOF 前没有换行的末行。一次只请求一行时，输入 `data: one\ndata: two\ndata: [DONE]`，原实现只得到前两行，然后正常完成。这里验证的是项目已有原生按行契约，不新增 SSE 事件或 SDK 解析规则。

- 直接驱动生产 SseLineLimitSubscriber + JDK fromLineSubscriber，有限行需求、单次输入后发送 EOF，确定性丢失末行。
- 真实本地 HTTP：源正文没有末尾换行，消费者读取第一行后暂停 100ms，再逐行请求；相同 transport 连续执行四次。修复前首轮的完成行读取就返回 null。

两项测试均失败，见 [red.log](red.log)；修复后完整 transport 测试通过，见 [green.log](green.log)。这些是原生 Flow 层回归，不声称已在真实慢 TCP 下测量出现频率。

## 修复

将已有 EOF 处理移到异步与阻塞共用的 SseLineLimitSubscriber：记录最后一个非空 byte buffer 是否以 LF 结束，只在正常 EOF 且确有未以 LF 结束的输入时，向内部 JDK 行解码器补一个 LF。字节上限仍按原始上游内容计数，CR/LF 不计入。没有额外 HTTP 请求、后台线程或累积正文缓存。

移除阻塞桥中重复的标记和补 LF 逻辑；其单测按生产链路包裹共享入口，原期望输出不变。两条链路仍只由 JDK 执行 UTF-8 解码和行拆分。

没有生成 `[DONE]`、`response.completed` 或 `message_stop`；只使已经收到的末行按原有行读取语义送达。空流不添加空行，已有 LF/CRLF 不重复添加；尾 CR、分片/不完整 UTF-8 沿用原有行为。错误和取消不冲刷待完成的完成标记，正常 EOF 不取消 HTTP transport。

## 验证与产物

新增确定性有限需求测试覆盖无换行/CR/LF/CRLF，以及错误/取消后的末行不发送；真实 HTTP 四次逐行读取覆盖异步正向路径。原有阻塞 EOF、单行上限、心跳、正常结束不取消及 40 路同 transport 并发回归继续执行。

默认、Redis、Redis+jtokkit+otel 三种配置各 **182 项 JUnit，0 失败、0 错误、0 跳过**，见 [profiles.json](profiles.json)。本轮独立 Redis 核对服务进程身份后才使用，已清理，见 [实例记录](redis-instance.json)。三种构建与最终 HTTP 被测 JAR 的 core、engine-api、WebFlux、VT、JDK transport 的有序 class 内容摘要一致，见 [产物与源码摘要](artifacts.json)。模块布局 71 项、原生边界（164 个 Java 文件、20 个模块、9 个描述符、21 个命名扩展）及 6 项 Python 边界回归通过。本轮 2 套专项和 7 套既有 HTTP 回归均通过：心跳状态/超时与单行边界专项，流式/错误 67 项、扩展 54 项、加固 46 项、多协议 56 项，以及两引擎租户隔离、6 种管理鉴权组合和两引擎 JWT JSON/SSE 声明检查。见 [contracts.json](contracts.json) / [计数汇总](contract-summary.json)；[文档与结构检查](checks.json) 通过。没有将历史供应商/平台或 native 结果计入本轮。冻结 JAR 位于 `bench/results/eof-2026-11-11/after.jar`，基线为上轮 `bench/results/backpressure-2026-11-11/after.jar`。

## 范围

没有修改主动关闭 transport 的策略或异步 watchdog 回调线程；它们仍属后续审查。未测生产供应商、真实 TCP 慢读下的 RSS/吞吐，未重建 native；本轮没有 AOT 配置变更。未提交、推送或发布。
