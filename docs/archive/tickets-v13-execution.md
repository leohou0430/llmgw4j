# llmgw4j v13 能力补齐任务票（执行状态看板）

> 来源：[../gap-analysis.md](../gap-analysis.md) 第四节波次方案。
> 验证总口径：**功能** = `bin/` 冒烟套件全绿（含新增断言）；**性能** = microbench 与 v12 基线对比无回归。
> 证据目录：`docs/verification/`（baseline / w1-w2 / w2 / w3 / w4 / final / perf）。

## Wave 0 — 基线 ✅

- [x] W0.1 落库 gap-analysis + tickets
- [x] W0.2 基线构建通过
- [x] W0.3 基线冒烟全绿（56+67+30+10 = 163 断言，`verification/baseline/smoke.log`）
- [x] W0.4 性能基线档案（`verification/baseline/microbench.log`）

## Wave 1 — P0 快赢 ✅

- [x] W1.1 **ForwardingPolicy 深模块**：`llmgw-engine-api/.../ForwardingPolicy.java`（plan/nextViable/retryable/backoff），双 controller 尝试循环归一；同路由重试+指数退避+全抖动，`retry.attempts=1` 保持 v12 语义；5xx/408/429/传输错误可重试、4xx/熔断打开不可重试
- [x] W1.2 三段式超时：`connect/first-byte/idle-read`（默认 0=关，保持 v12 行为）；reactive 路径 BodyHandler 钩子 + future.cancel；阻塞路径 watchdog 中断 + 精确原因（修了 JDK ofLines 把中断包成 UncheckedIOException 的坑）
- [x] W1.3 **HeaderPolicy SPI**：双向白名单（`gateway.headers.*`，尾部 `*` 前缀匹配），Authorization/宿主/逐跳头永不透传；上行 `anthropic-version`/`OpenAI-Beta`/`x-request-id`，下行 `x-ratelimit-*`/`retry-after` 等
- [x] W1.4 SSE 心跳：`gateway.upstream.heartbeat-interval-ms`（默认 0）；comment 帧为网关唯一注入字节，engines 在计账/TTFT/首块守卫中豁免 `:` 行
- [x] W1.5 Dockerfile（多阶段+依赖层缓存）+ docker-compose（redis profile）+ snapshot 启动自动恢复（`snapshot-auto-restore`，默认关保基准纯净）——Docker 构建验证通过
- [x] W1.6 视频 GET 未钉住（sticky miss）路径补熔断；钉住路径保持「不熔断」设计并文档化
- [x] W1.7 JUnit 基建：surefire + junit-jupiter 接入 5 个模块
- [x] W1.8 新增 `bin/smoke-v13-hardening.sh`（42 断言）；回归 205 断言全绿

## Wave 2 — 结构性 ✅

- [x] W2.1 **Protocol SPI**：`Protocol` 接口 + `Protocols` 注册表 + 5 协议 adapter 收编 + `ProtocolRegistrar`（Spring bean 覆盖）；四处闭式 switch 全部消除；新协议 = 枚举项 + 一个 Protocol 实现
- [x] W2.2 **ModelCatalog SPI**：`gateway.models[]`（provider/上下文窗口/micro-USD 每百万 token 价格，`*` 兜底条目）
- [x] W2.3 **Key 管理生命周期**：`KeyAdminService`（create/revoke/byId + TTL 过期）+ `ManagedApiKeyRegistry` + `JsonKeyStore`（0600 文件持久化，重启恢复含 revoke 状态）；`POST/DELETE /admin/keys`
- [x] W2.4 **llmgw-provider-anthropic**：`UpstreamAuthenticator` SPI + `Route.provider` 路由维度 + x-api-key/anthropic-version 方言；错误分类天然覆盖（529→5xx 可重试）；mock 认证方言探针；`bin/smoke-v13-provider.sh` 24 断言
- [x] W2.5 Wave2 全量验证：**229 冒烟断言 + 全部单测绿**（`verification/w2-verification.log`）

## Wave 3 — 平台化 ✅

