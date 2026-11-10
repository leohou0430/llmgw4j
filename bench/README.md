# 压测口径

> v0.3.1 当前结果与限制见 [性能说明](../docs/PERFORMANCE.md)和[最新 A/B](../docs/verification/perf/2026-11-10/perf-review/README.md)。下文 v1–v13 数字是历史记录，不能当作当前发布性能。

目标：**单变量对照实验**，不是跑个好看的数字。

## 矩阵

| 变量 | 取值 |
|---|---|
| 引擎 | `webflux`（Netty + Reactor） / `vt`（Tomcat 11 + 虚拟线程） |
| 治理 | `on` / `off`（`gateway.governance-enabled`） |
| 运行时 | `jvm` / `native` |
| 场景 | `json`（非流式） / `sse`（流式，8 chunk + `[DONE]`） |

共 8 组 × 2 场景 = 16 条结果。

## 公平性约束

1. 两个引擎共用 `llmgw-governance/llmgw-governance-default` 一份实现和 `JdkHttpUpstream` 一个上游客户端，唯一变量是并发模型。
2. 所有请求打内置 `MockUpstreamServer`，延迟与 chunk 数固定（`gateway.mock.delay-ms` / `chunks` / `chunk-interval-ms`），不受公网抖动影响。
3. 每组单独起进程，跑完 kill，避免同进程抢 CPU / 堆 / GC 污染数据。
4. 每组先跑 1 轮预热，再跑 3 轮正式，取 p50 / p99 / RPS / 错误率的中位数。
5. `wrk` 只做裸吞吐交叉验证，不替代 k6 的自定义指标。
6. `MockUpstreamServer` 的故障注入只由 `bin/smoke-stream-errors.sh` 的 prompt 触发；全部 k6 payload 不含任何标记，压测路径与 v1/v2 完全一致。v5 的标记共五类：`mock-fail-after-N`（抛异常断连）、`mock-eof-after-N`（干净关闭不发 `[DONE]`）、`mock-status-N`（上游返回 N + JSON 错误体）、`mock-done-then-fail`（发完 `[DONE]` 再断连）、`mock-echo-hash`（非流式回显整个请求体哈希）。正常流在 v5 只是每行多一次布尔判断和一次计量分支，不改写任何 chunk。

## 准入阈值覆盖（关键，别删）

`application.yml` 的演示默认值是 `quota=1,000,000`、`rate-limit=100,000 tokens/s`、`burst=200,000`。
在 50 VU / 30s 下这些阈值几秒内就会打满，于是 `governance=on` 组会退化成测「429 拒绝的延迟」：
p50 约 0.25ms、RPS 虚高到 2.4 万、`error_rate=1`，而 `sse` 组因为 json 组已把账本/令牌桶耗光而 100% 失败。
`summary-20261106-072224.csv` 与 `summary-20261106-075524.csv` 都是这种无效数据，已在 README 标注。

`bin/bench-matrix.sh` 因此对**每一组**（含 governance=off）统一附加命令行覆盖：

```
--gateway.governance.default-quota-tokens=2000000000
--gateway.governance.rate-limit-tokens-per-second=2000000000
--gateway.governance.rate-limit-burst=2000000000
--gateway.api-keys[0].quota-tokens=1000000000000
```

效果是让准入判定在整个矩阵期间**永远不会拒绝**，但治理代码路径完整执行：
API Key 查找、配额比较、原子令牌桶 CAS、缓存查找、虚拟线程旁路的账单 / 审计 / 指标。
所以 `governance-enabled` 仍是 on/off 之间的唯一变量，测到的是治理本身的开销，不是拒绝率。
配置文件里保留小默认值，是为了让演示环境仍能真实触发 429 / 限流行为。

## 缓存隔离

`k6-*.js` 的 payload 带有 `RUN_TAG`（每个 VU、每个 k6 进程都不同）+ `__VU` + `__ITER`，
保证 warmup 与 3 轮正式之间不会重复命中 `CaffeineSemanticCache` 的精确哈希缓存。
否则 `governance=on` 会变成「缓存命中率」测试，而不是治理开销测试。

