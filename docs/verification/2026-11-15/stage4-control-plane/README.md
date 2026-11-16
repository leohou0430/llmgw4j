# 阶段 4 可操作控制面 — 证据索引与状态（2026-11-15）

本目录为第四阶段（T4.1–T4.12）证据索引。所有运行时项均在当前构建产物上实测：`llmgw-app-0.3.1.jar`（`mvn -o -pl llmgw-app -am package -DskipTests` BUILD SUCCESS），`--spring.profiles.active=webflux`（双引擎项另注 vt），env 驱动首管理员（无默认凭据）。全反应堆 `mvn -o test`：**1023/0/0/26 BUILD SUCCESS**；三检查（layout / native-boundaries / native unittest）全绿。

## 任务 → 证据

| 任务 | 交付（已实现/已验证） | 证据 | 状态 |
| --- | --- | --- | --- |
| T4.1 账号与角色 | Role/Scope/Account、PBKDF2 加盐、一次性 bootstrap；**账号 SQLite 持久化跨重启** | t4.1-accounts-roles.md、t4.1-account-persistence.md | 逻辑+持久化完成；会话持久化未做 |
| T4.2 登录与会话 | 随机会话/过期/吊销/改密失效、CSRF、登录限速；`POST /control/login`(HttpOnly/SameSite+CSRF) | t4.2-sessions-csrf.md、t4.4-auth-runtime.md | 逻辑+端点完成；会话仍内存 |
| T4.3 RBAC | 矩阵/不越级/凭据不可读；运行时 admin 200 vs viewer 403 | t4.3-rbac.md、t4.4-role-runtime.md、t4.3-keystore-persistence.md | 完成（越权反例运行时验证） |
| T4.4 管理 API | key 一次性明文+掩码+读取掩码+生命周期(RBAC+SQLite hash-at-rest)；登录/建key/读key端点 | t4.4-key-masking.md、t4.4-key-lifecycle.md、t4.4-key-list.md、t4.4-app-wiring.md、t4.4-request-authorizer.md、t4.4-dual-engine-runtime.md | key 域完成；租户/项目/来源/目录/用量/审计端点未做 |
| T4.5 路由版本 | 草稿→不可变发布→激活；期望版本(409)；回滚新版本；**SQLite 持久化+启动恢复** | t4.5-route-versions.md、t4.5-route-runtime.md、t4.5-route-persistence.md | 完成（运行时） |
| T4.6 配置校验 | scheme/私网·回环·元数据/超时容量/敏感头 | t4.6-config-validation.md | 逻辑完成；接入发布/端点未做 |
| T4.7 密钥静态加密 | AES-256-GCM + 根密钥注入/缺失 fail-closed | t4.7-secret-encryption.md | 逻辑完成；未接入列加密 |
| T4.8 旧 token 迁移 | 默认禁用/迁移限本机受限网/紧急审计 | t4.8-token-migration.md | 逻辑完成；未接运维 |
| T4.9 审计与观察 | 变更+审计同事务回滚；有界队列丢弃计数 | t4.9-audit-observation.md | 逻辑完成；未接端点/指标 |
| T4.10 网页 | 同进程静态壳（登录/状态/发布）；**浏览器每角色实测**（admin 200/viewer 403） | t4.10-static-page.md、t4.10-browser.md | 基础壳+单页浏览器实测；完整菜单未做 |
| T4.11 备份恢复 | 在线一致性备份(VACUUM INTO)+还原校验 | t4.11-backup-restore.md | 备份/还原可测部分完成；强杀/断写/磁盘满/旧schema未测 |
| T4.12 native 与预算 | **整体 2C2G 复测**（docker 2CPU/2GiB，RSS~225MiB，无 OOM） | t4.12-2c2g.md | 2C2G 完成；**native AOT 未做** |

