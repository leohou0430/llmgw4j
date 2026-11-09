# llmgw4j vs 主流大模型网关：差距分析与补齐方案

> 分析日期：2026-11-08 ｜ 基线：v12（f7e30a7）｜ 方法：代码库全量盘点 × 14 款主流网关能力调研
>
> **执行状态（已完成）**：24 项差距全部闭合（含 P2-#19 协议面最终 11 协议、
> #21 任务粘性跨副本 Redis 化、#23 发布链路配置、作品级测试基建）。功能证据：
> `docs/verification/final-all-green.log`（9 个冒烟套件 + 5 个模块单测全绿）；
> 性能证据：`docs/verification/perf/k6-ab-v12-vs-v13.txt`（v12 基线 A/B：JSON
> 6107/s vs 基线 3734/s、SSE 持平、故障率 0%，无回归）。任务分解与逐项打勾见
> [tickets.md](tickets-v13-execution.md)。

**对标基线**：LiteLLM、Higress、Kong AI Gateway、APISIX、Cloudflare AI Gateway、Portkey、OpenRouter、OneAPI/NewAPI、Helicone、Vercel、TrueFoundry、Bifrost、BricksLLM、MLflow（口径：核心开源版，EE 单独标注）

**结论先行**：llmgw4j 的能力面**不是「全缺」**——在协议覆盖、流式治理、token 级配额三个维度上它反而领先多数开源网关；真正的差距集中在**四个公约数**：重试、密钥生命周期、$ 预算、可观测深度。这四项恰好都落在已有的 SPI seam 上，可以增量补齐而不动架构。最大的结构性前置是一个：**把封闭的协议 switch 重构成 Protocol SPI**，否则任何多协议/多 provider 扩张都会撞墙。

**已确认的设计立场**（与项目所有者对齐）：

1. 项目定位 = 作品级架构 + 逐步生产化，两条线并行。
2. **透传为主**：SSE 契约/计费/容错都建立在字节透传上，协议转换列为远期可选项，不作为「缺失」扣分。
3. **协议是路由维度，不是转换维度**：客户端讲什么协议 → 路由到讲同样协议的原生上游。roadmap 核心新增是上游侧 provider adapter 体系，不改 codec。
4. 上游假设 = 多 provider 原生（OpenAI/Anthropic/Gemini/私有模型）。
5. 优先级框架 = 场景分层 P0/P1/P2 主线 × 每项标实现成本(S/M/L) × 对标差距。

---

## 一、你的位置：公约数对照

市场调研得出的「几乎所有主流网关都具备」的六项公约数：①OpenAI 兼容统一接口 ②多 provider + fallback/重试 ③负载均衡 ④key 鉴权+限流 ⑤请求日志+用量/成本统计 ⑥管理界面。

| 能力 | llmgw4j | 主流水平 | 判定 |
|---|---|---|---|
| OpenAI Chat 统一接口 | ✓ 字节透传 | ✓ | **持平** |
| 多 provider fallback / **重试** | failover ✓，重试 ✗ | 12/12 都有 | **落后半档** |
| 负载均衡 | ✓ 加权随机 | ✓ | 持平 |
| key 鉴权 + 限流 | ✓ 但 key 是静态配置；限流是 **token 桶** | ✓；多数只做请求级或事后记账 | 限流**领先**，key 生命周期**半缺** |
| 用量统计 | ✓ token 级精确记账（usage 帧权威覆盖 + 在途预留/退款）；✗ $ 成本 | ✓ token + $ | **落后半档**（$ 缺） |
| 请求日志 / 管理界面 | ✗（仅 5000 条审计环 + JSON API） | 标配 | **缺失** |

**llmgw4j 领先市场的项**（对标中多数竞品没有）：

| 能力 | 说明 |
|---|---|
| Responses API 原生 | Higress ✗、APISIX ✗、Kong-OSS ◐、Cloudflare ✗、Helicone ✗——只有 LiteLLM/Vercel/Portkey/NewAPI 有 |
| Anthropic Messages 原生入口 | OpenRouter ✗，多家仅 ◐ |
| 视频任务协议一等公民 | 市场罕见；含粘性任务路由 |
| **在途配额预留 + 失败退款** | Higress/APISIX 的 token 统计是完成后事后记账；llmgw4j 的原子 CAS 预留+退款是硬预算级精度 |
| AI 感知熔断器（半开单探针 CAS） | Kong-OSS/APISIX 无此内置 |
| 字节保真流式（永不重序列化） | LiteLLM 重写流，边界 case 是社区长期痛点；llmgw4j 的 SSE 终止契约/静默截断判定是工程级答案 |