v5 起缓存键从 `hash(model + promptText)` 改为 `hash(完整请求体)`。旧键会让
`temperature` / `max_tokens` / `tools` / 多模态 content 不同但 prompt 相同的两个请求
互串答案；新键是保守的精确缓存（宁可 miss，不可错答），压测 payload 仍然每次唯一。

## 上游延迟剖面与 wire 实验（v5 新增）

主矩阵固定 `MOCK_DELAY_MS=20` + `MOCK_CHUNK_INTERVAL_MS=10`（8 chunk），
即每个 SSE 请求由上游贡献约 100ms。这是刻意的：它证明在真实 LLM 推理延迟下，
网关层的引擎/运行时差异被淹没。但它**测不出网关自身开销**。

`bin/bench-matrix.sh` 现在把这两个值显式传给每个进程，并写入 CSV 末尾两列
`mock_delay_ms` / `mock_chunk_interval_ms`。把它们都设成 0 就是独立的
**wire 实验**：上游几乎瞬时返回，端到端延迟主要由网关（协议解析、治理、
Netty/Tomcat、HTTP 往返）构成。

```bash
# 独立实验，不要与主矩阵逐行相减：上游剖面是另一个变量
MOCK_DELAY_MS=0 MOCK_CHUNK_INTERVAL_MS=0 \
  DURATION=15s WARMUP_DURATION=3s ROUNDS=3 ./bin/bench-matrix.sh
```

判定规则：wire 结果只与**同一份 wire CSV 内部**做引擎/运行时配对比较；
既不能与主矩阵横比，也不能与 v1–v4 横比。`analyze-matrix.py` 用
`csv.DictReader` 按列名读取，追加两列不影响旧 CSV 的分析。

## GC / 内存布局实验（2026-11-07 新增）

wire 剖面 + `JAVA_BIN` / `JVM_OPTS` 注入的单变量 GC 对照，两个驱动脚本：

- `bin/bench-gc-matrix.sh`：Oracle GraalVM 25 可用收集器矩阵
  （g1 / zgc / g1-coh / g1-pause10 / parallel / serial），汇总
  `gc-experiment-summary.csv`（run1 全量备份在 `gc-experiment-summary-run1.csv`）、
  GC 暂停统计 `gc-experiment-gclog.csv`。
  注意：Oracle GraalVM 的 JVMCI **不支持 Shenandoah**（`GraalError`），ZGC 暂停行在
  `{gc,phases}` 标签组，必须 `-Xlog:gc*`，裸 `-Xlog:gc` 会把暂停统计成 0。
- `bin/bench-shenandoah.sh`：Eclipse Temurin 25（解压在 `tools/`）内的 G1 vs
  Shenandoah A/B，50/100 VU 两档，汇总 `shen-experiment-summary.csv` /
  `shen-experiment-gclog.csv`。**必须同 JVM 内对照**——拿 Temurin 与 Oracle GraalVM
  横比会把 Graal JIT vs C2 的差异混进 GC 结论。
- `-Xmx2g` 钉堆对垒：`summary-20261107-082604.csv`（Shenandoah）vs
  `-082932.csv`（G1）。

结论摘要与完整数据表见根目录 `README.md` 5.7 节。批间噪声显著，**只做同批配对比较**。

## v7 热路径优化 A/B（2026-11-07 新增）

同批三臂 + executor 池扫描 + 终局确认，全部 wire 剖面：

- 三臂（5 轮中位数）：`summary-20261107-094128.csv`（v6）vs `-094700.csv`（v7 默认
  executor）vs `-095232.csv`（v7 + executor=16）。ARM2 暴露了 SPI 默认方法吃掉单次解析
  优化的坑：**4 参 `cache` 重载必须在实现类里真正 override**，否则回退 3 参重新解析。
- executor 扫描（v7.1）：8/16/32/64 线程，`summary-20261107-101336/101703/102057.csv`，
  拐点 32 → 代码默认值定 32。
- 终局确认（v7.2，5 轮中位数）：`summary-20261107-103046.csv`（v6）vs
  `-103618.csv`（v7.2）——JSON RPS +18.7%、SSE RPS +17.7%、p99 双降。
