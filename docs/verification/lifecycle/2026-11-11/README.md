# 生命周期与第1步细节审查（已完成）

阶段1已完成：9项收尾门槛通过，见[最终核对](phase-1-completion.json)。三profile各211项、九套固定JAR契约、四组64慢读/45秒、62容量样本，以及当前WebFlux native构建与四套功能契约通过。下文保留各候选和原始失败过程；不将中间结果替代最终产物。
阶段以[三步收尾清单](../../../plans/2026-11-11-three-stage-review.md)为准。第1步已通过收尾门槛，当前进入第2步审查，第3步尚未开始。最新机器可读状态见 [work-status.json](work-status.json)。

## 已复现并修复的候选变更

超时 CF 在取消底层请求之前执行用户回调；用户回调阻塞会延迟取消、阻塞共享 watchdog、拖住其他 deadline 和关闭。映射 Future 的普通取消也有相同的回调顺序问题。Flow 在行回调中通知结束会令完成回调里关闭 transport 自等待。红测试见 [red.log](red.log)、[expanded](red-expanded.log)、[Flow 关闭](red-flow-close.log)。expanded-compile-failure.log 是测试变量命名编译错误，不作为行为红测试。

候选实现将超时的终态通知交给每 transport 所属的虚拟线程 executor，watchdog 只裁定并派发；底层取消先于通知；正常 CF 完成先移除 timer；取消桥显式先传播到私有 parent，避免用户回调先阻塞。正常完成清理 parent 引用。Flow 同时等待 HTTP future 完成和所有行交付，之后才通知正常完成。仅用 HTTP future 作为终态曾导致缓存行丢失，失败保留在 [http-eof-before-drain-failure.log](http-eof-before-drain-failure.log)，已修正。

延迟 timeout 若遇到已经正常完成的 HTTP future，不再取消已可能复用的 transport；[red-late-timeout.log](red-late-timeout.log) 先复现再修复。关闭仍保持优雅等待活动 HTTP 的策略，dispatch 排空后停止本实例的通知资源，不等待用户通知回调返回；关闭一个实例不影响另一个。忽略中断的任意用户代码无法被强制终止。

[最初 8 项绿测试](green.log)、[最终完整 transport/core 回归](transport-final.log)、[候选 app verify](candidate-app-verify.log) 通过；最终 transport suite 含 10 项生命周期测试。尚未将其称为最终三 profile / native 验收通过。

候选产物 `bench/results/lifecycle-2026-11-11/candidate.jar`，SHA 及对应源码摘要在该目录 `candidate.json`；后续若更改解码器必须另冻结新产物，不能重写候选测量。

## 大帧 CPU 热点的原始失败证据

独立 2 CPU / 2 GiB、1 GiB 堆、无 swap 的 WebFlux，64 个真实慢读 socket，1 KiB receive buffer，SSE 每行接近现有 1 MiB 上限、每响应提供约 64 MiB payload，心跳 40ms。

- 初批 10s 客户端响应头预算出现超时；未见 JVM/cgroup OOM。保留 `candidate-slow-near-limit`。
- 单独 JFR 诊断批使用 30s 预算，64 个客户端均成功建立响应（最长约 4661ms），关闭后 64 个上游均取消、active=0，恢复请求 200；该批仅诊断，不与无采样批拼接。保留 `candidate-slow-profile`。
- 使用原始 10s 预算的无采样复查：60 个完成，4 个超时，无 OOM；不能以放宽预算的诊断批替代它。保留 `candidate-slow-recheck`。

全部路径位于 `bench/results/lifecycle-2026-11-11/`，含参数、源码/JAR 摘要、cgroup/proc 资源和网关日志，脚本只清理自己的容器。开启 OrbStack 已得到用户明确授权，没有修改/删除既有用户容器。

[正确负载窗口的 JFR 汇总](slow-profile-summary.txt) 中，275 个执行样本有约 70.55% 包含 JDK `LineSubscriberAdapter.LineSubscription.endOfLine`。这是执行栈采样，不直接当成整网关 CPU 百分比。分析器默认跳过前 15 秒，只覆盖了此短录制尾部，所得 [默认窗口结果](slow-profile-default-window.txt) 不作为热点证据。

