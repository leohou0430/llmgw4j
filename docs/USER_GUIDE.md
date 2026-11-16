# llmgw4j 使用指南（v0.4.0）

JDK 25 的 LLM HTTP 网关。一个 JVM JAR 可选择 WebFlux/Netty 或 Servlet/虚拟线程两种引擎；原生基础模块通过 项目内命名 SPI 组装，共用 JDK HttpClient 转发与治理逻辑。它提供协议透传和可替换治理能力，完整端点以 [OpenAPI](openapi.yaml) 为准。

从 v0.2.0 升级的 Java 扩展作者须先阅读 [扩展与迁移说明](EXTENSIONS.md)。HTTP 入口仍使用 Spring Boot，基础模块去框架依赖不等于整个应用无框架。

## 本地开始

需要 JDK 25、Maven 3.9+；curl、jq、Python 3 用于回归。Native 编译另需 GraalVM 25，Redis 可选。

```bash
mvn -B clean verify
java -jar llmgw-app/target/llmgw-app-0.4.0.jar \
  --spring.profiles.active=webflux --server.port=8080
```

默认同时启动 `9090` 的本地 mock，无需供应商密钥。默认客户端 key 是 `sk-demo`，legacy管理凭据为空且旧`/admin`为本地演示开放模式，仅适合回环地址演示。演示配置不可直接部署到公网。

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
java -jar llmgw-app/target/llmgw-app-0.4.0.jar \
  --spring.profiles.active=webflux \
  --gateway.mock.enabled=false \
  --gateway.upstream.type=openai-compatible \
  --gateway.upstream.base-url=http://127.0.0.1:8000/v1 \
  '--gateway.routes[0].target-url=http://127.0.0.1:8000/v1'
```

随仓库配置可从 `DASHSCOPE_API_KEY` 注入 OpenAI 兼容上游密钥。不要把真实密钥写入提交、命令记录或回归证据。route 的 `provider` 选择认证方言：OpenAI Bearer 或 Anthropic；这不代表自动转换请求/响应协议。显式未知 provider 在网络 IO 前失败。

生产配置还须设置 `gateway.production=true`、关闭 mock、替换整个 demo key/路由列表、设置非空管理凭据。完整示例见 [部署指南](DEPLOYMENT.md)和 [生产最佳实践](PRODUCTION_BEST_PRACTICES.md)。

## 官方 Python SDK 接入

v0.4.0已按 OpenAI Python 3.49.0 / Anthropic Python 2.8.0 验证双引擎，详细矩阵和固定依赖见 [兼容性审查](verification/compatibility/2026-11-11/README.md)。OpenAI 的 `base_url` 包含 `/v1`，Anthropic 的 `base_url` 指向网关根地址；API key 为网关客户端 key，route 另设真实上游凭据。Messages 路由须选择 `provider: anthropic`。

```python
from openai import OpenAI
from anthropic import Anthropic

with OpenAI(api_key="sk-demo", base_url="http://127.0.0.1:8080/v1", max_retries=0) as client:
    reply = client.chat.completions.create(
        model="qwen-plus", messages=[{"role": "user", "content": "你好"}])
    print(reply.choices[0].message.content)

# 配置对应的 Messages 路由后使用；生产用自己的网关 key。
with Anthropic(api_key="sk-demo", base_url="http://127.0.0.1:8080", max_retries=0) as client:
    reply = client.messages.create(
        model="your-anthropic-model", max_tokens=64,
        messages=[{"role": "user", "content": "你好"}])
