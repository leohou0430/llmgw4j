# llmgw4j 配置说明

> **适用版本**：`0.1.0-SNAPSHOT`（Unreleased，包名 `io.github.leohou0430.llmgw`）
> **文档日期**：2026-11-08，逐项核对 `GatewayProperties.java`、`application*.yml`、`bin/*.sh`、`bench/*` 实读结果
> **配套**：[使用手册](USER_GUIDE.md) · [项目 README](../README.md) · [压测口径](../bench/README.md)

---

## 1. 配置体系总览

### 1.1 优先级（低 → 高）

```
① GatewayProperties.java 里的 Java 字段默认值
        ↓ 被覆盖
② llmgw-app/src/main/resources/application.yml（随仓库发布的基线配置）
        ↓ 被覆盖
③ application-{profile}.yml（webflux / vt / real，按激活顺序后加载的优先）
        ↓ 被覆盖
④ 命令行参数 --key=value（Spring Boot 标准，含 run-*.sh 追加的 "$@"）
        ↓ 被覆盖/等价
⑤ 环境变量（经 Spring Boot 宽松绑定，或 yml 里 ${VAR:default} 显式引用）
```

**关键点**：`application.yml` 会覆盖 `GatewayProperties.java` 里的代码默认值，而且有两项差了 **10 倍**（见 [第 4 节](#4-代码默认--随仓库发布值差异重要)）。所以「代码里写的默认值」不等于「你实际跑起来的默认值」——配置说明里两者都列，别只信一边。

### 1.2 配置源清单

| 类型 | 位置 | 说明 |
| --- | --- | --- |
| 配置属性类 | `llmgw-core/.../config/GatewayProperties.java` | `@ConfigurationProperties(prefix = "gateway")`，所有 `gateway.*` 项的 Java 默认值与类型定义 |
| 主配置 | `llmgw-app/src/main/resources/application.yml` | 唯一主配置文件，60 行 |
| Profile 配置 | 同目录 `application-webflux.yml` / `application-vt.yml` / `application-real.yml` | 各 6 / 6 / 16 行 |
| 环境后置处理器 | `llmgw-app/src/main/resources/META-INF/spring.factories` | 注册 `EngineKindEnvironmentPostProcessor`，把 `llmgw.engine` 写进 System Property 供指标打标签 |
| 自动装配 | `llmgw-governance-*/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 默认（in-memory）与 Redis 两套治理实现包按条件装配 |
| 构建配置 | `pom.xml`、`llmgw-app/pom.xml` | 版本锁定 + 4 个 Maven profile |

### 1.3 明确不存在的配置形式

仓库里**没有**：`application.properties`、`application-local.yml`、`.env` / `.env.example`、`logback*.xml`、`log4j2*.xml`、任何 `*.properties`、`spring.config.import`、配置中心、Dockerfile、docker-compose、systemd unit、`logging.*` 配置、日志轮转配置。这些名字只在 `.gitignore` 里作为「将来可能出现」的忽略规则存在。

想加本地覆盖配置，两条路：**命令行 `--key=value`**，或新建 `application-local.yml`（需自己激活 `local` profile）——后者已被 `.gitignore` 忽略，适合放密钥以外的本地差异。

> 所有配置项**都不是必填**，Java 层均有默认值。下表「必填」列全为「否」，指的是「不配也能启动」；生产可用性是另一回事，见 [第 11 节](#11-安全配置与忽略规则)。

---

## 2. Spring Boot 基础配置

来源：`application.yml:1-11`、`:55-59`、`application-{webflux,vt}.yml`。

| 配置项 | 类型 | 值 / 默认 | 说明 |
| --- | --- | --- | --- |
| `spring.application.name` | String | `llmgw` | 应用名 |
| `spring.profiles.default` | String | `webflux` | 未显式指定 profile 时的默认值 |
| `spring.threads.virtual.enabled` | boolean | `true` | 启用虚拟线程（Spring Boot 4）；治理旁路依赖它 |
| `spring.main.web-application-type` | enum | `reactive`（webflux）/ `servlet`（vt） | **由 profile 文件设置，不要手改**；决定 Netty 还是 Tomcat 11 |
| `llmgw.engine` | String | `webflux` / `vt` | 由 profile 文件设置，`EngineKind.fromProfile()` 反查后写进 System Property，作为 Micrometer `engine` 标签 |
| `server.port` | int | `8080` | HTTP 监听端口；`run-*.sh` 会显式再传一次，防止被环境变量抢走 |
| `server.address` | String | **未配置** | 按 Spring Boot 默认绑定 `0.0.0.0`；共享环境建议显式限制 |
| `management.endpoints.web.exposure.include` | String | `health,info,metrics,prometheus` | Actuator 暴露端点 |
| `management.server.port` | int | 未配置 | 与主服务同端口 |
| `logging.*` | — | 未配置 | 使用 Spring Boot 默认 Logback 控制台输出，无文件 appender、无轮转 |

---

## 3. `gateway.*` 完整配置项

> 表中「代码默认」= `GatewayProperties.java` 字段初始值；「发布值」= `application.yml` 中显式配置的值；「—」表示 yml 未配置，实际取代码默认。

### 3.1 顶层

| 配置项 | 类型 | 代码默认 | 发布值 | 说明与约束 |
| --- | --- | --- | --- | --- |
| `gateway.governance-enabled` | boolean | `true` | `true` | `false` = 关闭整套治理（鉴权/配额/限流/缓存/熔断/计量/审计），纯透传对照用 |
| `gateway.admin-token` | String | `""` | `${LLMGW_ADMIN_TOKEN:}` | 空 = 所有 `/admin/*` **不鉴权**；非空则必须带 `X-Admin-Token` 头，常数时间比较 |
| `gateway.max-request-bytes` | long | `4194304`（4MB） | `4194304` | 请求体硬上限，两个数据面共用；超限返回 413 `request body too large` |
| `gateway.routes` | List\<RouteDef\> | 空列表 | 1 条 `mock-primary` | 为空时自动生成一条默认路由 |
| `gateway.api-keys` | List\<ApiKeyDef\> | 空列表 | 1 条 `sk-demo` | 为空时自动注册字面量 `sk-demo` |

### 3.2 `gateway.upstream.*`

| 配置项 | 类型 | 代码默认 | 发布值 | 说明与约束 |
| --- | --- | --- | --- | --- |
| `gateway.upstream.type` | String | `mock` | `mock` | 注释标注可选 `mock` / `openai-compatible`；也用于生成 `JdkHttpUpstream.id()` = `jdk-http-<type>` |
| `gateway.upstream.base-url` | String | `http://127.0.0.1:9090/mock/v1` | 同左 | 上游基址。`targetUri()` 会**自动追加 `/chat/completions`**，所以填到 `/v1` 这一级即可，别把路径写全 |
| `gateway.upstream.api-key` | String | `${DASHSCOPE_API_KEY:}` | `${DASHSCOPE_API_KEY:}` | 上游厂商密钥。值为空或以 `${` 开头时**不发送** `Authorization` 头 |
| `gateway.upstream.timeout-ms` | int | `60000` | `60000` | 传给 `HttpRequest.timeout()` 的**整体**超时。不是 connect / 首字节 / 空闲读三级超时；半开上游要等满 60s |
| `gateway.upstream.executor-threads` | int | `32` | **—（yml 未配）** | `>0` 用固定平台线程池跑 `HttpClient.sendAsync`；`0` 回退 JDK 默认 executor。8/16/32/64 线程扫描拐点在 32（16 掉约 8% JSON RPS，64 无增益），JDK 默认 executor 实测 SSE 吞吐 −30% |

`real` profile 会把 `type` 改为 `openai-compatible`、`base-url` 改为 `https://dashscope.aliyuncs.com/compatible-mode/v1`。

### 3.3 `gateway.governance.*`

| 配置项 | 类型 | 代码默认 | 发布值 | 说明与约束 |
| --- | --- | --- | --- | --- |
| `gateway.governance.default-quota-tokens` | int | `1_000_000` | `1000000` | 未配置 `api-keys` 时 `sk-demo` 的默认配额 |
| `gateway.governance.rate-limit-tokens-per-second` | int | `10_000` | **`100000`** | 令牌桶 refill 速率；`GovernanceLimits.from()` 会 `Math.max(0, ...)` |
| `gateway.governance.rate-limit-burst` | int | `20_000` | **`200000`** | 桶容量；归一化下限 1 |
| `gateway.governance.cache-max-size` | int | `1000` | `1000` | Caffeine `maximumSize`，下限 1 |
| `gateway.governance.cache-ttl-seconds` | int | `300` | `300` | Caffeine `expireAfterWrite`，下限 1 |
| `gateway.governance.snapshot-path` | String | `./data/llmgw-snapshot.json` | 同左 | 唯一落盘文件，手动触发；父目录自动创建 |
| `gateway.governance.circuit-failure-threshold` | int | `5` | `5` | 同一 route 连续「未交付任何 chunk 的失败」达到该值后开路；**`<=0` 完全禁用熔断** |
| `gateway.governance.circuit-cooldown-ms` | int | `30_000` | `30000` | 开路持续时长，之后放**一个**半开探测（CAS 防踩踏）；下限 1ms |

归一化入口是 `GovernanceLimits.from()`（`llmgw-governance-api`），in-memory 与 Redis 两个实现包共享，避免两边 clamp 行为漂移。

### 3.4 `gateway.mock.*`

| 配置项 | 类型 | 代码默认 | 发布值 | 说明与约束 |
| --- | --- | --- | --- | --- |
| `gateway.mock.enabled` | boolean | `true` | `true` | 是否启动内置 `MockUpstreamServer`。**接真实上游必须设 `false`**，否则仍占 9090 端口 |
| `gateway.mock.port` | int | `9090` | `9090` | mock 监听端口，绑定所有本地接口 |
| `gateway.mock.delay-ms` | int | `20` | `20` | 首 chunk 前延迟 |
| `gateway.mock.chunks` | int | `8` | `8` | SSE chunk 数，代码内 `Math.max(1, ...)` 保底 |
| `gateway.mock.chunk-interval-ms` | int | `10` | `10` | chunk 间隔 |

主压测矩阵口径固定 `20ms + 8×10ms ≈ 100ms`。**把 `delay-ms` 与 `chunk-interval-ms` 都设 0 是独立的 wire 实验，其数据不可与主矩阵逐行相减**（`bench/README.md` 的数据质量红线）。

### 3.5 `gateway.routes[]` 数组元素

发布值（`application.yml:42-47`）只有一条：

```yaml
routes:
  - id: mock-primary
    model: "*"
    target-url: http://127.0.0.1:9090/mock/v1
    weight: 1
    fallbacks: []
```

| 字段 | 类型 | 代码默认 | 说明与约束 |
| --- | --- | --- | --- |
| `id` | String | `default` | 路由唯一 id；热替换时必填、不可重复 |
| `model` | String | `*` | 匹配模型；**精确 model 优先于 `*` 通配** |
| `target-model` | String | `""` | 非空时用 `ProtocolCodec.withModel()` 改写顶层 `model` 字段（会重新序列化 JSON，字节不再逐字保真） |
| `target-url` | String | `""` | 空则回退 `gateway.upstream.base-url`；热替换校验必须以 `http://` 或 `https://` 开头 |
| `api-key` | String | `""` | 空则回退 `gateway.upstream.api-key`；**管理接口永不回显，只回 `apiKeyConfigured` 布尔值** |
| `weight` | int | `1` | 同 model 多路由**加权随机**分流，是负载比例不只是优先级；下限 1 |
| `fallbacks` | List\<String\> | `[]` | fallback 路由 id 列表，按声明顺序；**只在首 chunk 前允许切换**；引用 id 必须存在 |

> 注意 `real` profile 里有一整条 `routes` 配置（id=`dashscope`，`target-model: qwen-plus`），会**整表覆盖**主配置的 `routes`，不是追加。

### 3.6 `gateway.api-keys[]` 数组元素

发布值（`application.yml:48-53`）：

```yaml
api-keys:
  - id: demo
    token: sk-demo
    name: demo
    quota-tokens: 1000000
    allowed-models: []
```

| 字段 | 类型 | 代码默认 | 说明与约束 |
| --- | --- | --- | --- |
| `id` | String | `demo` | key 标识，用于账本/限流分桶 |
| `token` | String | `sk-demo` | Bearer token 字面量。**生产必须替换** |
| `name` | String | `demo` | 展示名 |
| `quota-tokens` | long | `1_000_000` | 该 key 的配额；准入按估算 token 原子预留、结算按实际 `usage`，并发下可能略超，非硬上限 |
| `allowed-models` | List\<String\> | `[]` | 空 = 不限制；非空且不含请求 model 时返回 403 |

### 3.7 Redis 分布式状态（可选，仅 `-Predis` 产物）

| 配置项 | 类型 | 默认 | 说明与约束 |
| --- | --- | --- | --- |
| `gateway.redis.enabled` | boolean | 无（条件装配） | **必须显式设 `true`** 且 jar 用 `-Predis` 编译，对应 `@ConditionalOnProperty(havingValue="true")` |
| `spring.data.redis.host` | String | Spring Boot 默认 `localhost` | 实验脚本示例 `127.0.0.1` |
| `spring.data.redis.port` | int | Spring Boot 默认 `6379` | 实验脚本用 `6399` |
| （常量）Redis key 前缀 | String | `llmgw:` | `GovernancePacks.DEFAULT_REDIS_KEY_PREFIX`，代码常量，不可配 |

只覆盖 `TokenLimiter` / `UsageLedger` / `CircuitBreaker` 三个 SPI；`ApiKeyRegistry` / `SemanticCache` / `SnapshotStore` 仍是本地实现。每请求 2–4 次 Redis 往返，无流水线合并。

---

## 4. 代码默认 ≠ 随仓库发布值（重要）

`application.yml` 覆盖了部分 Java 字段默认值，其中两项差 **10 倍**。如果你把 `application.yml` 拿掉、或换用自己的配置文件而没显式声明这些项，行为会静默变化：

| 配置项 | `GatewayProperties.java` | `application.yml`（实际生效） | 差异 |
| --- | --- | --- | --- |
| `gateway.governance.rate-limit-tokens-per-second` | `10_000` | `100000` | **10×** |
| `gateway.governance.rate-limit-burst` | `20_000` | `200000` | **10×** |
| `gateway.upstream.executor-threads` | `32` | 未配置 → 实际 `32` | 一致，但**只靠代码兜底**，自建配置时容易漏 |
| 其余所有项 | — | 与代码默认一致 | — |

另外两个「配置为空时的隐式行为」也要记住：

- `gateway.routes` 为空 → 自动生成一条默认路由（`InMemoryRouteConfigSource`）。
- `gateway.api-keys` 为空 → 自动注册 `sk-demo`（`InMemoryApiKeyRegistry`），配额取 `gateway.governance.default-quota-tokens`。

---

## 5. Profile 详解

### 5.1 四个配置文件

| 文件 | 内容 | 何时生效 |
| --- | --- | --- |
| `application.yml` | 主配置，60 行 | 始终 |
| `application-webflux.yml` | `web-application-type: reactive` + `llmgw.engine: webflux` | `--spring.profiles.active=webflux`，或不指定时按 `spring.profiles.default` |
| `application-vt.yml` | `web-application-type: servlet` + `llmgw.engine: vt` | `--spring.profiles.active=vt` |
| `application-real.yml` | `mock.enabled: false` + `type: openai-compatible` + DashScope `base-url` + 一条 `dashscope` 路由 | 需与引擎 profile **组合**：`webflux,real` / `vt,real` |

### 5.2 组合与覆盖规则

```bash
# 只用引擎（默认 mock 上游）
--spring.profiles.active=webflux
--spring.profiles.active=vt

# 引擎 + 真实上游（profile 按书写顺序加载，后者覆盖前者）
--spring.profiles.active=webflux,real
```

`real` 里的 `gateway.mock.enabled: false` 不是可选项：少了它，9090 端口被占用时启动直接失败。`application-real.yml` 文件头注释专门写了这个坑。

### 5.3 为什么 `llmgw.engine` 不能手改

`EngineKindEnvironmentPostProcessor`（注册在 `META-INF/spring.factories`，`Ordered.LOWEST_PRECEDENCE`）在环境准备阶段读取 `llmgw.engine`，调用 `EngineKind.activate()` 写入 `System.getProperty("llmgw.engine")`，供 `DefaultGovernanceChain.engineLabel()` 给指标打标签。手动改成别的值会让指标标签失真（历史上有过恒为 `unknown` 的缺陷，CI 现在有断言）。**只通过 profile 切换引擎**。

---

## 6. 应用读取的环境变量

应用本身只显式读 **2 个**环境变量：

| 变量 | 引用位置 | 用途 | 未设置时 |
| --- | --- | --- | --- |
| `LLMGW_ADMIN_TOKEN` | `application.yml:16` → `gateway.admin-token: ${LLMGW_ADMIN_TOKEN:}` | 管理面令牌 | 空字符串 → `/admin/*` 全部开放 |
| `DASHSCOPE_API_KEY` | `application.yml:21`、`application-real.yml:9`、`GatewayProperties.java:40` | 阿里百炼 / OpenAI 兼容上游密钥 | 空 → 不发 `Authorization` 头 |

`CONTRIBUTING.md` 明确要求：密钥通过 `DASHSCOPE_API_KEY` 注入，**永远不要写进文件**。仓库内无真实凭据。

其余 Spring Boot 标准环境变量（如 `SERVER_PORT`、`SPRING_DATA_REDIS_HOST`）通过宽松绑定生效——这正是 `run-*.sh` 要显式钉死 `--server.port=8080` 的原因：宿主注入 `SERVER__PORT` 之类变量会把端口抢走（本机实测被抢到 58458）。

---

## 7. 脚本级环境变量（实读汇总）

这些变量**不是应用配置**，只被 `bin/*.sh` / `bench/*` 读取，但对正确使用脚本很关键。默认值均来自脚本内 `${VAR:-default}` 实读。

### 7.1 启动与构建

| 变量 | 默认 | 使用位置 | 说明 |
| --- | --- | --- | --- |
| `PORT` | `8080` | `run-webflux.sh`、`run-vt.sh`、`bin/smoke-*.sh`、`bench-matrix.sh` | 网关端口 |
| `MOCK_PORT` | `9090` | `bin/smoke-*.sh`、`bench-matrix.sh` | mock 上游端口 |
| `GATEWAY_GC_OPTS` | `-XX:MaxGCPauseMillis=10` | `run-webflux.sh:9`、`run-vt.sh:9` | 来自 GC 矩阵实测（最大暂停 11.1ms → 6.6ms，吞吐无损）；设 `GATEWAY_GC_OPTS=""` 可关闭 |
| `JAVA_HOME` | 无（native 编译必填） | `build-native.sh:20-29` | 脚本检查 `$JAVA_HOME/bin/native-image` 可执行 |
| `NATIVE_GC` | 空（默认 Serial GC） | `build-native.sh:15` | `g1` → 选 `-Pnative-g1`（仅 Linux AMD64/AArch64） |
| `BINARY` | 空 | `smoke-stream-errors.sh`、`smoke-observability.sh:51` | 指向 native 二进制时改用该产物启动 |
| `JAVA_BIN` | `java` | `bench-matrix.sh:34` | 指定 java 可执行文件 |
| `JVM_OPTS` | 空 | `bench-matrix.sh:90`、`bench-gc-matrix.sh` | 额外 JVM 参数 |
| `CONTROL_JAR` | `bench/results/v8.1-final.jar` | `run-buffer-microbench.sh:12` | 微基准对照 jar |
| `JDK_JAVA` | `tools/jdk-25*/Contents/Home/bin/java` | `bench-shenandoah.sh:23` | Temurin 25 路径（Shenandoah 实验必须用 Temurin，不用 Oracle GraalVM） |

### 7.2 压测矩阵

| 变量 | 默认 | 使用位置 |
| --- | --- | --- |
| `VUS` | `50` | `bench-matrix.sh:12`、`wrk-baseline.sh:4`、`k6-*.js:28` |
| `DURATION` | `30s`（配对 A/B 为 `20s`） | `bench-matrix.sh:13`、`bench-paired-ab.sh:14`、`k6-*.js:29` |
| `WARMUP_DURATION` | `5s` | `bench-matrix.sh:14` |
| `ROUNDS` | `3` | `bench-matrix.sh:15` |
| `ENGINES` | `webflux vt` | `bench-matrix.sh:39` |
| `GOVS` | `on off` | `bench-matrix.sh:40` |
| `RUNTIMES` | `jvm native` | `bench-matrix.sh:41` |
| `MODES` | `json sse` | `bench-matrix.sh:42` |
| `MOCK_DELAY_MS` | `20` | `bench-matrix.sh:23` → 注入 `--gateway.mock.delay-ms` |
| `MOCK_CHUNK_INTERVAL_MS` | `10` | `bench-matrix.sh:24` → 注入 `--gateway.mock.chunk-interval-ms` |
| `PAYLOAD_BYTES` | `0` | `bench-matrix.sh:28`、`k6-*.js:12`；配对 A/B 必填语义 |
| `BENCH_DEFAULT_QUOTA` | `2000000000` | `bench-matrix.sh:60` → `--gateway.governance.default-quota-tokens` |
| `BENCH_KEY_QUOTA` | `1000000000000` | `bench-matrix.sh:61` → `--gateway.api-keys[0].quota-tokens` |
| `BENCH_LIMIT_QPS` | `2000000000` | `bench-matrix.sh:62` → `--gateway.governance.rate-limit-tokens-per-second` |
| `BENCH_LIMIT_BURST` | `2000000000` | `bench-matrix.sh:63` → `--gateway.governance.rate-limit-burst` |
| `ARM_A` / `ARM_B` | **必填**（`${VAR:?...}`） | `bench-paired-ab.sh:10-11`，基线 jar / 候选 jar |
| `PAIRS` | `4` | `bench-paired-ab.sh:13`、`bench-v9-io-ab.sh:21` |
| `VARIANTS` | `g1 zgc g1-coh g1-pause10 parallel serial` | `bench-gc-matrix.sh:19` |
| `NATIVE_WEBFLUX` / `NATIVE_VT` | `bin/native/llmgw-{webflux,vt}` | `bench-matrix.sh:6-7` |
| `CONNECTIONS` | `200` | `bench/wrk-baseline.sh:6` |
| `BASE_URL` | `http://127.0.0.1:8080` | `k6-json.js:5`、`k6-sse.js:5` |
| `API_KEY` | `sk-demo` | `k6-*.js:6` |
| `ENGINE` / `GOVERNANCE` / `RUNTIME` | `unknown` / `on` / `jvm` | `k6-*.js:7-9`，仅用于结果打标 |

> `BENCH_*` 四个准入阈值是**必须的**：演示默认配额几秒就打满 429，不覆盖的话压测数据全是限流噪声。这是 `bench/README.md` 明确的口径。

### 7.3 回归脚本

| 变量 | 默认 | 使用位置 |
| --- | --- | --- |
| `STREAMS` | `40` | `smoke-hot-reload.sh:15`，并发在途 SSE 路数 |
| `REDIS_PORT` | `6399` | `smoke-redis-cluster.sh:11` |
| `QUOTA` | `60` | `smoke-redis-cluster.sh:15` |
| `N` | `200` | `smoke-redis-cluster.sh:16` |

另有 `smoke-*.sh` 内部固定的第 1/2 实例端口（8080 / 8081）与故障注入用的不可达上游 19999，脚本内直接赋值，不作为可调变量。

---

## 8. Maven 构建 profile（`llmgw-app/pom.xml`）

| Profile | 作用 | 关键参数 |
| --- | --- | --- |
| `redis` | 加入 `llmgw-governance-redis` + `spring-boot-starter-data-redis` | 默认 fat jar 不含任何 Redis 类；启用需 `-Predis` 且 `--gateway.redis.enabled=true` |
| `kqueue` | macOS 原生传输实验，加 `netty-transport-classes-kqueue` + native kqueue | classifier 默认 `osx-x86_64`；Apple silicon 用 `-Dllmgw.kqueue.classifier=osx-aarch_64` |
| `native` | GraalVM native-image | `--enable-url-protocols=http,https`、`-H:+ReportExceptionStackTraces`；Spring Boot 只对名字**恰好叫 `native`** 的 profile 自动接线 AOT |
| `native-g1` | 实验性 G1 GC native 构建 | 因为名字不叫 `native`，必须显式声明 `process-aot` / `add-reachability-metadata` / `classesDirectory`，否则 native-image 在 fat jar 上找不到主类 |

根 `pom.xml` 的 properties 也是可覆盖的构建参数：

| 属性 | 默认 | 说明 |
| --- | --- | --- |
| `llmgw.native.profile` | `webflux` | `build-native.sh` 按引擎传入 |
| `llmgw.native.image` | `llmgw` | `build-native.sh` 设为 `llmgw-webflux` / `llmgw-vt` |
| `llmgw.kqueue.classifier` | `osx-x86_64` | Apple silicon 覆盖为 `osx-aarch_64` |
| `java.version` / `maven.compiler.release` | `25` | 不要改，代码用 Java 25 特性 |
| `caffeine.version` / `openai-java.version` / `native-buildtools.version` | `3.3.1` / `4.100.0` / `1.1.18` | 依赖锁定 |

---

## 9. 配置示例配方

### 9.1 本地开发（零配置）

不用写任何东西，直接 `./bin/run-webflux.sh`。等价于：

```yaml
spring.profiles.active: webflux
server.port: 8080
gateway:
  upstream: { type: mock, base-url: "http://127.0.0.1:9090/mock/v1" }
  mock: { enabled: true, port: 9090, delay-ms: 20, chunks: 8, chunk-interval-ms: 10 }
  api-keys: [{ id: demo, token: sk-demo, quota-tokens: 1000000 }]
```

### 9.2 接任意 OpenAI 兼容上游（纯命令行，不改文件）

```bash
export DASHSCOPE_API_KEY='<密钥>'
java -jar llmgw-app/target/llmgw-app-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=webflux,real \
  --gateway.upstream.base-url=http://127.0.0.1:8000/v1 \
  --gateway.routes[0].target-url=http://127.0.0.1:8000/v1 \
  --gateway.mock.enabled=false \
  --gateway.upstream.timeout-ms=120000
```

`real` profile 自带的 `routes[0].target-model: qwen-plus` 仍会生效（把请求体里的 `model` 改写为 `qwen-plus`）。如果目标服务不认这个模型名，用 `--gateway.routes[0].target-model=` 清空，或直接不用 `real` profile、改成逐项手写配置。`base-url` 与 `routes[0].target-url` 要一起覆盖，否则路由仍指向 DashScope。

### 9.3 生产/共享环境最小安全配置

```bash
export LLMGW_ADMIN_TOKEN="$(openssl rand -hex 24)"
export DASHSCOPE_API_KEY='<上游密钥>'

java -jar <jar> --spring.profiles.active=webflux,real \
  --server.address=127.0.0.1 \
  --server.port=8080 \
  --gateway.admin-token="$LLMGW_ADMIN_TOKEN" \
  --gateway.api-keys[0].id=prod-main \
  --gateway.api-keys[0].token='<强随机 token>' \
  --gateway.api-keys[0].quota-tokens=50000000 \
  --gateway.governance.rate-limit-tokens-per-second=5000 \
  --gateway.governance.rate-limit-burst=10000 \
  --gateway.mock.enabled=false
```

**仍需外部补齐**：TLS/mTLS/WAF/入口限流（网关无内建）、日志采集与轮转（无 `logback.xml`）、状态持久化（默认全内存）、配额硬上限（需替换 `UsageLedger`）。见 `SECURITY.md`。

### 9.4 加权路由 + fallback（配置文件形式）

```yaml
gateway:
  routes:
    - id: primary-a
      model: "*"
      target-url: https://provider-a.example.com/v1
      api-key: ${PROVIDER_A_KEY:}
      weight: 3
      fallbacks: [backup]
    - id: primary-b
      model: "*"
      target-url: https://provider-b.example.com/v1
      api-key: ${PROVIDER_B_KEY:}
      weight: 1
      fallbacks: [backup]
    - id: backup
      model: "*"
      target-url: https://backup.example.com/v1
      weight: 1
      fallbacks: []
```

`weight` 是负载比例（3:1），不是优先级；精确 `model` 匹配优先于 `*`；精确 model 的同组路由之间才参与加权。

### 9.5 运行时热替换路由（JSON 形式）

```bash
curl -sS -X POST http://127.0.0.1:8080/admin/routes/reload \
  -H "X-Admin-Token: $LLMGW_ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '[{"id":"a","model":"*","targetUrl":"http://127.0.0.1:9090/mock/v1","weight":1},
       {"id":"b","model":"*","targetUrl":"http://127.0.0.1:9090/mock/v1","weight":3}]'
```

字段名是 **camelCase**（`targetUrl` / `targetModel` / `apiKey`），与 yml 里的 kebab-case（`target-url`）不同——这是 Jackson record 绑定，别写错。校验规则见 [使用手册 5.1](USER_GUIDE.md#51-路由热替换)。

### 9.6 压测口径（脚本自动注入，不要手写）

```bash
# bench-matrix.sh 实际注入的 CLI 参数（节选）
--gateway.governance.default-quota-tokens=$BENCH_DEFAULT_QUOTA
--gateway.governance.rate-limit-tokens-per-second=$BENCH_LIMIT_QPS
--gateway.governance.rate-limit-burst=$BENCH_LIMIT_BURST
--gateway.api-keys[0].quota-tokens=$BENCH_KEY_QUOTA
--gateway.mock.delay-ms=$MOCK_DELAY_MS
--gateway.mock.chunk-interval-ms=$MOCK_CHUNK_INTERVAL_MS
```

---

## 10. 生效范围与热更新边界

| 配置 / 状态 | 能否运行时热更 | 机制 |
| --- | --- | --- |
| **路由表** | ✅ 能 | `POST /admin/routes/reload`，先全量校验再一次 CAS 换 `AtomicReference<RouteTable>`，在途 SSE 不中断；`GET /admin/routes` 的 `X-LLMGW-Config-Version` 递增 |
| 快照用量账本 | ✅ 能（手动、非幂等安全） | `POST /admin/snapshot/load`，替换语义；建议停流量后操作 |
| `api-keys` | ❌ 不能 | 构造期读入 `InMemoryApiKeyRegistry` |
| `quota` / `rate-limit` / `cache` / `circuit` 参数 | ❌ 不能 | `GovernanceLimits.from()` 在构造期固化 |
| `governance-enabled` / `admin-token` / `max-request-bytes` | ❌ 不能 | 启动时绑定 |
| `upstream.*` / `mock.*` / `server.port` | ❌ 不能 | 启动时绑定；mock 是构造期决定是否起服务 |
| 引擎 / profile | ❌ 不能 | profile 决定组件扫描，native 下更是构建期求值 |

> 热替换路由时，在途请求的 fallback 链按**当前表**解析；如果旧 id 被删掉，降级为空链。替换前确认所有在途请求已收尾，或接受这个语义。

---

## 11. 配置校验与常见错误

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 启动失败，9090 端口被占 | 用 `real` 或自定义上游时忘了 `gateway.mock.enabled=false` | 显式加该参数；`application-real.yml` 已内置 |
| 请求打到 404 / 上游路径重复 | `base-url` 把 `/chat/completions` 也写进去了 | `targetUri()` 会自动追加 `/chat/completions`，base-url 填到 `/v1` 即可 |
| 上游返回 401 | `api-key` 为空或仍是字面量 `${...}`；此时网关**不发** `Authorization` 头 | 通过 `DASHSCOPE_API_KEY` 注入真实值 |
| `/admin/routes/reload` 400 | `specs` 为空；`id` 空白/重复；`targetUrl` 非 http(s)；`fallback` 引用了不存在的 id | 按 `AdminController.reloadRoutes` 的校验逐条修；字段名用 camelCase |
| `/admin/routes/reload` 409 | `RouteConfigSource` 只读或不可用 | 默认 `InMemoryRouteConfigSource` 可写；检查是否替换成了只读实现 |
| `/admin/*` 401 | 设置了 `LLMGW_ADMIN_TOKEN` 但请求没带 `X-Admin-Token` | 补头；未设置令牌时是放行 |
| 指标里 `engine=unknown` | `llmgw.engine` 未通过 profile 正确设置 | 用 `--spring.profiles.active=webflux|vt` 启动，别手改 `llmgw.engine` |
| 压测全是 429 | 用了演示默认配额 | 走 `bench-matrix.sh`，或手动注入 `BENCH_*` 四个极大阈值 |
| 端口跑到 58458 之类 | 宿主 `SERVER__PORT` 经宽松绑定抢走了端口 | 用 `bin/run-*.sh`，或显式 `--server.port=8080` |
| 改了 yml 但没生效 | 打进 jar 的 `application.yml` 是构建期资源 | 重新 `./bin/build.sh`；或改用命令行参数（优先级更高、无需重编） |
| native 二进制行为与预期不符 | 配置/引擎在 AOT 构建期已固化 | 按引擎分编；运行期只能传 Spring Boot 支持的参数 |

---

## 12. 安全配置与忽略规则

`.gitignore` 已为敏感文件预留规则：

```
.env / .env.*                # 仅放行 .env.example（该文件实际不存在）
application-local.yml        # 本地覆盖配置
*.pem / *.key / *.p12 / *.jks
data/                        # 快照目录
llmgw-snapshot.json
*.tar.gz / tools/            # 本地 JDK、GraalVM 工具链
bench/results/ / target/ / bin/native/
```

配置层面的硬性要求（`SECURITY.md`）：

1. `LLMGW_ADMIN_TOKEN` 在**任何共享环境**必须设置，否则 `/admin/*` 全员可读写（含路由热替换、快照恢复、查看 keys）。
2. 删除字面量 `sk-demo`，替换 `gateway.api-keys`。
3. 上游密钥只走 `DASHSCOPE_API_KEY` 环境变量；`/admin/routes` 只回 `apiKeyConfigured` 布尔值，不回显密钥。
4. 无内建 TLS / mTLS / WAF，传输加密与入口防护由前置反向代理或服务网格承担。
5. 上游响应头不透传（`x-ratelimit-*` 等），不要依赖网关传递厂商配额语义。
6. 配额不是硬上限；需要财务级上限请自行替换 `UsageLedger` SPI。

---

## 13. 附录：带注释的 `application.yml` 全文

```yaml
spring:
  application:
    name: llmgw
  profiles:
    default: webflux              # 不指定 profile 时用 WebFlux
  threads:
    virtual:
      enabled: true               # 治理旁路虚拟线程依赖

server:
  port: 8080

gateway:
  governance-enabled: true        # false = 纯透传对照
  admin-token: ${LLMGW_ADMIN_TOKEN:}   # 空 = /admin 不鉴权（仅限本地）
  max-request-bytes: 4194304      # 4MB，超限 413
  upstream:
    type: mock                    # mock | openai-compatible
    base-url: http://127.0.0.1:9090/mock/v1   # 会自动追加 /chat/completions
    api-key: ${DASHSCOPE_API_KEY:}
    timeout-ms: 60000             # 整体超时，非三级超时
    # executor-threads: 32        # 未显式配置，取代码默认 32
  governance:
    default-quota-tokens: 1000000
    rate-limit-tokens-per-second: 100000   # 代码默认 10000，此处覆盖为 10×
    rate-limit-burst: 200000               # 代码默认 20000，此处覆盖为 10×
    cache-max-size: 1000
    cache-ttl-seconds: 300
    snapshot-path: ./data/llmgw-snapshot.json
    circuit-failure-threshold: 5          # <=0 禁用熔断
    circuit-cooldown-ms: 30000
  mock:
    enabled: true                 # 接真实上游必须 false
    port: 9090
    delay-ms: 20
    chunks: 8
    chunk-interval-ms: 10         # 主矩阵：20 + 8×10 ≈ 100ms
  routes:
    - id: mock-primary
      model: "*"
      target-url: http://127.0.0.1:9090/mock/v1
      weight: 1
      fallbacks: []
  api-keys:
    - id: demo
      token: sk-demo              # 生产必须替换
      name: demo
      quota-tokens: 1000000
      allowed-models: []          # 空 = 不限制

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
```

---

**怎么装、怎么跑、怎么用、坏了怎么修**，见 [USER_GUIDE.md](USER_GUIDE.md)。
