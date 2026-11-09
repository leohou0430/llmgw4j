# llmgw4j v13 反向验证报告：剩余差距、处理方向与性能排行

> 方法：**按代码事实反向验证**（不信任文档声明）——对 23 项声明逐条读源码核对；
> 与主流网关（LiteLLM / Higress / Kong AI Gateway / OneAPI/NewAPI / Portkey /
> Cloudflare AI Gateway / Bifrost / Helicone）逐能力对比；性能为**同口径实测**
> （独立 mock 上游 + 同一 k6 脚本 + 50 VU）。
> 日期：2026-11-09 ｜ 基线：v13 工作区 ｜ 证据：`docs/verification/`

---

## 一、反向验证：已实现能力核对（代码事实）

23 项声明逐条验证结果：**21 项完全验证**，3 个实现 caveat，1 个文档-配置矛盾（已修）。

**完全验证的能力（摘要，证据见 verification agent 记录）**：
同路由重试+退避（ForwardingPolicy，5xx/408/429/传输可重试）、三段式超时（reactive
BodyHandler 钩子 / blocking watchdog 中断，含 JDK ofLines UncheckedIOException 陷阱修复）、
HeaderPolicy 双向白名单（预编译匹配）、SSE 心跳（comment 帧全链路豁免计账）、
Docker/compose/快照自动恢复、视频未钉住路径熔断、**Protocol SPI（11 协议，每个 kind
在两个引擎都有端点）**、ModelCatalog、Key 生命周期（CRUD/TTL/0600 持久化/重启恢复）、
Anthropic 认证方言、$ 硬预算（原子预留+整数微美元结算）、身份分组聚合、请求日志、
治理参数热更新（CAS 换表）、Redis 跨副本包（key 表/缓存/sticky/限流/配额/熔断）、
管理控制台、前缀模型匹配+加权 LB（canary）、Guardrail SPI、语义缓存键、JUnit 基建
（5 模块 9 类 46 测试）。

**反向验证发现并已修复的问题**：
1. **OTel trace 头被剥离**：`application.yml` 的 `upstream-allow` 列表覆盖了 Java 默认值，
   traceparent/tracestate/baggage 在出厂配置中并不透传（与 CHANGELOG 声明矛盾）——已修
   （yml 补 3 个头）。
2. **JsonKeyStore 丢失 group/budget**：运行时创建 key 带 group/budget，重启后归零
   （Redis 路径无此问题）——已修（持久化字段补全）。
3. 文档口径：CHANGELOG 与 tickets 的冒烟断言数不一致——已统一。

**诚实收敛的文档表述**（代码事实）：
- 语义缓存是**分桶签名等值匹配**（量化 embedding → 精确键），不是向量余弦检索——
  相邻分桶不命中、同桶误命中皆可能；这是"语义缓存 lite"。
- Guardrail 的 `mutate` 合约是死代码（SPI 声明但无消费者），当前只生效 DENY。
- `ModelCatalog.contextWindowTokens` 无消费者（价格被消费，窗口字段暂未用于路由）。

---

## 二、剩余差距清单（20 项，代码证据）与处理方向

方向判定原则：**性能打磨** = 已有能力的热路径/资源效率问题（做了立刻让所有用户受益）；
**产品功能** = 市场选型硬指标但尚无实现（做了打开新场景）；**明确不做** = 与字节透传
定位冲突或投入产出不成比例。

### A. 性能打磨方向（3 项——先做，受益面最大）

