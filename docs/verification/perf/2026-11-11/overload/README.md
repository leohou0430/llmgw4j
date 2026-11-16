# 2026-11-11 过载拒绝成本审查

本轮延续 2 CPU / 2 GiB、单副本、64 个全生命周期请求名额的配置，分析 96 VU 过载时成功吞吐与尾延迟恶化。此前容量批次保留在 [容量审查](../../../capacity/2026-11-11/README.md)，不改写为本次证据。本轮基线是上轮已修复 transport 的冻结 after JAR（SHA256 `946c4d7baf2c70c8d03cb6324f7dbd59ca6332aec53f1d8c44d2be598358ae82`），不是最初的 v0.3.1 原始产物。

## 复现与定位

- 短启动复现：64→96 VU，成功吞吐约 809→648 RPS，诊断比值 0.80；启动/JIT 尚未稳定，不作为生产容量或改进幅度。
- 独立 JFR 采样：64 VU 约 1311 RPS、p99 93ms，96 VU 约 1161 RPS、p99 228ms；采样有额外开销，不混入非采样 A/B。
- 每个正式采样窗口仍有 C2 编译事件；以请求窗口切分，避免把启动栈当成稳态开销。连接建立耗时很低，未支持“连接重建是主要问题”的假设。
- 分配采样中，框架参数解析器的缓存/ConcurrentHashMap 初始化权重在过载窗口显著增加。JFR 栈采样包含 native poll 等 OS 等待栈，不能将所有样本百分比直接解释为 CPU 占用；CPU 配额与节流另看 cgroup 计数。

固定错误体原本已用字符串转义拼接，没有逐次调用 ObjectMapper；目前没有证据支持把 JSON 序列化当成主要根因。

## 实施

原链路：路由匹配 → Spring 参数解析 → controller 限并发 → 数据处理/响应。
新链路：路由匹配 → 限并发 → Spring 参数解析 → 数据处理/响应。

`WebfluxCapacityAdmission` 显式包裹标准 RequestMappingHandlerAdapter，仅处理实际匹配到 WebfluxGatewayController 的方法。它同时作为 WebFilter 在整个响应生命周期末尾释放名额，不能在 HandlerResult 产生时释放：HandlerResult 中的异步响应此时尚未执行。lease 记录持有者，只有所属实例释放；取消、同步异常、正常结束均清理。

删除 controller 内四处重复 Semaphore 包装，避免重复准入。VT 保持原准入位置，两引擎的容量拒绝均新增 `Retry-After: 1`；其它错误不会伪造此容量提示。没有放宽名额，没有新增等待队列、盲重试或业务治理 Spring 依赖，也没有独立硬编码 URL 白名单。未知路径、错误 HTTP method 和管理接口继续由原框架处理。固定 503 JSON 和响应类型保持一致。

## 快反馈

```bash
mvn -B -pl llmgw-engine/llmgw-engine-webflux -am test \
  -Dtest=CapacityAdmissionTest -Dsurefire.failIfNoSpecifiedTests=false
```

`admission-red.log`：3 项中 1 项失败，满载第二个请求仍调用框架 adapter，期望次数 1、实际 2。`admission-green.log`：相同 3 项通过；最终补充实例隔离、同步异常、管理/404/405 等检查。使用真实 DispatcherHandler、框架 adapter 与真实 controller，mock HTTP transport 和可控上游 future，避免只检查 helper 返回值的假绿。

## 实测与验证

正式 A/B 使用冻结 before/after JAR、2C2G、相同 mock 与治理，64/96 VU，各四轮 15s、30s 64 VU 预热、交替执行。不开 JFR，也不并行 Maven/native 构建。该批 19 个样本无非预期错误，上游计数一致。96 VU 成功 RPS 中位数基线/提前准入为 1920.1/1878.8，p99 为 223.90/224.59ms，没有端到端吞吐改善；拒绝数反而由 270,413 增至 284,223。闭环客户端拒绝更快后会继续加速发送，不能把“拒绝更多”当成收益。64 VU 首轮仍有明显编译预热变化，所有轮次保留，不能认定稳态容量。详见 [闭环原始汇总](closed-loop/matrix.md)。

另做固定到达率对照：6000/s、256 个负载端 VU，以及 4500/s、1024 个负载端 VU。两组各 8 个正式样本都因负载端丢弃迭代而无效，输入压力未匹配，不用于性能结论。完整数据保留于 [6000/s 失败批次](arrival-6000-invalid/matrix.json) / [4500/s 失败批次](arrival-4500-invalid/matrix.json)，没有通过删除失败样本或放宽阈值获取结论。

### 实际拒绝路径的局部成本

`OverloadAdmissionBench` 复用真实 DispatcherHandler、框架参数解析器与 controller 的测试装配，保持首个请求占用名额，再反复执行真实拒绝路径。每个独立 JVM 预热 10,000 次、测量 20,000 次，三轮交替，使用 ThreadMXBean 测当前线程分配与 CPU 时间；上游只收到首个请求，adapter 调用次数分别为 30,001 / 1。

