# 网关性能审查与优化（2026-11-10）

## 范围与结论

本轮针对请求热路径的 CPU、分配和调度成本。保留两个小改动：SSE payload 只截取一次；WebFlux 读取请求头第一值时不创建多值集合。默认线程数仍为 32，容量、响应体限制、治理和 SSE 完整性保护没有放宽。代码差异见 [production-change.patch](production-change.patch)，冻结产物校验见 [artifacts.json](artifacts.json)。

## 测量与发现

先冻结 before JAR，再做 JFR 定位、单变量线程实验、局部分配基准及无 profiler 的正式 A/B。JFR 使用 profile 设置，分析时排除开始 15 秒。原始录制在 [profile-on/raw](profile-on/raw)，分析在 [WebFlux](profile-on/webflux-analysis.txt) / [VT](profile-on/vt-analysis.txt)，流式分析器在 `bench/jfr/AnalyzeRecording.java`。

- WebFlux 的 CPU 样本中，Reactor MonoFlatMap、JDK SequentialScheduler 等异步桥接栈占比较高（inclusive 分别约 33%、30%，存在重叠，不能相加）。JdkHttpUpstream.onNext、ChunkParser.chat 和 header 第一值复制出现在应用热点中。约 64% 样本没有应用帧，不能全部归类成不可优化框架成本。
- JFR 出现容器 CPU 配额节流：1173 个周期中有 597 个被节流。32 个平台线程在 4 CPU 配额下值得实测，但节流累计时间不是墙钟耗时，也不能单独证明线程数有误。
- SSE 原实现 trim/substrings 重复复制；请求头 headerSet 会构建多值集合。两个改动均保持输入语义和转发内容。
- URI 缓存 `JdkHttpUpstream.targetUris` 是无容量上限的 ConcurrentHashMap。动态资源路径持续增加时存在长期堆增长风险；本轮短请求测试没有覆盖该问题，尚未修改。

## 局部可复现收益

使用真实生产方法，首轮预热后取五轮中位数，ThreadMXBean 测当前线程分配及 CPU。SSE 采用 256 种帧防止常量折叠；请求头基准使用真实 Netty adapter，并检查大小写、重复头第一值和空字符串行为。CSV 与汇总见 [micro](micro)。

| 热路径 | before 分配 B/op | after 分配 B/op | 变化 | CPU ns/op before → after |
|---|---:|---:|---:|---:|
| SSE 64 字符 | 1332.55 | 1036.87 | −22.2% | 590.97 → 557.40 |
| SSE 4096 字符 | 17460.55 | 9100.87 | −47.9% | 4566.13 → 3866.57 |
| WebFlux 13 个请求头 | 8008 | 5464 | −31.8% | 3637.16 → 2096.04 |

这是局部工作量，不能换算成整个网关的同等提速。新增三个 SSE 边界测试覆盖三种协议、UTF 文本、ASCII 控制空白、终止和 usage。

## 未采纳的线程实验

同一 before JAR 比较 4 与 32 线程：[executor-4-vs-32](executor-4-vs-32)。JSON 中位数约 6399 对 5054 RPS，p99 45.43 对 70.04 ms；但 4 线程 SSE 第二轮发生一次截断（66519 个请求中 1 次失败）。失败原始记录保留，该配置排除出成功排名。原因没有隔离确认，不能断言线程数导致，也不能说已修复。默认保留 32。

## 正式 A/B 方法

每个引擎 4 CPU / 2 GB、Xms256m/Xmx1024m、32 上游线程；相同 Nginx 鉴权前置、相同本地 mock。50 VU、wire 0ms 模拟延迟、JSON/SSE 分别测，配置预热 30s，每轮 15s、四轮。四配置轮换使每个配置占每个顺序位置一次。没有 profiler，不访问付费上游。每轮校验完整内容和上游真实请求数；失败轮不删除。治理开启执行本地真实治理，使用隔离测试 key 及大配额避免 429，不含 Redis、远程审核和 embedding。

VT JSON 没有经过本轮两个修改，是噪声参照；若它也变化，不能把所有变化归因于优化。短闭环测试、无 TLS、同宿主资源竞争，不代表生产最大容量或 SLO。JFR 数据不混入吞吐结果，本轮未重测 native。

## 正式 A/B 结果

RPS 表示四轮中位数（最小–最大）；p99 单位 ms，也是逐轮 p99 的中位数。变化列是两个中位数的比值，不是统计显著性结论。