- jar 快照：`v6-before-perf.jar` / `v7-new.jar` / `v7.1-jar.jar` / `v7.2-final.jar`。

## v8 载荷剖面、微基准与配对 A/B（2026-11-07 新增）

- **载荷旋钮**：`PAYLOAD_BYTES=4096` 让 k6 请求体带上 4KB 填充（模拟 RAG 上下文），
  默认 0 时 payload 与全部历史数据**逐字节一致**。CSV 新增 `payload_bytes` 列，
  **0 与 >0 是两类独立实验，不可横比**。
- **网络无关微基准**：`bin/run-parse-microbench.sh`（源码 `bench/micro/ParseMicrobench.java`），
  用 Maven 解析真实 classpath，交错轮次对比 v7.2 树解析与 v8 流式解析，
  输出 ns/op、每次分配字节数与成本分解。不受 k6/网络/上游噪声影响。
- **交错配对驱动**：`bin/bench-paired-ab.sh`（`ARM_A` / `ARM_B` / `PAYLOAD_BYTES` / `PAIRS`），
  每轮背靠背跑两臂、下一轮反序（ABBA），各取中位数，用于抵消宿主机负载漂移。
- **数据质量红线**：2026-11-07 下午宿主负载一度达到 `vm.loadavg≈111`、1017 个进程
  （Chrome / ChatGPT / Metal 编译服务等），`summary-20261107-1404*.csv` 至
  `-1441*.csv` 这批端到端数据 RPS 掉到 2000–5000（正常 ~11000）且 p99 剧烈抖动，
  **已判定污染、只作方向参考、不得作为结论**。压测前必须先看 `sysctl -n vm.loadavg`。
- **有效数据**：`v8.1-parse-microbench-final.csv`（微基准，含成本分解与 URL 归因）、
  `paired-ab-20261107-150316.csv`（4096B 配对明细）与 `-151331.csv`（0B 配对明细）。
  配对结果：p99 8/8 全胜、RPS 14/16 胜；大载荷 JSON RPS +44%、p99 −78%，
  SSE RPS +42%、p99 −40%；小载荷吞吐打平但 p99 −67%。详细表见根目录 README 5.9 节。
- Netty `ioWorkerCount` 扫描（4/8/16/24，`summary-20261107-1404*.csv` 至 `-1415*.csv`）
  无可靠赢家，**保持默认 16**。该批同样受外部负载干扰。

## v9 I/O 层实验（2026-11-07 新增，结论：两项候选均不采纳）

- **带负载采样的链式驱动**：`bin/bench-v9-io-ab.sh`，支持 `PAIRS` / `DURATION` / `VUS`，
  全程每 15s 记录 `vm.loadavg` 到 `bench/results/v9-load-*.log`。两臂分别是
  kqueue 原生传输与池化 direct 响应 buffer，都以 `v8.1-final.jar` 为对照，互不混淆。
  **看中位数前必须先看逐轮一致性 + 负载时间线**：本轮两次实验负载都从个位数
  飙到 100+，端到端数字只作方向参考。
- **kqueue**：`-Pkqueue` 给 app 加 `netty-transport-classes-kqueue` +
  `netty-transport-native-kqueue:osx-x86_64`（Apple silicon 换 classifier）。
  用 `jcmd <pid> Thread.print` 确认启用：对照 `reactor-http-nio`、实验
  `reactor-http-kqueue`。数据 `paired-ab-20261107-165602.csv`（4 对，2/4 噪声）、
  `-172309.csv`（3 对，最干净轮次 JSON 反向，中位数 +42% 系漂移扭曲）→ **不设默认**。
- **direct buffer**：`bin/run-buffer-microbench.sh` + `bench/micro/BufferMicrobench.java`
  （网络无关、ABBA 交错、ThreadMXBean 量分配字节）。三次运行 104B 0.83–1.05×、
  238B 0.63–1.05×、分配降 0–1.5% → 无收益；`paired-ab-20261107-172857.csv`
  端到端方向偏负 → **已回退**，v8.1 仍是终版。
