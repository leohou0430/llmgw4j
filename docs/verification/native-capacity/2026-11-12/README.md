# Linux native 2C2G 补验（2026-11-12）

> 后续复查发现宿主并行编译干扰，本文性能表仅为观察值，不能证明稳定的native收益或退步；功能验收结论不变。详见[性能复查](../../native-sse/2026-11-12/README.md)。

同一份当前 Java 源码的 JVM 与 Linux native 对照。before 标签为 JVM，after 为 native；不是前后两版代码优化对照。二进制与 JVM SHA-256 保存在 capacity-summary.json；沿用 Linux 构建验收产物，本轮未改生产 Java 代码。

## 容量结果

62 个样本（48 正式、8 预热、2 直连、4 长流）全部通过；异常失败、正常完成误取消、cgroup/JVM OOM 均为零，96 并发观察到容量拒绝且 Retry-After 有效。每个网关 2 CPU / 2 GiB、禁 swap、堆上限 1 GiB，单副本；每轮 8 秒，正式三轮，64/96 并发。

| 引擎/运行时 | 场景 | 并发 | 成功请求/秒 | p99 ms | 累计容量拒绝 | 峰值 MiB |
|---|---|---:|---:|---:|---:|---:|
| native vt | json | 64 | 2484.8 | 63.1 | 0 | 271 |
| native vt | json | 96 | 2305.4 | 65.6 | 742 | 271 |
| native vt | sse | 64 | 488.9 | 322.0 | 0 | 271 |
| native vt | sse | 96 | 479.4 | 314.1 | 666 | 271 |
| native webflux | json | 64 | 2457.0 | 63.9 | 3 | 284 |
| native webflux | json | 96 | 2159.7 | 101.8 | 758 | 284 |
| native webflux | sse | 64 | 450.9 | 513.7 | 0 | 327 |
| native webflux | sse | 96 | 430.1 | 542.7 | 716 | 327 |
| JVM vt | json | 64 | 2017.3 | 64.6 | 0 | 355 |
| JVM vt | json | 96 | 2634.5 | 37.3 | 733 | 360 |
| JVM vt | sse | 64 | 536.3 | 323.2 | 0 | 360 |
| JVM vt | sse | 96 | 474.9 | 309.3 | 568 | 360 |
| JVM webflux | json | 64 | 1156.3 | 96.1 | 2 | 342 |
| JVM webflux | json | 96 | 1542.1 | 226.2 | 729 | 358 |
| JVM webflux | sse | 64 | 464.6 | 325.1 | 0 | 376 |
| JVM webflux | sse | 96 | 496.5 | 316.5 | 671 | 376 |

吞吐与 p99 为三轮各指标的中位数，p99 不是合并分位数。内存为该组轮次结束采样的 cgroup 峰值累计计数，不是独立单场景 RSS。闭环、短时、受控上游结果不能推为真实供应商最大 QPS 或长期 SLO。

native 内存更低，WebFlux JSON 吞吐较高，但 WebFlux SSE 在两种并发下吞吐降低、p99 升高；VT 收益随场景不同。因此不能宣称 native 全面提速，也不据此把线上默认切换为 native。SSE 热点需要单独剖析与有针对性的复测。

## 证据与复现

- `capacity-summary.json`：指标、产物摘要、全部断言。
- `capacity.log` 和 `bench/results/native-capacity-2026-11-12/capacity/`：原始日志、manifest、62 样本。
- `post-capacity.py`：四组严格慢客户端与本地 TLS 验收。
- `verify-native-deepseek.py`：用户授权追加最多两次生成调用；凭据仅隐藏 stdin 与子进程环境传入。

完整结束状态与未覆盖范围见 completion.json。

## 慢客户端与 TLS

四组（WebFlux/VT × 心跳0/50ms）均在2C2G单副本下接入64条慢读连接，近1MiB SSE行、保持45秒，不放宽10秒响应头时限。四组最大响应头耗时分别为5.736/1.399/4.441/5.689秒；各64次取消全部传播到上游，活动归零、正常完成0，随后恢复请求200，无OOM。详见 slow-summary.json。

本地TLS八项全部通过：两种引擎使用自定义信任库时JSON/SSE正常；缺失CA时明确报证书链错误，错误主机名时报SAN不匹配并返回502，未关闭证书校验。此部分没有外网请求，原始结果在 bench/results/native-capacity-2026-11-12/tls/results.json。

## 真实供应商

按用户追加授权，Linux native WebFlux JSON与VT SSE各调用DeepSeek deepseek-flash一次，max_tokens=32、thinking关闭、SDK重试0、网关attempts=1、无fallback。两次均返回OK、finish_reason=stop，分别10 tokens、合计20。默认公网TLS链路端到端通过；未关闭证书校验。详细脱敏证据见 live/live-results.json。凭据未写入文件、命令参数或报告，任务容器已清理。

模块布局71项、原生边界、6项规则测试和git diff --check通过。未重新构建或重复运行与本轮脚本修改无关的三profile Java测试；其固定产物构建证据见上一轮Linux报告。本轮未提交、推送或发布。