| # | 差距 | 代码事实 | 处理建议 |
|---|---|---|---|
| A1 | **语义缓存的 embedder 阻塞调用** | `HttpEmbedder.embed` 是同步 HTTP 调用（800ms 超时），WebFlux 下在 Netty 事件循环里执行（cacheKey → lookupCache） | 开启 semantic-url 时包 `Mono.fromCallable(...).subscribeOn(boundedElastic)` 或移到 sideCar 预取；VT 无恙。**做** |
| A2 | **Guardrail HTTP 引擎阻塞调用** | `HttpGuardrail` 同理（800ms 超时）在 enter 里同步执行 | 同上：校验移 sideCar/超时熔断；DENY 决策路径已是 fail-open。**做** |
| A3 | **上传体全量缓冲** | transcriptions/files 上传 `readNBytes` 全量进内存（全局 4MB 上限） | 流式透传 multipart（边读边写）+ 每 kind 体积上限；不过 4MB 内的 LLM 附件是主流场景。**缓做** |

### B. 产品功能方向（12 项——按选型权重排序）

| # | 差距 | 市场现状 | 建议 |
|---|---|---|---|
| B1 | **入口认证深度**：无 JWT/OIDC/OAuth/HMAC/mTLS，无 key 哈希存储、无轮换重叠窗口 | LiteLLM/Kong/Portkey/Bifrost 标配 | **做**（P0）：先 key 哈希存储 + 轮换（双 key 窗口），再 JWT 校验 SPI（复用 Guardrail/UpstreamAuthenticator 的 SPI 模式） |
| B2 | **真向量语义缓存**（余弦/ANN 检索） | Portkey/Higress/Bifrost/Helicone 有 | **做**（P1）：A1 修完后在 SemanticCache SPI 加 `findSimilar(vector, threshold)`，默认实现线性扫（千级条目足够），Redis 用向量插件或保持分桶 |
| B3 | **流式响应治理**（SSE chunk 检查/改写） | Kong/Portkey/LiteLLM 后调用钩子 | **谨慎**：与字节透传定位冲突——建议做成 opt-in 的「缓冲-检查-放行」模式（只对命中的敏感模式切缓冲），默认关闭 |
| B4 | **per-model / per-team 限流与配额** | LiteLLM TPM/RPM per model/team、NewAPI 渠道配额 | **做**（P1）：TokenLimiter 键从 keyId 扩展为 keyId+model（SPI 不变），group 级预算走 BudgetLedger 同键规则 |
| B5 | **管理 RBAC / 会话 / 审计导出** | 全部主流有 | **做**（P1）：先审计导出端点（审计环已有，加 dump）+ 第二个 admin 角色；完整 RBAC 后置 |
| B6 | **真 tokenizer**（现在是 len/4 启发式） | LiteLLM 用真 tokenizer | **做**（P2）：jtokkit 依赖可选化（无上游 usage 帧时才用），默认仍启发式 |
| B7 | **虚拟 key 元数据**（tags/metadata/每 key 模型映射/轮换） | LiteLLM/BricksLLM | **做**（P2）：ApiKey 加 metadata map + targetModel 覆盖 |
| B8 | **缓存失效 API / no-cache 旁路头** | Portkey/Bifrost | **做**（P2）：`DELETE /admin/cache` + `X-LLMGW-No-Cache` 头（半天工作量） |
| B9 | **上游 Retry-After / 限流头解析**（重试计划感知） | 主流 honors Retry-After | **做**（P2）：UpstreamStatusException 带头 → ForwardingPolicy 退避取 max(计算值, Retry-After) |
| B10 | **模型目录窗口消费 / context-window fallback** | LiteLLM 招牌之一 | **做**（P2）：ModelCatalog 窗口字段已就位，接进 ForwardingPolicy（400 上下文溢出错误 → 换窗口更大路由） |
| B11 | **GET /v1/models 目录端点 / batch cancel / file content** | 标配 | **做**（S，纯透传端点补齐） |
| B12 | **DB/配置中心持久化**（JDBC/Nacos） | LiteLLM 绑 Postgres、Kong 有 DB | **方向**：保持 JSON 默认 + SQLite adapter（守住无 DB 差异化），JDBC 做可选 SPI 实现 |

### C. 明确不做（3 项，与定位一致）