- **classpath 提示**：解析带内部 SNAPSHOT 依赖的模块时不要用
  `dependency:build-classpath -pl`；`run-buffer-microbench.sh` 直接从对照 fat jar 的
  `BOOT-INF/lib` 提取依赖，保证版本与被测产物一致。
- 上游连接复用诊断：压测中 9090 端口 `ESTABLISHED` 稳定 100、`TIME_WAIT=0`，
  keep-alive 正常，此路无优化空间。
- 实验快照：`v9-kqueue.jar` / `v9.1-direct.jar`；微基准 `v9-buffer-microbench*.csv`。

## v10 能力建设与零回归验证（2026-11-07 新增）

- 三项能力（AI 可观测 / 路由热配置 / 可选 Redis 分布式状态）见根目录 `README.md` 5.11 节。
- **新增三个独立回归脚本**，刻意不并入 `smoke-stream-errors.sh`，让 67/34 的断言数保持历史可比：

  | 脚本 | 断言 | 覆盖 |
  |---|---|---|
  | `bin/smoke-observability.sh` | 30/30（双引擎各 15；2026-11-08 起） | `X-LLMGW-*` 响应头、`llmgw.ttft/upstream/overhead` 指标、审计计时字段、流式仍收到 `[DONE]`、metrics 无 NaN、**`engine` 标签等于当前引擎且不得出现 `engine=unknown`**（此前为 26/26） |
  | `bin/smoke-hot-reload.sh <engine>` | 10/10（webflux 与 vt 各一轮） | 40 路在途 SSE 跨 reload 零中断、权重 1:3→3:1 即时生效、非法配置 400 且版本不变、admin token 401、不回显 apiKey |
  | `bin/smoke-redis-cluster.sh` | 7/7 | 本地 Redis 6399 + 双实例；默认 jar 无 Redis 类；共享账本 vs 两个独立内存账本；两实例快照一致 |

- **性能裁决口径**：`v8.1-final.jar`（改动前）vs `v10-default-pre-redis.jar`（改动后），
  `bin/bench-paired-ab.sh` 的 ABBA 交错配对，固定 wire 剖面
  （`MOCK_DELAY_MS=0` / `MOCK_CHUNK_INTERVAL_MS=0`）、`PAYLOAD_BYTES=0`、
  `PAIRS=3` / `DURATION=15s` / `VUS=50`。只看**同轮配对差值与逐轮一致性**，
  不看跨批绝对 RPS。新增的计时 CAS、两个响应头、`AtomicReference` 读必须落在噪声带内。
- **不参与矩阵的实验**：`-Predis` 产物没有配对 RPS 基线，Redis 模式是「延迟换一致性」，
  只做正确性验证，不进 `bench-matrix.sh`；流式 TTFT 只在指标里，响应头只有提交前可算的量。
- 产物：`v10-default-pre-redis.jar`（v10 默认 arm）、`v10-redis.jar`（Redis arm）、
  `v11-final-default.jar`（2026-11-08 分包重构 + `engine` 标签修复后的默认 arm）、
  `paired-ab-20261107-*.csv` / `paired-ab-20261108-*.csv` 及配套 `.manifest` /
  `.log`，负载时间线 `v11-load-20261108-*.log`。
