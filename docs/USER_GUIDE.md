# llmgw4j 使用指南（v0.3.1）

JDK 25 的 LLM HTTP 网关。一个 JVM JAR 可选择 WebFlux/Netty 或 Servlet/虚拟线程两种引擎；原生基础模块通过 项目内命名 SPI 组装，共用 JDK HttpClient 转发与治理逻辑。它提供协议透传和可替换治理能力，完整端点以 [OpenAPI](openapi.yaml) 为准。

从 v0.2.0 升级的 Java 扩展作者须先阅读 [扩展与迁移说明](EXTENSIONS.md)。HTTP 入口仍使用 Spring Boot，基础模块去框架依赖不等于整个应用无框架。

## 本地开始

需要 JDK 25、Maven 3.9+；curl、jq、Python 3 用于回归。Native 编译另需 GraalVM 25，Redis 可选。

```bash
mvn -B clean verify
java -jar llmgw-app/target/llmgw-app-0.3.1.jar \
  --spring.profiles.active=webflux --server.port=8080
```

默认同时启动 `9090` 的本地 mock，无需供应商密钥。默认客户端 key 是 `sk-demo`，管理凭据为空，仅适合本地演示。演示配置不可直接部署到公网。

```bash
curl -s http://127.0.0.1:8080/actuator/health
curl -s http://127.0.0.1:8080/v1/chat/completions \
  -H 'Authorization: Bearer sk-demo' -H 'Content-Type: application/json' \
  -d '{"model":"qwen-plus","messages":[{"role":"user","content":"你好"}],"stream":false}'
curl -N http://127.0.0.1:8080/v1/chat/completions \
  -H 'Authorization: Bearer sk-demo' -H 'Content-Type: application/json' \
  -d '{"model":"qwen-plus","messages":[{"role":"user","content":"你好"}],"stream":true}'
```

切换为 `--spring.profiles.active=vt` 使用虚拟线程引擎。每个进程只启用一个引擎。`bin/run-webflux.sh`、`bin/run-vt.sh` 是相应启动入口。

## 接入真实上游

关闭 mock，替换上游及路由基址，并通过环境或密钥系统注入凭据：

```bash
java -jar llmgw-app/target/llmgw-app-0.3.1.jar \
  --spring.profiles.active=webflux \
  --gateway.mock.enabled=false \
  --gateway.upstream.type=openai-compatible \
  --gateway.upstream.base-url=http://127.0.0.1:8000/v1 \
  '--gateway.routes[0].target-url=http://127.0.0.1:8000/v1'
```

随仓库配置可从 `DASHSCOPE_API_KEY` 注入 OpenAI 兼容上游密钥。不要把真实密钥写入提交、命令记录或回归证据。route 的 `provider` 选择认证方言：OpenAI Bearer 或 Anthropic；这不代表自动转换请求/响应协议。显式未知 provider 在网络 IO 前失败。

生产配置还须设置 `gateway.production=true`、关闭 mock、替换整个 demo key/路由列表、设置非空管理凭据。完整示例见 [部署指南](DEPLOYMENT.md)和 [生产最佳实践](PRODUCTION_BEST_PRACTICES.md)。

## 配置与治理

配置类为普通 `GatewayProperties`，HTTP app 使用 Boot 绑定后调用校验。常用优先顺序为 Java 默认值、配置文件、环境变量/JVM 属性、命令行；profile 配置覆盖基线。具体默认值和外部配置方式见 [配置说明](CONFIGURATION.md)。

| 能力 | 使用与边界 |
|---|---|
| 路由 | 模型/协议匹配、权重、熔断、条件重试与 fallback；结果未知的付费操作不能盲目重发 |
| 超时与流 | connect、首字节、空闲读、整体超时；可选 SSE 心跳；首 chunk 前保留真实错误状态，之后以流错误结束，不拼接 fallback |
| token 治理 | key token 桶、共享模型桶、配额预留与结算；上游 usage 优先，估算不构成硬费用上界 |
| 缓存 | 精确缓存按租户/协议/模型/参数隔离；语义检索仅针对支持的文本子集 |
| 内容治理 | 请求前置审核；强制审核依赖不可用返回 503；不包含完整响应/SSE PII 改写 |
| header | `gateway.headers.upstream-allow/client-allow` 控制透传；认证与 transport header 始终受保护 |
| 限制 | 默认请求/非流式响应各 4 MiB、并发 64；拒绝超限请求，上传/下载仍整包缓冲 |
| 身份 | 静态 key、运行时 key 生命周期、可选 HS256 JWT issuer/audience 校验；无 OIDC/JWKS/RBAC |

### 管理接口

共享环境必须设置 `LLMGW_ADMIN_TOKEN`。完整管理请求使用 `X-Admin-Token`；可另设不同的只读 token，通过 `X-Admin-Read-Token` 仅用于 `/admin/audit/export` 审计导出。只读 token 不授权写入。

| 入口 | 用途 |
|---|---|
| `GET /admin/routes`、`POST /admin/routes/reload` | 读取及原子替换路由表；返回配置版本 |
| `POST /admin/governance` | 更新支持的 rate/burst/cache/circuit 治理参数，详见 OpenAPI |
| `/admin/keys` 及 key 子资源 | 创建、轮换、吊销；默认 JSON 存储为单进程，Redis 后端运行时 CRUD 尚返回 501 |
| `GET /admin/snapshot`、`POST /admin/snapshot/save`、`POST /admin/snapshot/load` | 用量快照与保存/恢复；load 是替换语义，建议停流量后操作 |
| `GET /admin/requests`、`GET /admin/audit/export`、`GET /admin/metrics` | 请求、审计和治理观测；内建记录不等于持久化合规审计 |
| `GET /admin/verify` | 显式 OpenAI SDK 连通性检查；真实凭据下可能访问/消耗上游 |