| 局部指标 | 原实现中位数 [范围] | 最终实现中位数 [范围] |
|---|---:|---:|
| 分配 B/拒绝 | 17874.91 [17856.31, 17941.86] | 14717.20 [14655.63, 14723.56] |
| 当前线程 CPU μs/拒绝 | 61.98 [57.09, 68.80] | 45.93 [45.65, 56.45] |

局部分配约减少 **17.7%**、CPU 中位数约减少 25.9%。该微基准包含 mock HTTP 对象与计数器，不包含真实 socket、其它线程及完整服务开销，不能推导为网关整体提升 25.9%。复现工具见 `bench/capacity/rejection_micro.py`，数据见 [micro.json](micro.json)。早期未加提示头的微基准单独保留，不与最终产物混算。

### 客户端合作实验

为隔离退避提示的作用，对比“提前准入但无提示”的冻结产物与“相同提前准入 + Retry-After: 1”的最终产物；两臂的负载端都配置为遵循提示，控制臂没有头所以不等待。仍为 2C2G、64 个名额、96 VU、90s 预热、四轮各 15s 正反交错，没有 JFR 或并行构建。

| 指标 | 无提示 | 有提示且客户端遵循 |
|---|---:|---:|
| 成功 RPS 中位数 [范围] | 668.0 [604.8, 1028.4] | 1880.8 [1633.9, 2066.3] |
| 成功 HTTP p99 ms 中位数 | 301.90 | 86.62 |
| 四轮容量拒绝总数 | 59656 | 1916 |
| 四轮客户端退避次数 | 0 | 1916 |

该批 11 个样本（含预热与直连）共 296,050 成功响应、144,349 容量拒绝，非预期错误 0、上游计数一致。提示让客户端在服务端之外退避，不是提高服务器并发或新增排队；**成功 HTTP p99 不包含被拒绝后的 1 秒等待，也不是逻辑请求的重试完成时间**。完整迭代耗时及累计等待保留在 [合作实验数据](cooperative/matrix.json)。没有验证具体生产 SDK 的默认行为，不遵循提示的客户端仍可能压满 CPU。

本轮共享宿主存在系统更新/索引活动，绝对值不能作为专用宿主或生产 SLO；各批次不拼接，不将合作式退避收益归因为单纯的服务器执行加速。

工具沿用 [capacity runner](../../../../../bench/capacity/README.md)，新增可选预热并发、JFR 导出、固定到达率和遵循退避提示的负载模式；每次仍只清理本任务创建的容器/网络。JFR 导出失败也会清理所属容器。原始数据在 `bench/results/overload-2026-11-11/`。


## 最终验证状态

默认、Redis、Redis+jtokkit+otel 各 **164 项 JUnit，0 失败、0 错误、0 跳过**。独立 Redis 启动后核对进程身份，仅操作本次实例；三种验证构建的 engine-api / WebFlux / VT / JDK transport 字节码均与最终性能冻结 JAR 逐类一致，见 [产物索引](artifacts.json) / [测试计数](profile-tests.json)。

命名布局、原生边界及其 Python 回归、OpenAPI 与工作流结构检查通过，最终工具检查见 [final-checks.json](final-checks.json)，关键模块源码摘要见 [final-source-sha256.json](final-source-sha256.json)。

本轮新构建的 WebFlux native 通过流式 34 项、多协议 28 项及租户隔离/容量边界回归（包含容量 503 的 Retry-After: 1 断言），见 [native-contracts.json](native-contracts.json)。构建输入四个关键模块的字节码与最终性能 JAR 逐类一致，见 [native-input-classes.json](native-input-classes.json)；二进制 SHA256 为 `651bd61312af6b264b019ef66c1ffd653040443fc0ed337f46b5217e890c8c58`，见 [摘要](native-sha256.txt)。只验证 WebFlux native，没有新增 VT native 或 native 容量压测。

双引擎 JVM 的 9 套 HTTP 回归全部通过：流式/错误 67 项、扩展 54 项、加固 46 项、多协议 56 项、供应商 24 项、平台 48 项，另覆盖两引擎租户隔离、6 种管理鉴权组合及两引擎 JWT JSON/SSE 声明检查。全部使用冻结最终 JAR，见 [contracts.json](contracts.json) / [计数汇总](contract-summary.json)。JWT 工具补充 `--jar` / `LLMGW_TEST_JAR`，防止 native/profile 重建改变默认被测产物；租户/JWT 工具避免本次两端口相同。本轮没有提交、推送或发布。

历史实验中的 `after.jar` 由阶段性文件名指代，提前准入版本已保存在 `early-admission.jar`，最终版本在 `after.jar`；以各 manifest 的 SHA256 和本报告的产物索引识别，绝不按同名文件混认产物。Docker 守护进程当前不可用，已完成测量保留；本轮没有为最终清理检查重启用户的 Docker 服务。
