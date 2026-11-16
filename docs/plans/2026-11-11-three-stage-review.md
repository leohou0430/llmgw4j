# 三步审查执行与收尾门槛

用户最新目标：**优先完成第 1 步的细节审查，完成后再开始第 2、3 步。** 本清单用于判定阶段是否可以结束，不以累计修复数量代替验收。第1步已按9项门槛完成，见[最终核对](../verification/lifecycle/2026-11-11/phase-1-completion.json)。第2步也已完成十项验收，见[第2步收尾](../verification/multi-replica/2026-11-11/phase-2-completion.json)。第3步已完成十一项验收，见[第3步收尾](../verification/compatibility/2026-11-11/phase-3-completion.json)。三步已按顺序完成；证据均绑定各阶段产物，不追溯改写历史测量。

## 1. 稳定性与容量（已完成）

| 门槛 | 当前证据/状态 |
|---|---|
| 资源边界：并发名额、URI 缓存、非流式/错误体、SSE 单行 | 已完成红绿、双引擎与最新固定产物契约 |
| Flow/Future：需求、取消、EOF、终态、正常完成不取消 transport | EOF、10项生命周期与6项解码回归已通过；新出口按8KiB分块，固定产物及native契约已通过 |
| 超时：首字节、空闲、整体正文、用户回调阻塞隔离、超时前取消 | 已覆盖回调阻塞/取消顺序，另修复跨片段行的空闲误判；7项读取进展回归及三profile通过，固定JAR/native契约已通过 |
| 关闭：显式取消后关闭、超时后关闭、终态回调中关闭、实例资源隔离 | 真实HTTP/子进程及三profile通过，当时冻结native验证已通过 |
| 当前源码：default / Redis / Redis+jtokkit+otel、模块与原生边界 | idle-output冻结源码：三profile各211项、0失败/错误/跳过，模块/原生边界与OpenAPI通过，核心class摘要一致 |
| 最终产物：双引擎流式、扩展、加固、多协议、租户、管理/JWT | idle-output固定JAR的9套契约全部通过；逐字节读取进展的双引擎端到端另验通过 |
| 最终产物：2C2G / 单副本容量、明确拒绝、长流、真实慢读/断开恢复、无 OOM | 已修复大帧重复扫描、VT断开误记熔断、WebFlux按整行预取OOM。新冻结产物双引擎×心跳开关、64慢读、近1MiB行、严格10s响应头/45s持续均通过，取消/恢复正常、无OOM；补查空闲读取进展后，新idle-output四组45秒慢读与62容量样本也通过，计数/取消/拒绝/退避/OOM检查通过 |
| 第1步 WebFlux native 构建及流式/协议/租户契约 | 新WebFlux native构建、34流式+28协议、租户与读取进展通过；产物SHA/源码复查已记录 |
| 收尾审计：产物/源码 SHA、失败样本保留、性能成本和覆盖范围 | 9项逐条确认通过；容量/CPU与候选SHA、失败保留、覆盖限制已记录 |

当前不承诺生产最大 QPS、供应商可靠性或长期 SLO；硬件为 2C2G 单副本，SLO 尚未由用户定义。压测用本地受控上游，指标必须区分成功请求与容量拒绝。CPU 性能比较不与 Maven/native 构建并行。

## 2. 多副本治理（已完成）

共享时钟/配额/快照/凭据/熔断缺陷已复现并修复；独立 Redis 竞争、双副本故障恢复、三 profile 各243项、九套 HTTP 契约、34+13 个2C2G对照样本通过。额外性能成本与升级限制见[阶段报告](../verification/multi-replica/2026-11-11/README.md)。

系统检查共享配额预留/结算、限流、缓存、资源归属、熔断、Redis 故障恢复，以及 key/路由/策略更新的一致性。跨副本竞争、进程重启、Redis 中断和失败关闭需有本轮证据。对未知提交、持久账本和动态控制面尚未实现的能力分别明确边界；不把局部 Redis 共享等同完整生产多副本治理。

## 3. 接入兼容性（已完成）

修复 multipart 头丢失、批任务模型字段注入、Anthropic 重复版本头。最终三 profile 各246项，固定版本官方 Python SDK双引擎52项及九套HTTP契约通过；最新Redis产物的恢复/归属及24项provider契约通过。矩阵、失败样本、未知启动异常及未覆盖范围见[阶段报告](../verification/compatibility/2026-11-11/README.md)。SDK回归已加入CI，本地命令已验证，未触发远端CI。第3步收尾当时未覆盖新native与真实供应商；其后补验见下节。

按协议/供应商认证/SDK/错误与重试/资源 API 建立矩阵，验证 JSON 与 SSE、工具调用、usage、完成与截断、请求头、模型路由、文件/批任务等已有能力。优先实际 SDK 对本地受控上游的验收；没有供应商凭据时明确未实测，不替换成真实供应商通过。记录 501 等未实现能力及兼容边界。

阶段 2、3 的审查会修复可复现的现有能力缺陷；新增财务平台、版本化控制面或供应商转换等产品能力另行明确设计和验收，不在报告里伪称已实现。

不自动提交、推送或发布。

## 后续补验（已完成）

当前源码重新构建本机WebFlux native，26项官方SDK、34项流式、28项多协议、租户隔离与3种读取进展场景通过；174个项目class与阶段3固定JVM产物一致。另按用户指定模型完成DeepSeek的WebFlux JSON与VT SSE各一次，usage合计20 tokens，应用层重试关闭。详见[native报告](../verification/native-compatibility/2026-11-11/README.md)及[DeepSeek报告](../verification/live-provider/2026-11-11/README.md)。未构建Linux/VT native，未做native公网TLS、native吞吐或持续供应商负载验收；未提交、推送、发布。

Linux后续补验亦已完成：两种native各26项SDK、34项流式、28项协议及隔离/读取进展通过，三profile各246项；详见[Linux报告](../verification/linux-native/2026-11-11/README.md)。此结果补充上文当时未覆盖的Linux/VT范围，不改写历史产物结论。

Linux native容量与TLS补验已完成：62样本、4组严格慢客户端、8项本地TLS和两次授权DeepSeek调用全部通过，见[2026-11-12报告](../verification/native-capacity/2026-11-12/README.md)。上文历史未覆盖范围由此单独补齐；新发现为WebFlux native SSE相对同源码JVM的吞吐/p99退步，下一项应先定位该场景，暂不变更默认运行时。

性能复查发现宿主并行LLVM编译使差异方向不稳定，native SSE退步尚未归因；已补容量脚本负载门槛，待空闲环境复测，见[复查记录](../verification/native-sse/2026-11-12/README.md)。

等待LLVM完整结束后的本机复测已完成；两批p99比值2.57/1.20，根因尚未锁定，年轻代调参无收益未采用。[复测报告](../verification/native-sse/2026-11-12/README.md)保留27个受控样本及受干扰失败批次。

后续稳态及G1验收已完成：[报告](../verification/native-g1/2026-11-12/README.md)记录同源Serial/G1与JVM三方60秒对照。G1相较Serial的SSE端到端p99降低16.7%，资源开销更高；SDK、流式、协议、隔离、读取进展、严格慢读及本地TLS通过。仅确认为Linux WebFlux的显式可选方案，默认配置与提交/发布状态不变。
