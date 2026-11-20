# 控制面部署与操作

适用范围：单进程 Java + SQLite。持久库由 `LLMGW_CONTROL_DB` 指定；数据库目录和实例暂存目录应独占，持久卷要包含数据库、WAL 与 SHM。备份应使用 SQLite 一致性备份，不要只拷贝正在写入的主文件。数据库空间上限由 `LLMGW_CONTROL_DB_MAX_BYTES` 控制，可用磁盘安全余量由 `LLMGW_CONTROL_DB_MIN_FREE_BYTES` 控制。

新库首次启动仅导入一次旧 JSON 和配置 Key；后续配置不会恢复已吊销凭据，Key 变更通过控制面完成。SQLite 始终保存摘要；旧 `hash-keys-at-rest` 设置仅用于识别迁移源的格式。

首次启动显式设置 `LLMGW_ADMIN_USERNAME` 和 `LLMGW_ADMIN_PASSWORD`。已有账号时不会重新初始化或覆盖密码；无默认密码、无公开注册。浏览器打开 `/control/`，通过 HttpOnly、SameSite=Lax 的 `llmgw_sid` 会话管理。HTTPS 部署必须设置 `gateway.control-cookie-secure=true`；反向代理不得把控制接口开放给不受信网络。`GET /control/session` 恢复有效会话并返回当前 CSRF token（禁止缓存）；网页刷新或进程重启后先调用此接口，再携带 `X-CSRF-Token` 写入。登录每账号每分钟至多10次，超过返回429；限速状态有界并过期清理。会话默认一小时。

平台管理员先创建租户和项目，再选择项目，设置项目累计 token 额度。新项目额度默认0，创建额外 Key 或轮换 Key 不增加项目总额。Key 可设置独立累计上限；修改额度必须给出原因，不清空已有用量和在途预留。创建/轮换 Key 的明文只返回一次。轮换或吊销后旧凭据停止接受新请求，在途请求仍需完成并结算。历史用量按持久项目归属查询。`GET /control/quotas/pending` 返回预留及 `active` 标志；平台/租户管理员在核实上游实际用量后通过 `/control/quotas/reconcile` 填写输入、输出 token 与原因。当前在途预留不可核销，重复或跨项目核销拒绝，未知结果在重启后仍占额。该额度为累计 token 治理，不是财务硬预算。

账号支持 PLATFORM_ADMIN、TENANT_ADMIN、PROJECT_ADMIN、PROJECT_VIEWER。平台管理员可授予所有角色；租户管理员只能在本租户创建项目管理员/观察者；项目管理员只能在本项目创建观察者。观察者只读目录、用量和审计。跨租户项目ID即使伪装成本租户也拒绝。组织、Key、目录、用量、审计与凭据按主体或显式选定项目过滤；明文供应商凭据和密码hash不可读取。

路由是全局配置，仅平台管理员可发布和回滚。使用完整结构化路由JSON及当前 `expectedVersion`，版本冲突409时重新读取后确认候选内容。回滚生成新版本，不能覆盖旧历史。不要把手工目录登记当作供应商资源创建；网页只展示真实数据面目录，不提供手工伪造资源的菜单。

供应商凭据使用 `LLMGW_ROOT_KEY`（Base64 AES密钥）加密；根密钥缺失时供应商凭据控制接口写入返回503。根密钥必须随备份单独管理，不能保存在仓库或与数据库一同公开。目录的内部来源快照与凭据不通过控制API暴露。供应商凭据轮换后应发布引用该secretId的路由版本，以显式激活新凭据；已有资源继续使用其创建来源快照，需为后续资源操作保留有效的来源访问凭据。

旧 `/admin` token API 仅为显式迁移模式；生产配置legacy凭据以退出无凭据本地演示例外，并保持默认disabled策略，不应把旧token写旁路作为控制面权限替代。`docs/openapi.yaml` 描述 `/control` API；`bin/check-openapi.py` 会拒绝整组控制操作遗漏。

可通过固定产物验证，避免并行 profile/AOT 构建覆盖默认target：

```sh
LLMGW_TEST_JAR=/absolute/path/verified.jar python3 bin/smoke-admin-auth.py
LLMGW_TEST_JAR=/absolute/path/verified.jar python3 bin/smoke-jwt-auth.py
```

生产迁移前先备份数据库并保留对应版本产物。验证需要覆盖两个引擎、跨租户/项目反例、创建→真实调用→用量→轮换→吊销→重启、路由实际转发目标切换及回滚，不以版本号或组件单测替代业务验收。

## 账务归档与灾难恢复（ADR-0007 任务 9）

### 归档

`BillingArchiveService.archiveBefore(thresholdMs)` 把终态（settled/released）预留移入
`billing_reservation_archive`，并持久高水位与 SHA-256 摘要（`billing_archive_watermark`）。
账户锚点（`billing_account`）与充值幂等索引（`billing_recharge` 的
UNIQUE(tenant_id, voucher_no)）永不归档：归档前后余额/累计/在途不变，凭证重放在归档后
仍被幂等拒绝。活动（reserved）与未知（pendingVerify）预留不能归档。摘要损坏时
`verifyDigest()` 抛出异常——恢复必须拒绝。

### 备份与恢复

```sh
# 备份（目标必须为独立存储域；同盘副本会被拒绝）
python3 bin/backup-control.py --db /data/llmgw/control.db --target /vol-backup/llmgw/

# 恢复（核验清单摘要 + billing 表存在性；失败保持关闸）
python3 bin/restore-control.py --backup /vol-backup/llmgw/control-<stamp>.db \
  --target /data/llmgw-recovery/control.db
```

备份写入 `<backup>.manifest.json`（SHA-256）。恢复流程：核验通过后把备份放到恢复目标，
网关以 maintenance 启动；缺口核对（归档高水位之后的活动记录对账）结果**显式记录**后调用
`RecoveryGate.open(evidence)` 开闸——空证据不开闸。已吊销 Key 不因旧备份自动有效（密钥
状态随控制库恢复，恢复后仍为吊销态）。

### 演练

```sh
python3 bin/smoke-billing-recovery.py   # 全流程 ≤4 小时预算；恢复点 ≤1 小时
```

演练覆盖：实时结算 → 在线备份 → 同盘拒绝 → 篡改拒绝（摘要不匹配闸门保持关闭）→
独立目标核验恢复 → 余额/高水位一致 → 计时预算。恢复闸门组件合同（关闸→证据→开闸）
由 `RecoveryGateTest` 覆盖。
