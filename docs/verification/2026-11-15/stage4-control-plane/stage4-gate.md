# 阶段 4 全量门（2026-11-16）

脚本：`/tmp/stage4-gate.sh`（三检查 + openapi + jtokkit/otel/redis 独立实例 6394 + build.sh + fixture 校验 + 7 双引擎 smoke，`LLMGW_TEST_JAR` 固定为构建产物）。全部 14 项退出码 0。

## 三检查与 openapi
- layout / native-boundaries / native unittest：exit 0。
- openapi：系统 python3 缺 PyYAML 且 PEP668 阻止系统安装；改用隔离 venv（`/tmp/llmgw-venv/bin/python`，PyYAML 6.0.3）执行 `bin/check-openapi.py`，exit 0。未修改系统 Python。

## profile 矩阵（XML 独立重计）
| profile | XML 文件 | tests | failures | errors | skipped |
|---|---|---|---|---|---|
| jtokkit | 118 | 1023 | 0 | 0 | 26 |
| otel | 118 | 1023 | 0 | 0 | 26 |
| redis（独立 6394，跑完 shutdown nosave 并确认无残留） | 118 | 1023 | 0 | 0 | 0 |

## 构建与合同
- `bin/build.sh` exit 0；JAR sha256 `45bdc5e460f6b80b999a2f03d679821404bde6c2a758f5f409f71ea5e3482829`。
- fixture 校验 exit 0。
- smoke：multi-protocol、v13-extensions、v13-hardening、stream-errors、tenant-isolation、admin-auth、jwt-auth 全 exit 0（固定被测 JAR）。

## 范围与说明
- build.sh 会清理各模块 `target`，门脚本内已按 XML 重计并落盘 count；当前工作区报告目录为空，故以上计数为门内实测记录，不再另行重算。
- 本门为 JAR 双引擎与合同门；native 证据见 `t4.12-native-aot.md`（macOS x86_64，webflux），不含 Linux/vt native。
- openapi 依赖通过 venv 解决，属本机环境准备，不改变仓库依赖。

## 门后变更复跑（2026-11-16）
门后新增：路由结构化校验接线、logout、目录 SQLite 化、密钥加密端点与 V6 迁移、磁盘保护接线（覆盖全部管理写路径）与旧库升级演练、native record 反射修复。全反应堆 `mvn -o -B package` exit 0，XML=120 tests=1034 failures=0 errors=0 skipped=26；layout/native-boundaries/native unittest 均 exit 0。上述 JAR sha 与 1023 计数对应门时刻产物；门后最新 JAR 为 145,859,998 bytes，sha256 `710b12df3add3f196dc0f36108ab32558cf38ebb47bba82aac87924cddf41694`，计数以本节为准。
