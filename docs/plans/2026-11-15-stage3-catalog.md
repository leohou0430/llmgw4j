# 第三阶段细化子计划：项目资源目录与列表（T3.1–T3.10）

日期：2026-11-15。上游设计：`docs/superpowers/specs/2026-11-12-gateway-four-stage-design.md` §5；总计划 `docs/plans/2026-11-12-gateway-four-stage.md` §4（T3.1–T3.10，line 305–322）。本文件把阶段 3 细化为可逐切片 TDD 执行的子计划。**进入实施前阶段 2 须过门（T2.10 容器压力 + 阶段 2 全量回归绿；T1.18 真实窗口仍由用户硬门）。**

## 1. 新域与模块结构（多实现模块规则）

- `llmgw-persistence/`：聚合 parent + 唯一 `llmgw-persistence-api` + `llmgw-persistence-sqlite`（xerial sqlite-jdbc 实现）。
- `llmgw-catalog/`：聚合 parent + 唯一 `llmgw-catalog-api` + `llmgw-catalog-default`（本地目录/分页/状态机实现）。
- 根 POM 只追加这两个聚合目录；api 只依赖 core 及必要外部契约；实现依赖本域 api，不依赖同域其他实现，不共享 api 裸 Java 包。
- 所有基础 jar `llmgw.foundation.enforcer.skip=false`（parent 设置），由 Enforcer 检查传递依赖；persistence/catalog 禁 Spring/Boot/Reactor/Dubbo（HTTP 仅 app/engine）。JDBC 读写不在 WebFlux 事件循环——引擎侧用 boundedElastic 桥接，persistence 本身纯 JDK JDBC。
- KeyStore SPI（`llmgw-core/.../governance/KeyStore.java`）**不变**；SQLite 实现放 persistence-sqlite，命名 `ExtensionModule` 显式 `replace` JsonKeyStore（seal 前），未知模块/扩展名失败。
- 状态与资源归每个 `ExtensionContext`；AutoCloseable；双 context 隔离；禁静态全局有状态实例。

## 2. 逐任务 seam

### T3.1 SQLite 基础
- xerial sqlite-jdbc 版本固定（离线缓存可用：3.53.2.1 等，实施时核对 jar 存在）；WAL、短事务、有限写队列、busy timeout。
- seam：`SqliteContext`（AutoCloseable，归 context）打开/关闭/双实例隔离单测；读写并发不阻塞事件循环（引擎桥接测试）；native JNI 解压目录/资源包含实测（native-image 工具链已在 GraalVM bin；缺失先报阻塞）。

### T3.2 schema 与迁移
- 版本表 + 顺序迁移 + 失败回退指引；表：租户/项目/key 摘要、来源与配置版本、资源目录、审计（阶段 4 复用）；原文不进库。
- seam：迁移幂等/版本递增/失败回退单测（内存或临时文件 DB）；schema 针脚测试。

### T3.3 KeyStore 槽位替换
- SQLite KeyStore 显式 replace JsonKeyStore；upsert/delete/rotate/rotateAtRest/load/enabled 全合同。
- seam：命名发现/替换/失败路径 + 关闭/恢复/实例隔离测试；与 JsonKeyStore 合同 parity。

### T3.4 目录模型与授权
- 条目：网关 ID/类型/上游 ID/来源路由/供应商/配置版本/创建时间/可观察状态/元数据/最后同步/删除标志。先查项目授权再访问固定来源；默认路由切换不改旧资源来源；密钥轮换=同来源新凭据，来源撤销=明确不可访问。
- seam：目录 CRUD + 授权前置 + 来源固定（路由切换后旧资源仍指原来源）单测；跨项目隔离。

### T3.5 创建一致性
- 上游创建成功→先提交目录再回成功；目录提交失败→脱敏待核对事件 + 结果未知；不谎称跨系统原子；无幂等合同不自动重发。
- seam：受控上游创建成功/目录提交失败注入→断言响应口径（成功 vs 未知）+ 待核对事件脱敏；不自动重发。

### T3.6 稳定分页
- 本地目录分页；创建序号+ID 稳定排序；签名游标绑定租户/项目/筛选+首屏高水位；新增不混入同次遍历；页大小/过滤/查询时长限制；上游不可用可读目录+陈旧状态注明最后观察时间。
- seam：分页稳定性（遍历中新增/删除）+ 游标签名绑定（篡改拒绝）+ 页大小/时长限制 + 陈旧状态读单测。

### T3.7 任务目录
- 仅上游异步资源（batch/video）目录/元数据/状态/分页/已有查询取消；无网关执行器/队列/恢复调度；DeepSeek 未支持任务 API 只做受控上游合同。
- seam：任务目录 CRUD + 状态/分页 + 已有查询取消透传（受控上游）；不冒充真实供应商任务验收。

### T3.8 删除/取消状态机
- pending→confirmed/unknown；仅上游确认标完成；非幂等不自动重试；超时 unknown 可人工触发核对；无原文持久化/下载能力明确拒绝原文请求。
- seam：状态机迁移单测（上游确认/超时/人工核对）+ 原文请求拒绝；删除目录条目≠上游已删除。

### T3.9 旧资源迁移
- 旧 key 空间独立保留；显式 tenant/project 映射+人工审核记录才共享；缺归属证据不猜测、不扫供应商全账户导入；兼容入口限原 owner；预览/冲突拒绝/审计，不自动合并。
- seam：迁移预览/冲突拒绝/审计单测；缺归属拒绝；兼容入口原 owner 限制。

### T3.10 保留与磁盘保护
- 默认墓碑 30 天/详细审计 30 天（可配）；数据库/审计空间上限+磁盘剩余保护；达阈值拒绝需持久化的新建/变更，不无声丢审计/删活跃资源；强杀恢复/断写/磁盘满实测。
- seam：保留清理策略单测 + 空间上限/磁盘保护拒绝 + 强杀恢复/断写/磁盘满实测（临时 DB）。

## 3. 阶段门
- 设计 §8 第三阶段证据行；openapi/管理端点同步（`bin/check-openapi.py`）；全量回归同 T1.17/T2 门（三检查 + build.sh + 默认/Redis/jtokkit/otel profile + 双引擎流式/扩展/加固/租户隔离/管理/JWT smoke，LLMGW_TEST_JAR 固定；Redis 独立实例并清理）。
- 证据入 `docs/verification/<日期>/stage3-catalog/`；不改写历史测量；不自动提交/推送/发布。

## 4. 执行方式
子代理驱动（implementer TDD → spec-reviewer + code-quality-reviewer 并行静态审查 → 本人复核重跑/读 diff/独立计数/变异自证）。每切片独立可验证；资源切片必须覆盖关闭与实例隔离。子代理不可用时本人直接实施 + 自审 + trust-but-verify。

## 5. 准入核对（本轮已确认）
- sqlite-jdbc 离线缓存存在（~/.m2/org/xerial/sqlite-jdbc 多版本）；native-image 在 GraalVM JDK 25 bin。T3.1 无工具链阻塞。
- KeyStore SPI 已就位（core），JsonKeyStore 在 governance-default；T3.3 替换 seam 明确。
- 域 POM 约定参照 llmgw-governance（parent+api+impl，enforcer.skip=false）。