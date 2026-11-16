# 2026-11-11 上游 SSE 单行边界

本轮接续 [过载拒绝优化](../../perf/2026-11-11/overload/README.md)，补齐 JDK transport 的单行缓冲边界。没有重新解释上轮性能数据，也没有宣称本轮吞吐提升。

## 缺陷复现与实现

异步 `fromLineSubscriber` 和阻塞 `ofLines` 都由 JDK 在完整行到达前缓冲。原有非流式/错误体总字节上限不适用于成功 SSE；整体/空闲超时不能代替单行字节边界。

真实本地 HTTP fixture 持续发送 2 MiB 无换行正文并保持连接。两个测试要求在 2 秒内失败，原实现均等待超时，见 [red.log](red.log)；添加检查后无需等待 EOF、换行或 5 秒整体 deadline 即报错，见 [green.log](green.log)。这不是通过实际耗尽堆内存复现 OOM。

新增包内 `SseLineLimitSubscriber` 包裹两个现有 JDK BodySubscriber，在 JDK 解码器接收字节之前扫描。只保存每响应独立的字节计数，不复制或保留正文 ByteBuffer，不移动原 buffer 的 position；UTF-8 解码、CR/LF 行拆分和 Flow demand/cancel 仍交给原 JDK subscriber。遇到超长行时取消该响应订阅并发送一次错误，正常完成不取消 transport。

配置 `gateway.upstream.max-sse-line-bytes` 默认 `1048576`（1 MiB），必须大于 0。按原始字节计数，CR/LF 不计；不是字符数、SSE event 的累计大小或整个流的大小。注释行也适用。使用大帧的供应商需按实际数据调节，默认值是本项目保护策略，不是供应商协议上限。非成功响应仍按 `gateway.max-response-bytes` 总量限制。

## 覆盖

- 真实 HTTP：异步、阻塞均在无换行且正文仍打开时拒绝超长行。
- JDK 行解码：逐字节拆开的四字节 UTF-8、CR/LF/CRLF、只读且有偏移的 ByteBuffer、精确等于上限、EOF 前最后一个无换行短行。
- 原始 UTF-8 字节数超过阈值时只报错一次，取消该响应；两个响应的限制与状态独立。
- 约 8 MiB 合法短行流正常完成；逐行 demand 与取消透传，正常完成不取消 transport。原有 40 路同 transport sibling 流回归继续执行。
- 双引擎真实 HTTP，以 CLI 配置单行 256 字节、非流式上限 64 字节、并发名额 1：首行超限返回 502；已发送首个数据后超限返回 in-band error；精确 256 字节注释、Unicode 和累计大于 4 KiB 的流成功；后续请求可使用释放的名额；两次错误记入失败账本，上游各调用一次。见 [http-line-limit.log](http-line-limit.log)。

首次集成测试错误地假设 CHAT 错误后不能有 DONE；实际已有契约是 `event: error` 后用单个 `[DONE]` 收尾，并按失败记账。已修正测试并保留 [初次日志](http-line-limit-contract-assumption.log)，没有修改生产 CHAT 协议。responses/messages 的完成语义由现有双引擎协议回归覆盖。

## 产物与验证

默认、Redis、Redis+jtokkit+otel 三种配置各 **171 项 JUnit，0 失败、0 错误、0 跳过**，见 [profiles.json](profiles.json)。Redis 为本轮独立本地进程，核对服务进程 ID 后才使用，结束时只清理本轮实例。三种验证构建的 core、engine-api、WebFlux、VT、JDK transport 的全部 class 与 HTTP 被测冻结 JAR 逐类摘要一致，见 [artifacts.json](artifacts.json)。冻结 JAR 位于 `bench/results/sse-2026-11-11/after.jar`，不覆盖上轮产物。

模块布局检查 71 项通过；原生边界检查覆盖 160 个 Java 文件、20 个模块、9 个描述符、21 个命名扩展，Python 边界回归 6 项通过；[文档/工具检查](checks.json) 通过。既有双引擎 9 套 HTTP 回归全部通过：流式/错误 67 项、扩展 54 项、加固 46 项、多协议 56 项、供应商 24 项、平台 48 项，以及两引擎租户隔离、6 种管理鉴权组合和 JWT JSON/SSE 声明检查。见 [contracts.json](contracts.json) / [计数汇总](contract-summary.json)。新增单行边界集成测试两引擎也全部通过；所有 HTTP 测试均固定本轮冻结 JAR。

## 限制

该检查增加一次线性字节扫描，没有新增正文副本；计数边界不等于整个进程的硬内存上限，JDK 解码器及 HTTP 缓冲仍占用空间。未测生产供应商、TLS、大帧分布、2C2G 吞吐变化或进程峰值 RSS；本轮没有 AOT 配置变更，未重新构建 native，不能将上轮 native 结果当作本轮证据。未提交、推送或发布。
