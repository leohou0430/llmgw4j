# 第二阶段全量门回归（T2 stage-2 full gate）

日期：2026-11-15
 runner：`/tmp/run-stage2-gate.sh`（后台 b5ipgy420，exit 0，`STAGE2_GATE_DONE`）；default profile 由 `/tmp/stage2-land-default.sh`（后台 b1i6h0y6j，exit 0）在 smoke 结束后补跑。
被测 JAR：`llmgw-app/target/llmgw-app-0.3.1.jar`，sha256 `3f03abedd504589f79a4e32000829f95b1621d7ee14fc7cdadb45b3a5533c4be`（133223599 bytes），`LLMGW_TEST_JAR` 全程固定。

本文件所有计数均为**本次运行的真实测量**，独立从 surefire XML 复算（aggregate）与 redis.log/default.log 逐类行核对，未复用历史测量。

## 三检查（verification trio）— 全绿

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 模块布局 | `bash bin/check-module-layout.sh` | PASS=121 FAIL=0 |
| 原生边界 | `python3 bin/check-native-boundaries.py` | PASS：277 模块 Java 文件 / 30 模块 / 14 描述符 / 26 命名扩展 |
| 原生单测 | `python3 -m unittest discover -s bin -p 'test_native_*.py'` | 6 tests OK |

布局检查识别并认可惰性 `llmgw-persistence` 骨架（尚未接入根 POM modules），不违反布局/原生边界规则。

## Profile 矩阵 — 全绿（每 profile 独立复算）

| profile | 命令 | exit | tests | failures | errors | skipped | XML |
| --- | --- | --- | --- | --- | --- | --- | --- |
| default | `mvn -o test` | 0 | 908 | 0 | 0 | 26 | 92 |
| jtokkit | `mvn -o -Pjtokkit test` | 0 | 908 | 0 | 0 | 26 | 92 |
| otel | `mvn -o -Potel test` | 0 | 908 | 0 | 0 | 26 | 92 |
| redis | `LLMGW_TEST_REDIS_PORT=6392 mvn -o -Predis test` | 0 | 908 | 0 | 0 | 0 | 92 |

- default/jtokkit/otel 跳过 26 个 redis 门控测试；redis profile 执行它们（超集）：`RedisConsistencyTest` 20 + `RedisTokenLimiterTest` 6。
- Redis 使用本次任务独立实例（port 6392，`--save '' --appendonly no`）：ping_before=PONG → `shutdown nosave` → ping_after="Could not connect" → `pgrep redis-server.*6392` 空。自清理完成。
- `build.sh` 跑 `clean package -DskipTests` 会清空 surefire XML；各 profile 复算在 build 之前捕获。default 在 smoke 之后单独跑（避免与时序敏感场景争用 CPU）。

## build.sh + fixture 校验 — 全绿

- `bash bin/build.sh`：exit 0，BUILD SUCCESS，13.150 s，产物即上述被测 JAR。
- `python3 -m unittest discover -s bin -p 'test_acceptance_fixtures.py'`：exit 0，41 tests OK。

## 双引擎 7 smoke — 全绿（webflux + vt）

| smoke | 脚本 | exit | 通过 |
| --- | --- | --- | --- |
| multi-protocol | bin/smoke-multi-protocol.sh | 0 | PASS=56 FAIL=0 |
| v13-extensions | bin/smoke-v13-extensions.sh | 0 | PASS=54 FAIL=0 |
| v13-hardening | bin/smoke-v13-hardening.sh | 0 | PASS=46 FAIL=0 |
| stream-errors | bin/smoke-stream-errors.sh | 0 | PASS=67 FAIL=0 |
| tenant-isolation | bin/smoke-tenant-isolation.py | 0 | webflux + vt PASS |
| admin-auth | bin/smoke-admin-auth.py | 0 | webflux/vt × protected/read-only/demo PASS |
| jwt-auth | bin/smoke-jwt-auth.py | 0 | webflux + vt PASS |

顺序执行（固定端口 8080/9090），全部 against 固定 build.sh JAR。

## 结论与口径

- 第二阶段门全绿：三检查 + default/jtokkit/otel/redis profile + build.sh + fixture 校验 + 双引擎 7 smoke。
- 源状态：profile 矩阵与 build.sh 跑在纯净 stage-2 源上；T3.1 惰性文件在门期间未接根 POM，不影响这些测量。
- 未覆盖（明确口径）：真实 DeepSeek 上游（T1.18 硬门，待用户放行+凭据）；数小时 soak/多副本/磁盘满（T2.6 单测覆盖磁盘保护逻辑）；完整 native-image 构建（重型 native 门，另行执行）。
- 证据文件：`gate-profiles.json`（profile/build/fixture/trio）、`gate-smokes.json`（7 smoke）。原始日志在 `/tmp/stage2-gate/`。
- 不自动提交/推送/发布本轮变更。