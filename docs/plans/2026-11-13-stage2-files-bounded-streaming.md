# 第二阶段子计划：Files 有界流式传输

日期：2026-11-13。上游设计：`docs/superpowers/specs/2026-11-12-gateway-four-stage-design.md` §8 第二阶段；总计划 `docs/plans/2026-11-12-gateway-four-stage.md` T2.1–T2.10。本文件把阶段 2 细化为可逐切片 TDD 执行的子计划。**进入实施前阶段 1 须过门（T1.17 全量回归绿；T1.18 真实窗口由用户硬门）。**

## 0. 现状（已读代码核对，作为改造起点）

- `core/upstream/UpstreamRequest.java:36` 请求体是整体 `byte[] rawBody`（W4.3 二进制 multipart 上传整包持有）；紧凑构造器对非二进制体防御性剥离 content-type/accept，对二进制体保留 content-type（boundary 在其中）。
- `provider/UpstreamProvider.java`：响应已流式（`Flow.Publisher<String> streamRaw`、`ChunkSink streamRawBlocking`）；**下载是整体 `byte[] fetchRaw` / `CompletableFuture<byte[]> fetchRawAsync`**；上传走 `UpstreamRequest.rawBody`。
- `provider-http-jdk/JdkHttpUpstream.java`：唯一 JDK 传输实现，响应 sendAsync 流式，上传/下载整包 byte[]。
- 引擎：engine-webflux（DataBuffer）、engine-vt（ChunkSink 阻塞）。Files 上传当前在认证/准入后整包读入。

改造原则（AGENTS.md）：基础模块禁 Spring/Boot/Reactor/Dubbo；上游公开异步契约用 JDK Flow/CompletableFuture；正常流完成不得取消 HTTP transport；直接改契约并更新唯一实现，不留 compat shim；多实现域规则；资源改动必须覆盖关闭与实例隔离。

## 1. 边界与限额（合同常量，集中可测）

- 单文件 ≤ 64 MiB；单 multipart ≤ 66 MiB；非文件元数据 ≤ 64 KiB。
- 每进程 ≤ 4 并行 Files 传输，且计入总并发许可。
- 临时 spool：单传输 ≤ 66 MiB，总 ≤ 264 MiB，磁盘安全余量；独立受限目录。
- 普通 JSON 限额独立保留，不被 Files 限额覆盖。
- 已知长度超限 → 前置 413；未知长度 → 按累计拒绝；生命周期许可只释放一次。

## 2. 切片顺序与 seam（每切片：写失败测试→看红→最小实现→看绿→本人复核）

### T2.1 原生字节流合同（core/upstream，纯 JDK）
- 新增字节流类型：`Flow.Publisher<ByteBuffer>`（或项目内 `ByteChunk` 记录，明确 chunk 所有权/只订阅一次/有限 demand/取消与关闭责任）。请求体与下载响应共用同一字节契约。
- seam：合同类型的单元测试（订阅一次、demand 边界、取消释放、关闭幂等、所有权转移后原引用不可再用）。纯 JDK，无 HTTP。
- 直接改 `UpstreamProvider`：新增流式下载 `Flow.Publisher<ByteBuffer> fetchStream(...)` 与流式上传体；`UpstreamRequest` 以流式体取代/增补 `rawBody`（不留 byte[] compat shim，更新唯一实现与所有调用点）。
- 验证：core 单测 + `check-native-boundaries.py`（core 仍纯 JDK）。

### T2.2 JDK 传输流式化（provider-http-jdk）
- `JdkHttpUpstream` 上传用 `BodyPublishers.fromPublisher`（有限 demand），下载用 `BodySubscribers` 流式消费，不整包 byte[]。
- 正常完成不 cancel transport；超限取消上游并关闭输入流；首字节/空闲超时保留。
- seam：受控回环上游（复用 MockUpstreamServer 思路）的流式上传/下载集成测试 + 取消/超时回归。
- 验证：provider-http-jdk 单测 + 双引擎流式回归。

### T2.5 multipart 流式扫描（core 新 multipart 包，纯 JDK 可测）
- 认证 + 全局/Files 准入在读取正文前；流式边界扫描、文件计数、metadata 上限；后置字段有界 spool（仅前置校验依赖后置字段时落盘）。不要求官方客户端加自定义路由字段。
- seam：纯 JDK multipart 扫描器单测（边界分片跨 chunk、文件计数、metadata 超限、后置字段 spool 触发）。
- 注意：此切片逻辑上先于引擎桥（T2.3/T2.4），故排在 T2.2 后、桥前。

### T2.6 临时 spool 与磁盘保护（core multipart 包 / 受限目录）
- ≤66 MiB/传输、总 ≤264 MiB、磁盘安全余量；独立受限目录；结束/取消/异常立即清理 + 启动清理遗留；配额/空间不足提前拒绝。
- seam：spool 生命周期单测（写入/清理/取消清理/异常清理/启动遗留清理/配额拒绝/磁盘余量拒绝），双 context 实例隔离。

### T2.3 WebFlux 桥（engine-webflux）
- DataBuffer 复制/转移有明确生命周期，禁引用已释放池内存；背压/取消回归。
- seam：WebFlux Files 上传/下载集成测试（DataBuffer 释放计数、背压、取消）。

### T2.4 VT 桥（engine-vt）
- 有界队列/读写，不退回整包 byte[]。
- seam：VT Files 上传/下载集成测试（队列上界、慢读、取消）。

### T2.7 限制与错误（两引擎 + 传输）
- 已知长度超限前置 413；未知长度按累计拒绝；生命周期许可只释放一次；4 并行计入总并发。
- seam：双引擎 413/累计拒绝/许可归零/4 并行计入总并发回归。

### T2.8 慢路径回归（smoke 增补）
- 客户端慢读/慢写、上游慢读、首字节/空闲超时（两引擎）。
- seam：smoke 脚本（受控上游 stall marker）+ 双引擎。

### T2.9 下载合同（受控上游 + 真实记录）
- 用明确支持下载的受控上游验证；DeepSeek 真实仅上传/列表/元数据/删除，不声称下载；超限被发现前上游可能已收部分 → 报未知/失败。
- seam：受控下载集成测试 + 真实记录口径（不谎称 DeepSeek 下载）。

### T2.10 2C2G 文件压力（deploy + docs/verification）
- 容器 2CPU/2GiB：4 路 64 MiB 上传 + 管理并发；RSS/CPU/OOM/取消后许可归零/临时原文清理/恢复。
- seam：容器压力实测 + 证据（不引用阶段 1 资源结果代替）。

## 3. 阶段门
- 设计 §8 第二阶段证据行；全量回归同 T1.17（三检查 + build.sh + 默认/Redis/jtokkit/otel profile + 双引擎流式/扩展/加固/租户隔离/管理/JWT smoke，LLMGW_TEST_JAR 固定）。
- 顺带修复阶段 1 记录的范围外项：binary/upload 路由 pass 头未无条件剥离 x-llmgw-*（VT:219,516；Webflux:320,1002）——在 T2.3/T2.4/T2.7 中一并加固并回归。
- 证据入 `docs/verification/<日期>/stage2-files/`；不改写历史测量；不自动提交/推送/发布。

## 4. 执行方式
子代理驱动（implementer TDD → spec-reviewer + code-quality-reviewer 并行静态审查 → 本人复核重跑/读 diff/独立计数/变异自证）。每切片独立可验证；资源切片必须覆盖关闭与实例隔离。