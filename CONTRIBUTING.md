# 贡献指南

感谢你愿意参与 llmgw4j。这是一个**以可复现数据和协议保真为核心**的项目，
所以下面的规矩里，回归与压测证据比代码风格更重要。

## 1. 环境要求

| 组件 | 版本 | 用途 |
|---|---|---|
| JDK | **25** | 编译与运行（虚拟线程、`--release 25`） |
| Maven | 3.9+ | 多模块构建 |
| GraalVM | 25+ | 仅 native image 需要 |
| k6 | 任意近期版本 | 仅压测需要 |
| Redis | 6+ | 仅 `-Predis` 分布式状态实验需要 |

默认配置会启动内置 mock 上游（`127.0.0.1:9090`），**不需要任何真实厂商密钥**
就能跑通全部功能回归。真实上游仅在 `application-real.yml` profile 下使用，
密钥通过 `DASHSCOPE_API_KEY` 环境变量注入，永远不要写进文件。

## 2. 构建

```bash
./bin/build.sh                              # 默认 fat jar
mvn -B clean verify                        # 构建并执行 JUnit（CI 门槛）
mvn -B -Predis clean verify                # Redis profile 同样执行 JUnit
mvn -B -Predis,jtokkit,otel clean verify   # 组合 profile（CI 同样执行）
```

默认产物：`llmgw-app/target/llmgw-app-0.3.1.jar`。
`-Predis` 只在显式启用时才把 `llmgw-governance/llmgw-governance-redis` 链入 fat jar；
默认 jar 不含任何 Redis 依赖，这是刻意保持的边界。

### 模块布局规范（所有能力域强制）

**凡是有多实现的能力域，一律采用「聚合层 + 一个 api + 每个实现一个 module」。**
后续新增的实现、以及新增的能力域，都必须照此办理，不再允许在根目录平铺。

```
llmgw-<domain>/                      聚合 POM（packaging=pom），同时是子模块的 parent
├── pom.xml                         <modules> 只列本目录下的子模块
├── llmgw-<domain>-api/             公共契约，只依赖 llmgw-core
├── llmgw-<domain>-<impl-A>/        实现 A：依赖 api + core + 自己的技术栈
└── llmgw-<domain>-<impl-B>/        实现 B：同上，与 A 互不依赖
```

硬性规则（由 `./bin/check-module-layout.sh` 自动校验，CI 每次 PR 都跑）：

1. 聚合 POM 必须 `packaging=pom`，并列出 `<domain>-api`；根 `pom.xml` **只列聚合目录**，不列嵌套路径。
2. `api` 只依赖 `llmgw-core`，**不得依赖任何其他 `llmgw-*` 模块**，也不得引入具体实现的技术栈。
3. 每个实现模块以聚合 POM 为 parent，并显式依赖 `<domain>-api`。
4. **实现之间互不依赖**。可替换性靠命名 项目内命名 SPI module 与显式 `replace`，不靠互相引用。
5. 实现的 Java 包必须是 api 包的**子包**（如 `...llmgw.engine.webflux`、`...llmgw.governance.inmemory`），避免跨 jar split package。
6. 通用领域契约留 core；provider/tokenizer 等跨层契约放各自 api，按依赖方向消除反向环。基础模块不得依赖 Spring/Reactor，必须启用传递依赖检查。

现有四个能力域：

| 能力域 | api 契约 | 实现 |
|---|---|---|
| `llmgw-engine/` | `llmgw-engine-api` | `llmgw-engine-webflux`（Reactor Netty）、`llmgw-engine-vt`（Servlet/Tomcat） |
| `llmgw-governance/` | `llmgw-governance-api` | `llmgw-governance-default`（全内存）、`llmgw-governance-redis`（可选，`-Predis`） |
| `llmgw-provider/` | `llmgw-provider-api` | http-jdk、openai、openai-sdk、anthropic |
| `llmgw-tokenizer/` | `llmgw-tokenizer-api` | heuristic、jtokkit |

**新增一个实现**：在对应聚合目录下建模块 → parent 指向聚合 POM → 依赖本域 api →
复用 api 里已有的契约（engine 的 `SseChunkAccounting` / `EngineRoutes` /
`EngineTimings` / `EngineHeaders`；governance 的 `GovernanceLimits` / `QuotaMath` /
`UsageSummaries` / `GovernancePacks`）→ 把模块加进聚合 POM 的 `<modules>` 和根 POM 的
`dependencyManagement` → 跑 `./bin/check-module-layout.sh`。
engine 的配置类继承 `AbstractEngineConfiguration` 即可自动登记 `EngineKind`。
**协议契约不得在实现里另写一份**——两个引擎必须发出逐字节一致的错误帧与响应头。

**新增一个全新能力域**：按上面的目录模板建聚合 + api + 至少两个实现；校验脚本会
自动发现新域（根目录下一级、且 `packaging=pom` 的 `llmgw-*` 目录），无需改脚本。

Provider 与 tokenizer 均已采用聚合/api/实现模块，不能再恢复平铺例外。新增认证适配实现 `provider.UpstreamAuthenticator`，通过 `META-INF/llmgw/extensions` 注册唯一名称；有依赖的实现用 `ExtensionModule` 显式构造，不使用 Spring component。示例与旧 API 迁移见 [docs/EXTENSIONS.md](docs/EXTENSIONS.md)。