---

## 二、差距清单（P0/P1/P2 × 成本 × seam 落点）

图例：成本 S=天级 / M=周级 / L=月级；P0=个人自用就痛，P1=内部平台必需，P2=对外生产必需。

### P0

| # | 缺失能力 | 对标 | 成本 | seam 落点与模块形状 |
|---|---|---|---|---|
| 1 | **同路由重试 + 退避** | 12/12 标配；LiteLLM 还有 context-window fallback | M | 新建 **ForwardingPolicy 深模块**（设计决策 C）：从两个镜像 controller 抽出尝试循环，纳入重试/退避/failover/熔断探测 |
| 2 | 三段式超时（connect/首字节/空闲读） | 现在只有 60s 整体 + 10s connect，README 自认 TODO | S | `GatewayProperties.Upstream` 加三参数；`JdkHttpUpstream` 实现 |
| 3 | **双向 header 透传** | 上游需要 `anthropic-version`/`OpenAI-Beta`/`x-request-id`；客户端需要 `x-ratelimit-*`。现在上行只有 Content-Type/Accept/Authorization | S | 新 **HeaderPolicy 小 SPI**（进出白名单，config 驱动）；`JdkHttpUpstream.build` 接入 |
| 4 | SSE 心跳 | 中间 LB/代理 50-60s 斩空闲连接 | S-M | `JdkHttpUpstream.streamRaw` 循环注入 comment 帧；注意与字节透传哲学的边界（心跳是唯一允许的注入，需写进契约） |
| 5 | Docker 打包 + snapshot 自动恢复 | 现在「跑 fat jar」+ 手动 `/admin/snapshot/load` | S | 打包产物；启动时调 `SnapshotStore.load`（SEAM 已在） |
| 6 | **API key 运行时管理**（CRUD/过期/轮换） | BricksLLM 的全部价值、LiteLLM/NewAPI 标配；现在 key 只能写死在 `gateway.api-keys` | M | `ApiKeyRegistry` SEAM 已在：补 3 个 admin 端点 + 一个持久化 adapter（文件/SQLite），不引 Postgres |
| 7 | 视频 GET 三洞（无记账/无 fallback/无熔断） | 自我一致性缺口：配额可以绕过视频轮询 | M | 治理链覆盖 GET /v1/videos/{id} 的 settle 路径；粘性路由保留 |
| 8 | 模型目录（上下文窗口/价格/能力元数据） | $ 预算与智能 fallback 的前置；LiteLLM 内置全目录 | M | 新 **ModelCatalog SPI**（设计决策 D） |

### P1

| # | 缺失能力 | 对标 | 成本 | seam 落点 |
|---|---|---|---|---|
| 9 | **$ 硬预算**（per-key 花费上限，到额即拒） | LiteLLM/Portkey/Bifrost/BricksLLM/NewAPI 标配；调研结论：「硬预算执行是网关与纯观测产品的分水岭」 | M | `QuotaMath` 保持纯函数，扩展价格维度（输入/输出/缓存 token 分价）；`UsageLedger` 双单位记账。依赖 #8 |
| 10 | 身份层级（团队/项目 → key 归属） | LiteLLM teams/orgs、NewAPI 用户分组 | M-L | `ApiKeyRegistry` 返回值深化为 Identity（带 group 路径）；配额/预算沿 group 聚合 |
| 11 | OTel 追踪 | 全部严肃自托管网关标配；现在只有 X-Request-Id echo | S-M | Micrometer Tracing 桥接（已在 Micrometer 生态内）；审计点已集中，接入面小 |
| 12 | 请求/响应日志（采样、有界） | 调试刚需；现在审计环不可配 | S-M | 新 **RequestLogSink**，与 AuditSink 平行的 SPI，同一 `@ConditionalOnMissingBean` 模式 |
| 13 | 治理参数热更新 | 路由表可热更，配额/限流/缓存/熔断构造时冻结（自我承认的限制） | M | 治理默认实现改读动态配置源，复用 RouteTable 的 AtomicReference CAS 换表模式 |
| 14 | Redis 覆盖补全 | Redis 包只覆盖 11 个 SPI 中的 3 个；多副本时 key 表/缓存/粘性路由各自为政 | M | 复用 `llmgw-governance-redis` 既有 Lua 原子模式，补 registry/cache 两个 adapter |
| 15 | 最小管理控制台 | 除 BricksLLM 全都有 UI | M-L | 只读 dashboard（`/admin/*` JSON 已够喂一个静态页），不做框架 |
| 16 | 语义缓存 | Portkey/Higress/Helicone/Bifrost 开源有；Kong/LiteLLM 在 EE。**非公约数，可选** | M-L | `SemanticCache` SPI 名字已预留但键是精确哈希——补 embedding 键生成器即成形 |