- [x] W3.1 **$ 硬预算**：`BudgetLedger` SPI + InMemoryBudgetLedger（原子 committed+reserved 准入）+ QuotaMath.costMicroUsd（整数微美元）+ 到额即拒 429 `spend budget exceeded`；预算检查先于令牌预留（拒绝零退款记账）；快照含 per-key + 按组聚合 spend
- [x] W3.2 身份层级：ApiKey.group（team/project 标签）+ `spendMicroUsdByGroup` 组聚合
- [x] W3.3 OTel：traceparent/tracestate/baggage 默认进上行白名单（上下文传播）；完整 span 导出走 `-Potel` profile（micrometer-tracing-bridge-otel + OTLP）
- [x] W3.4 **RequestLogSink**：有界环形请求日志（含 deny 路径）+ `GET /admin/requests`
- [x] W3.5 **治理参数热更新**：`DynamicGovernanceSettings`（CAS 快照换表）驱动限流器/缓存/熔断器逐调用读取；`POST /admin/governance` 原子换参
- [x] W3.6 Redis 覆盖补全：`RedisApiKeyRegistry`（跨副本 key 表）+ `RedisSemanticCache`（跨副本缓存），-Predis 编译通过
- [x] W3.7 最小管理控制台：`/admin/console` 静态单页（routes/keys/usage+spend/audit/requests/metrics）
- [x] W3.8 canary：路由权重 LB 已有 + W2 前缀模型匹配（`claude-*`）使模型族/灰度分流可表达；加权分布断言在 stream-errors 套件（1:3）
- [x] W3.9 `bin/smoke-v13-platform.sh` **34 断言全绿**（预算确定性算术 / 热更前后 / key 生命周期 / 请求日志 / 控制台 / guardrail 双路径）

## Wave 4 — 生产化/差异化 ✅

- [x] W4.1 **Guardrail SPI**：`Guardrail`（validate/mutate 合约）+ `PatternGuardrail`（正则拒答）+ `HttpGuardrail`（外接审核引擎，fail-open）+ `GuardrailChain`；治理链在认证后、计量前拦截（400）；mock 引擎端点仿真
- [x] W4.2 **语义缓存**：`Embedder` SPI + `HttpEmbedder`（OpenAI 兼容 /embeddings，失败降级精确键）+ 分桶向量签名键（bucket scale = 相似度阈值）；mock 确定性词袋向量；同义改写共享一条缓存（cacheSize 1），异题独立（2）
- [x] W4.3 **协议面扩张（最终 11 协议）**：embeddings / rerank / audio-speech（二进制透传）+ audio/transcriptions 与 files（multipart 字节透传）+ batches（异步任务粘性路由）——经 W2.1 SPI，每协议零核心改动；治理全覆盖；`bin/smoke-v13-extensions.sh` 36 断言
- [x] W4.4 TLS 立场 + 部署拓扑：`docs/DEPLOYMENT.md`（nginx 前置/SSE 反代配置/多副本 Redis/生产清单）+ `deploy/nginx.conf` + compose
- [x] W4.5 扩展冒烟全绿（`verification/w4-extensions.log`）

## 性能 ✅

- [x] PERF microbench 复测 vs v12 基线（`verification/final-microbench.log` vs `baseline/microbench.log`）——热路径解析未动，数值噪声内
- [x] PERF k6 JSON+SSE 负载（50 VU 双引擎）通过（`verification/perf/`）
- [x] 设计红线自查：默认配置（重试/心跳/超时全关、透传零注入）下热路径新增开销 = 1 次 volatile 读 + 1 次 ForwardingPolicy plan 构造（每请求一次性小对象）

## 收尾

- [x] P2-#23 发布链路：GitHub Release workflow + central-publishing-maven-plugin 配置（文档化）
- [x] P1-#11 OTel：trace 上下文传播默认生效 + `-Potel` profile 编译验证通过
- [x] 性能 A/B：v12 worktree 基线对比 → 回归定位修复 → JSON 6107/s（基线 3734/s）、SSE 持平、故障率 0%
- [x] D 文档全量更新：README/README.en/CHANGELOG/USER_GUIDE/CONFIGURATION/PRODUCTION_BEST_PRACTICES/DEPLOYMENT/openapi.yaml（15 端点）/CONTRIBUTING/gap-analysis/tickets