| 治理 | 引擎 | 模式 | before RPS（范围） | after RPS（范围） | 中位数变化 | p99 before → after |
|---|---|---|---:|---:|---:|---:|
| on | webflux | json | 5226 (4165–6261) | 5766 (4323–6202) | +10.3% | 38.74 → 48.50 |
| on | vt | json | 6025 (5356–6628) | 6092 (5090–6924) | +1.1% | 19.62 → 37.77 |
| on | webflux | sse | 3800 (3347–4007) | 4077 (3962–4209) | +7.3% | 41.94 → 36.60 |
| on | vt | sse | 4462 (4310–4754) | 4591 (4515–4781) | +2.9% | 28.01 → 26.83 |
| off | webflux | json | 4621 (3077–4779) | 4186 (3678–4820) | -9.4% | 42.06 → 47.88 |
| off | vt | json | 4719 (4548–4921) | 4713 (4476–5481) | -0.1% | 149.49 → 43.72 |
| off | webflux | sse | 2762 (2695–2858) | 2907 (2823–2937) | +5.2% | 130.89 → 49.13 |
| off | vt | sse | 3130 (2799–3312) | 3088 (3047–3239) | -1.4% | 36.24 → 37.33 |

治理开启 32 样本、2,410,097 请求；治理关闭 32 样本、1,807,673 请求。共 64 样本、4,217,770 请求，全部零错误、上游计数吻合，没有排除项。原始证据：[治理开启](ab-governance-on/)、[治理关闭](ab-governance-off/)，含每轮 p50/p95/p99、k6 原始日志、镜像/资源/JAR manifest。机器可读汇总见 [ab-summary.json](ab-summary.json)。

WebFlux SSE 在两批中位数分别 +7.3%、+5.2%，方向一致，但范围重叠；VT SSE 一正一负。WebFlux JSON 一正一负，且部分 p99 退化；未经过两个修改的 VT JSON 参照路径也有明显 p99 波动。现有数据支持“减少热路径分配”，只提供 SSE 吞吐正向线索，**不支持全面或稳定的整网关提速承诺**。治理 on/off 按批次先后运行，不能直接跨批计算治理开销。需要专用宿主、更长轮次和真实流长度分布验证生产收益。

## 验证与复现

- `mvn -B clean verify`：117 项，115 通过、2 项 Redis 环境测试跳过；随后独立本地 Redis 补跑两项通过。合并最新套件报告共 117 项、零失败/错误/跳过，见 [junit-final.json](junit-final.json)、[完整构建日志](maven-verify.log) 和 [Redis 日志](redis-tests.log)。
- Python 基准/模拟上游/平衡顺序单元测试：8 项通过。
- OpenAPI、application、两份 Compose 与 CI/release YAML 严格重复键校验通过，使用已有 `/tmp/llmgw-engineering-venv/bin/python`（系统 Python 缺 PyYAML）。
- 4KB SSE 的 12000 B/op 分配预算在冻结 before 失败、after 通过；日志见 [micro](micro)，用于确定局部分配差异，不充当端到端吞吐测试。
- 双引擎契约回归：流式错误 67 项、扩展接口 54 项、加固 46 项通过；见 [stream-errors.log](stream-errors.log)、[extensions.log](extensions.log)、[hardening.log](hardening.log)。两引擎租户隔离通过，见 [tenant-isolation.log](tenant-isolation.log)；脚本退出码见 [contract-results.json](contract-results.json)。

冻结的 before/after JAR 保存在本机已忽略目录 `bench/results/perf-review-20261110/`；SHA256 见 [artifacts.json](artifacts.json)。生产文件摘要也在该文件中，确保实际被测代码一致。审查阶段 JAR 和性能录制仅保留本地；后续 v0.2.0 发布纳入文档证据。A/B 对应上述冻结审查 JAR，发布构建改变版本元数据，不代表对发布资产重跑性能测量。

正式对照命令（gov 分别取 on/off，两个批次顺序执行）：

```sh
python3 bench/cross-gateway/compare.py \
  --jar bench/results/perf-review-20261110/after.jar \
  --before-jar bench/results/perf-review-20261110/before.jar \
  --arms llmgw-webflux-proxy,llmgw-vt-proxy,llmgw-vt-before \
  --profiles wire --modes json,sse --governance on \
  --rounds 4 --duration 15s --warmup 30s --output /tmp/llmgw-ab-on
```
