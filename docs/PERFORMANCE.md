# llmgw4j 性能说明

更新：2026-11-10。v0.3.1 的项目内原生 SPI/Flow 桥接重构尚未重新执行性能 A/B；下列数据仅属于 v0.2.0 之前的冻结性能审查产物，不能视为 v0.3.1 的吞吐或延迟结果。当前结论是减少请求热路径分配；没有证据支持所有场景稳定提速。当时的 v0.2.0 发布构建仅变更版本元数据，下面 A/B 对应冻结的审查 JAR，SHA256 均在证据中，不冒充最终发布 JAR 的新测量。

## 本轮改动与局部结果

| 热路径 | before B/op | after B/op | 分配变化 |
|---|---:|---:|---:|
| SSE 64 字符 payload | 1332.55 | 1036.87 | −22.2% |
| SSE 4096 字符 payload | 17460.55 | 9100.87 | −47.9% |
| WebFlux 13 个请求头复制 | 8008 | 5464 | −31.8% |

SSE 用索引扫描边界后仅截取一次，保持三种流协议的原始字节、终止和计量语义；WebFlux 直接读取 header 第一值，避免构造多值集合。4KB SSE 的 12000 B/op 预算在 before 失败、after 通过；局部 CPU 与分配不是整个网关收益。

## 端到端 A/B

同一批使用相同的 4 CPU / 2 GB、32 上游线程和 JVM 堆；50 VU、本地 wire mock、无 TLS/付费上游，每配置预热 30s、四轮各 15s、四配置平衡顺序。JSON/SSE 和本地治理 on/off 分开测。

64 个正式样本、4,217,770 请求全部零错误，上游计数一致。WebFlux SSE 吞吐中位数在治理 on/off 分别 +7.3% / +5.2%，但范围重叠；WebFlux JSON 分别 +10.3% / −9.4%，部分 p99 退化，VT JSON 参照路径也有波动。不能宣称全面或稳定提速，也不能用先后两批推算治理成本。

完整表、范围、p50/p95/p99、JFR、失败实验和源码/产物校验见 [性能审查报告](verification/perf/2026-11-10/perf-review/README.md)。

## 性能审查发现与调优边界

- 旧性能审查中 JDK HttpClient、Reactor 与 Netty 的异步桥接和逐行处理是热点。当前已改为原生 Flow 桥接，并完成行为验证；性能影响尚未复测。
- 4 CPU 容器出现 CPU 配额节流。32→4 线程虽改善 JSON，但 SSE 曾出现一次截断，该配置判失败；默认仍为 32。按实际 CPU 配额、流长度与治理依赖延迟测量，不能照抄历史单机“最优值”。
- `JdkHttpUpstream.targetUris` 尚无容量上限，动态路径高基数可能造成长期堆增长，本轮未修。
- 并发默认 64、非流式响应默认 4 MiB、上传/下载仍整包缓冲。此次没有放宽保护来换吞吐。
- 没有覆盖公网 TLS、远程审核/embedding、Redis 治理延迟或生产长流容量。Native 的发布契约检查不等于 native 性能复测。

## 跨网关历史对照与复现

[统一容器对照](verification/perf/2026-11-10/final/README.md)是 72 样本的旧固定产物批次，不含其后 JWT 与本次 SSE/header 修改，不能作为 v0.2.0 最新排名。[差距报告](gap-analysis.md)分别说明产品能力和测量范围。

执行入口为 [统一容器基准](../bench/cross-gateway/README.md)，方法约束见 [bench/README.md](../bench/README.md)。正式 A/B 不开 JFR，保留所有失败轮次和上游计数，不将闭环 mock RPS 推广为生产最大容量或 SLO。

v12/v13 记录和被撤回的异环境对照仅保留于 [历史文档](archive/performance-v12-v13.md)与[历史图表](archive/performance-charts-v12-v13.html)。
