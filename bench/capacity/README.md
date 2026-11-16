# 2C2G 单副本容量实验

依赖：Docker、JDK 25、k6、Python 3；本地已存在 `llmgw-audit:redis` JRE 基础镜像。
工具不会拉取供应商镜像或调用供应商；基础镜像的旧 JAR 会被指定的冻结 JAR 覆盖。

```bash
python3 bench/capacity/run.py \
  --before /absolute/path/before.jar --after /absolute/path/after.jar \
  --output /absolute/path/results --rounds 3 --duration 8s
python3 bench/capacity/slow_clients.py \
  --jar /absolute/path/after.jar --output /absolute/path/slow-results
```

每个网关 Docker 限额为 2 CPU / 2 GiB，禁用 swap，JVM 堆 128 MiB–1 GiB。保持默认 64 并发闸门与 4 MiB 非流式响应上限，启用本地治理。独立 mock 使用 4 CPU / 768 MiB，显式设置 `maxIdleConnections=10000`、`idleInterval=1800s`，避免四个连接池轮转测量时触发默认空闲连接淘汰；负载驱动为宿主 k6，经随机 loopback 映射端口直连网关，没有 Nginx。每次使用 UUID 网络/容器名，finally 只清理本次创建的 ID。

JSON 模拟 20ms 上游耗时，短 SSE 模拟 20ms 首部等待 + 8×10ms 分块；VU 8/32/64/96，各三轮，四个臂（before/after × WebFlux/VT）交替执行。每种模式先做 64 VU 直连 mock 对照及各臂 3s 预热。30s 长流为 32 VU、1s 启动窗口、40s graceful stop，每臂一次；样本少，不可据此推断可靠 p99。各臂顺序反转只能降低部分顺序偏差，不能消除共享宿主噪声和 JIT 影响。

`audit_successes` 仅计通过完整正文检查的 200；503 单独列为 `audit_rejected`，其他状态/错误帧/缺失 DONE 为非预期错误。k6 的通用 `http_req_failed` 会包含合法容量拒绝，不可直接当业务错误率。测后应核对成功数与 mock 请求数，确认没有缓存命中、重试或丢失。

`audit_end_to_end_ms` 使用 k6 `Response.timings.duration`（发送、等待、接收；不含 DNS/建连/blocked 时间），不是完整客户端迭代耗时；减去该响应内 `audit_upstream_ms` 的残差仍含 Docker 网络、调度和排队，**不能命名为纯网关开销**。`audit_preflight_ms` 直接采集 `X-LLMGW-Overhead-Ms`，该名称沿用初版工具：SSE 值仅为前置处理；JSON 值来自上游正文就绪时的本机总时长减上游耗时，也不包含完整响应发送与后续结算。两种口径不混算，不解释为完整转发耗时。资源采样在每轮前后，`memory.peak` 为容器整个生命周期峰值，不能说是该轮独立峰值；CPU 节流用前后差值。

慢读探针每引擎用 8 个 socket，每响应约 16 MiB，缩小接收缓冲，低速读取 3s 后断开；确认上游取消、active 回零以及新请求成功。它不代表生产所有慢客户端或恶意流量的覆盖。

测量时不要并行 Maven/native 构建或其他负载。原始摘要、日志、容器 ID、镜像 ID、JAR/源码 SHA256 和资源快照保存在输出目录。RPS 是闭环 mock 场景测量，不是生产容量承诺；没有覆盖 TLS、真实模型、长时间运行或远程治理。

汇总：`python3 bench/capacity/summarize.py /absolute/path/results /absolute/path/report`，保留每轮数据与原始文件 SHA256；上游计数不符或存在非预期响应的轮次标记无效。不要通过删除失败轮次宣称全批通过。

尾延迟定点复核使用 `--labels before-webflux,after-webflux --modes sse --vus 64 --rounds 4 --duration 15s --warmup 15s --skip-long`；它是独立批次，不能与默认短轮矩阵拼接。


过载诊断支持 `--warmup-vus`、`--jfr`、`--arrival-rate`、`--respect-retry-after`：JFR 为独立采样批次；固定到达率仅用于正式网关轮次，预热/直连仍为 constant-vus，任何 `dropped_iterations` 都使该轮无效。遵循退避时记录 `audit_backoffs` / `audit_backoff_ms`，每次最多等待 5 秒防止坏提示阻塞工具；本次服务端提示固定 1 秒。成功 HTTP 耗时不含退避等待，原始 iteration duration 包含等待。

`rejection_micro.py` 在已编译的 CapacityAdmissionTest 装配上替换冻结 engine jar，用独立 JVM 测真实框架拒绝路径的当前线程分配和 CPU；它排除真实 socket、其它线程和完整服务成本，不能当端到端吞吐。先运行 CapacityAdmissionTest 再调用该工具。
