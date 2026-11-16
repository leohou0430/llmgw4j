# 安全策略

## 报告漏洞

请通过 [GitHub Security Advisory 私下报告](https://github.com/leohou0430/llmgw4j/security/advisories/new)提交影响版本、复现及潜在影响，不在公开issue或证据中附真实供应商凭据。项目由个人维护，无响应SLA。

## 支持的版本

| 版本 | 支持 |
|---|---|
| `0.4.x` | 当前修复版本 |
| `0.3.x`及更早 | 升级到`0.4.0`；先按[升级说明](docs/releases/v0.4.0.md)备份与迁移 |

## 当前部署边界

1. **单进程Java + SQLite**：密钥、累计token额度、目录、会话、审计与已发布路由共用本实例数据库。不支持多网关副本共享SQLite；Redis可选治理不改变这个范围。
2. **管理入口**：`/control`使用账号/会话/角色及真实租户项目关系；写入需要CSRF。首次从外部设置`LLMGW_ADMIN_USERNAME`、`LLMGW_ADMIN_PASSWORD`，没有默认管理密码或公开注册。HTTPS设置`gateway.control-cookie-secure=true`，管理入口应放在受信网络。
3. **旧管理接口的本地演示例外**：完整和只读legacy token都未配置时，旧`/admin`仍保持本地演示开放语义，不能暴露到共享网络。生产校验要求非空完整管理token；配置legacy凭据后`gateway.admin-token-mode=disabled`默认拒绝携带legacy token的旧管理访问。需要迁移时显式启用`migration`并限制来源网络；只读token不授权写入。
4. **客户端与供应商凭据分开**：生产必须替换demo客户端key并关闭mock。SQLite客户端key始终保存摘要；新库仅导入一次旧JSON/配置key，吊销后重启不会复活。生产路由发布通过`secretId`引用外部根密钥加密的凭据，禁止直接发布明文`apiKey`。
5. **根密钥与备份**：`LLMGW_ROOT_KEY`用于供应商凭据及带凭据的资源来源快照。根密钥缺失时相关功能失败关闭。根密钥单独保管；SQLite采用一致性备份，不能只复制正在写入的主库。数据库目录限制访问，保留WAL/SHM所在持久卷。
6. **额度语义**：项目总额与key子上限原子预留；权威usage用于结算，未知上游结果跨重启保守占额。估算预留并不是生成输出硬上限，实际结算可能超出原估算。管理员核实未知用量、给出原因并审计后才可核对；不能清除当前在途请求。金额预算仍是实验能力，不提供财务级硬预算。
7. **配置与审计原子性**：管理业务变更与actor/tenant/project审计在同一SQLite事务；路由仅在成功提交后激活。数据库、WAL、SHM计入容量保护，空间不足时拒绝写入，不能绕过保护继续计量。
8. **资源归属**：文件与批任务按项目持久目录授权，同项目多key共享，未知/跨项目404；列表只列本项目目录。创建时来源配置快照固定并按需加密，不因路由改版把资源请求发往别的供应商。视频保留独立sticky语义；未知归属不重新猜测上游。
9. **HTTP与重试**：TLS/mTLS/WAF由前置代理或标准服务器配置承担。认证/transport头始终受保护；multipart保留boundary。已可能提交上游的请求不因普通故障盲目重发，fallback不拼接已输出的流。
10. **资源与流式完整性**：默认全生命周期并发64，请求/非流式响应上限4MiB；成功SSE按单行边界限制而非累计响应大小。超时、取消、完整性与心跳均有合同测试。暂存目录使用实例独占锁和启动回收；每实例必须独占数据库和暂存路径。

JWT内建仅HS256，可配置issuer/audience及NumericDate时间声明；无OIDC/JWKS/企业SSO。账号角色不代表企业身份联邦。供应商支持与性能以对应固定产物证据为准，详见[控制面操作](docs/control-plane-operations.md)、[发布说明](docs/releases/v0.4.0.md)和[验证范围](docs/reviews/2026-11-16-remediation/README.md)。
