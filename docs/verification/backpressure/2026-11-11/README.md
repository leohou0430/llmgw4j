# 2026-11-11 心跳、背压与超时隔离审查

本轮延续 [SSE 单行边界](../../sse/2026-11-11/README.md)，检查慢消费者是否阻塞共享资源，以及心跳是否改变错误响应。没有做新的吞吐 A/B，也没有改写前几轮测量。

## 已复现问题

1. VT `streamRawBlocking` 原先在 transport 的单线程 watchdog 中调用 `sink.onChunk` 写心跳。一个阻塞的心跳写可占住该线程，使另一请求的正文整体超时不能执行。真实本地 HTTP + 受控阻塞 ChunkSink 的回归中，第二请求 250ms deadline 未生效，等待 1 秒仍无超时结果。
2. VT 心跳定时任务在 HTTP 请求发出后就启动，早于上游响应头；WebFlux 也在订阅时启动心跳。开启心跳时，上游响应头延迟会提前提交 200 SSE，使首字节超时无法返回本应有的 502 JSON。WebFlux 真实 HTTP 的失败日志明确记录了 `200 text/event-stream`、若干心跳及流中错误。
3. WebFlux 将 interval 与真实数据合流，慢消费者不请求数据时，第 32 次 tick 因缺少需求触发 OverflowException，连带终止真实数据流。

证据：[阻塞心跳红测试](red.log)、[WebFlux HTTP 红测试](webflux-red.log)、[心跳需求红测试](heartbeat-demand-red.log)。最初线程断言版本保留在 [red-thread-check.log](red-thread-check.log)。

## 最终实现

- VT 使用原生 JDK BodySubscriber/Stream 桥：继续由 JDK 解码 UTF-8 与拆行，只交接一行数据和一个结束信号。当前行的 sink 写入返回后才请求下一行；等待下一行期间由请求线程发送心跳，禁用心跳时直接阻塞等行。没有新增后台线程或写任务队列。
- VT 在成功响应头确认后才迭代正文；非成功响应沿用总量受限的错误体读取，不写心跳。共享 watchdog 不再承担阻塞心跳写入。
- 正常 EOF 不取消 HTTP 订阅；未完成流发生写失败/取消时关闭所属订阅。实例状态按响应独立。JDK 逐行 demand 下的 EOF 无换行末行遗漏在测试中被捕获，见 [eof-line-red.log](eof-line-red.log)；仅在必要时给内部行解码器补一个 EOF LF，保留原有末行、CR/LF、空流和不完整 UTF-8 的替换行为，不额外输出空行。
- WebFlux 只有在 HeaderListener 收到 2xx 后才启动心跳；错误、结束、取消停止该分支。在 interval 分支加 backpressure drop，只丢弃没有需求的心跳 tick，真实 SSE 数据仍遵循原有背压。
- 并发名额、超时配置、账本和 CHAT/其他协议的错误收尾规则保持原契约。心跳默认仍关闭。

## 覆盖与产物

[最初绿测试](green.log) 覆盖共享 deadline 不被阻塞心跳拖住、心跳等待响应头且在请求线程执行。最终测试另外覆盖非成功响应没有心跳、慢写入不继续请求输入、写失败仅取消所属订阅、正常完成不取消 transport，以及 UTF-8/CRLF/EOF 行兼容性。

[心跳 HTTP 集成](smoke-heartbeat-deadline.py.log) 在两引擎分别验证：开启 40ms 心跳时，500ms 首字节超时保留 502 JSON；503 上游慢速错误正文不提前提交 200；成功空闲流收到心跳和单个 DONE；后续请求成功且两次错误按失败记账。[单行边界 HTTP 回归](smoke-sse-line-limit.py.log) 也通过。

冻结产物位于 `bench/results/backpressure-2026-11-11/`。`before-webflux.jar` 是已包含 VT 修改、尚未修复 WebFlux 的阶段产物，仅用于 WebFlux HTTP 红测试；不能冒充全变更基线。完整旧基线为上轮 `bench/results/sse-2026-11-11/after.jar`。默认、Redis、Redis+jtokkit+otel 三种配置各 **179 项 JUnit，0 失败、0 错误、0 跳过**，见 [profiles.json](profiles.json)。Redis 使用核对进程身份后的本轮独立实例，已清理，见 [实例记录](redis-instance.json)。三种构建与冻结最终 HTTP JAR 的 core、engine-api、WebFlux、VT、JDK transport 有序 class 内容摘要一致，见 [artifacts.json](artifacts.json)。本轮 2 套专项和 7 套既有 HTTP 回归均通过：流式/错误 67 项、扩展 54 项、加固 46 项、多协议 56 项，另覆盖两引擎租户隔离、6 种管理鉴权组合和两引擎 JWT JSON/SSE 声明检查；专项为心跳状态/超时与 SSE 单行边界。见 [contracts.json](contracts.json) / [计数汇总](contract-summary.json)。没有将上轮供应商/平台脚本结果计入本轮 HTTP 证据。

模块布局 71 项、原生依赖边界（164 个 Java 文件、20 个模块、9 个描述符、21 个命名扩展）及 6 项 Python 边界回归通过；[文档与工具检查](checks.json) 也通过。

## 未覆盖与风险

受控阻塞 sink 证明的是共享超时隔离，不等同于真实 TCP 慢读客户端的资源压测。没有测量峰值 RSS、2C2G 吞吐变化、TLS/供应商延迟或容器写超时；不保证任意忽略中断的自定义 sink 都能及时结束自己的请求，也不宣称已消除所有慢客户端资源风险。VT 新读取桥改变了预读策略，性能需另测。未重新构建 native；本轮无 AOT 配置变更，历史 native 结果不作为当前证据。未提交、推送或发布。