- **A/B 裁决状态（2026-11-08 更新）：已补跑三轮，仍无有效样本，仅留档**。
  - 首轮 `paired-ab-20261107-185412.csv`：宿主 `vm.loadavg` 13–50，`B,3` 前
    4 分钟卡顿（manifest 18:58:40 → 19:02:45）；JSON 三轮 A/B/A 赢
    （5822→4782、2803→2577、5568→2140），SSE `A,3=1438` 对 `B,3=6620`
    的 4.6× 反向离散。
  - 补跑 `paired-ab-20261108-004610.csv` 与 `-005533.csv`：完全相同的口径
    （`v8.1-final.jar` → `v11-final-default.jar`，wire 剖面 `0/0`、
    `PAYLOAD_BYTES=0`、`PAIRS=3` / `DURATION=15s` / `VUS=50`），
    全程 10–15s 粒度记录负载（`v11-load-20261108-004610.log` /
    `-005533.log`；第三轮 `-lowvus-20261108-021716.log`）。第二轮开始前特意
    等到 load1 降到 6.78；第三轮 `VUS=20`。

  | 轮次 | load1 起点→峰值 | JSON RPS A/B | SSE RPS A/B | JSON 逐轮 | SSE 逐轮 |
  |---|---|---|---|---|---|
  | `004610` | 4.14 → 144.95 | 5198 / 4048 | 4349 / 3876 | 3×A | 2×B |
  | `005533` | 6.78 → 117.60 | 4743 / 4385 | 3874 / 4054 | 2×A | 3×B |

  | 轮次 | load1 起点→峰值 | JSON RPS A/B | SSE RPS A/B | JSON 逐轮 | SSE 逐轮 |
  |---|---|---|---|---|---|
  | `004610` | 4.14 → 144.95 | 5198 / 4048 | 4349 / 3876 | 3×A | 2×B |
  | `005533` | 6.78 → 117.60 | 4743 / 4385 | 3874 / 4054 | 2×A | 3×B |
  | `021716`（VUS=20） | 4.48 → 59.67 | 5650 / 5627 | 4932 / 4485 | p50 1A/2B | 3×A |

  **两轮 50 VU 中位数方向互相反转**：第一轮 B 的 p50 / p99 / RPS 全面偏慢
  （JSON p99 23.4→25.9ms、RPS −22%），第二轮 B 的 p50 / p99 反而全面略优
  （SSE p99 32.9→28.1ms、RPS +4.6%）。新发现是：即便起点 load1 只有 4–7，
  **50 VU wire 压测自身**在 1 分钟内就把 load1 推到 30–100+（16 核），
  之后单调上行，ABBA 交错无法抵消这种单调漂移；`pgrep` 确认实验结束后
  无 java / k6 残留进程，不是外部任务污染。按数据质量红线，这两轮
  **既不能声称零回归，也不能声称退化**。
  第三轮把 `VUS` 降到 20 后负载峰值降至 59（仍高于核数），JSON 两臂打平
  （RPS 差 0.4%，B 的 p50/p99 略优），但 **SSE 三轮同向 A 占优**（+7% / +37%
  / +48%，幅度不稳且 `A,1` 6901 RPS 为离群值）；方向性观察是 B 的 SSE
  端到端可能存在小幅真实开销——与 v10 在 SSE 每帧上新增计时 CAS 的设计
  一致——但三次实验均不满足负载平稳前提，不能作零回归裁决。
  可确认的只有错误率 ≤0.005%、p50 3.0–11.5ms、p99 5.3–36ms、RPS 3589–7795
  处于同一数量级，**没有数量级级退化**；JVM 67/67 与双引擎启动 + 链路冒烟全绿。
  默认热路径的零回归结论需要在**专用空闲宿主**，或降低 `VUS` / 加轮间冷却
  使 load1 全程稳定后再跑；`v11-final-default.jar` 与全部 CSV / 负载日志
  保留供复现。

## 固定指标

- `gateway_latency_ms` p50 / p99（k6 自定义 Trend）
- `http_reqs.rate`（RPS）
- `gateway_failure_rate`：v4 起 SSE 场景有三条 check（HTTP 200、含真实 `data: [DONE]` 行、**不含 `event: error`**）。此前只查 `body.includes('[DONE]')`，会被网关伪造的终止帧或错误文案里的 `[DONE]` 字样骗过；现在带内错误也会计入失败率，压测本身能发现静默截断
- 进程内存峰值 KB（`bench/results/<case>.rss` 为逐轮采样原值，CSV 取峰值）+ `rss_source` 来源列。
  采样顺序：`ps -o rss= -p <pid>`（真实 RSS）→ macOS
  `footprint -f bytes --noCategories -p <pid>` 的 `phys_footprint`（字节换算 KB）→ `NA`。
  本机沙箱对 `/bin/ps` 与 `/usr/bin/top` 返回 `operation not permitted`，因此实际走 `footprint`。
  **两个来源不等价**（footprint 还计入压缩页），绝不能把 `ps` 与 `footprint` 的数字混在同一比较里；
  `NA` 表示未采集、不是 0，此时不得用该列下结论。
