# T1.12 两引擎转换接缝 — 切片6 全量验证报告

- 日期：2026-11-13
- 任务：T1.12（Chat / Responses / Messages 三入口 → 固定 DeepSeek Chat 上游）在 VT 与 Webflux 两引擎的接缝收口
- 执行方式：后台脚本 `/tmp/llmgw-slice6-run.sh`（background id `b0n56wgtv`，整体 exit 0）
- 环境：GraalVM JDK 25 + Maven 3.9.16，`mvn -o`（离线）
- 证据：本目录 `*-verify.log`、`module-layout.log`、`native-boundaries.log`、`native-unittest.log`、`slice6-console.log`；结构化数据见 `profiles.json` / `artifacts.json` / `checks.json`

> 本报告所有数字均为**本次运行的实测值**，未改写或复用历史测量。surefire XML 磁盘聚合反映最后一次运行（redis profile，为超集）。

## 1. 结论

四个 profile 与边界三件套**全绿**，T1.12 双引擎转换接缝收口完成。

| Profile | 命令 | exit | BUILD | run | fail | err | skip | 实际执行 |
|---|---|---|---|---|---|---|---|---|
| default | `mvn -o test` | 0 | SUCCESS | 749 | 0 | 0 | 26 | 723 |
| jtokkit | `mvn -o -Pjtokkit test` | 0 | SUCCESS | 749 | 0 | 0 | 26 | 723 |
| otel | `mvn -o -Potel test` | 0 | SUCCESS | 749 | 0 | 0 | 26 | 723 |
| redis | `LLMGW_TEST_REDIS_PORT=6390 mvn -o -Predis test` | 0 | SUCCESS | 749 | 0 | 0 | 0 | 749 |

- default/jtokkit/otel 的 26 skipped 为 `llmgw-governance-redis` 受 `LLMGW_TEST_REDIS_PORT` 门控的条件跳过；redis profile 起独立实例后全部执行。
- redis 独立实例（port 6390）：跑前 `PONG`，跑后 `shutdown nosave`，再 ping 无法连接 → **资源自清理**。
- 边界三件套：`module-layout` PASS=114 FAIL=0；`native-boundaries` 247 模块 Java 文件 / 27 模块 / 14 描述符 / 26 命名扩展；`native-unittest` 6 tests OK。三者 exit 均 0。
- 基础模块 Enforcer（`llmgw.foundation.enforcer.skip=false`）随每个 profile 构建执行，无 Spring/Boot/Reactor/Dubbo 泄漏；HTTP 框架仅限 app/engine-webflux/engine-vt。

## 2. 本次覆盖的场景

T1.12 焦点套件（含双审查 #37–#44 全部修复的回归）：

- **ConversionEngineIntegrationTest（llmgw-app，真实 codec 双引擎，9 tests）**：COMPLETE / TRUNCATED / FINISH_NO_DONE / ROLE_ONLY / FINISH_THEN_ERROR / HEARTBEAT_ONLY / HEARTBEAT_THEN_ERROR 七种上游模式 × 三入口协议，断言两引擎状态码、计费 finished、终态形状一致。
  - committed 带内错误一律渲染 **entry 协议形状**（CHAT `data:{"error":...}` 无 event 行无 [DONE]；RESPONSES/MESSAGES `event:error` + 顶层 `"type":"error"`），不再泄漏通用 gateway 形状。
  - 心跳（`: keep-alive`）提交 200 但不置 chunkSent/dataSeen/started；心跳后 EOF/传输错误仍按 entry 形状带内渲染、计费失败一次。
- **RouteTableTest（llmgw-core，14 tests）**：回退链上游协议一致性守卫——chat→passthrough 与 passthrough→chat 均拒绝（消息含两端 route id）；一致链与传递链放行；未知/自指 fallbackId 跳过不报错；upstreamProtocol 归一化与 legacy 9 参构造 byte-identical。
- **engine-vt（20）+ engine-webflux（28）= 48 tests**：含 `WebfluxConversionTest.streamIllegalUpstreamChunkEmitsAnEntryError`（#44 回归：非法 upstream chunk 在带内渲染 entry 错误、200，与 VT onChunk catch parity）。
- **ChunkParser（#37/#41）**：多行 SSE 事件终态判定、dataPayload 快路径多行并入与 CR 分隔。
- **DefaultStreamTranscoder（#40）**：chat finish-then-error 时 `onUpstreamError` 在 `terminalSeen` 后委托 `onComplete`，自守卫且幂等，绝不伪造成功。

## 3. 未覆盖范围（本切片未跑，留待后续任务）

- **native / AOT**：本切片仅 JVM `mvn test`，未构建/运行 native image（T1.12 未改 AOT 绑定，按 AGENTS.md 非必需；如需 native 证据另起切片）。
- **真实上游生成调用**：未发起任何真实 provider 请求。按硬门控，真实生成仅在 **T1.18** 单一 10 分钟窗口（≤60 次、并发 1、max_tokens 1024、无 retry/fallback），需用户明确 go + 安全凭据。本切片全部为受控 mock 上游。
- **T1.13–T1.17**：受控上游 + 版本化 fixture、3 入口×6 场景合同、SDK 兼容、验收 harness、全量回归尚未执行。
- **阶段 2–4**：Files 有界流式、项目资源目录/列表、可操作控制面未开始。

## 4. 延期 Minor（不阻塞 T1.12，记录待后续）

- **quality#7**：Webflux transcoder 在装配线（~587）创建，而非 per-subscription `Flux.defer`/`using`。当前单订阅路径正确；多订阅/重订阅语义留待加固切片评估。
- **quality#8**：补充覆盖——RESPONSES/MESSAGES 的 FINISH_NO_DONE、onError/cancel/cache-hit/close 路径的更多双引擎 parity 用例。

## 5. 变更产物摘要（SHA-256，详见 artifacts.json）

5 个 tracked 文件承载 T1.12 接缝修改（`git diff --stat`：715 insertions / 105 deletions）：VirtualThreadGatewayController（302）、WebfluxGatewayController（425）、RouteTable（44）、ChunkParser（29）、EngineRoutes（20）。转换模块源（DefaultStreamTranscoder 等）为新增 untracked 文件。关键文件 digest 与本次 console 日志 digest 均记录于 `artifacts.json`。

## 6. 复现

```bash
export JAVA_HOME=/Users/leo/Library/Java/JavaVirtualMachines/graalvm-jdk-25/Contents/Home
export PATH="$JAVA_HOME/bin:/Users/leo/work/maven/apache-maven-3.9.16/bin:$PATH"
cd /Users/leo/WorkBuddy/2026-11-06-01-50-12/llm-gateway
mvn -o test                 # default
mvn -o -Pjtokkit test       # jtokkit
mvn -o -Potel test          # otel
# redis：起独立实例 :6390，LLMGW_TEST_REDIS_PORT=6390 mvn -o -Predis test，跑后 shutdown nosave
bash bin/check-module-layout.sh
python3 bin/check-native-boundaries.py
python3 -m unittest discover -s bin -p 'test_native_*.py'
```