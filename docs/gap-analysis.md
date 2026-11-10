# 当前实现与主流大模型网关的差距

核查日期：2026-11-10。结论：本项目适合 Java 技术栈中的轻量协议透传与可扩展治理数据面，尚不能替代完整企业 AI 管理平台。生产金额预算、统一身份与 RBAC、跨副本控制面是优先缺口。旧版“市场综合第一”和宿主机对 Docker 的倍数比较已撤回，原文仅保留在 [历史档案](archive/gap-analysis-v13-pre-audit.md)。

## 产品定位与官方能力对照

下表是定位与文档核查，不代表各产品的所有部署版本都有相同功能。功能存在不等于实现了严格财务上限；云平台功能也不自动属于开源镜像。

| 产品 / 维护方 | 形态与主要能力 | 对本项目的启示 | 本轮代理压测 |
|---|---|---|---|
| llmgw4j / 本仓库 | Java 25、WebFlux/VT、协议透传、SPI、内存/Redis 治理 | 保持小数据面，补齐治理一致性与资源边界 | 两引擎 |
| LiteLLM / BerriAI | Python 自托管代理，多供应商转换、虚拟 key、团队权限和路由；预算依赖部署及数据库 | 控制面与供应商生态更完整，不能用吞吐抵消能力差距 | v1.108.3 |
| Bifrost / Maxim AI | Go 自托管代理，供应商适配、虚拟 key、缓存、MCP；企业 SSO/集群等有版本边界 | 数据面与平台功能可以分层交付 | v2.5.0 |
| Portkey / Portkey AI | 可自托管网关及托管平台，供应商路由/回退/缓存等；云平台能力应单独评估 | SDK 兼容和运维体验值得补强 | OSS 1.15.2 |
| Higress / Higress 社区 | 基于 Envoy 的云原生 AI 网关，Wasm 插件、token 治理、语义缓存 | 更适合已有 K8s/网关体系；不可把框架开销归因为功能缺陷 | 未同口径实测 |
| Kong AI Gateway / Kong | 网关插件及平台体系，AI/MCP/A2A、身份和治理 | 企业网关生态强；普通 Kong HTTP 代理成绩不能冒充 AI Gateway | 未同口径实测 |
| New API / QuantumNous 社区 | 模型渠道、用户/令牌/额度/日志的运营平台 | 面向渠道运营的控制面明显比本项目完整 | 未同口径实测 |
| Cloudflare AI Gateway / Cloudflare | 托管缓存、日志、路由、预算限制等 | 免维护与云网络是不同选型维度，无法本机 Docker 等价部署 | 未同口径实测 |