新增基础域 parent 必须设置 `llmgw.foundation.enforcer.skip=false`，其 jar 子模块继承规则。运行：

```sh
bash bin/check-module-layout.sh
python3 bin/check-native-boundaries.py
python3 -m unittest discover -s bin -p 'test_native_*.py'
mvn -B clean verify
mvn -B -Predis,jtokkit,otel clean verify
```

HTTP 回归可用 `LLMGW_TEST_JAR=/absolute/path/verified.jar` 固定被测产物，避免 Maven profile/AOT 重建污染。Redis 测试需独立测试 Redis 和 `LLMGW_TEST_REDIS_PORT`，不得清理用户已有实例。

## 3. 两个数据面

```bash
java -jar llmgw-app/target/llmgw-app-0.3.1.jar --spring.profiles.active=webflux
java -jar llmgw-app/target/llmgw-app-0.3.1.jar --spring.profiles.active=vt
```

同一套 `llmgw-governance/llmgw-governance-default` 治理层驱动两条数据面。
任何行为改动都要在两个 profile 上分别验证——它们不一致通常意味着 bug，而不是特性。

## 4. 回归脚本

| 脚本 | 断言 | 覆盖 |
|---|---|---|
| `./bin/check-module-layout.sh` | 71 | 聚合层/api/多实现布局、依赖方向、无 split package（CI 每次 PR 运行） |
| `./bin/smoke-stream-errors.sh` | JVM 67 / native 各 34 | 流式错误契约、静默截断、取消结算、fallback 边界、并发 SSE |
| `./bin/smoke-observability.sh` | 26 | `X-LLMGW-*` 响应头、TTFT/上游/开销指标、审计字段、metrics 无 NaN |
| `./bin/smoke-hot-reload.sh <engine>` | 各 10 | 在途 SSE 跨 reload 零中断、权重即时生效、非法配置 400、admin token |
| `./bin/smoke-redis-cluster.sh` | 7 | 双实例共享账本 vs 两个独立内存账本 |

**不要往 `smoke-stream-errors.sh` 里加断言**：它的 67/34 是历史基线，
新能力请写独立脚本，以保持跨版本可比。

微基准（`./bin/run-parse-microbench.sh` / `./bin/run-buffer-microbench.sh`）
会在 `bench/micro/` 下自行生成 `.classpath` / `.lib` / `.classes`，这些都是
本机产物、已被忽略。其中 `run-buffer-microbench.sh` 默认从
`bench/results/v8.1-final.jar` 提取依赖，而 `bench/results/` 同样被忽略，
因此新克隆的仓库需先自行准备对照 jar，或用
`CONTROL_JAR=/path/to.jar ./bin/run-buffer-microbench.sh` 显式指定。

## 5. 代码约定

- 核心只依赖 SPI：`Router`、`UpstreamProvider`、`TokenLimiter`、`UsageLedger`、
  `CircuitBreaker`、`SemanticCache`、`ApiKeyRegistry`、`AuditSink`、
  `SnapshotStore`、`RouteConfigSource`。默认实现由
  ExtensionModule 显式构造，核心不得 import 实现包；Spring/Reactor 仅在 HTTP 边缘。
- 热路径避免额外分配：不做重复 JSON 解析、不拼字符串、不查 registry；
  计时用 `System.nanoTime()` + 原子时间戳，格式化与指标上报放治理虚拟线程旁路。
- 响应头只能在提交前设置。WebFlux 的 `writeWith` 会在订阅 body 前提交头，
  所以 SSE 只能给「提交前可算的量」，完整 TTFT 走指标。
- 新增 AOT 可达类型（Jackson 绑定的 record 等）必须同步
  `GatewayRuntimeHints`，否则 native 下 `/admin/*` 会 500。
- 不使用 Lombok；Jackson 3 的注解包名是 `com.fasterxml.jackson.annotation`，
  databind 是 `tools.jackson.*`。

## 6. 性能数字规矩（重要）

引用或提交任何性能结论前必须确认：

1. 有 **15s 粒度的宿主负载时间线**（`sysctl -n vm.loadavg`）。
   历史实验多次撞上 loadavg 80–110，那些数字只作方向参考，**不得作为结论**。
2. 有**逐轮原始 CSV**，并逐轮检查配对差值的符号是否一致。
   方向来回翻转 → 判为噪声，不要比较中位数。
3. `MOCK_DELAY_MS=0 / MOCK_CHUNK_INTERVAL_MS=0` 的 wire 实验与 `20/10`
   主矩阵是两个独立口径，**绝不能逐行相减**。
4. 两臂除被测变量外构建一致，并保留 jar SHA-256、manifest、CSV、日志。

详见 `bench/README.md` 与根目录 `README.md` 5.10 节。性能类 issue 请用
「性能 / 压测数据」模板。

## 7. 提交与 PR

- 提交信息建议 Conventional Commits：`fix(sse): ...`、`feat(routing): ...`、
  `bench: ...`、`docs: ...`。
- PR 请填满模板里的协议契约、双引擎对称与回归结果三张清单。
- 涉及 `README.md` 的架构/口径改动，同步检查 `CHANGELOG.md`。

## 8. 许可证

本项目采用 **Apache License 2.0**。提交 PR 即表示你同意你的贡献按同一许可证发布，
并确认你有权提交这些代码。
