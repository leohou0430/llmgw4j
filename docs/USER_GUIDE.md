# llmgw4j 使用手册

> **适用版本**：`0.1.0-SNAPSHOT`（Unreleased，包名 `io.github.leohou0430.llmgw`）
> **文档日期**：2026-11-08，依据本仓库源码与配置文件实读整理
> **配套**：[配置说明](CONFIGURATION.md) · [项目 README](../README.md) · [压测口径](../bench/README.md) · [贡献指南](../CONTRIBUTING.md) · [安全策略](../SECURITY.md)

---

## 0. 这份文档解决什么

根目录 `README.md` 是 78KB 的设计文档 + 压测报告 + 风险清单，讲「为什么这么设计」和「跑分多少」。本手册只回答四件事：**怎么装、怎么跑、怎么用、坏了怎么修**。每个配置项的逐条含义放在 [配置说明](CONFIGURATION.md)，两边不重复。

**先把边界说清楚**（免得有人拿默认配置上线然后来问为什么被打穿）：

| 它是什么 | 它不是什么 |
| --- | --- |
| Java 25 + Spring Boot 4.1.2 写的 LLM API 网关参考实现 | 不是 Kong / Higress / Envoy 的即插即用生产替代品 |
| WebFlux/Netty 与 Servlet+虚拟线程/Tomcat 11 两套数据面的单变量 A/B 测量台 | 不是「两个引擎叠加」的高可用方案，同一时刻只有一个生效 |
| 共享一套全 SPI 化治理层（鉴权 / 配额 / 限流 / 缓存 / 熔断 / 计量 / 审计） | 治理参数除路由表外不支持热更新，重启才生效 |
| 默认全内存、零外部依赖、自带 mock 上游（9090），无需任何真实 API Key | 重启状态归零、多实例不共享、无内建 TLS/mTLS/WAF |
| Apache-2.0 开源 | 没有自动化测试套件（全库无 `src/test/`），验证靠 smoke 脚本 |

一句话：**本地能跑通全部功能，生产必须自己补安全和持久化**。

---

## 1. 环境要求

| 组件 | 版本 | 是否必需 | 用途 |
| --- | --- | --- | --- |
| JDK | **25**（推荐 Oracle GraalVM 25） | 必需 | 编译与运行，`maven.compiler.release=25` |
| Maven | 3.9+ | 必需 | 多模块 reactor 构建 |
| GraalVM | 25+，含 `native-image` | 可选 | 仅 Native Image 编译 |
| k6 | 近期版本 | 可选 | 压测 |
| Python 3 | — | 可选 | `bench/analyze-matrix.py` 结果分析 |
| Redis | 6+ | 可选 | 仅 `-Predis` 分布式状态实验 |

版本事实（`pom.xml` / `README.md` 第七节）：Spring Boot 4.1.2 GA、Caffeine 3.3.1、`com.openai:openai-java` 4.100.0、native-maven-plugin 1.1.18、Jackson **3**（`tools.jackson.*`）、Tomcat 11、Reactor Netty。

> 明确不用的东西：Lombok、Spring AI、LangChain4j。上游转发走 JDK 原生 `java.net.http.HttpClient`，官方 SDK 只用于连通性校验，不进热路径。

---

## 2. 五分钟跑起来

```bash
cd /path/to/llm-gateway
chmod +x bin/*.sh bench/*.sh        # 首次使用必做
./bin/build.sh                      # 产出 llmgw-app/target/llmgw-app-0.1.0-SNAPSHOT.jar
./bin/run-webflux.sh                # 或 ./bin/run-vt.sh
```

