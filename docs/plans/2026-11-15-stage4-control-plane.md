# 第四阶段细化子计划：可操作控制面（T4.1–T4.12）

日期：2026-11-15。上游设计：`docs/superpowers/specs/2026-11-12-gateway-four-stage-design.md` §6；总计划 §5（T4.1–T4.12）。本文件把阶段 4 细化为可逐切片 TDD 执行的子计划。进入实施前阶段 3 已过门（全反应堆 961/0/0/26 + 三检查绿 + 各 T3 切片 TDD/变异自证）。

## 1. 新域与结构

- `llmgw-identity/`：聚合 parent + 唯一 `llmgw-identity-api` + `llmgw-identity-default`（账号/角色/scope/RBAC/会话/密码哈希实现）。
- 复用 `llmgw-persistence`（SQLite）保存账号/角色/会话/管理审计；复用 `llmgw-catalog`（目录/发布后资源）；HTTP 端点与静态网页在 `llmgw-app` 与双引擎（HTTP 只在 app/engine）。
- foundation 规则不变：无 Spring/Reactor/HTTP；`llmgw.foundation.enforcer.skip=false`；命名 `ExtensionModule` 显式构造，seal 前 `replace`；状态归 `ExtensionContext`；禁静态有状态注册表。
- 密码哈希：bcrypt 库离线不可用；按设计「不自行实现密码学、兼顾 native」采用 JDK 内置 `PBKDF2WithHmacSHA256`（成熟标准 KDF）+ `SecureRandom` 盐 + 可配迭代，常数时间比较。记录选择依据。

## 2. 逐任务 seam（可 TDD 切片）

- **T4.1 账号与角色**：`Role` 枚举（PLATFORM_ADMIN/TENANT_ADMIN/PROJECT_ADMIN/PROJECT_VIEWER）+ `Scope`（tenantId/projectId）；账号记录（id、username、passwordHash、role、scope、enabled）；`PasswordHasher`（PBKDF2 哈希/校验）；一次性本地 bootstrap 建首个平台管理员（无默认凭据/无公网注册）。seam：角色/scope 模型 + 哈希校验 + bootstrap 仅一次 + 未授权拒绝。
- **T4.2 登录与会话**：随机服务端会话（`SessionStore`）；HttpOnly/Secure/SameSite cookie、CSRF token；登录限速；过期/吊销；改密/改权限失效相关会话；TLS 部署说明。seam：会话创建/校验/过期/吊销 + CSRF + 限速 + 改密失效。
- **T4.3 RBAC 执行**：每接口服务端校验 scope+动作；跨租户/越权统一拒绝；项目管理员不能自授上级角色/引入未允许上游；凭据/密码哈希不可读。seam：授权矩阵单测（每角色×动作）+ 越权反例。
- **T4.4 管理 API**：租户/项目/key（明文仅创建/轮换显示一次、读取掩码）、来源、目录、用量、脱敏审计。seam：API 合同 + 掩码 + 越权拒绝。
- **T4.5 路由版本**：草稿→校验→不可变发布→本进程原子激活；期望版本防并发覆盖；回滚=新版本引用目标；启动恢复已提交版本；响应注明本进程激活状态；进行中请求/既有资源持有来源快照；密钥吊销单独定义；不宣称跨副本。seam：版本发布/回滚/期望版本冲突/启动恢复。
- **T4.6 配置校验**：扩展名/模型协议能力/允许上游地址与协议（平台权限，项目不能放宽）/敏感头/超时容量边界；拒地址探测/重定向绕过/DNS 变化绕过。seam：校验矩阵 + 地址绕过反例。
- **T4.7 密钥静态加密**：供应商密钥加密保存；根密钥库外注入；缺失行为明确；备份根密钥恢复前提。seam：加解密往返 + 根密钥缺失行为。
- **T4.8 旧 token 迁移**：新控制面默认禁 X-Admin-Token 远程管理；显式迁移模式限本机/受限网、可审计紧急平台权限；旧只读页迁移；不留无 scope 后门；JWT 独立继续验证。seam：默认禁用 + 迁移模式受限 + 越权拒绝。
- **T4.9 审计与观察**：配置/资源变更与管理审计同事务，失败拒绝变更；上游已生效按 unknown 补偿；HTTP 观察有界队列+汇总、丢弃数可见；非财务账本；磁盘满影响写入判断。seam：同事务失败回滚 + 有界队列丢弃计数 + 磁盘满拒绝。
- **T4.10 网页**：同进程静态资源（无 Node）；登录+租户/项目/key/路由发布回滚/目录/用量/审计菜单闭环；每角色动作浏览器实测（含越权反例）。
- **T4.11 备份恢复**：WAL 一致性在线备份或停写快照；升级前备份+schema 迁移回退；强杀/启动恢复/断写/磁盘满/还原/缺根密钥/旧 schema 实测。
- **T4.12 native 与总预算**：JNI 解压目录、native 资源包含、双引擎 native 启动+SQLite 恢复实测；全部产品进程合计 2C2G 复测（不引用第一阶段资源结果代替）。

## 3. 阶段门

设计 §8 第四阶段证据行：网页和 API 每角色动作回归、登录/会话/CSRF、key 生命周期、版本发布/回滚、审计与备份恢复；全部产品进程总 2C2G 复测。全量回归 + 三检查 + 双引擎 smoke（LLMGW_TEST_JAR 固定；Redis 独立实例并清理）+ openapi 同步（`bin/check-openapi.py`）。证据入 `docs/verification/<日期>/stage4-control-plane/`；不改写历史测量；不自动提交/推送/发布。

## 4. 执行方式

本人直接 TDD 实施 + 自审 + trust-but-verify（重跑、读 diff、独立计数、变异自证）。资源切片覆盖关闭与实例隔离。子代理不可用。

## 5. 准入核对

- SQLite/persistence（T3.1-T3.3）与 catalog（T3.4-T3.10）已就位，供账号/会话/审计/目录持久化。
- bcrypt 库离线不可用 → 采用 JDK PBKDF2WithHmacSHA256（成熟标准 KDF，native 友好），不自行实现密码学原语。
- 既有管理/JWT 端点与 console 静态资源模式可复用（T1 已有 admin-auth/jwt-auth smoke）。
