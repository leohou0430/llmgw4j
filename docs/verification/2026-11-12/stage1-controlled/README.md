# T1.17 第一阶段全量回归证据

运行日期：2026-11-14。被测产物：`llmgw-app/target/llmgw-app-0.3.1.jar`（smoke 批次全程 `LLMGW_TEST_JAR` 固定）。
环境：GraalVM JDK 25（`/Users/leo/Library/Java/JavaVirtualMachines/graalvm-jdk-25/Contents/Home`）+ Maven 3.9.16，离线 `mvn -o`。

本目录记录本轮实际测量，不引用历史测量作为当前证据。

## 范围（总计划 T1.17，line 276）

三检查 + `bash bin/build.sh` + 默认/Redis/jtokkit/otel profile + 双引擎流式/扩展/加固/租户隔离/管理/JWT smoke（LLMGW_TEST_JAR 固定）+ T1.14/T1.15 全绿。Redis 用本次独立实例并清理。

## 已确认（本轮实际）

### 验证三检查
- `bash bin/check-module-layout.sh`：PASS=114 FAIL=0。
- `python3 bin/check-native-boundaries.py`：250 文件 / 27 模块 / 14 描述符 / 26 命名扩展。
- `python3 -m unittest discover -s bin -p 'test_native_*.py'`：6 OK。
- fixture 校验 `python3 -m unittest discover -s bin -p 'test_acceptance_fixtures.py'`：41 OK。

### 全量默认测试
- `mvn -o test`：exit 0；surefire 独立复算 **830 测试 / 0 失败 / 0 错误 / 26 跳过**（26 为 `llmgw-governance-redis` 受 `LLMGW_TEST_REDIS_PORT` 门控的条件跳过）。ConversionContractTest 49/0/0/0。

### T1.14 / T1.15
- T1.14 三入口×六场景受控合同（双引擎）：ConversionContractTest 全绿。
- T1.15 SDK 兼容（受控）：36/36 live checks（3 入口 × 6 场景 × 2 引擎），纯逻辑 16 OK；上游真值 chat 形状 / deepseek-chat / Bearer sdk-upstream。

### 双引擎 smoke（见 smokes.json，全部 exit 0）
- multi-protocol 56/0、v13-extensions 54/0、v13-hardening 46/0、stream-errors 67/0（计数为脚本报告的双引擎合计）。
- tenant-isolation：webflux+vt PASS；admin-auth：6 PASS（protected/read-only/demo × 2 引擎）；jwt-auth：webflux+vt PASS。

### profile 验证（见 profiles.json，全部 exit 0 / BUILD SUCCESS）
- `mvn -o -Pjtokkit test`：**830 / 0 / 0 / 26**（76 surefire XML）。
- `mvn -o -Potel test`：**830 / 0 / 0 / 26**（76 surefire XML）。
- `LLMGW_TEST_REDIS_PORT=6391 mvn -o -Predis test`：**830 / 0 / 0 / 0**（26 门控测试全部执行：RedisConsistencyTest 20 + RedisTokenLimiterTest 6）。
- Redis 独立实例：端口 6391，pid 82401，`ping_before` PONG；`redis-cli -p 6391 shutdown nosave` 清理；`ping_after` 无法连接，`pgrep` 确认无残留。
- 每 profile surefire 独立复算；per-module 明细从 redis.log 逐类求和（830 / 76 类）。

### 构建产物
- `bash bin/build.sh`：exit 0，BUILD SUCCESS（22.763 s）。
- 产物 `llmgw-app/target/llmgw-app-0.3.1.jar`，133175908 字节，sha256 `2d5acbec5bfb1331bf9d8532d9461178d1af29e299b7a7181093a5ee7e0cacc3`。
- 与 smoke 批次同一份 pristine stage-1 源（smoke 期 jar sha256 前缀 `a95cb47e07c4324a`，仅重建差异，源未改）。

### 结论
T1.17 全部受控项全绿（三检查 + 全量默认 + jtokkit/otel/redis profile + build.sh + 双引擎 7 smoke + T1.14/T1.15）。汇总见 `evidence.json`。唯一未覆盖项为 T1.18 用户硬门（见下）。

## 未覆盖 / 硬门

- T1.18 真实 10 分钟窗口：用户硬门，需显式 go + 经安全环境提供 DeepSeek 凭据；本轮未执行（无真实生成调用）。
- 真实供应商 Files 下载能力：阶段 2 按供应商能力记录，不声称 DeepSeek 下载。