# llmgw4j 性能说明

v0.4.0当前证据为[全盘修复验证](reviews/2026-11-16-remediation/README.md)中的单进程2CPU/2GiB四路64MiB上传、管理并发、取消与恢复；峰值容器内存约804/667MiB。此批没有通用吞吐A/B、远端OTLP或新真实供应商性能测量。下列历史数据保留各自原始产物归属。

第1步冻结产物的[生命周期与大帧容量补审](verification/lifecycle/2026-11-11/capacity-results.md)已完成211项三profile、固定JAR契约与2C2G/单实例复验；64慢读×45秒及常规/过载/长流通过。SSE每成功响应CPU有下降，JSON/吞吐/尾延迟有混合变化，不宣称全面稳定提速。当时的WebFlux native构建与功能契约通过，未测native吞吐；不覆盖其后的多副本和SDK改动。

第2步的[独立性能对照](verification/multi-replica/2026-11-11/capacity-results.md)记录34样本及13个延长预热复查样本。延长预热的WebFlux JSON吞吐中位数-0.25%、p99 +3.81%、CPU/成功 +4.34%；同步修复有成本，不宣称全面提速。这些数字只属于第2步冻结产物；第3步的上传头、批任务和版本头修复完成[功能验收](verification/compatibility/2026-11-11/README.md)，之后也完成[本机WebFlux native功能补验](verification/native-compatibility/2026-11-11/README.md)及[两次DeepSeek实测](verification/live-provider/2026-11-11/README.md)。仍没有新native吞吐测量；真实调用的单次耗时也不构成SLO。

此前未发布变更见 [过载拒绝成本审查](verification/perf/2026-11-11/overload/README.md)：局部拒绝分配减少约 18%，但忽略退避的闭环没有稳定吞吐改善；遵循提示的客户端实验单独解释，成功 HTTP p99 不包含退避等待。

当前工作区的 [2026-11-11 容量审查](verification/capacity/2026-11-11/README.md)使用 2 CPU / 2 GiB、单副本及本地治理；与下文历史 4 CPU 批次分开记录。当前修复旨在保证资源边界和超时契约，不预设吞吐提升。

发布版本说明（2026-11-10）：v0.3.1 的项目内原生 SPI/Flow 桥接重构尚未重新执行性能 A/B；下列数据仅属于 v0.2.0 之前的冻结性能审查产物，不能视为 v0.3.1 的吞吐或延迟结果。当前结论是减少请求热路径分配；没有证据支持所有场景稳定提速。当时的 v0.2.0 发布构建仅变更版本元数据，下面 A/B 对应冻结的审查 JAR，SHA256 均在证据中，不冒充最终发布 JAR 的新测量。

## 历史分配优化与局部结果

| 热路径 | before B/op | after B/op | 分配变化 |
|---|---:|---:|---:|
| SSE 64 字符 payload | 1332.55 | 1036.87 | −22.2% |
| SSE 4096 字符 payload | 17460.55 | 9100.87 | −47.9% |
| WebFlux 13 个请求头复制 | 8008 | 5464 | −31.8% |

SSE 用索引扫描边界后仅截取一次，保持三种流协议的原始字节、终止和计量语义；WebFlux 直接读取 header 第一值，避免构造多值集合。4KB SSE 的 12000 B/op 预算在 before 失败、after 通过；局部 CPU 与分配不是整个网关收益。

## 历史端到端 A/B（4 CPU）

同一批使用相同的 4 CPU / 2 GB、32 上游线程和 JVM 堆；50 VU、本地 wire mock、无 TLS/付费上游，每配置预热 30s、四轮各 15s、四配置平衡顺序。JSON/SSE 和本地治理 on/off 分开测。

64 个正式样本、4,217,770 请求全部零错误，上游计数一致。WebFlux SSE 吞吐中位数在治理 on/off 分别 +7.3% / +5.2%，但范围重叠；WebFlux JSON 分别 +10.3% / −9.4%，部分 p99 退化，VT JSON 参照路径也有波动。不能宣称全面或稳定提速，也不能用先后两批推算治理成本。