- 冷启动时间（启动日志 `Started LlmGatewayApplication in X seconds`）

## 构建顺序（改了 Java 代码必须遵守）

`build-native.sh` 内部是 `mvn -Pnative clean package`，AOT 生成的类会编进
`llmgw-app/target/*.jar`。编完 native 直接跑矩阵，JVM 组就会用到**AOT 固化过 profile 的 jar**，
与 native 组的代码产物不一致，数据不可比。正确顺序：

```bash
./bin/build-native.sh webflux    # -> bin/native/llmgw-webflux
./bin/build-native.sh vt         # -> bin/native/llmgw-vt
./bin/build.sh                   # 重建干净的普通 JVM jar（会清 target，但二进制已复制出去）
./bin/bench-matrix.sh            # JVM 组用普通 jar，native 组用各自二进制
```

## 运行

```bash
# 完整：默认 VUS=50 DURATION=30s ROUNDS=3（约 30s × 3 × 16 = 24 分钟以上）
./bin/bench-matrix.sh

# 快速冒烟
DURATION=10s WARMUP_DURATION=2s ROUNDS=1 ./bin/bench-matrix.sh

# 只跑单组，用于复现 / 排障
ENGINES=webflux GOVS=on RUNTIMES=native MODES=json \
  DURATION=8s WARMUP_DURATION=2s ROUNDS=1 ./bin/bench-matrix.sh

# 编 native（每个引擎单独编，AOT 构建期求值 @Profile）
./bin/build-native.sh webflux
./bin/build-native.sh vt

# 单场景手工跑（注意：手工跑不带准入覆盖，高并发下会被 429 打满）
ENGINE=webflux GOVERNANCE=on RUNTIME=jvm DURATION=10s VUS=50 \
  k6 run bench/k6-json.js

# wrk 交叉验证
./bench/wrk-baseline.sh

# 回归：改了流式 / fallback / 完成判定 / 准入 / 计量 / 管理接口后必须先过
# JVM 双引擎 67 项断言；单个 native 二进制 34 项（含 40 路并发 SSE 防回归）
./bin/smoke-stream-errors.sh
BINARY="$PWD/bin/native/llmgw-webflux" ./bin/smoke-stream-errors.sh webflux
BINARY="$PWD/bin/native/llmgw-vt"      ./bin/smoke-stream-errors.sh vt
# native 也验证 engine 标签（EnvironmentPostProcessor 在 AOT 镜像里同样生效）
BINARY="$PWD/bin/native/llmgw-webflux" ./bin/smoke-observability.sh webflux
BINARY="$PWD/bin/native/llmgw-vt"      ./bin/smoke-observability.sh vt

# v10 独立回归（30 / 10+10 / 7 项，不并入 67/34）
./bin/smoke-observability.sh
./bin/smoke-hot-reload.sh webflux && ./bin/smoke-hot-reload.sh vt
./bin/smoke-redis-cluster.sh

# 改动前后零回归裁决（ABBA 配对，wire 剖面）
ARM_A=bench/results/v8.1-final.jar ARM_B=bench/results/v10-default-pre-redis.jar \
  PAIRS=3 DURATION=15s ./bin/bench-paired-ab.sh
```

脚本结束时会自动检查每组 `error_rate > 1%` 并打印 `WARNING ... INVALID`。

## 产物

- `bench/results/summary-<runid>.csv`：汇总，每行一组场景；末尾两列记录该轮 mock 延迟剖面，用于区分主矩阵与 wire 实验
- `bench/results/<case>-<mode>-r<n>.json`：k6 原始 summary export
- `bench/results/<case>-<mode>-r<n>.log`：k6 控制台输出
- `bench/results/<case>.rss`：该组逐次内存采样（KB）
- `bench/results/<case>.rss.source`：每次采样来源（`ps` / `footprint`），CSV 的 `rss_source` 由此汇总
- `bench/results/gateway-<runid>.log`：网关启动日志（冷启动数据来源；每组重写，最终保留最后一组）

## 结果分析（可复现）

```bash
python3 bench/analyze-matrix.py bench/results/summary-<runid>.csv
python3 bench/analyze-matrix.py --compare old.csv new.csv
```

