# Linux WebFlux G1 native 验收（2026-11-12，已完成）

使用项目已有的native-g1构建配置验证一项可选延迟优化，没有修改生产Java或全局默认运行时。**本批G1相较Serial native，SSE端到端p99降低16.7%，HTTP首字节p99降低45.9%，吞吐提高2.6%；每个成功请求的容器CPU耗时增加约5.1%，内存峰值增加约213MiB。**

## 受控三方对照

Linux amd64 / Ubuntu 26.04.1，网关各2CPU/2GiB、禁swap、最小堆128MiB/最大堆1GiB、单副本。64并发SSE，每种运行时各预热60秒，交错三轮各60秒；这指采样窗口，不是每条SSE持续60秒。构建及其他编译结束后，连续5分钟低负载才启动；每轮前后1分钟宿主负载/逻辑核心数≤0.75。使用本地受控上游，无供应商生成调用。

JVM为冻结镜像内Temurin25.0.5+8（jvm-runtime.log）；两个native均用同一Oracle GraalVM25.0.4工具链、同一Java快照构建。因此Serial/G1是同工具链GC构建变体对照，JVM列是部署运行时基线，不能将JVM/native差异归因于GC一个因素。

| 运行时 | 端到端p99 ms | HTTP首字节p99 ms | 成功请求/秒 | CPU ms/成功请求 | 进程累计cgroup峰值 MiB |
|---|---:|---:|---:|---:|---:|
| JVM | 123.3 | 32.9 | 583.5 | 1.438 | 388.5 |
| native Serial | 141.8 | 53.7 | 571.7 | 1.767 | 255.3 |
| native G1 | 118.2 | 29.0 | 586.5 | 1.857 | 467.9 |

三轮端到端p99：

- JVM：122.8, 125.0, 123.3ms。
- native Serial：141.0, 141.8, 173.9ms。
- native G1：115.8, 118.8, 118.2ms。

13样本包括1直连、3预热、9正式；正式成功请求合计314,032，容量拒绝0、异常失败0、正常完成误取消0、OOM0，上游完成数与客户端成功数一致。每个样本的宿主负载门槛均通过。

p99/吞吐/CPU值为三轮各指标中位数，不是合并分位数；内存峰值跨进程生命周期累计，CPU计数包含采样与排空开销。HTTP首字节不是模型首token。G1与本批JVM吞吐接近，G1使用更多CPU和内存，不据此切换全局默认。该短时、闭环、本地受控上游结果不代表生产SLO或供应商最大QPS。

## 原生构建与功能回归

- 249个输入文件摘要匹配已验证的Linux快照，174个项目库class与冻结JVM产物字节一致。
- 已有native-g1 profile离线构建，显式G1 GC；构建限12CPU/16GiB、禁swap、builder最大堆10GiB，未与性能采样并行。构建/功能阶段不是2C2G容量测量。
- G1 native通过26项官方SDK、34项流式、28项多协议、租户隔离及3项读取进展检查。模块布局、原生边界和6项原生规则通过。
- 没有重跑未改源码的三种Java profile测试；其246项/profile证据保持在原Linux报告，不计作本轮新执行。
- ELF直接引用GLIBC最高2.34；本轮仅证明当前Linux amd64环境可运行，仍需匹配CPU指令集、动态库等条件。

## 2C2G慢客户端与TLS

两组都使用64连接、近1MiB SSE行、保持45秒、严格10秒响应头时限；心跳分别0/50ms。

- slow-webflux-h0：响应头最大1.265秒，峰值808.2MiB；64次取消全部传递，上游活动归零，恢复请求200，无OOM。
- slow-webflux-h50：响应头最大0.458秒，峰值1078.1MiB；64次取消全部传递，上游活动归零，恢复请求200，无OOM。

本地TLS四项通过：自定义信任库下JSON/SSE正常，缺失CA返回明确证书链错误，域名不匹配返回明确SAN错误；未关闭证书或域名校验。TLS报告摘要与G1二进制SHA一致。无新增真实供应商请求。

## 启用方式与边界

在Linux Oracle GraalVM构建环境中，显式使用已有命令：

```bash
NATIVE_GC=g1 ./bin/build-native.sh webflux
```

本次没有改变该脚本或默认GC。原生内存余量、CPU开销和目标SLO应一同评估；此次仅验收WebFlux/default能力组合的SSE性能。G1的VT/Redis构建变体、JSON性能和真实供应商生成尚未验证，不将之前其他产物的验收混用为G1证明。G1平台约束与参数语义见[GraalVM官方文档](https://www.graalvm.org/latest/reference-manual/native-image/optimizations-and-performance/MemoryManagement/)。

## 证据与产物

- completion.json：本轮整体状态；build-completion.json保留构建完成当时状态。
- results-summary.json：三轮完整指标、实际产物SHA-256与验证断言；slow-summary.json：严格慢读结果。
- 原始产物、构建/SDK/协议日志、TLS及所有样本在bench/results/native-g1-2026-11-12/。
- G1产物：bench/results/native-g1-2026-11-12/evidence/llmgw-webflux-g1-linux。
- SHA-256：`35a2069407ebc823cf04fef464633eaf4b480aa2ad7179b1355254283e1b6eca`。
- run-threeway.py等为本轮固定工作区的证据快照，正式通用容量入口仍是bench/capacity/run.py。
- 初次受编译/IDE干扰的失败稳态批次保留在[稳态补测](../../native-sse-steady/2026-11-12/README.md)，不覆盖或计入本表。

自己的构建/测试容器和网络已清理；未提交、推送或发布。