`bin/build.sh` 实际执行的是 `mvn -B -f <ROOT>/pom.xml -DskipTests clean package`。两个 `run-*.sh` 会先检查 jar 是否存在，并显式传 `--server.port=8080`（原因见 [12.1 端口被抢](#121-端口被环境变量抢走)）。

另开一个终端验证：

```bash
# 1) 健康检查
curl -sS http://127.0.0.1:8080/actuator/health

# 2) 非流式推理
curl -sS -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer sk-demo' \
  -d '{"model":"qwen-plus","messages":[{"role":"user","content":"hi"}],"stream":false}'

# 3) 流式推理（SSE）
curl -N -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer sk-demo' \
  -d '{"model":"qwen-plus","messages":[{"role":"user","content":"hi"}],"stream":true}'

# 4) 管理面
curl -sS http://127.0.0.1:8080/admin/routes
```

能看到的正常结果：非流式返回 mock 的 `{"choices":[{"message":{"content":"ok"}}],...}`；流式返回 8 个 `data: {"...delta...":"tokN"}` 帧、一个 `data: {"usage":...}` 帧，最后以 `data: [DONE]` 收尾。

**直接等价形式**（不用脚本）：

```bash
java -jar llmgw-app/target/llmgw-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=webflux
java -jar llmgw-app/target/llmgw-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=vt
```

### 2.1 端口一览

| 端口 | 服务 | 备注 |
| --- | --- | --- |
| 8080 | 网关主服务 | 默认，可被 `--server.port` 或脚本 `PORT` 覆盖 |
| 9090 | 内置 `MockUpstreamServer` | `gateway.mock.enabled=false` 时不启动 |
| 6399 / 8081 | Redis 实验实例 / 第二网关实例 | 仅 `smoke-redis-cluster.sh` 使用 |
| 19999 | 故意不可达的上游 | 故障注入对照 |

未配置 `server.address`，按 Spring Boot 默认绑定 `0.0.0.0`；文档示例统一用 `127.0.0.1`。对外暴露前请自行加反向代理。

---

## 3. 两套数据面：怎么切、为什么切

切换只要一个参数：

| Profile | 数据面 | `spring.main.web-application-type` | `llmgw.engine` 指标标签 |
| --- | --- | --- | --- |
| `webflux`（默认） | WebFlux / Reactor Netty | `reactive` | `webflux` |
| `vt` | Servlet + 虚拟线程 / Tomcat 11 | `servlet` | `vt` |

机制链路：`--spring.profiles.active=webflux|vt` → `application-{profile}.yml` 设置 `web-application-type` 与 `llmgw.engine` → `EngineKindEnvironmentPostProcessor`（注册在 `META-INF/spring.factories`）在环境准备阶段把它写进 `System.getProperty("llmgw.engine")` → 作为 Micrometer 的 `engine` 标签。

设计意图是**单变量对照**：同一个 fat jar、同一套治理层、同一个 `JdkHttpUpstream`，只换数据面，用来回答「流式 LLM 代理到底该用反应式还是虚拟线程」。项目的实测结论是：**当上游是秒级推理时，引擎选择对端到端延迟几乎无影响**，瓶颈不在网关。

注意：
- 两个 Controller 用 `@Profile` 互斥，同一进程只注册一个。
- Native Image 在**构建期**求值 `@Profile`，所以一个 native 二进制只能装一个引擎，两个引擎必须各编一个（见 [8.6](#86-native-image)）。
- 业务接口行为在两个引擎下逐字节一致，包括响应头、错误码和 SSE 完成判定。

---

## 4. 业务接口：`POST /v1/chat/completions`

这是唯一对外推理接口，OpenAI 兼容。

### 4.1 请求

| 项 | 说明 |
| --- | --- |
| 路径 | `/v1/chat/completions` |
| 方法 | POST |
| 请求头 | `Content-Type: application/json`、`Authorization: Bearer <token>`；可选 `X-Request-Id` |
| 请求体 | OpenAI 兼容 JSON。**只解析 `model` / `messages` / `stream` 三个字段并只解析一次**，其余字段（temperature、厂商扩展字段等）原样透传 |
| 体大小 | 默认上限 4MB（`gateway.max-request-bytes`），超限返回 413 |

```json
{"model":"qwen-plus","messages":[{"role":"user","content":"hi"}],"stream":false}
```

默认唯一可用 key 是字面量 `sk-demo`（`application.yml:48-53`）。生产必须替换。

### 4.2 响应

| 模式 | 状态 | 内容 |
| --- | --- | --- |
| `stream:false` | `200 application/json` | 上游响应体原样返回 |
| `stream:true` | `200 text/event-stream` + `Cache-Control: no-cache` | 上游 SSE 行原样透传，**`data: [DONE]` 是唯一成功判据**，网关绝不伪造完成帧 |

响应头（两个引擎一致，`EngineHeaders.java`）：

| 头 | 说明 |
| --- | --- |
| `X-Request-Id` | 客户端传入则原样回显，否则生成 `gw-<16hex>`；同时进入审计事件 |
| `X-LLMGW-Overhead-Ms` | 网关开销毫秒，两种模式都有（只统计提交前可算的量） |
| `X-LLMGW-Upstream-Ms` | **仅非流式**；SSE 的响应头在首 chunk 前已提交，没法回填 |

### 4.3 错误码

| 状态 | reason | 触发条件 |
| --- | --- | --- |
| 400 | `invalid json: ...` | 请求体不是合法 JSON |
| 401 | `invalid api key` | Bearer token 不存在 |
| 403 | `model not allowed for this key` | key 的 `allowed-models` 非空且不含该 model |
| 413 | `request body too large` | 超过 4MB |
| 429 | `token rate limit exceeded` / `token quota exceeded` | 令牌桶拒绝 / 配额耗尽 |
| 502 | `upstream: ...` | 首 chunk 前上游失败，含 `upstream closed SSE stream without [DONE]` |

网关错误统一格式：`{"error":{"message":"...","type":"gateway_error"}}`。

### 4.4 SSE 错误契约（重点，很多网关在这里说谎）

| 失败时点 | 网关行为 |
| --- | --- |
| 首 chunk **之前** | 返回真 `502` + JSON 错误体，允许切换 fallback |
| 首 chunk **之后** | 写 `event: error`，补 `data: [DONE]`，记账为失败；**禁止**拼接 fallback 内容 |
| 已收到 `[DONE]` 后断连 | 协议已完成，算成功，不补错误帧、不记失败 |
| 上游静默截断（干净 EOF 无 `[DONE]`） | 判定 `TRUNCATED_MESSAGE`，算失败；传输层 EOF ≠ 协议层完成 |
| 下游客户端取消 | 只挂 `onCancel`（不是 `onDispose`，后者正常完成也触发），账单/审计走 `doFinally` 结算并退还配额预留 |

---

## 5. 管理接口 `/admin/*`

**鉴权**：所有端点先走 `requireAdmin()`。`gateway.admin-token` 为空（默认）时全部放行——这只在本地演示可接受；非空时必须带 `X-Admin-Token` 头，用常数时间比较，不符返回 `401 admin token required`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/admin/snapshot` | 当前治理快照：`governanceEnabled`、`usage`、`cacheSize`、`recentAudit`（最近 20 条） |
| POST | `/admin/snapshot/save` | 保存用量快照到 `gateway.governance.snapshot-path`，返回 `{"saved":true,...}` |
| POST | `/admin/snapshot/load` | 从快照恢复，`{"loaded":true}`；**替换语义**，可重复调用不累加 |
| GET | `/admin/routes` | 路由表（脱敏，只回 `apiKeyConfigured` 布尔）；响应头 `X-LLMGW-Config-Version` 为配置版本 |
| POST | `/admin/routes/reload` | 路由整表热替换，返回 `{"applied":true,"version":N,"routes":[...]}` |
| GET | `/admin/keys` | 全部 API Key 定义 |
| GET | `/admin/verify` | 真实上游 SDK 连通性验证，返回 `{"sdkValidation":...,"connectivity":"..."}`；不在请求路径上 |
| GET | `/admin/metrics` | 聚合所有 `llmgw.*` Micrometer 计量（counters + timers 的 p50/p99/max）；无 Registry 时 `{"available":false}` |

### 5.1 路由热替换

请求体是 `RouteSpec` 数组，字段：`id, model, targetModel, targetUrl, apiKey, weight, fallbacks`（后四个可省略）。

```bash
curl -sS -X POST http://127.0.0.1:8080/admin/routes/reload \
  -H 'Content-Type: application/json' \
  -d '[{"id":"a","model":"*","targetUrl":"http://127.0.0.1:9090/mock/v1","weight":1},
       {"id":"b","model":"*","targetUrl":"http://127.0.0.1:9090/mock/v1","weight":3}]'
```

校验规则（`AdminController.reloadRoutes`）：数组非空；`id` 非空白且唯一；`targetUrl` 为空则回退 `gateway.upstream.base-url` 且必须以 `http://` 或 `https://` 开头；`weight` 下限 1；`fallbacks` 引用的 id 必须存在；只读配置源返回 409。校验通过后才一次性原子换引用，在途 SSE 不中断。

> `apiKey` 可选且**永不回显**——这是 v5 修掉的一个真实泄密问题：早期 `/admin/routes` 直接序列化 `Route` record，一个未鉴权 GET 就能把上游厂商密钥交出去。

### 5.2 打开管理鉴权

```bash
LLMGW_ADMIN_TOKEN=change-me ./bin/run-webflux.sh
curl -sS -H 'X-Admin-Token: change-me' http://127.0.0.1:8080/admin/routes
curl -sS http://127.0.0.1:8080/admin/keys        # -> 401
```

---

## 6. 运维接口与可观测性

`application.yml:55-59` 暴露 Actuator：`/actuator/health`、`/actuator/info`、`/actuator/metrics`、`/actuator/prometheus`。CI 与全部 smoke 脚本用 `/actuator/health` 做 60 秒轮询就绪判定。

核心指标（`README.md` 5.11、`/admin/metrics`）：

| 指标 | 含义 |
| --- | --- |
| `llmgw.ttft` | 首 token 时间，纳秒精度 + p50/p99 |
| `llmgw.upstream` | 上游耗时（JSON 完整 / SSE 首字节） |
| `llmgw.overhead` | 网关开销 = 总耗时 − 上游耗时 |
| `llmgw.latency` / `llmgw.errors` | 端到端延迟 / 错误计数 |
| `llmgw.denied` | 拒绝计数，`reason=invalid_key\|model_not_allowed\|rate_limited\|quota_exceeded` |
| `llmgw.cache` | 命中/未命中 |
| `llmgw.requests` | 带 `route` 标签，加权分流的可观测证据 |
| `llmgw.circuit` | `open` / `reopened` / `closed` |

标签含 `key`、`model`、`engine`、`route`、`mode`。没有 `MeterRegistry` 时静默降级，不会启动失败。

**日志**：只有 Spring Boot 默认 Logback 输出到控制台，仓库里**没有** `logback.xml`、没有 `logging.*` 配置、没有日志轮转。落盘的 `bench/results/*.log` 都是脚本重定向产物，不是 appender。要接日志系统请自己在启动命令里加 `-Dlogging...` 或由容器平台采集 stdout。

---

## 7. 治理能力速查

| 能力 | 行为 | 关键配置 |
| --- | --- | --- |
| 鉴权 | Bearer token → `ApiKeyRegistry`；`allowed-models` 白名单 | `gateway.api-keys[]` |
| 配额 | 准入时按估算 token 原子预留，结算按实际 `usage` 多退少补；**不是硬上限**，并发下可略超 | `quota-tokens` |
| 限流 | 原子令牌桶 CAS，按 `keyId` 分桶 | `gateway.governance.rate-limit-*` |
| 语义缓存 | 仅非流式；缓存键 = `keyId + ":" + SHA-256(完整请求体)`，不是 `model+prompt` | `cache-max-size` / `cache-ttl-seconds` |
| 加权路由 | 同 model 多路由按 `weight` **加权随机**分流；精确 model 优先于 `*` | `gateway.routes[].weight` |
| fallback | 按 `fallbacks` 声明顺序尝试，**仅首 chunk 前允许切换** | `gateway.routes[].fallbacks` |
| 熔断 | 同一 route 连续 N 次「未交付任何 chunk 的失败」后开路，冷却后放**一个**半开探测（CAS 防踩踏）；开路时零网络 I/O 直接走 fallback | `circuit-failure-threshold` / `circuit-cooldown-ms` |
| 计量/审计 | 旁路 `newVirtualThreadPerTaskExecutor`，不占热路径；审计环形队列上限 5000 条 | — |
| 关联 ID | `X-Request-Id` 贯穿响应头与审计事件 | — |

热路径只做纯内存原子操作；账单、审计、指标、快照走虚拟线程旁路。治理整体可用 `--gateway.governance-enabled=false` 一键关掉做纯透传对照。

---

## 8. 典型场景配方

### 8.1 本地开发 / 功能验证（默认）

```bash
./bin/run-webflux.sh
# 无需任何环境变量、无需数据库、无需 API Key
```

### 8.2 切换虚拟线程引擎

```bash
./bin/run-vt.sh
# 或：java -jar <jar> --spring.profiles.active=vt
```

### 8.3 接真实上游（阿里百炼 OpenAI 兼容端点）

```bash
export DASHSCOPE_API_KEY='<你的密钥>'      # 永远不要写进配置文件
java -jar llmgw-app/target/llmgw-app-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=webflux,real
curl -sS http://127.0.0.1:8080/admin/verify
```

`real` profile 做了三件事：关掉 mock（`enabled: false`，否则 9090 端口被占会启动失败）、把 `type` 设为 `openai-compatible`、`base-url` 指向 `https://dashscope.aliyuncs.com/compatible-mode/v1`，并配一条 `target-model: qwen-plus` 的默认路由。

> `real` 里的 `target-model` 非空会触发 `ProtocolCodec.withModel()` 重新序列化 JSON，只改写顶层 `model` 字段；其余字段原样保留，但字节不再逐字保真。

换成任意 OpenAI 兼容服务（自建 vLLM / Ollama / 其他厂商）：直接覆盖 CLI 参数即可，不必改文件：

```bash
java -jar <jar> --spring.profiles.active=webflux \
  --gateway.mock.enabled=false \
  --gateway.upstream.type=openai-compatible \
  --gateway.upstream.base-url=http://127.0.0.1:8000/v1 \
  --gateway.upstream.api-key=<key> \
  --gateway.routes[0].target-url=http://127.0.0.1:8000/v1
```

注意 `targetUri()` 会自动在 `base-url` 后追加 `/chat/completions`，所以 base-url 填到 `/v1` 这一级即可。

### 8.4 关闭治理做纯透传对照

```bash
java -jar <jar> --spring.profiles.active=webflux --gateway.governance-enabled=false
```

### 8.5 加权路由 + fallback + 热更新

```yaml
gateway:
  routes:
    - id: primary
      model: "*"
      target-url: http://127.0.0.1:9090/mock/v1
      weight: 3
      fallbacks: [secondary]
    - id: secondary
      model: "*"
      target-url: http://127.0.0.1:9090/mock/v1
      weight: 1
      fallbacks: []
```

或运行时热替换（见 [5.1](#51-路由热替换)）。`weight` 是负载比例，不是优先级。

### 8.6 Native Image

```bash
export JAVA_HOME=<GraalVM 25 路径>       # 脚本检查 $JAVA_HOME/bin/native-image
./bin/build-native.sh webflux            # -> bin/native/llmgw-webflux
./bin/build-native.sh vt                 # -> bin/native/llmgw-vt

bin/native/llmgw-webflux --spring.profiles.active=webflux --server.port=8080
```

要点：
- **一个二进制只装一个引擎**（Spring AOT 构建期求值 `@Profile`），两个引擎各编一个。
- `NATIVE_GC=g1` 选实验性 G1 构建（`-Pnative-g1`），**仅 Linux AMD64/AArch64**；macOS 上 `--gc=G1` 直接报错，只能用默认 Serial GC。`--gc=G1` 大小写敏感。
- 本机实测：首次单二进制编译约 8 分 43 秒、产物 282.64MB、构建峰值 RSS 10.73GB；native 冷启动 0.387–0.560s vs JVM 3.538–6.946s。
- native 下 Jackson 3 序列化 record 需要反射注册，`GatewayRuntimeHints` 已补齐 `/admin/*` 可达的 DTO。**native 冒烟必须覆盖管理接口，只测数据面会漏掉 500**。
- 编译 native 会往 `target/` 写 AOT 类污染 fat jar；正确顺序是 **native → `./bin/build.sh` 重建干净 JVM jar → 压测**。

### 8.7 Redis 分布式状态（实验性）

```bash
mvn -B -DskipTests -Predis clean package
java -jar <jar> --spring.profiles.active=webflux \
  --gateway.redis.enabled=true \
  --spring.data.redis.host=127.0.0.1 \
  --spring.data.redis.port=6399
./bin/smoke-redis-cluster.sh             # 双实例共享配额验证
```

只覆盖 `TokenLimiter` / `UsageLedger` / `CircuitBreaker` 三个 SPI；`ApiKeyRegistry` / `SemanticCache` / `SnapshotStore` 仍是本地实现。代价是每请求 2–4 次 Redis 往返，无流水线合并。

### 8.8 快照保存与恢复

```bash
curl -X POST http://127.0.0.1:8080/admin/snapshot/save
curl -X POST http://127.0.0.1:8080/admin/snapshot/load
```

默认路径 `./data/llmgw-snapshot.json`，是**唯一**落盘持久化文件，格式：

```json
{"usage":{"<keyId>":{"requests":0,"promptTokens":0,"completionTokens":0,"errors":0}}}
```

**故意不做启动时自动恢复**——压测每组单独起进程，残留快照会把配额变成不可控变量。恢复是替换语义、可重复调用；但与实时流量并存时仍非幂等安全，建议停流量再 load。读写失败只 `log.warn`，不抛异常。

### 8.9 压测

```bash
./bin/bench-matrix.sh                    # 8 组 × 2 场景主矩阵
ARM_A=<jarA> ARM_B=<jarB> ./bin/bench-paired-ab.sh   # ABBA 交错配对
./bin/bench-gc-matrix.sh
python3 bench/analyze-matrix.py
```

主矩阵口径固定 `20ms + 8×10ms ≈ 100ms` 的 mock 上游；`delay-ms` 与 `chunk-interval-ms` 都设 0 是独立的 **wire 实验**，两组数据不可逐行相减。压测脚本会给每组注入极大 quota/rate，否则演示默认配额几秒就被 429 打满。详细数据质量红线见 `bench/README.md`。

---

## 9. 回归与验证脚本

仓库**没有单元测试**（无 `src/test/`，所有构建带 `-DskipTests`），验证完全靠脚本。`bin/` 下共 **16 个** `.sh`：

| 类别 | 脚本 | 用途 |
| --- | --- | --- |
| 构建启动 | `build.sh`、`run-webflux.sh`、`run-vt.sh`、`build-native.sh` | 见第 2 / 8.6 节 |
| 结构校验 | `check-module-layout.sh` | 27 项：聚合层 + api + 多实现的布局、依赖方向、无 split package；CI 每次 PR 自动跑 |
| 契约回归 | `smoke-stream-errors.sh` | JVM 双引擎 67 项 / 单个 native 34 项；覆盖正常路径、401/400/413、上游不可达、非 2xx、fallback、首 chunk 后断流、静默截断、`[DONE]` 后断连、缓存键、精确计量、管理接口脱敏与鉴权、快照重复恢复、40 路并发 SSE、关联 ID、熔断、加权路由 |
| 契约回归 | `smoke-observability.sh` | TTFT / 上游 / 开销头 + 指标 + 审计 |
| 契约回归 | `smoke-hot-reload.sh <engine>` | 40 路在途 SSE 零中断 + 权重即时生效 |
| 契约回归 | `smoke-redis-cluster.sh` | 双实例共享配额（需 `-Predis` 产物 + 本地 Redis） |
| 压测 | `bench-matrix.sh`、`bench-paired-ab.sh`、`bench-gc-matrix.sh`、`bench-shenandoah.sh`、`bench-v9-io-ab.sh` | 矩阵 / 配对 A/B / GC / Shenandoah / IO 对照 |
| 微基准 | `run-parse-microbench.sh`、`run-buffer-microbench.sh` | 网络无关，输出 ns/op 与分配字节 |

native 产物验证：

```bash
BINARY="$PWD/bin/native/llmgw-webflux" ./bin/smoke-stream-errors.sh webflux
BINARY="$PWD/bin/native/llmgw-vt"      ./bin/smoke-stream-errors.sh vt
```

CI（`.github/workflows/ci.yml`）只跑结构校验 + 双引擎启动 + 一条请求 + 指标 `engine` 标签断言 + `-Predis` 构建；完整契约回归与 native 因耗时 10–17 分钟且依赖 macOS 辅助命令，**故意不进 CI**。

---

## 10. Mock 上游与故障注入

`POST http://127.0.0.1:9090/mock/v1/chat/completions`，正常 SSE 输出 8 个 chunk + 一个 `usage` 帧 + `data: [DONE]`。把标记字符串**放进请求体文本**即可触发受控故障；这些标记对所有压测 payload 都不生效，不影响压测口径。

| 标记 | 上游行为 | 覆盖边界 |
| --- | --- | --- |
| `mock-fail-after-N` | 发 N 个 chunk 后抛 `IOException` 异常断连 | 首 chunk 后带内报错、禁止 fallback 拼接 |
| `mock-eof-after-0` | 一帧不发就干净关闭 | 空流截断，首 chunk 前必须 502 |
| `mock-eof-after-N` | 发 N 个 chunk 后干净关闭，不抛异常、不发 `[DONE]` | 静默截断：EOF 不能当成功 |
| `mock-status-N` | 返回 HTTP 状态 N + JSON 错误体（流式/非流式均可） | 非 2xx 不能变成 SSE `data:` 行，必须网关 502 |
| `mock-done-then-fail` | 发完 `[DONE]` 后再断连 | 协议已完成就不能补 `event: error`、不能记失败 |
| `mock-echo-hash` | 非流式回显完整请求体 SHA-256 前 12 位 | 缓存键若只看 `model+prompt`，同文本不同采样参数会互串答案 |

> `MockUpstreamServer` 的类注释写「both markers」，README 正文写「两类」，但表格与代码实际支持上表 6 个标记。以代码与表格为准。

---

## 11. 数据与存储

| 状态 | 实现 | 约束 |
| --- | --- | --- |
| API Key | `InMemoryApiKeyRegistry`（ConcurrentHashMap） | 配置为空时自动注册 `sk-demo` |
| 令牌桶 | `AtomicTokenLimiter` | CAS，按实际 usage 结算并退还失败/取消/缓存命中 |
| 用量账本 | `InMemoryUsageLedger` | `load()` 为替换语义 |
| 语义缓存 | `CaffeineSemanticCache` | `maximumSize` + `expireAfterWrite` |
| 审计 | `InMemoryAuditSink` | 无锁环形队列，上限 5000 条，`recent(20)` |
| 熔断 | `WindowedCircuitBreaker` | 按 route id 分桶 |
| 路由表 | `InMemoryRouteConfigSource` | `AtomicReference<RouteTable>`，先校验再一次 CAS 换引用 |

**全部默认内存，重启归零、多实例不共享。** 唯一落盘是手动触发的 JSON 快照（第 8.8 节）。`data/`、`.env`、`*.pem`/`*.key`、`application-local.yml`、`llmgw-snapshot.json` 都在 `.gitignore` 里。

`bench/results/`（约 1.4GB）、各模块 `target/`、`tools/`（本地 JDK）、`bin/native/` 都是运行时数据或构建产物，不是源码，别当代码读。

---

## 12. 故障排查

### 12.1 端口被环境变量抢走
**现象**：服务起来了但 8080 连不上，日志显示监听在奇怪端口（本机实测 58458）。
**原因**：宿主注入 `SERVER__PORT` 之类变量，Spring Boot 宽松绑定覆盖了 `server.port`。
**处理**：用 `bin/run-*.sh`（脚本显式传 `--server.port=8080`），或自己加 `--server.port=8080`；`PORT` 环境变量可改端口。

### 12.2 接真实上游时启动失败 / 9090 端口占用
**原因**：`real` profile 未关 mock，或自己覆盖配置时忘了 `gateway.mock.enabled=false`。
**处理**：`application-real.yml` 已显式设 `false`；自定义配置务必带上。

### 12.3 压测时被 429 打满
**原因**：演示默认配额 1,000,000 token、限流 100k/s，几秒就见底。
**处理**：`bench-matrix.sh` 已为每组注入极大 quota/rate；自己压测时也照做，别把 429 当成性能数据。

### 12.4 `hs_err_pid41857.log`（工作区根目录）
**结论：不是网关运行时崩溃。** 该文件记录的是 2026-11-07 07:38 一次 JVM **参数探测**，进程只活了 0.194 秒，尚未加载任何应用类（命令行只有 GC/JVMCI 参数，没有 `-jar`）。

**根因**：Oracle GraalVM 25 的 JVMCI/Graal JIT 不支持 Shenandoah GC，`-XX:+UseShenandoahGC` + `-XX:+EnableJVMCI` 组合下 `GraalHotSpotVMConfig.getSelectedGC()` 抛 `GraalError`，未捕获异常升级为 `VMError::report_and_die`。

**规避**：不要在 Oracle GraalVM 25 上叠加 Shenandoah 与 JVMCI；Shenandoah 实验改用 Eclipse Temurin 25（`bin/bench-shenandoah.sh`），Oracle GraalVM 用 G1/ZGC。项目文档 `bench/README.md` 已记录这个坑。

### 12.5 Native 相关

| 现象 | 原因 / 处理 |
| --- | --- |
| macOS 上 `--gc=G1` 报错 | G1 仅 Linux AMD64/AArch64，macOS 用默认 Serial GC；`--gc=G1` 大小写敏感 |
| native 二进制只有单个引擎 | AOT 构建期求值 `@Profile`，按引擎分编 |
| native 下 `/admin/*` 返回 500 | Jackson 3 record 反射未注册；`GatewayRuntimeHints` 已修复，冒烟务必覆盖管理接口 |
| native 编译后 JVM jar 行为异常 | AOT 类污染 `target/`；重跑 `./bin/build.sh` 生成干净 jar |
| `-Pnative-g1` 找不到主类/AOT 类 | Spring Boot 只对名字恰好为 `native` 的 profile 自动接线 AOT；`native-g1` 已显式声明 `process-aot` 等，别自造 profile id |

### 12.6 其他

| 现象 | 原因 / 处理 |
| --- | --- |
| 流式上游失败却返回 200 | 已修复：首 chunk 前不 `flushBuffer()`，返回真 502 |
| 高并发 SSE 失败率约 16% | 已修复：取消钩子误挂 `onDispose` 拆掉共享 keep-alive 连接，改为只挂 `onCancel` |
| 指标里 `engine=unknown` | 既有缺陷，Unreleased 已通过 `EngineKindEnvironmentPostProcessor` 修复；CI 有断言 |
| 配额用超了 | 准入按估算 token 预留、结算按实际 `usage`，并发下 `used` 可能略超 `quota`（实测 72/60）；要财务级硬上限请替换 `UsageLedger` |
| 请求头/响应头丢了 | `JdkHttpUpstream.build()` 只设 `Content-Type` / `Accept` / `Authorization`，`OpenAI-Beta`、`OpenAI-Organization`、`anthropic-version`、`x-request-id` 不透传，`x-ratelimit-*` 不回写 |

---

## 13. 上线前检查清单

默认配置是**刻意为了本地零依赖演示**而设的，以下每条在共享/生产环境都必须处理：

- [ ] 设置 `LLMGW_ADMIN_TOKEN`，否则 `/admin/*` 全员可读写（含路由热替换和快照恢复）
- [ ] 替换 `gateway.api-keys`，删掉字面量 `sk-demo`
- [ ] `DASHSCOPE_API_KEY` 等上游密钥只走环境变量，不写进任何文件；`/admin/routes` 只回 `apiKeyConfigured` 布尔值
- [ ] 前置反向代理 / 服务网格承担 TLS、mTLS、入口限流、WAF——**网关无内建 TLS**
- [ ] 用 `server.address` 或防火墙限制监听范围（默认 `0.0.0.0`）
- [ ] 补齐应用日志采集与轮转（仓库无 `logback.xml`、无轮转配置）
- [ ] 明确接受「全内存、重启归零、多实例不共享」，或用 `-Predis` + 自行替换剩余 SPI
- [ ] 明确接受「配额不是硬上限」「token 计量是启发式估算，未接真实 tokenizer」
- [ ] 明确接受「治理参数除路由表外构造期固化，改配置要重启」
- [ ] 若用 native：按引擎分编、覆盖管理接口冒烟、注意 macOS 只能 Serial GC

详见 `SECURITY.md`，其中把上述若干条定义为「当前设计，不是漏洞」——意思是别指望上游会「修」它。

---

## 14. 已知限制（摘录）

- `openai-java` core jar 约 71MB，fat jar 127MB、native 代码区 46MB；SDK 只在校验/连通性路径，可按需摘除。
- `HttpRequest.timeout(60s)` 是**整体**超时，没有 connect / 首字节 / 空闲读三级超时；半开上游要等满 60s。
- token 计量在上游无 `usage` 时用启发式估算（CJK 字符计 1、其余按 4 字符 1 token），未接真实 tokenizer。
- 热配置只覆盖路由表；`quota` / `rate` / `cache` / `circuit` 构造期固化。
- 无自动化测试，CI 不含完整回归与 native。
- 历史压测中有一组 ABBA 配对 A/B 因宿主过载（`vm.loadavg` 13–50）被判为无效样本，不能据此声称零回归。
- `README.md` 中「接真实上游」整段重复出现两次；`README.en.md` 把 `X-LLMGW-Config-Version` 泛化为通用响应头，但代码里只在 `GET /admin/routes` 设置——**以代码为准**。

完整风险清单见 `README.md` 第六节、`CHANGELOG.md` 的「已知限制」。

---

## 15. 关键文件速查

| 用途 | 路径（相对仓库根） |
| --- | --- |
| 主配置 / 各 profile | `llmgw-app/src/main/resources/application.yml`、`application-{webflux,vt,real}.yml` |
| 配置属性类（默认值的唯一权威） | `llmgw-core/src/main/java/.../config/GatewayProperties.java` |
| 启动类 | `llmgw-app/src/main/java/.../app/LlmGatewayApplication.java` |
| 管理 API | `llmgw-app/src/main/java/.../app/AdminController.java` |
| 上游客户端 | `llmgw-app/src/main/java/.../app/JdkHttpUpstream.java` |
| Mock 上游 | `llmgw-app/src/main/java/.../app/MockUpstreamServer.java` |
| 两个数据面 Controller | `llmgw-engine/llmgw-engine-{webflux,vt}/src/main/java/.../` |
| 请求管线 / 协议编解码 | `llmgw-core/src/main/java/.../governance/GatewayPipeline.java`、`.../protocol/ProtocolCodec.java` |
| 默认治理链 | `llmgw-governance/llmgw-governance-default/src/main/java/.../inmemory/DefaultGovernanceChain.java` |
| 快照存储 | `llmgw-governance/llmgw-governance-default/src/main/java/.../inmemory/JsonSnapshotStore.java` |
| 构建配置 | `pom.xml`、`llmgw-app/pom.xml`（4 个 Maven profile） |
| CI | `.github/workflows/ci.yml` |

---

**与配置相关的所有细节**（每一项的类型、默认值、边界、示例、覆盖优先级、环境变量、Maven profile）见 [CONFIGURATION.md](CONFIGURATION.md)。