完整表、范围、p50/p95/p99、JFR、失败实验和源码/产物校验见 [性能审查报告](verification/perf/2026-11-10/perf-review/README.md)。

## 性能审查发现与调优边界

- 旧性能审查中 JDK HttpClient、Reactor 与 Netty 的异步桥接和逐行处理是热点。当前已改为原生 Flow 桥接，后续2C2G A/B比较的是两个原生Flow候选，不隔离旧桥迁移成本。
- 4 CPU 容器出现 CPU 配额节流。32→4 线程虽改善 JSON，但 SSE 曾出现一次截断，该配置判失败；默认仍为 32。按实际 CPU 配额、流长度与治理依赖延迟测量，不能照抄历史单机“最优值”。
- 该历史批次的 `JdkHttpUpstream.targetUris` 无容量上限；当前未发布工作区已限制为 1024 项，见下方新审查。
- 并发默认 64、非流式响应默认 4 MiB、上传/下载仍整包缓冲。此次没有放宽保护来换吞吐。
- 没有覆盖公网 TLS、远程审核/embedding、Redis 治理延迟或生产长流容量。Native 的发布契约检查不等于 native 性能复测。

## 跨网关历史对照与复现

[统一容器对照](verification/perf/2026-11-10/final/README.md)是 72 样本的旧固定产物批次，不含其后 JWT 与本次 SSE/header 修改，不能作为 v0.2.0 最新排名。[差距报告](gap-analysis.md)分别说明产品能力和测量范围。

执行入口为 [统一容器基准](../bench/cross-gateway/README.md)，方法约束见 [bench/README.md](../bench/README.md)。正式 A/B 不开 JFR，保留所有失败轮次和上游计数，不将闭环 mock RPS 推广为生产最大容量或 SLO。

v12/v13 记录和被撤回的异环境对照仅保留于 [历史文档](archive/performance-v12-v13.md)与[历史图表](archive/performance-charts-v12-v13.html)。

## 2026-11-12 Linux native 后续测量

[早期62样本容量/TLS补验](verification/native-capacity/2026-11-12/README.md)与[短批次复查](verification/native-sse/2026-11-12/README.md)分别保留原始测量及宿主干扰记录。[60秒稳态补测](verification/native-sse-steady/2026-11-12/README.md)在连续5分钟低负载后完成，Serial native/JVM p99比值1.153，较小年轻代无收益不采用。

随后使用已有G1 profile完成[三方稳态对照与原生验收](verification/native-g1/2026-11-12/README.md)：

| 运行时 | SSE端到端p99 ms | 成功请求/秒 | CPU ms/成功请求 | 进程累计cgroup峰值 MiB |
|---|---:|---:|---:|---:|
| JVM | 123.3 | 583.5 | 1.438 | 388.5 |
| native Serial | 141.8 | 571.7 | 1.767 | 255.3 |
| native G1 | 118.2 | 586.5 | 1.857 | 467.9 |

各2CPU/2GiB单副本、64并发SSE，各60秒预热、交错三轮60秒。p99/吞吐/CPU是逐轮指标中位数，内存峰值跨进程累计；不是生产SLO。JVM为Temurin25.0.5，两个native为同源Oracle GraalVM25.0.4构建，JVM/native差异不能单独归因于GC。

本批G1相较Serial native端到端p99降低16.7%、HTTP首字节p99降低45.9%、吞吐提高2.6%，CPU/成功请求增加约5.1%，峰值内存增加约213MiB。G1还通过SDK/流式/协议/隔离/读取进展、两组64连接慢读和四项本地TLS；两组慢读峰值808/1078MiB、无OOM、取消后恢复200。

可在Linux Oracle GraalVM环境显式使用`NATIVE_GC=g1 ./bin/build-native.sh webflux`。默认GC保持不变；此结果仅覆盖WebFlux/default组合的受控SSE，G1 VT/Redis、JSON性能及真实供应商生成未验收。没有追加供应商请求、提交、推送或发布。
