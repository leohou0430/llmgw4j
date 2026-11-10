## 变更概述

<!-- 一两句话说明改了什么、为什么。纯性能改动请先读 bench/README.md 的数据质量红线。 -->

## 变更类型

- [ ] 缺陷修复
- [ ] 新能力 / SPI 扩展
- [ ] 性能改动（**必须附 ABBA 配对 A/B、逐轮 CSV 与宿主负载时间线**）
- [ ] 文档 / 脚本 / CI
- [ ] 构建 / 依赖升级

## 模块布局（新增或移动模块时必须确认）

- [ ] 新实现放进对应能力域的聚合目录，且依赖该域 `-api`；**未与其他实现互相依赖**
- [ ] 新能力域采用 `聚合 POM + <domain>-api + 每个实现一个 module`，根 `pom.xml` 只列聚合目录
- [ ] 通用领域契约留 core，能力域契约放对应 `-api`；实现使用 api 包的**子包**（无 split package）
- [ ] `./bin/check-module-layout.sh` 通过（CI 每次 PR 自动运行，新域自动纳入）
- [ ] 规则详见 `CONTRIBUTING.md` 第 2 节

## 协议契约（动过数据面就必须逐条确认）

- [ ] chat 的 `[DONE]`、Responses 的 `response.completed`、Messages 的 `message_stop` 仍分别作为完成判据，静默截断记为失败
- [ ] 首 chunk 前上游失败 → `502 + JSON`；已发 chunk 后 → `event: error` + `[DONE]` 并记账失败
- [ ] Flow 取消传播到实际 HTTP future/订阅；正常结束不取消 transport，账单/审计结算并退还预留
- [ ] 已发 chunk 后不再切换上游，不会拼出两段语义错乱的回答
- [ ] 上游非 2xx 在 `BodyHandler.apply` 阶段拦截，错误体不会当 `data:` 行透传

## 双引擎对称

- [ ] WebFlux 与虚拟线程两条数据面行为一致（若不一致，在此说明原因）
- [ ] 新增的 AOT 可达类型已同步到 `GatewayRuntimeHints`
- [ ] 热路径未引入额外的 JSON 解析、字符串格式化或阻塞调用

## 回归结果

| 脚本 | 结果 |
|---|---|
| `./bin/check-module-layout.sh` + `python3 bin/check-native-boundaries.py` | PASS=__ / FAIL=__ |
| `./bin/smoke-stream-errors.sh` | PASS=__ / FAIL=__ |
| `./bin/smoke-observability.sh` | PASS=__ / FAIL=__ |
| `./bin/smoke-hot-reload.sh webflux` / `vt` | __ / __ |
| `./bin/smoke-redis-cluster.sh`（仅在改动治理状态时） | PASS=__ / FAIL=__ |
| native 两个二进制各 34 项（仅在改动 native 可见代码时） | __ / __ |

## 性能数字声明

- [ ] 本次 PR 不包含性能结论；或
- [ ] 已附逐轮 CSV + 15s 粒度 `vm.loadavg` 采样，且逐轮配对方向一致

## 文档同步

- [ ] `README.md` / `bench/README.md` / `CHANGELOG.md` 已按需更新
- [ ] 新增 SPI、配置项或响应头已写入文档
- [ ] 未引入真实密钥、内网地址或本机绝对路径