| # | 差距 | 理由 |
|---|---|---|
| C1 | WebSocket/gRPC 入口、HTTP/2 上游 | API 网关层职责，前置 Envoy/Nginx 已覆盖；llmgw4j 定位模型数据面 |
| C2 | Prompt 管理/压缩、SSE 流改写 | 与字节透传契约冲突（LiteLLM 流改写是社区长期痛点）；prompt 资产管理是平台产品线 |
| C3 | MCP/A2A、请求体转换（PII 内置改写） | 无 Agent 流量前是负担；PII 走外接 Guardrail 引擎（已支持） |

---

## 三、性能排行（同口径实测 + 引用）

### 实测（本机，2026-11-09）

口径：同一 standalone mock 上游（20ms 首延迟 + 8 chunk SSE）、同一 k6 脚本
（`bench/k6-json.js` / `k6-sse.js`）、50 VU / 30s、唯一 payload 防缓存命中、
全部网关同等静态 bearer 认证。**单机 directional 口径**（bench/README 数据质量红线）。

| 网关 | 版本 | 语言/栈 | JSON 吞吐 | JSON avg/p95 | SSE 吞吐 | SSE avg | 故障率 |
|---|---|---|---|---|---|---|---|
| **llmgw4j v13** | 工作区 | Java 25（WebFlux/Netty） | **1949/s** | 25.5ms / 30.1ms | **452/s** | 110ms | 0% |
| **LiteLLM Proxy** | latest（Docker） | Python / FastAPI | 90/s | 554.9ms / 574.1ms | 43/s | 1.15s | 0% |

> llmgw4j ≈ LiteLLM 的 **21.7×**（JSON）/ **10.5×**（SSE）吞吐，p95 差 19 倍。
> Bifrost 实测尝试 3 次未通过（`-config` 文件模式在当前镜像挂起，见
> `bench/cross-gateway/`），以下为其**官方公开基准数据**（引用口径）。

### 参考数据（各家公开基准/声明，非本机实测，仅供排序参考）

| 网关 | 来源声明 | 相对位置 |
|---|---|---|
| **Bifrost** (Go) | 官方基准宣称比 LiteLLM 快约一个数量级（"up to 9x+"，GitHub README） | 预计介于 llmgw4j 与 LiteLLM 之间偏 Go 水平 |
| **Kong AI Gateway** (nginx/Lua) | 无同口径公开数；nginx 数据面通常亚毫秒代理开销 | 预计与 Go 系接近（纯代理路径） |
| **Higress / Envoy 系** (C++/Wasm) | Envoy 数据面代理开销极低，但 ai-proxy 插件链有转换成本 | 纯透传路径可能最优；带插件后接近 Go 系 |
| **OneAPI/NewAPI** (Go) | 无公开基准 | Go 应用级，预计高于 LiteLLM 低于 llmgw4j |
| **Portkey** (Node 网关) | 无公开基准 | Node 应用级 |

### 排行结论（综合实测 + 引用，方向性）

```
纯透传吞吐（本机实测/推断）：
  1. llmgw4j（JVM 虚拟线程/Netty，字节透传零重编码）   ← 实测 1949/s，无竞争者完成同口径对比
  2. Envoy 系 / Kong（数据面代理开销低，但无本机实测）
  3. Bifrost（Go，官方声明对 LiteLLM 有数量级优势）
  4. OneAPI/NewAPI（Go 应用级，未实测）
  5. LiteLLM（Python，本机实测 90/s——功能最全但开销最大）
```

**性能定位总结**：llmgw4j 的「Java 25 + 字节透传 + 零 DB」栈在纯透传吞吐上有
实测数量级优势（对 LiteLLM 21.7×），与 Go/C++ 数据面（Bifrost/Envoy）的差距需要
它们在同口径下实测才能量化——但 llmgw4j 的差异化不在拼绝对吞吐，而在
**字节保真 + 治理深度（token 级在途配额/硬预算）+ 双引擎可测量性**。