默认关闭启动快照恢复；可显式设置 `gateway.governance.snapshot-auto-restore=true`。用量文件与密钥文件用途不同，不应在多实例间共享 JSON 文件来替代分布式存储。

路由热替换不打断在途 SSE；其他配置只有明确支持的管理接口可热更新。profile、凭据绑定、客户端池与服务器端口等需要重启。回归入口为 `smoke-hot-reload.sh <webflux|vt>`。

### 可选 Redis

```bash
mvn -B -Predis clean verify
java -jar llmgw-app/target/llmgw-app-0.3.1.jar \
  --spring.profiles.active=webflux \
  --gateway.redis.enabled=true \
  --gateway.redis.host=127.0.0.1 --gateway.redis.port=6379
```

构建 profile 链接实现，运行配置负责启用，两者均需要。Jedis 连接池归当前原生上下文持有；共享 limiter、usage、熔断、key 配置、响应缓存和任务 sticky。金额预算、快照、请求日志等仍为本地实现，不支持财务级硬预算或完整分布式控制面。

优先使用 `gateway.redis.*`；旧 `spring.data.redis.*` 部分独立字段仅在 app 边缘兼容，URL 形式必须迁移。仅支持单节点连接池，不承诺 Cluster/Sentinel 自动发现。

### 可选 tokenizer 与 tracing

`mvn -B -Pjtokkit clean verify` 链接 jtokkit，运行时再设 `gateway.tokenizer=jtokkit`。默认 `heuristic`，未知或缺失插件会失败。真实 tokenizer 用于支持的文本准入估算，上游 usage 优先，无 usage 的输出仍为启发式。

`-Potel` 链接 tracing bridge 和 OTLP exporter；配置采集端点后使用。无需此 profile 也可按 header 白名单透传 trace 上下文。指标/审计依赖实际部署的采集与保留方案。

## 模块与自定义扩展

| 能力域 | 组成 |
|---|---|
| `llmgw-engine/` | API、WebFlux、VT |
| `llmgw-governance/` | API、default、Redis |
| `llmgw-provider/` | API、JDK HTTP、OpenAI 认证、OpenAI SDK 验证、Anthropic 认证 |
| `llmgw-tokenizer/` | API、heuristic、jtokkit |

core 与能力基础模块不依赖 Spring/Reactor；app 和 HTTP 引擎负责框架适配。命名 项目内命名 SPI 使用 `@ExtensionPoint`、`META-INF/llmgw/extensions/<接口>`、Extensions；有依赖服务由 ExtensionModule 显式构造。ExtensionContext 按实例管理状态和资源，seal 前可显式替换绑定，关闭时逆序释放。详见 [扩展开发](EXTENSIONS.md)和根 [AGENTS.md](../AGENTS.md)。

## 构建、Native 与验证

```bash
bash bin/check-module-layout.sh
python3 bin/check-native-boundaries.py
python3 -m unittest discover -s bin -p 'test_native_*.py'
mvn -B clean verify
bash bin/smoke-stream-errors.sh
bash bin/smoke-multi-protocol.sh
```

JUnit、真实 Redis、隔离/鉴权、双引擎协议与流契约由 CI 执行。Redis JUnit 使用 `LLMGW_TEST_REDIS_PORT` 指向测试实例；没有实例时相应测试会跳过，不能称为完成真实 Redis 验证。完整结果见 [架构验证](verification/architecture/native-extension-mechanism/README.md)。

Native 每引擎独立编译，Spring AOT 在构建期选择 profile：

```bash
JAVA_HOME=/path/to/graalvm-25 bash bin/build-native.sh webflux
# 或显式命名，避免覆盖已有 stable binary
mvn -B clean package -Pnative -DskipTests \
  -Dllmgw.native.profile=webflux -Dllmgw.native.image=llmgw-webflux
BINARY="$PWD/llmgw-app/target/llmgw-webflux" bash bin/smoke-stream-errors.sh webflux
```

G1 为 Oracle GraalVM 的 opt-in 实验 profile `native-g1`，默认 native 使用 Serial GC；按目标平台验证，不用历史 macOS 数字预测 Linux 容量。

## 排错与当前限制

- 端口冲突：检查 `server.port` 与 mock `gateway.mock.port`；接真实上游时关闭 mock。
- 401：区分客户端 key/JWT 与上游凭据；header 白名单不会绕过认证保护。
- 404/501：未知/非本租户文件或任务返回 404；文件/批任务列表与 Redis key 管理等未实现入口返回 501。
- 429/503：检查 quota、模型桶、全生命周期并发闸门和强制审核依赖；不要通过关闭保护掩盖容量不足。
- 未知扩展：确认模块在 classpath、SPI 名字正确、资源描述文件已打包；基础实现不再由 Spring component 扫描。
- URI 缓存仍无容量上限；动态路径高基数需要长期堆验证。协议注册覆盖和引擎指标身份仍为进程级。

v0.3.1 未重新压测。旧性能数字属于证据内冻结的旧产物；本地 mock 契约检查不能代表公网 TLS、真实供应商或远程治理的延迟和生产容量。见 [性能说明](PERFORMANCE.md)及 [安全策略](../SECURITY.md)。