独立 JDK 局部 CPU 探针 [原始结果](jdk-line-scaling.jsonl) / [汇总](jdk-line-scaling.summary.json)：输入从 16 KiB 到约 1 MiB，字节数约 64 倍，当前线程解码 CPU 中位数从 0.135ms/行增至 369.2ms/行，约 2741 倍。它支持长行重复扫描的非线性成本，不是网关吞吐倍率。测量时异步/阻塞路径均使用 JDK 行解码器；不得通过降低既有合法行上限或放宽 10s 复查预算来冒充修复。

## 本轮线性解码与断开误分类修复（最终验收仍在进行）

`SseLineDecoder` 替换两条路径的 JDK 行解码，按新到达字节寻找 CR/LF，按需求交付 UTF-8 行；外层保留既有 1 MiB 字节上限。仅有实际行需求时请求下一原始 batch，原始 EOF 与缓存行交付独立完成，正常完成不取消 transport。

6 项新增解码回归覆盖固定种子随机畸形 UTF-8 / Unicode / 随机分片与 BufferedReader 对比、只读 buffer 位置、有限需求 EOF、取消/错误、2 万次重入、并发需求与非法需求。`decoder-red.log` 是新增类不存在的脚手架失败，不是性能缺陷的行为红证据；性能失败由前述真实慢读/JFR/旧解码探针提供。[局部新解码测量](linear-line-scaling.jsonl) 中约 1 MiB 行的 CPU 中位数为 2.40ms，16 KiB 为 0.0347ms；该局部结果不代表整网关吞吐倍率。

初次新解码 64 慢读，WebFlux 心跳场景 64/64、最大响应头 2248ms、断开取消64/64、恢复200、无OOM；VT 64/64 响应头达标，但恢复一次502。独立 VT 重跑和关闭心跳的双引擎各64客户端均恢复200，故保留偶发失败，不以重跑抹除。

随后代码与确定性回归证实：VT 首个数据/心跳写入发生客户端 IOException 时，`chunkSent` 尚未设置，会误记上游熔断失败。现显式标记客户端写入失败，并停止该请求的熔断失败统计和 fallback。`disconnect-red.log` 中两个断开用例为真实行为红证据；第三个上游控制用例起初错误地期待普通 IOException 可以 fallback，已按既有“未知付费结果不重试”规则改用 ConnectException，不能把此测试修正说成生产缺陷。[最终 app verify](linear-final-app-verify.log) 包含三个绿用例及完整 transport/lifecycle 回归。

冻结产物 `bench/results/lifecycle-2026-11-11/after.jar`，本轮源码/产物 SHA 见 [after-artifacts.json](after-artifacts.json)。原 `candidate.jar` 和中间 `linear-decoder.jar` 的测量不冒充最终产物验收。

## 延长负载暴露的后续缺陷（仍待最终复验）

`after.jar` 开启心跳30秒的双引擎各64客户端通过，但 WebFlux 峰值约1593 MiB。随后 `after-slow-no-heartbeat` 无心跳批未在原10秒预算内建立响应，线程栈集中在冷态解码扫描；旧局部探针经过预热，不能覆盖该冷态常数成本。将传输 batch 批量复制为堆字节数组，并将分隔扫描拆为小方法，保留一个原始 batch 的需求约束；`linear-bulk-app-verify.log` 通过。

新 `bulk-decoder.jar`、严格绝对10秒响应头预算下，`bulk-slow-no-heartbeat` 64客户端均建立并持续慢读，但WebFlux在约30秒被cgroup OOM kill（State.OOMKilled=true，exit137），恢复请求连接拒绝。该失败确认更快解码暴露按完整大行预取的出口内存问题，不把它报告成容量通过。

正在将WebFlux SSE出口按最多8KiB的DataBuffer惰性分块，前端按1行需求转换，并将数据/心跳合并的预取从默认值收紧到1。新增回归检查128个socket buffer的需求不会拉取128个1MiB行，以及UTF-8分块后的精确字节与顺序。首版无限同步融合的测试源在Reactor取消discard队列时自旋，线程转储见 `bounded-output-test-hang.txt`；已终止仅本轮测试JVM，改为有限且非同步融合的源以模拟实际HTTP Flow。此夹具修正不冒充生产修复。

## 出口分块候选的真实慢读门槛已通过