输出完整表、分场景变异系数、冷启动汇总、治理 on/off 配对差异、有效性检查
（`error_rate > 1%` 标 INVALID、`rss_source` 是否唯一且非 NA）。
`--compare` 给逐组版本差值，但脚本会明确提示：**跨轮次的绝对差值不构成因果证据**。

## 写报告前必查

- [ ] 上游确实是 mock，没有打真实 API
- [ ] 每组是否单独进程、是否有预热
- [ ] p99 是否取了三轮中位数
- [ ] native 组是否真的用 native 二进制（CSV 不应出现 SKIPPED）
- [ ] 错误率 < 1%，否则该组数据作废
- [ ] `governance=on` 的 SSE 组 p50 是否与 `off` 同一量级（若仍只有 0.2ms，说明准入覆盖没生效）
- [ ] `rss_kb=NA` 时不要写内存结论；`rss_source=footprint` 时只写数量级/趋势，不写「严格 RSS」
- [ ] 同一份比较里所有行的 `rss_source` 必须一致，否则内存数据不可比
- [ ] `bin/smoke-stream-errors.sh` 全绿（含 `mock-eof-after-0/N` 静默截断、`mock-status-N` 非 2xx、`[DONE]` 后断连、缓存键、计量、管理接口脱敏、断连结算、**40 路并发 SSE 零 502**）；否则 SSE `[DONE]` 检查通过不等于「流完整」。串行 curl 测不出共享连接被误取消，v5 第一版正是靠 50 VU 短跑才暴露 16% 失败率
- [ ] 比较前先看 CSV 的 `mock_delay_ms` / `mock_chunk_interval_ms`：两份 CSV 剖面不同就不能逐行相减

## 正式结果（2026-11-06，四个版本）

四版均用同一口径：`VUS=50 DURATION=30s WARMUP_DURATION=5s ROUNDS=3`，
8 组 × 2 场景、耗时约 28 分钟、`EXIT=0`、16/16 场景 `error_rate=0`、SSE `[DONE]` 全通过。
v1/v2 的 `rss_kb` 全为 `NA`（当时脚本只会 `ps`，被沙箱拒绝），不给内存结论；
v3 起脚本增加 `footprint` 兜底并输出 `rss_source`，16/16 采到，见「固定指标」。
v4 起 SSE 的 `error_rate` 额外包含「真实 `data: [DONE]` 行 / 无 `event: error`」两条 check，
与前三版口径略有差别（正常流四版均为 0）。

| 版本 | 产物 | 代码状态 | 用途 |
|---|---|---|---|
| v1 | `summary-20261106-081741.csv` | WebFlux 非流式仍用 `boundedElastic` + 阻塞 `send`；JVM jar 可能带 AOT 产物 | 修复前基线（有效，但不可逐点相减做性能归因） |
| v2 | `summary-20261106-092107.csv` | 两条数据面全异步 / 全阻塞虚拟线程；两个 native 重编；`build.sh` 重建干净 JVM jar | 公平对照基线 |
| v3 | `summary-20261106-111405.csv` | 流式错误契约修复 + 请求体单次解析 + mock 断流注入 + 内存采样补齐；两个 native 再次重编 | v3 正式口径 |
| v4 | `summary-20261106-132434.csv` | SSE 完成判定（未见 `[DONE]` = 失败）+ native 管理接口 AOT 反射修复 + k6 SSE 更严格 check | v4 正式口径 |
| **v5** | **`summary-20261106-155049.csv`** | **上游状态/取消/背压、配额原子预留、计量与缓存键、管理面脱敏、40 路并发防回归；两个 native 再次重编；CSV 新增 mock 延迟两列** | **当前正式口径（20/10 主矩阵）** |
| **v5-wire** | **见 5.7 / `mock_delay_ms=0` 的那份** | **同一份代码，上游零延迟，独立实验** | **只能在该 CSV 内部配对比较** |

- v2 关键结论：SSE p50 CV 2.1%、RPS CV 2.6%，引擎/运行时/治理差异仍被 100ms 上游延迟淹没；
  SSE p99 CV 从 v1 的 34.8% 收敛到 7.7%（但 SSE 代码未改，不归因于异步化）；
  native 冷启动 0.263s vs JVM 4.351s，约一个数量级；JSON 场景 p50 CV 29.5%，依旧不能排座次。