## 后续补充（2026-11-16，运行时验证）
- **T4.2 会话 SQLite 持久化**：V5 `session` 表 + `SqliteSessionStore`；登录→重启同库→同一 cookie GET status/audit 200。证据 `t4.2-session-persistence.md`。
- **T4.4 租户/项目端点**：`OrgStore` + `ControlOrgController`（GET/POST tenants/projects，CATALOG_READ 读 / TENANT_MANAGE 写 + 审计）。证据 `t4.10-menu-browser.md`。
- **T4.9 审计接线**：key.create/route.publish/rollback 写 `audit_event`；`GET /control/audit`(AUDIT_READ 脱敏)。证据 `t4.9-audit-endpoint.md`。
- **T4.10 多菜单浏览器**：`index.html` 登录/状态/租户/项目/key/路由/审计；浏览器 admin 全通、viewer 建 403/读 200。证据 `t4.10-menu-browser.md`。
- **T4.4 目录与用量端点**：`GET/POST /control/catalog`（CATALOG_READ/CATALOG_WRITE + 审计），目录改为 `SqliteResourceCatalog`（`resource_catalog` 表、rowid 稳定序、签名游标高水位、跨项目存储键隔离）；`GET /control/usage`（CATALOG_READ）。webflux/vt 各 200；viewer 写 403；跨重启目录仍在。证据 `t4.4-catalog-usage-runtime.md`。
- **T4.12 native AOT（webflux）**：本机 macOS x86_64 实际构建 292,205,960 bytes 并启动验证（health/status/login/建 key/发布路由/SQLite schema v5）。证据 `t4.12-native-aot.md`。
- **T4.2 登出**：`POST /control/logout`（会话+CSRF；吊销会话、清 cookie）；运行时登出后受保护端点 401、session 行归零。证据 `t4.2-logout-runtime.md`。
- **阶段 4 全量门**：三检查 + openapi（隔离 venv 提供 PyYAML）+ jtokkit/otel/redis(独立 6394) 各 1023/0/0 + build + fixtures + 7 smoke 全 exit 0；JAR sha 45bdc5e4…。证据 `stage4-gate.md`。
- 新增菜单浏览器实测：admin 建/列目录 + usage + audit 200；viewer 干净来源首登写 403。证据 `t4.10-catalog-usage-browser.md`。

## 仍缺（不声称完成）
- T4.12 native：macOS 双引擎（webflux/vt）已构建并验证；**Linux native 未验证**。
- T4.8 旧 X-Admin-Token 迁移未接运维入口；断写故障注入未实测。
- 阶段 1 硬门 T1.18（用户放行+凭据）。

## 后续补充（2026-11-16，门后变更）
- **T4.6 路由结构化校验接线**：`RouteCandidateValidator` + 发布体可选 `upstream`；http/私网/越界/敏感头 400 并审计，合法 https 200。证据 `t4.6-route-validation.md`。
- **T4.7 密钥加密端点**：V6 `upstream_secret` + `SecretStore`（AES-GCM，仅密文入库，掩码读取，无根密钥 503）；平台级 SOURCE_MANAGE，viewer 403；重启解密一致。证据 `t4.7-secret-encryption-runtime.md`。
- 门后全反应堆复跑：XML=120 tests=1034 failures=0 errors=0 skipped=26；三检查全绿。
- **T4.11 磁盘保护与旧库升级**：`WriteGate` 已覆盖密钥/路由/目录/租户项目/供应商密钥全部管理写路径（超限 503、零部分写入、读仍可用）；V1 库端到端升级到 V6 且旧数据保留。证据 `t4.11-storage-guard-upgrade.md`。
- **T4.12 vt native**：构建成功并在 vt profile 下验证认证/会话/key/路由校验/目录/密钥加密/审计/logout；期间发现并修复 record 反射缺口（AOT hint）。证据 `t4.12-native-vt.md`。
- **T4.12 webflux native（第二次构建）**：含最新端点与 hints，292,390,576 bytes；secrets/catalog 列表序列化正常、logout 401、schema v6。证据 `t4.12-native-aot.md`。

## 复现入口
- 构建：`JAVA_HOME=<graalvm-jdk-25> mvn -o -pl llmgw-app -am package -DskipTests`
- 运行：`LLMGW_ADMIN_USERNAME=… LLMGW_ADMIN_PASSWORD=… LLMGW_VIEWER_USERNAME=… LLMGW_VIEWER_PASSWORD=… LLMGW_CONTROL_DB=/tmp/llmgw-control.db java -jar llmgw-app/target/llmgw-app-0.3.1.jar --spring.profiles.active=webflux`
- 端点：`POST /control/login`、`POST /control/keys`、`GET /control/keys`、`POST /control/routes/publish|rollback`、`GET /control/routes`、`GET /control/status`、静态 `/control/index.html`。