`bounded-output.jar` SHA256 `338884488c5615285e55b73eead9ea8152211aa98c84688d32f228efb50149e4`，源码与产物摘要见 [bounded-artifacts.json](bounded-artifacts.json)。独立2C2G、1GiB堆、无swap，每行近1MiB，每响应提供约64MiB，64真实socket，接收缓冲1KiB，每0.5秒只读64字节，持续45秒。客户端响应头现按**绝对10秒预算**实现；较早脚本为每次recv 10秒，原成功数据仍保留，不冒充新预算测量。

[四组汇总](bounded-slow-summary.json)：WebFlux心跳关/开最大响应头7852/3006ms，峰值1171/1310MiB；VT为3296/2911ms，峰值827/882MiB。四组各64客户端全部成功建立与持续慢读，关闭后上游requests64/active0/cancelled64，恢复请求200，无JVM/cgroup OOM。仅本轮容器与网络已清理。它证明此场景的资源与恢复，不保证任意并发/帧长组合都能在2GiB内运行。

完整app验证 [bounded-output-final-app-verify.log](bounded-output-final-app-verify.log) 通过；另补心跳预取1行、128个writer buffer请求、UTF-8完整字节回归 [bounded-output-prefetch.log](bounded-output-prefetch.log) 通过。前述性能/恢复/OOM失败保留，用于说明各次不同候选，不能合并成一次全绿。

## 补查：未完成行的真实读取进展

继续审查时，真实HTTP用例复现异步/阻塞两条路径：上游每50ms持续发送1字节，250ms空闲阈值仍在首行完成前误报超时。两项行为红证据见 [idle-progress-red.log](idle-progress-red.log)。旧文档写的是按完整行刷新，本轮改进为按实际正文读取进展刷新，以避免把持续读取中的较长行当成停读；整体deadline与行字节上限仍有效。

`ReadActivitySubscriber` 在成功SSE和非成功错误体的解码前观察非空正文片段，仅当idle-read启用才装配；关闭此功能的默认路径不增加逐batch计时开销。7项回归覆盖双路径未完成行、缓慢503错误体、真正停读仍然超时，以及空batch/空buffer不刷新、buffer位置和正常完成不取消。完整provider/core见 `idle-progress-transport.log`（前6项），加第7项后的完整app见 [idle-final-app-verify.log](idle-final-app-verify.log)，均通过。

新冻结候选为 `bench/results/lifecycle-2026-11-11/idle-output.jar`，摘要见 [idle-artifacts.json](idle-artifacts.json)。`bounded-output.jar`的45秒慢读及204项三profile/9套HTTP结果仍只属于该旧候选，不冒充本次新产物的最终验收。[新产物三profile](idle-profiles/final-verification.json) 各211项0失败/错误/跳过，核心class与冻结JAR一致；[九套固定产物HTTP回归](idle-contracts/final-verification.json) 全部通过。新增读取进展在双引擎真实HTTP上另验：分片SSE200、缓慢503正文转换为正确502原因，真停读仍502空闲超时，见 `jvm-idle-fixture-check` 与 `jvm-vt-idle-fixture-check`。本次新产物容器/native与性能验收仍在进行。

第一次 `final-verification-first.json` 误选较早`after.jar`，导致class摘要不匹配；定位后已验证`bounded-output.jar`与该次实际构建逐class一致，并保留脚本失败记录。报告开头的误抄摘要已改正，原始测量文件未修改。

## 阶段1最终核对与后续

[9项门槛核对](phase-1-completion.json)全部通过。新WebFlux native二进制SHA256 `6a73673783a2f40dda4b9aca0ac85d4d30af89517bc2dee9f7a8e4e28787cd94`，实际源码输入复查无变化，见[构建/契约](final-native.json)和[源码核对](native-source-check.json)。native流式34项、多协议28项、租户隔离与新增逐字节SSE/错误体/真停读均通过；只作为功能证据，不是native容量测量。

[容量和CPU报告](capacity-results.md)记录最新候选与EOF基线的真实对比，含所有限制和历史失败。以上不包含多副本治理/实际SDK兼容性系统审查。

接下来按[三阶段计划](../../../plans/2026-11-11-three-stage-review.md)执行第2步，再执行第3步。未提交、推送或发布，完整目标仍active。