### P2

| # | 缺失能力 | 对标 | 成本 | seam 落点 |
|---|---|---|---|---|
| 17 | **Guardrails / PII / 防注入** | 调研结论：「网关侧内容治理已成 2025 企业选型硬指标」（Portkey 60+、Kong prompt-guard、Higress、LiteLLM EE Presidio） | L | 新 **Guardrail SPI**（enter/exit 双钩，validate/mutate 两模式，参考 TrueFoundry 的 4 hook 点设计），先接外部引擎，不做内置规则库 |
| 18 | TLS 直属终止 | 现在立场是前置代理——应用级代理普遍如此（LiteLLM 同款），对外生产需文档化部署拓扑 | S | 文档 + compose 示例 |
| 19 | **协议面扩张**：embeddings / audio(TTS/ASR) / rerank / files / batch | 市场标配模态；LiteLLM 全有 | M/协议 | **前置条件是设计决策 A**——否则闭式 switch 会爆炸 |
| 20 | Canary / 流量切分 | Higress 原生、Kong EE | S | 加权随机 LB 已在，`RouteTable` 配置层表达权重即可 |
| 21 | 多副本一致性完整版 | sticky 路由/缓存跨实例 | M-L | #14 完成后自然覆盖大半 |

### 作品级 / 开源线（第二条轨道）

| # | 缺失 | 为什么对作品级致命 | 成本 |
|---|---|---|---|
| 22 | **JUnit 测试** | 全库无 `src/test/`。shell 契约套件覆盖了 e2e（140+ 断言），但接口层测试为零 = 贡献者门槛无限高 | M |
| 23 | Maven Central 发布 / GitHub Release 产物 | 无发布，别人用不了 | S |
| 24 | 开源基建最小集 | docker compose 示例、快速上手 5 分钟路径 | S |

---

## 三、自研 vs 采用：诚实红线

**立即换 LiteLLM，如果**：三天内要给多团队发 key + $ 预算 + UI + 50 provider 开箱即用——这些它都有，llmgw4j 补齐 #6/#9/#15/#8 也至少是数周。

**前置 Higress/Kong 共存，如果**：需要 Envoy 级南北向治理（mTLS、WAF、灰度、全站限流）——前置代理 + llmgw4j 做模型数据面是自洽拓扑。

**留在自研，如果**（差异化位置全部成立）：

- 字节保真是需求：客户端和上游之间的每个字节可审计、可复现（LiteLLM 做不到）
- 性能与冷启动是卖点：native image + 双引擎 A/B 基准文化
- Java/JVM 团队栈
- **无 DB 单二进制**：LiteLLM 绑定 Postgres、NewAPI 绑定 SQL——「零外部依赖」是真实差异，别丢（用 SQLite/文件，不引 Postgres）

**结论**：llmgw4j 的公约数缺口（重试、key 生命周期、$ 预算、观测）全部是 SPI seam 已就位、可增量补的——**架构上没有一条非换不可的理由**。该换的场景都是「时间窗口」理由，不是「能力不可达」理由。

---

## 四、补齐方案：四个 seam 决策

### A. Protocol SPI——最关键的结构性前置

**现状**：协议是封闭 switch，散在 `llmgw-core/.../protocol/ProtocolCodec.java`、`ChunkParser.parse`、`completionTokensFromResponse` 和两个 controller 的注解里。加第 6 个协议要动 ~4 处核心文件 + 双 controller。

**判断**：「两个 adapter 才是真 seam」——已有 **5 个协议实现**，seam 是真实的，只是没被提出来。

**模块形状**：

```
Protocol（core 接口，5 个现有实现收编为 adapter）:
  matches(path)          // 路由识别
  probe(body)            // 最小解析：model/stream/计费单元数
  streamContract()       // SSE 终止帧 + 错误帧 + [DONE] 约定
  usageFrom(frame)       // usage 帧提取
```

Controller 变成协议无关（按注册表分发）。

### B. Provider adapter 体系——「多 provider 原生」的落点

**现状**：`UpstreamProvider` seam 存在，但转发路径上 **adapter 数量为零**——`JdkHttpUpstream` 对所有上游隐式假设 OpenAI 兼容。