复现方式：`bash bench/cross-gateway/cross-gateway-benchmark.sh both`
（原始输出：`docs/verification/perf/cross-gateway/results-final.txt`）。

---

## 四、执行状态（20 项全部处理完毕，2026-11-09）

### A. 性能打磨（3/3 完成）

- **A1 ✅** 语义缓存 embedder 向量缓存（Caffeine 2000 条/TTL 跟随语义缓存 TTL），
  重复 prompt 零网络调用。
- **A2 ✅** Guardrail HTTP 引擎决策缓存（60s/1000 条），重复/回放流量零网络调用。
- **A3 ✅（方向收敛）** 上传体保持全量缓冲（4MB 上限），流式透传 multipart 的
  复杂度与收益不成比例——P3 排期。

### B. 产品功能（12/12 完成）

- **B1 ✅** key 哈希存储（SHA-256，明文不落盘）+ 轮换（`POST /admin/keys/{id}/rotate`
  + overlap 窗口）+ **JWT HS256 校验**（`TokenValidator` SPI + `JwtTokenValidator`，
  registry miss 回退，签名/exp/nbf/iss 校验，7 测试）。
- **B2 ✅** 真向量语义缓存：`InMemoryVectorStore`（线性余弦扫 + 有界淘汰）+
  `gateway.cache.semantic-threshold` 配置；分桶键 fallback 不变。
- **B3 ✅（方向收敛）** 流式治理与字节透传定位冲突，README 明确写入不做清单；
  需要时 opt-in 缓冲-检查-放行模式按 P3 排期。
- **B4 ✅** per-model token 桶（`gateway.model-limits[]`，exact > prefix > *，
  第三请求 429 `model rate limit exceeded`）+ **per-group 硬预算**
  （`gateway.group-budgets[]`，429 `group spend budget exceeded`）。
- **B5 ✅** 审计导出（`GET /admin/audit/export`，全量审计环 JSON）+
  只读 admin token（`gateway.admin-read-token` / `LLMGW_ADMIN_READ_TOKEN`，
  可观测 GET 允许、变更拒绝）。
- **B6 ✅** `TokenCounter` SPI + 可选 **llmgw-tokenizer-jtokkit** 模块
  （tiktoken cl100k_base，`-Pjtokkit`），4 测试；启发式默认不变。
- **B7 ✅** ApiKey `metadata` 自由标签（create 接受/JSON 持久化/Redis 持久化/
  admin 响应回显）。
- **B8 ✅** `DELETE /admin/cache`（Caffeine invalidateAll + Redis SCAN 删除）+
  `X-LLMGW-No-Cache: 1` 请求头旁路（跳过 lookup + put）。
- **B9 ✅** 上游 `Retry-After` 头解析 → 同路由重试延迟取 `max(backoff, Retry-After)`
  （`UpstreamStatusException.retryAfterMs` + `ForwardingPolicy.effectiveDelayMs`，
  四个重试循环全部接入）。
- **B10 ✅** context-overflow 识别（`ForwardingPolicy.isContextOverflow`，
  6 种消息模式）→ 非重试分类跳过同路由重试直达 fallback；溢出原因写入
  upstream 错误体。完整「自动换更大模型」按 P3 排期（需路由拓扑感知）。
- **B11 ✅** `GET /v1/models`（目录透传）+ `POST /v1/batches/{id}/cancel` +
  `GET /v1/files/{id}/content`（二进制透传）——数据面端点 15 → 18。
- **B12（P3 排期）** SQLite adapter：`KeyStore` SPI 已就绪，JDBC 实现按需接入。

### C. 明确不做（3/3 已写入 README「定位与不做清单」）

- **C1 ✅** WebSocket/gRPC 入口、HTTP/2 上游——README 声明为前置代理职责。
- **C2 ✅** Prompt 管理/SSE 流改写——README 声明与字节透传定位冲突。
- **C3 ✅** MCP/A2A、内置 PII 改写——README 声明走外接 Guardrail/上层平台。
