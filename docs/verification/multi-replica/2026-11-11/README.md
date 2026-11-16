# 第 2 步：多副本治理审查（2026-11-11，已完成）

第 1 步使用其独立冻结产物完成；本目录记录后续源码和产物，不覆盖前一阶段的测量。十项[收尾门槛](phase-2-completion.json)全部通过；34 个性能样本及 13 个延长预热复查样本零断言失败，性能成本见 [性能报告](capacity-results.md)。当前没有提交、推送或发布。

## 已复现并修复

| 问题 | 修复及定向证据 |
|---|---|
| 客户端时钟快一小时会立即补发限流额度；慢时钟会回拨桶时间 | Lua 使用 Redis TIME，并保留时间单调下界；`red.log` → `hash-counter-green.log` |
| 后启动副本覆盖共享 token 策略；启动批次失败仍发布部分新 key | 比较完整策略后一次 Lua 校验并 HSETNX；相同配置并发启动可成功，冲突失败不改已有值 |
| Redis 凭据丢失 metadata、过期凭据仍能认证 | 保留完整 metadata，认证过滤 expiresAt；`metadata-range-red.log` → `metadata-green.log` |
| 默认 hash-keys-at-rest 对 Redis 无效，原始 token 是字段名 | SHA-256 字段使用独立表；明文/哈希模式冲突失败，不自动删除迁移数据；`hash-counter-red.log` → `hash-counter-green.log` |
| `keys` ID 与 Redis usage 索引冲突；索引类型损坏后 Lua 报错却已扣预留 | 拒绝冲突 ID，变更前检查 Redis 数据/索引类型；`typeguard-red.log` → `consistency-green.log` |
| 损坏 requests 字段导致结算先释放预留再失败 | 修改前验证六个计数字段；`hash-counter-red.log` → `hash-counter-green.log` |
| 快照恢复抹掉其他副本在途预留；批次恢复不原子 | Redis 单脚本校验全部目标后恢复；内存生命周期读写锁；有预留整批拒绝，保留未提供的 ID |
| 内存结算/新预留竞争可能短暂少算已用量 | 每个身份的预留、结算和读数使用同一锁；`snapshot-red.log` → `snapshot-green.log`，20,000 次公开 API 竞争回归 |
| 快照损坏/落盘失败被当作成功，非法数字覆盖现存数据 | 严格对象和非负整数校验、同目录临时文件原子替换、传播错误；SnapshotSafetyTest 10 项通过 |
| 大整数计费中间乘法溢出，即使最终金额可表示也算错 | 检查过的 long 快路径及 BigInteger 精确回退；无法表示的最终金额抛错；`identity-math-red.log` → `default-green.log` 的 QuotaMathTest 9 项 |
| key ID 可冒充模型/群组治理作用域 | 禁止 `model:`/`group:` 前缀；已有 ID 升级需协调迁移，禁止自动清零 |
| 共享熔断冷却期允许探测，冷却后所有副本同时放行 | 冷却标记和 tripped 状态分离，冷却后竞争 10 秒探测租约；失败立即重新冷却，成功清状态；`breaker-red.log` 两项真实失败（0→1、1→16）→ `breaker-green.log` 26 项通过 |

限流时间的依据是 [Redis 的脚本与效果复制说明](https://redis.io/docs/latest/develop/programmability/eval-intro/)；使用现代 Redis 的脚本执行语义。时间回拨时不会凭空增加额度，但旧的未来时间戳可能使桶暂停补充直至服务时间追上或桶过期。

## 产物与验收

- 最终 Redis：`bench/results/multi-replica-2026-11-11/redis-fixed.jar`；默认包：同目录 `default.jar`。SHA 与双副本命令记录于 `distributed-final.json`。
- `redis.jar` 是修复熔断前的阶段检查点；不能把其 Redis 熔断行为当作最终证据。
- `profiles/` 是检查点验证，`final-profiles/` 用最终产物校验 default、Redis、Redis/jtokkit/otel 的 clean verify 和基础模块 class 摘要；三种配置各 243 项测试、0 失败/错误/跳过，共同及 Redis 模块 class 摘要匹配。
- `contracts/` 固定检查点产物的双引擎 HTTP 契约；最终产物需核对共同模块 class 摘要，新增 Redis 熔断单独由真实 Redis 回归与最终双副本测试验收。
- `cluster.log` 是两个独立内存副本对照一个共享 Redis 账本的并发配额验收；`recovery-final.log` 是 WebFlux/VT 共享状态、单节点重启、Redis 中断失败关闭、RDB 保存点恢复重连、跨节点文件归属和外租户隔离。
- 分布式功能验收使用宿主机 JVM，未把每个副本限制为 2C2G；单副本性能对照另用 2 CPU / 2 GiB 容器、1 GiB heap、禁 swap、64 VU，结果与功能验收分开。
- 所有 Redis 实例由测试创建并核对进程 PID，使用临时目录，结束时只清理本次资源。`cluster-first.log` / `distributed-first.json` 保留未 clean 切换 profile 时误复用 Redis fat JAR 的真实失败；`package-clean-default.log` 后重新验收，默认包无 Redis 依赖且两个分布式套件通过。

## 能力边界及升级影响

共享六项能力：token 限流、token 用量账本、响应缓存、任务/资源绑定、熔断及凭据读取。金额预算、请求日志、路由/模型目录/策略管理状态仍是本地实现；没有版本化分布式控制面。所有副本必须统一版本、路由 ID、凭据策略、限流配置。Redis 包运行时 key CRUD、文件及批任务列表仍返回 501。

本轮真实 Redis 为 7.0.7。Redis 使用单 endpoint/Jedis pool，不支持 Cluster/Sentinel 自动发现；固定前缀下同一 database 属于同一逻辑网关，独立部署需隔离 database/实例。探测租约为 10 秒，长于此时间的探测可与下一次租约重叠；跨版本脚本混用不提供状态一致性保证。动态删除的旧 route 状态没有自动全量清理。

默认开启哈希存储现在实际生效；此前被忽略的配置会在旧明文表存在时触发明确启动失败。保留明文模式或离线迁移的具体要求见 [配置说明](../../../CONFIGURATION.md#38-redis-分布式状态可选仅--predis-产物)。旧备份/AOF 里的 token 不会自动消失。保留前缀 ID、Redis `keys` ID 与超过 `2^53-1` 的配额需在升级前处理。

配额预留为估算，最终真实 token 可超过估算；没有持久预约 ID、重复结算幂等键或进程崩溃后的预留对账。Redis 测试通过显式 SAVE 后恢复 RDB，不能推导任意掉电/网络分区下不丢账。快照是按 ID 替换的手动诊断能力，文件原子替换不承诺财务级崩溃持久性，恢复应停流量。没有把这些缺失能力包装成分布式财务预算。