官方来源（本轮核查）：[LiteLLM 架构](https://docs.litellm.ai/docs/proxy/architecture)、[虚拟 key](https://docs.litellm.ai/docs/proxy/virtual_keys)、[DB-less 限制](https://docs.litellm.ai/docs/proxy/users)；[Bifrost 版本与能力边界](https://getbifrost.ai/pricing)、[部署配置](https://github.com/maximhq/bifrost/blob/dev/docs/deployment-guides/config-json.mdx)；[Portkey OSS](https://github.com/Portkey-AI/gateway/blob/main/README.md)；[Higress AI 网关](https://higress.ai/en/ai-gateway)、[token 治理](https://higress.ai/en/docs/ai/scene-guide/token-management/)；[Kong AI Gateway](https://developer.konghq.com/ai-gateway/)；[New API 功能](https://docs.newapi.pro/zh/docs/guide/feature-guide)；[Cloudflare 功能](https://developers.cloudflare.com/ai-gateway/features/)。这些是官方文档声明，未逐一开展竞品全功能验收，也不据此作采购许可判断。

## 按代码反向核对

| 能力 | 当前实际边界 | 代码证据 |
|---|---|---|
| 多协议入口 | 支持多类 API 端点及 OpenAI/Anthropic 认证方言；端点数量不等于供应商协议转换数量 | `ProtocolKind`、两引擎控制器、provider 模块 |
| key 生命周期 | 创建、轮换、重叠窗口、吊销及 JSON 恢复；JSON 是单进程存储；Redis 运行时 key CRUD 仍 501 | `ManagedApiKeyRegistry`、`JsonKeyStore`、`RedisApiKeyRegistry` |
| 身份 | HS256 JWT 签名、时间声明格式和精度、可配置 issuer/audience；没有完整 OIDC/JWKS/企业 SSO，不能称企业身份平台 | `JwtTokenValidator` |
| 权限 | 模型允许列表、租户资源归属；审计读取 token；尚无组织/项目完整 RBAC | `EngineRoutes`、`TaskRouteSticky`、`AdminController` |
| 限流与配额 | key token 桶及按模型名跨 key 共享的模型桶、使用量统计；本轮修复 Redis 模型参数和模型额度退还。不是每 key×model、RPM 或团队多级策略系统 | `DefaultGovernanceChain`、`RedisTokenLimiter`、`RedisScripts` |
| 金额预算 | 只有内存预留/结算原型；生产模式拒绝金额预算配置，未提供持久化财务账本、未知价格完整覆盖及严格费用上界 | `GatewayProperties`、`InMemoryBudgetLedger` |
| 路由 | 模型/协议匹配、权重、熔断、条件回退；没有依据持续观测到的价格/延迟/质量进行自适应决策 | `RouteTable`、`ConfigRouter`、`ForwardingPolicy` |
| 重试 | 仅明确可重试的情况；普通 5xx/超时等提交结果不明确时停止，避免盲目重复计费；不保证请求幂等 | `ForwardingPolicy` |
| 缓存 | 租户/协议/模型/参数隔离；纯文本子集可做语义匹配；Redis 实现与内存向量能力不等价，不能统一宣称生产 ANN | `InMemoryVectorStore`、`RedisSemanticCache` |
| Guardrail | 请求前置 ALLOW/DENY/UNAVAILABLE，服务失败返回 503；不是完整的响应/SSE 审核与 PII 改写产品 | `HttpGuardrail`、`GuardrailChain` |
| 资源 API | 文件/异步任务按租户和路由绑定，未知归属 404；files/batches 列表仍 501；引用文件也要验证归属 | 两引擎控制器、`EngineRoutes` |
| 可观测性 | 指标、trace 头透传、请求及审计记录；不等于持久化合规审计、全链路分析或评测平台 | `HeaderPolicy`、`RequestLogSink`、`AuditSink` |
| 资源边界 | 全请求生命周期并发闸门、非流式响应大小上限、阻塞治理移出 WebFlux 事件循环；上传/二进制仍整包缓冲 | 两引擎控制器、`JdkHttpUpstream` |
| 多副本 | Redis 可共享部分使用量/限流/缓存/sticky/熔断；配置热更新和管理状态不具有完整分布式控制面一致性 | Redis 模块、`DynamicGovernanceSettings` |

## 本轮已落地的功能及性能改进

1. Redis 模型限流现在使用模型独立速率/容量，退款从 Redis 桶记录取容量，防止回到全局大容量。两实例共享真实 Redis 的失败测试先复现、修复后通过；CI 增加 Redis service。
2. 治理结算同步退还 key/model 预留，缓存和失败不再占住模型额度；正向差额也维持模型桶形状。新增三项回归测试。
3. 资源引用检查对没有 `file_id` 且没有 Unicode 转义的请求跳过额外 JSON 建树；含转义仍解析，保持归属验证。新增嵌套及 Unicode 引用回归测试。
4. 新增统一 Docker 基准、官方离线配置、严格数据解析及失败轮次排除规则；八项 Python 测试校验基准数据和 mock 契约。历史不公平的性能结论撤回。

局部微基准：4KB 普通文本的额外资源归属检查，线程 CPU 时间约 3.6–3.9μs/次降至 0.275–0.282μs/次，线程分配 5816B/次降至 0B/次。它仅衡量该检查，不能解释成整网关吞吐提升 13 倍。原始数据与端到端测量见本轮验证报告。

管理鉴权后续修复：缺失审计凭据返回 401；仅配置审计只读凭据时禁止匿名管理写入；生产拒绝相同读写凭据。[实施与验证](verification/audit/2026-11-10-admin-auth.md)。只读权限仍仅用于审计导出，完整 RBAC/OIDC 仍未实现。包含该修复的旧固定产物已完成下文历史跨网关测量，源码与产物校验和一并保留。

JWT 后续增补：新增 `gateway.auth.jwt-audience`，修复无效时间声明、到期瞬间和多余 token 段的接受问题，复用 JSON mapper 降低认证局部开销。[实施及证据](verification/audit/2026-11-10-jwt-auth.md)。下文性能表仍对应此前固定产物，本次未重新开展跨网关整服务测量。

## 后续产品与架构优先级

| 优先级 | 工作 | 验收标准 |
|---|---|---|
| P0 | 持久化预算/账本与结算恢复 | 多副本原子预留、崩溃恢复、未知提交状态对账、价格覆盖和费用上界；满足后才解除生产禁用 |
| P0 | 统一管理权限与身份 | 管理接口统一授权、角色/租户范围、拒绝路径测试；OIDC/JWKS 与审计持久化分阶段引入 |
| P1 | 可一致更新的控制面 | key CRUD、版本化配置、跨副本发布/回滚、Redis 动态参数语义明确；与数据面保持 SPI 边界 |
| P1 | 资源目录与大文件 | 租户独立文件/任务目录、列表/分页/生命周期；流式上传下载、取消和背压，测试峰值内存 |
| P1 | 路由和供应商覆盖 | 先增加有明确业务需求的适配器与契约测试，再做可解释的延迟/成本路由；不默认扩大不安全重试 |
| P2 | 响应治理与运维体验 | 可选响应审核、数据脱敏、持久请求检索、策略变更审计；分别披露性能成本 |

当前建议：继续把 llmgw4j 做成可靠的 Java 数据面；若立即需要企业身份、团队财务治理或渠道运营，应先评估已有平台。本轮优先修复已存在能力的不一致，不在审查中临时拼装未经充分验收的账本或身份平台。

## 性能测量

此前固定产物（不含后续 JWT、SSE/header 修改）的统一容器实测已完成：72 个正式样本全部零错误，且上游计数一致。每场景预热 15s、三轮各 10s；完整排名、p50/p95/p99、区间、配置和原始数据见 [最终实测报告](verification/perf/2026-11-10/final/README.md)。此前 84 样本批次及独立 A/B 保留为历史，不与本批拼接。

| 配置 | wire JSON RPS 中位数 | wire SSE RPS 中位数 |
|---|---:|---:|
| llmgw4j VT | 5845 | 4609 |
| Bifrost 2.5.0 | 4010 | 2680 |
| llmgw4j WebFlux | 2453 | 4291 |
| Portkey OSS 1.15.2 | 490 | 526 |
| LiteLLM 1.108.3 | 313 | 156 |

上表只是本批配置的中位数观察顺序；JVM JSON 轮次波动明显，不能认定稳态或普遍优胜。模拟 20ms JSON 推理延迟下，本项目两引擎中位数相差约 1.2%，Bifrost 比它们约高 4.4–5.6%；模拟 SSE 推理延迟下，本项目与 Bifrost 的差距均不到 5%，应视为接近。

独立 [长预热 A/B](verification/perf/2026-11-10/webflux-ab/README.md) 的 WebFlux SSE 中位数 3603→3835 RPS（+6.4%），区间重叠；资源归属检查的 CPU/分配下降也有单独微基准支持，均不能作为所有场景稳定提速承诺。参赛配置为代理基线，统一静态 bearer 认证由公共 Nginx 执行，各产品内部治理关闭；LiteLLM 为 4 worker，Portkey 使用官方镜像默认 Node 启动方式。不能据此推出最优部署、全功能治理或全市场排名。

Cloudflare、Kong、Higress、New API 没有同口径数据，保持“未测”。该性能批次源码通过真实 Redis 环境下的 105 项 JUnit（零跳过），WebFlux native 构建及 34 项契约通过。随后 JWT 增补的 114 项测试已通过（含单独补跑的 2 项真实 Redis 测试），但未重跑 native 或整网关压测。完整 [完成审计和证据](verification/audit/2026-11-10-completion-audit.md)。

## v0.2.0 最新性能与验证

最新 [性能审查](verification/perf/2026-11-10/perf-review/README.md)包含 JWT 后的冻结产物、两项 SSE/header 优化与 64 个治理 on/off A/B 样本，约 422 万请求零错误。确认局部分配下降；WebFlux SSE 吞吐中位数正向但范围重叠，JSON 一正一负，不认定稳定全面提速。它与上方旧跨网关表不是同一批。测试增加到 117 项（含独立真实 Redis 补跑），契约 67/54/46 与双引擎隔离通过。发布构建的 native 验证由 tag workflow 单独记录，不追溯更改历史产物证据。

性能后续优先级：动态 URI 缓存容量边界、异步桥接成本、专用宿主下的 CPU 配额/线程数与长流容量验证；缩小线程池的失败证据保留，默认仍为 32。