**关键洞察**：**协议是路由维度而非转换维度**。字节透传意味着 provider adapter 需要变化的只有四样：**URL 构造、认证头（Anthropic 用 `x-api-key`+`anthropic-version`，不是 Bearer）、错误分类（可重试性判定）、必需 header**——小接口，变化真实、不碰 codec、不碰 SSE 契约。Gemini 原生协议是唯一例外（流式格式不同），应作为「协议=路由维度」立场的第一个压力测试：要么 Gemini 原生入口作为第 6 协议加入（走 A 的 seam），要么先只做它的 OpenAI 兼容端。

**模块形状**：`llmgw-provider-anthropic` 按 `llmgw-provider-openai` 模板落位，route 配置加 `provider` 字段。第一个真 adapter 让 UpstreamProvider 从假想 seam 变成真 seam。

### C. ForwardingPolicy——一石三鸟的深模块

**现状**：fallback 循环、首块前后规则、熔断探测散在两个镜像 controller 里各写一遍。

**检验**：删除测试——把循环删掉，尝试规则会在两个 controller 各重现一遍，复杂度×2；它是挣饭吃的模块。它也解释了为什么重试一直没加：**没有归口的尝试循环，重试规则无处安放**。

**接口形状**：`forward(ctx, candidates) → UpstreamResult`，内部拥有：同路由重试+退避（新增）→ failover（现有）→ 熔断探测（现有）→ 首块前后规则（现有契约原样保留）。

### D. ModelCatalog + Pricing——$ 预算的地基

`QuotaMath` 已是纯函数模块（好 seam），扩展价格维度即可；`ModelCatalog` 作为新 SPI 同时喂三处：$ 换算（#9）、context-window 感知 fallback、模型白名单的人类可读名。避免单独做一个「计费模块」——预算就是配额换个单位，不要新造治理层。

### 不做清单（scope 纪律）

- **协议转换**——已拍板透传为主
- **MCP/A2A**——没有 Agent 流量之前是负担，保持观察
- **Postgres 依赖**——「无 DB 单二进制」是差异化
- **插件市场/框架化**——那是 Kong/Higress 的战场

---

## 五、演进波次（详见 [tickets.md](tickets-v13-execution.md)）

| 波次 | 内容 | 逻辑 |
|---|---|---|
| **1**（P0 快赢） | #1 ForwardingPolicy + 重试、#2 三段超时、#3 header 透传、#4 心跳、#5 Docker、#7 视频三洞、#22 接口层测试（同步写） | 全是现有 seam 内的小改 |
| **2**（结构性） | A 决策 Protocol SPI 重构 → #8 ModelCatalog → #6 key 管理 API+持久化 → B 决策 Anthropic adapter | 先立 seam 再长能力，顺序不能反 |
| **3**（平台化） | #9 $ 硬预算（D 决策）→ #10 身份层级 → #11 OTel → #12 请求日志 → #14 Redis 补全 → #20 canary | 「个人够用→内部平台」的分界 |
| **4**（生产化/差异化） | #17 Guardrail hook、#16 语义缓存、#19 新协议（embeddings 优先）、#18/#21 部署硬化 | 每步独立可发布 |

---

## 六、来源（载荷结论的出处）

- LiteLLM passthrough/enterprise/PII: [native_passthrough](https://docs.litellm.ai/docs/anthropic_unified/native_passthrough)、[enterprise](https://docs.litellm.ai/docs/enterprise)、[load_balancing](https://docs.litellm.ai/docs/proxy/load_balancing)
- Higress: [AI 网关](https://higress.ai/ai-gateway/)、[插件市场](https://higress.ai/plugins/)；APISIX: [ai-proxy](https://apisix.apache.org/docs/apisix/plugins/ai-proxy/)、[ai-rate-limiting](https://apisix.apache.org/docs/apisix/plugins/ai-rate-limiting/)；Kong: [AI Gateway](https://developer.konghq.com/ai-gateway/)
- Portkey: [gateway repo](https://github.com/Portkey-AI/gateway)、[缓存](https://docs.portkey.ai/docs/product/ai-gateway/caching-simple-and-semantic)；Cloudflare: [AI Gateway](https://www.cloudflare.com/products/ai-gateway/)；Vercel: [docs](https://vercel.com/docs/ai-gateway)
- NewAPI: [repo](https://github.com/QuantumNous/new-api)；BricksLLM: [repo](https://github.com/bricks-cloud/bricksllm)；Bifrost: [repo](https://github.com/maximhq/bifrost)；agentgateway: [官网](https://agentgateway.dev)
- 火山引擎: [AI 网关](https://www.volcengine.com/docs/apig/what-is-the-ai-gateway)；阿里云: [AI 网关](https://www.aliyun.com/product/api-gateway/ai-gateway)

> 调研注：个别条目（TrueFoundry 缓存、Helicone 许可细节）存在版本差异，正式引用前以官方文档为准。
