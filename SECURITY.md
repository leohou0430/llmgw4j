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
| `0.1.x` | ✅ |
| `< 0.1.0` | ❌（尚未发布） |

## 部署前必须知道的安全边界

这些是**当前设计**，不是漏洞，但直接暴露到公网会很危险：

1. **管理面默认开放**：`application.yml` 里
   `gateway.admin-token: ${LLMGW_ADMIN_TOKEN:}` 默认空值，`/admin/*` 无需鉴权。
   任何共享环境都必须设置 `LLMGW_ADMIN_TOKEN`。
2. **示例密钥**：默认 api-key 是字面量 `sk-demo`，仅用于本地 mock 演示，
   生产环境必须替换 `gateway.api-keys` 并使用真实密钥存储。
3. **上游密钥注入**：`application-real.yml` 从 `DASHSCOPE_API_KEY` 读密钥，
   仓库内没有任何真实凭据；`/admin/routes` 只回显 `apiKeyConfigured` 布尔值，
   不会输出密钥本身。热配置 `POST /admin/routes/reload` 同样不回显。
4. **无内建 TLS / mTLS / WAF**：网关只管转发与治理，传输加密、
   客户端证书、入口限流应由前置反向代理或服务网格承担。
5. **上游响应头不透传**：`x-ratelimit-*` 等厂商头目前不回写，
   不要依赖网关向下游传递厂商的安全/配额语义。
6. **配额不是硬上限**：准入按 `estimatedTokens` 预留、结算按实际
   `prompt + completion` 记账，并发下最终 `used` 可能略超 `quota`。
   需要财务级硬上限请自行替换 `UsageLedger` 实现。

## 依赖安全

- 依赖版本集中在根 `pom.xml` 的 `<properties>` 与 `<dependencyManagement>`。
- CI 会对每个 PR 跑 `mvn -B verify`；依赖升级请单独提 PR 并附回归结果。