- v1 的 `webflux + native + governance=on` 尾延迟逐轮恶化（p99 73.7→122.4→117.7ms、max 606ms、
  rps 4909→3535），v2 同组 p99 稳定在 35.8–36.7ms。推断与 `boundedElastic` offload 相关，
  但属强相关推断，非严格因果证明。
- v3 关键结论：SSE p50 CV 3.1%、RPS CV 3.7%，引擎差异仍被 100ms 上游延迟淹没；
  JVM 冷启动 4.998s vs native 0.114s（倍数随负载变化，只说「快一个数量级」）；
  JSON p50 CV 20.4% 仍不能排座次；内存首次有数（JVM 687–742MB、native 142–480MB，
  约 1/3 量级，但同运行时内波动 3.4× 且 footprint ≠ 严格 RSS）。
  **v3 的价值是正确性与口径，不是性能提升**：v2→v3 的 JSON RPS 在 −37%～+44% 间乱跳，
  SSE 正常路径代码未改却普遍 −0.5%～−4.7%，跨轮次绝对差值不构成因果证据。
- v4 关键结论：16/16 场景在更严格的新 check 下 `error_rate=0`（正常流未被误判为截断、无带内错误帧）；
  SSE p50 CV 3.2%、RPS CV 3.5%，引擎差异仍被 100ms 上游延迟淹没；
  JSON 场景治理 on/off 配对 8 组方向一致（RPS −7.7%～−14.6%），本轮 JSON RPS CV 仅 11.5%，
  是历次噪声最低的一轮，但仍只说「本轮内可分辨」；
  JVM 冷启动 3.605s vs native 0.144s；内存 JVM 688–760MB、native 101–179MB（约 1/5 量级）。
  **v4 的价值是正确性与 AOT 兼容性**：v3→v4 的 JSON RPS 整体上跳 +20%～+116%、SSE 却普遍 −1.5%～−2.8%，
  且 v4 机器负载明显更轻（JVM 冷启动 4.998s → 3.605s），跨轮次绝对差值依旧不是性能证据。
- v5 关键结论：16/16 场景 `error_rate=0`、内存 16/16 采到、SSE 完成 check 全通过；
  修复前 50 VU 短跑 SSE `error_rate=16%`（`Request cancelled`）归零，且 40 路并发防回归在
  JVM 双引擎 + 两个 native 全过。SSE p50 CV 2.7% / RPS CV 3.2%，引擎差异仍被 100ms 上游延迟淹没。
  **治理 on/off 配对方向在 v5 反转为 6 负 1 正**（vt-jvm 为 +15.9%），说明 v4 那轮「8 组方向一致」
  不能外推成治理成本百分比；JSON p99 CV 54.3% 仍不能排座次。
  JVM 冷启动 4.107s vs native 0.266s（15.5×，五版 10.5/16.5/43.8/25.0/15.5×，只说快一个数量级）；
  内存 JVM 614–928MB、native 109–180MB（约 1/5 量级）。
  **v5 的价值是正确性、安全、资源与口径，不是提速**：v4→v5 JSON RPS 均值 +13% 但 SSE 几乎不变，
  机器负载更重（JVM 冷启动 3.605→4.107s）且治理层代码大改，跨轮次绝对差值不是因果证据。
- 完整解读见根目录 `README.md` 5.1–5.7 节。
- 历史**无效 / 非结论**数据：`summary-20261106-072224.csv`、`-072536.csv`、`-075524.csv`
  （准入阈值未覆盖导致 429 打满 / 仅 JVM 短跑）；
  `summary-20261106-102748.csv` 是 8s/1 轮 RSS 采样冒烟（native 仍是 v2 二进制）；
  `summary-20261106-152012.csv` 是并发取消缺陷修复前的单组短跑（SSE `error_rate=16%`）；
  `summary-20261106-152417.csv` 是修复后的单组 12s 验证冒烟。
  以上均**不作性能结论**，保留未删。