```

JSON 模型别名、工具调用及扩展字段可保留；multipart 内的模型和批任务输入 JSONL 不自动改写。上游错误在首 chunk 前归一为网关 502，会影响 SDK 默认重试与错误类型；示例显式关闭客户端重试，避免与网关策略叠加。文件/批任务列表返回本项目持久目录，支持分页；供应商SDK的其他方法不表示全部已实现，按OpenAPI和实际合同选择。

## 配置与治理

配置类为普通 `GatewayProperties`，HTTP app 使用 Boot 绑定后调用校验。常用优先顺序为 Java 默认值、配置文件、环境变量/JVM 属性、命令行；profile 配置覆盖基线。具体默认值和外部配置方式见 [配置说明](CONFIGURATION.md)。

| 能力 | 使用与边界 |
|---|---|
| 路由 | 模型/协议匹配、权重、熔断、条件重试与 fallback；结果未知的付费操作不能盲目重发 |
| 超时与流 | connect、首字节、空闲读、整体超时；可选 SSE 心跳；首 chunk 前以 HTTP 错误拒绝（上游错误归一 502），之后以流错误结束，不拼接 fallback |
| token 治理 | key token 桶、共享模型桶、配额预留与结算；上游 usage 优先，估算不构成硬费用上界 |
| 缓存 | 精确缓存按租户/协议/模型/参数隔离；语义检索仅针对支持的文本子集 |
| 内容治理 | 请求前置审核；强制审核依赖不可用返回 503；不包含完整响应/SSE PII 改写 |
| header | `gateway.headers.upstream-allow/client-allow` 控制透传；认证与 transport header 受保护；原始 multipart 的 Content-Type 保留 boundary |
| 限制 | 默认请求/非流式响应各 4 MiB、并发 64；拒绝超限请求，文件上传使用有界暂存，受支持的文件下载按流转发；其他非流式响应受大小限制 |
| 身份 | 静态 key、运行时 key 生命周期、可选 HS256 JWT issuer/audience 校验；内建账号与项目RBAC；无OIDC/JWKS |

### 管理接口

推荐使用`/control/`控制台：首次设置`LLMGW_ADMIN_USERNAME`和`LLMGW_ADMIN_PASSWORD`，供应商凭据与来源快照另设`LLMGW_ROOT_KEY`。建立租户/项目、设置项目额度、创建key，再实际调用、查用量、轮换/吊销。完整步骤见[控制面操作](control-plane-operations.md)。

| 入口 | 当前用途 |
|---|---|
| `/control/session`、`/control/login`、`/control/logout` | 会话、CSRF恢复及登录限速 |
| `/control/tenants`、`/control/projects`、`/control/accounts` | 组织和分级角色授权 |
| `/control/keys`、`/control/quotas` | 持久key、项目总额度/key子额度与未知占额核对 |
| `/control/routes`、`/control/routes/publish`、`/control/routes/rollback` | 平台管理员发布/回滚全局结构化路由，使用`expectedVersion` |
| `/control/secrets`、`/control/catalog`、`/control/usage`、`/control/audit` | 加密供应商凭据、项目目录、用量与审计 |

生产仍须配置非空`LLMGW_ADMIN_TOKEN`通过启动校验，使旧`/admin`退出无凭据本地演示模式；旧token访问默认禁用，迁移需显式`gateway.admin-token-mode=migration`和受限来源。`/admin/routes/reload`、`/admin/snapshot/load`不能绕过SQLite权威状态，返回409。`/admin/governance`等遗留操作仅在明确迁移模式下使用，完整契约见OpenAPI。

用量和密钥重启直接恢复SQLite；保持`gateway.governance.snapshot-auto-restore=false`，禁止JSON覆盖累计用量或在途预留。路由通过控制面更新，不打断在途SSE；首次引导后的启动路由/配置key修改不会覆盖已发布状态。

### 可选 Redis

```bash
mvn -B -Predis clean verify
java -jar llmgw-app/target/llmgw-app-0.4.0.jar \
  --spring.profiles.active=webflux \
  --gateway.redis.enabled=true \
  --gateway.redis.host=127.0.0.1 --gateway.redis.port=6379
```

构建profile链接实现，运行配置负责启用，两者均需要。Redis提供限流、熔断、缓存等治理；当前应用的key、累计token账本及已发布路由仍以本实例SQLite为权威。仅支持已配置的单节点连接池，不承诺Cluster/Sentinel自动发现或多网关副本共享数据库。金额预算仍是进程级实验，不提供财务保证。

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
- 404/409：未知或跨项目资源404；旧路由替换/快照覆盖及发布版本冲突409。
- 429/503：检查 quota、模型桶、全生命周期并发闸门和强制审核依赖；不要通过关闭保护掩盖容量不足。
- 未知扩展：确认模块在 classpath、SPI 名字正确、资源描述文件已打包；基础实现不再由 Spring component 扫描。
- 当前工作区 URI 缓存上限为 1024 项，满后新路径不再保留。协议注册覆盖和引擎指标身份仍为进程级。

v0.4.0的2C2G上传场景见[本轮验证](reviews/2026-11-16-remediation/README.md)，不作为通用吞吐A/B。旧性能数字属于证据内冻结的旧产物；本地 mock 契约检查不能代表公网 TLS、真实供应商或远程治理的延迟和生产容量。见 [性能说明](PERFORMANCE.md)及 [安全策略](../SECURITY.md)。
