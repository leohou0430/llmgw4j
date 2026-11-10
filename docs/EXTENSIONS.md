# 原生扩展与模块开发（v0.3.0）

本说明适用于 v0.3.0；v0.2.0 的旧扩展 API 以对应 tag 为准。基础模块不依赖 Spring、Spring Boot 或 Reactor；HTTP 框架仅保留在 app、engine-webflux、engine-vt。基础仍按职责使用 Dubbo common、Jackson、Caffeine、Micrometer、Jedis 等库，不宣称零外部依赖或整个应用脱离 Boot。

## 模块结构

| 能力域 | api | 实现 child modules |
|---|---|---|
| engine | engine-api | webflux、vt |
| governance | governance-api | default、redis |
| provider | provider-api | http-jdk、openai、openai-sdk、anthropic |
| tokenizer | tokenizer-api | heuristic、jtokkit |

表中前缀统一为 `llmgw-`。聚合目录兼作 parent，根 POM 只列聚合层；api 依赖 core，实现显式依赖本域 api，同域实现之间无依赖且不共享 api 的裸 Java 包。跨域只依赖所需 api；app 是可选择多个实现包的组合根。OpenAI 原生 Bearer 认证与大 SDK 的验证/连通性能力已分包，使用认证适配器不必引入 SDK；默认 app 为保持既有管理功能仍链接 SDK 验证包。

## 实际采用的 Dubbo SPI

锁定并验证 `org.apache.dubbo:dubbo-common:3.3.6`，不引入 RPC、registry 或 Spring starter。接口使用 `@SPI`，实现通过 `META-INF/dubbo/<接口全名>` 的 `名字=实现类` 映射加载：

| 扩展接口 | 内建名称 |
|---|---|
| `core.extension.ExtensionModule` | `in-memory`、`redis`、`jdk-http` |
| `core.protocol.Protocol` | 11 个 `ProtocolKind.wireName()` |
| `provider.UpstreamAuthenticator` | `openai`（别名 `openai-compatible`、`default`）、`anthropic` |
| `provider.ProviderVerifier` | `openai` |
| `tokenizer.TokenCounter` | `heuristic`、可选 `jtokkit` |

无依赖的策略直接由真实 `ExtensionLoader` 创建；有构造依赖的治理/客户端通过 `ExtensionModule` 显式组装，不用 setter 注入或 Spring bean 优先级。扩展查找、组装在启动时完成；请求热路径持有已解析的对象。

