# 安全策略

## 报告漏洞

**请不要为安全漏洞公开提 issue。** 请使用
[GitHub Security Advisory 私下报告](https://github.com/leohou0430/llmgw4j/security/advisories/new)，
或通过 GitHub 私信仓库维护者。公开 issue 会被关闭并转到私信渠道。

请在报告中包含：影响的版本/commit、复现步骤、潜在影响、以及（如有）建议的修复。
请**不要**在报告里附带真实的上游 API key，复现请用内置 mock 上游。

这是个人维护的项目，没有 SLA；目标是尽快确认并给出回复，重大问题会在修复后
发布公告与版本。修复发布前请勿公开细节。

## 支持的版本

| 版本 | 支持 |
|---|---|
| `0.3.x` | ✅（当前修复版本） |
| `0.2.x` | ❌，升级到 `0.3.1`；Java 扩展需按迁移说明适配 |
| `0.1.x` | ❌，升级到 `0.3.1` 以获得隔离、鉴权和治理修复 |
| `< 0.1.0` | ❌（尚未发布） |

## 部署前必须知道的安全边界

默认配置仅用于本地演示。生产必须显式启用 `gateway.production=true`，并满足启动校验：

1. **管理面默认开放**：`application.yml` 里
   `gateway.admin-token: ${LLMGW_ADMIN_TOKEN:}` 默认空值；仅完整管理 token 与
   `gateway.admin-read-token` 都为空时，管理数据接口无需鉴权。仅设置只读 token 不会开放写接口。
   生产模式缺少完整管理凭据或读写凭据相同会拒绝启动；任何共享环境都必须设置 `LLMGW_ADMIN_TOKEN`。
2. **示例密钥**：默认 api-key 是字面量 `sk-demo`，仅用于本地 mock 演示，
   生产环境必须替换 `gateway.api-keys` 并使用真实密钥存储。
3. **上游密钥注入**：`application-real.yml` 从 `DASHSCOPE_API_KEY` 读密钥，
   仓库内没有任何真实凭据；`/admin/routes` 只回显 `apiKeyConfigured` 布尔值，
   不会输出密钥本身。热配置 `POST /admin/routes/reload` 同样不回显。
4. **无内建 TLS / mTLS / WAF**：网关只管转发与治理，传输加密、
   客户端证书、入口限流应由前置反向代理或服务网格承担。
5. **响应头按白名单透传**：`gateway.headers.client-allow` 控制厂商响应头；
   Authorization 等受保护头始终剥离。未列入白名单的头不会回写。
6. **配额不是硬上限**：准入按 `estimatedTokens` 预留、结算按实际
   `prompt + completion` 记账，并发下最终 `used` 可能略超 `quota`。
   金额账本默认也只在内存中，Redis 包不替换 `BudgetLedger`。当前内建实现
   不满足跨重启、多副本的财务级硬预算，生产模式不能启用金额预算。
7. **外部强制审核**：已配置的审核服务超时、异常或无效响应返回服务不可用，
   不缓存故障放行。明确未启用审核与已启用但故障是不同状态。
8. **重试边界**：可能已提交上游的付费请求不会因普通超时或 5xx 自动重发；
   只有确认未发送、明确限流或熔断等已证明安全的情况才允许后续尝试。
9. **资源归属**：文件、批任务、视频按稳定 key ID 与协议隔离；未知、过期或重启后
   未恢复归属的资源返回 404。旧版未分区绑定不会授权访问。文件和批任务列表暂返回 501，
   不直接暴露共享上游的完整资源列表。
10. **内存边界**：`gateway.max-response-bytes` 默认 4MiB，限制非流式 JSON 和二进制响应；
    `gateway.max-concurrent-requests` 默认 64，覆盖两引擎的整个数据面请求生命周期。
    大文件尚非流式下载，超限返回上游错误；调高限制前需按并发总内存评估。

## 依赖安全

- 依赖版本集中在根 `pom.xml` 的 `<properties>` 与 `<dependencyManagement>`。
- CI 会对每个 PR 跑 `mvn -B verify`；依赖升级请单独提 PR 并附回归结果。

JSON 密钥存储只保证单进程内原子读改写，不可让多个进程共享该文件作为一致性数据库。
Redis 包当前提供配置密钥的共享查询，不实现 `KeyAdminService`；运行时创建、轮换和吊销返回 501。
审计只读 token 仅用于 `/admin/audit/export`，其他管理数据接口仍要求完整管理 token。

JWT 内建实现仅支持 HS256。启用 JWT 时可设置 `gateway.auth.jwt-issuer` 与
`gateway.auth.jwt-audience` 限制发行者和接收方；audience 默认空时不作接收方限制。
提供的 exp/nbf 必须为数值，过期或尚未生效的 token 返回 401。缺失 sub 的兼容映射
仍为 `jwt:anonymous`，不替代完整身份或租户授权；OIDC/JWKS/企业 SSO 未内建。