依据：[Dubbo SPI 官方说明](https://github.com/apache/dubbo-website/blob/master/content/en/overview/mannual/java-sdk/reference-manual/architecture/dubbo-spi.md)，经 Context7 检索并以实际依赖/测试验证；不是自写 ServiceLoader 仿制品。

## 无 Spring 的组合入口

在 classpath 上选择 core、所需 api、治理默认包、heuristic tokenizer、JDK HTTP 与认证实现包后：

```java
var properties = new GatewayProperties(); // 默认是本地演示配置；生产需设置凭据并校验
try (var runtime = new ExtensionContext()) {
    runtime.external(GatewayProperties.class, properties);
    runtime.loadModule("in-memory");
    // 如需 Redis，先配置 properties.getRedis()，并在 classpath 上加入 Redis 包：
    // runtime.loadModule("redis");
    runtime.loadModule("jdk-http");
    runtime.seal();
    var governance = runtime.get(GovernanceChain.class);
    var upstream = runtime.get(UpstreamProvider.class);
}
```

`ExtensionContext` 是按类型保存显式 Java 构造函数的实例级容器，不扫描字段/构造参数，不自动发现服务 bean。其 Dubbo factory scope 独立，治理、限流、账本、HTTP/Redis 资源由该实例持有。创建对象延迟到首次 `get`，同一类型复用同一实例；循环、重复、缺失、已创建对象的替换均明确报错。

`seal` 后不能再改定义。关闭时按创建逆序释放已创建的 AutoCloseable，重复关闭无效；`external` 提供的对象仍由调用者负责关闭。app 只是将这张原生图导出给 HTTP 控制器，不再扫描治理/provider/tokenizer 实现或读取它们的 Spring 自动配置文件。启动快照恢复在应用入口显式执行。

协议目录和无状态 Dubbo 策略有进程级缓存，不承载租户/账本资源；兼容的 `Protocols.register` 是启动时显式的全进程覆盖，不支持同进程不同应用各自覆盖协议行为。引擎指标身份也仍是进程级，不能据此承诺同 JVM 中多个异构引擎标签隔离。

## 替换一个治理实现

自定义实现放入独立 child module，依赖能力域 api 和所需其他 api。定义一个无参、命名的模块 factory：

```java
public final class CompanyRules implements ExtensionModule {
    public void configure(ExtensionContext runtime) {
        runtime.replace(TokenLimiter.class, () -> new CompanyLimiter(
            runtime.get(GatewayProperties.class)));
    }
}
```

`src/main/resources/META-INF/dubbo/io.github.leohou0430.llmgw.core.extension.ExtensionModule`：

```properties
company-rules=com.example.CompanyRules
```

app 配置：

```yaml
gateway:
  extension-modules: [company-rules]
```

这些模块在内建治理、可选 Redis、JDK transport 注册后、任何服务解析前加载。也可在原生入口直接调用 `loadModule("company-rules")`。替换必须显式，未知名字不能悄悄降级；不要用与内建扩展重名的资源条目模拟覆盖。旧 `@Component` / `@Primary` / `@ConditionalOnMissingBean` 扩展方式不再适用于基础能力。

## Provider 和上游契约

认证适配器实现 `provider.UpstreamAuthenticator` 并注册唯一名称；route 的 `provider` 选择该名称。空 provider 采用 OpenAI 默认方言，未知显式 provider 在网络 I/O 前失败，即使未配置密钥也不会默默变为其他方言。认证实现必须无状态，密钥从本次请求参数获取。

`provider.UpstreamProvider` 公开 `Flow.Publisher<String>`、`CompletableFuture<String/byte[]>` 及阻塞接口。流 publisher 为冷源，每次订阅独立管理需求、取消和 watchdog；正常完成不取消 transport，避免破坏复用连接。Future 操作在调用时启动，WebFlux 使用 supplier 延迟调用，避免缓存命中/未订阅时发请求。取消会传播到真实 HTTP future。Reactor 转换与异步心跳只在 WebFlux 适配层，VT 仍使用阻塞 JDK HTTP/ChunkSink 心跳。

## Tokenizer

默认 `gateway.tokenizer=heuristic` 复用已解析的估算，避免重复扫描。`-Pjtokkit` 加入实现包后，显式 `gateway.tokenizer=jtokkit` 才启用。选择未链接的实现会失败；不再把配置忽略后偷偷回到 heuristic。

真实 tokenizer 用于文本准入的 prompt 估算，并将结果带入治理决策，原始 JSON 不改变。上游 authoritative usage 仍优先；没有 usage 的 completion 回退仍是现有启发式，不承诺所有厂商或流式输出都实现精确 BPE 计费。media 单位计量和无请求体轮询保持原语义。

## Redis 原生客户端与配置

`-Predis` 链接 Jedis 7.0.0 与 Redis 实现包，默认包不含 Redis 客户端。六段原有 Lua 正文保持一致；EVALSHA 缓存失效时回退 EVAL，配额预留/结算、模型桶容量/退款仍是原子操作。连接池、凭据、数据库、TLS 和超时从普通 POJO 读取，池由 ExtensionContext 关闭。客户端为独立 Redis 服务/单节点连接池，不宣称提供 Redis Cluster/Sentinel 自动发现。

```yaml
gateway:
  redis:
    enabled: true
    host: redis
    port: 6379
    database: 0
    username: ""
    password: ${REDIS_PASSWORD:}
    ssl: false
    timeout-ms: 2000
    pool-size: 64
```

app 边缘兼容 `spring.data.redis.host/port/database/username/password/ssl.enabled/timeout`，原生模块不引用这些 Spring 配置名。新 `gateway.redis.*` 优先；旧 URL 配置需要迁移为独立字段并会明确报错。基础库不再接收 `StringRedisTemplate`，不能把 Spring Data 类型传给构造函数。缓存失效按 SCAN 页删除，保留其他资源命名空间。

## 迁移和检查

| 原契约/实现 | 新位置/方式 |
|---|---|
| `core.upstream.UpstreamProvider` | `provider.UpstreamProvider`，Flow/Future |
| `core.upstream.UpstreamAuthenticator` | `provider.UpstreamAuthenticator`，Dubbo 命名发现 |
| `core.governance.TokenCounter` | `tokenizer.TokenCounter` |
| `app.JdkHttpUpstream` | `provider.http.jdk.JdkHttpUpstream`，AutoCloseable |
| `provider.openai.OpenAiSdkVerifier` | `provider.openai.sdk.OpenAiSdkVerifier`，调用方优先使用 ProviderVerifier |
| core 的 Spring 配置注解/InitializingBean | 普通 GatewayProperties.validate，绑定留在 app |
| ProtocolRegistrar / 两个治理 AutoConfiguration | Dubbo 描述文件、ExtensionModule 和显式构造 |

`bin/check-module-layout.sh` 校验域结构，`bin/check-native-boundaries.py` 校验全部基础源码、发现资源和多实现平铺，Maven Enforcer 校验直接及传递 Spring/Reactor 依赖。后续新增能力同样受规则约束；见根 [AGENTS.md](../AGENTS.md)。

CI 包括规则的正/负测试、默认/Redis/组合 profile、双引擎契约及身份/资源隔离。AOT 按 classpath 中的 Dubbo 描述文件登记扩展和配置反射元数据；Dubbo 内部常量字段和 scope bean 构造器另以经过 agent 采集的 `META-INF/native-image/llmgw/dubbo-runtime` 元数据补齐。升级 Dubbo 后需重新采集并验证，不能只更新依赖版本。Netty/JDK 25 的 shared arena 构建支持已放在独立 native-image.properties，避免被 Boot AOT 默认文件覆盖。native 仍须按引擎编译，并运行实际二进制回归，不能只凭生成 hints 宣称 native 可用。当前验证及逐文件审查记录见 [架构审查](verification/architecture/native-spi/README.md)。v0.3.0 发布不沿用旧性能数字；tag 对应源码与资产由发布工作流重新验证。
