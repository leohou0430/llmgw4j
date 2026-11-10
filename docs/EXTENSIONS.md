# 原生扩展与模块开发（v0.3.1）

本项目参考 Dubbo SPI 的命名映射、默认实现、惰性实例化和别名机制，使用项目内的 JDK 实现；**不直接使用 Dubbo，也不声称兼容其 SPI 协议**。基础模块不依赖 Spring、Boot、Reactor，HTTP 框架保留在 app、engine-webflux、engine-vt。

依据经 Context7 检索的 [Dubbo 扩展机制说明](https://github.com/apache/dubbo-website/blob/master/content/en/overview/mannual/java-sdk/reference-manual/architecture/dubbo-spi.md)。只借鉴本项目需要的发现原则；不复制 Dubbo IoC/AOP、排序激活、adaptive 代码生成、RPC 或注册中心。基础扩展加载本身只使用 JDK API。

## 模块规则

| 能力域 | api | 独立实现 child modules |
|---|---|---|
| engine | engine-api | webflux、vt |
| governance | governance-api | default、redis |
| provider | provider-api | http-jdk、openai、openai-sdk、anthropic |
| tokenizer | tokenizer-api | heuristic、jtokkit |

统一前缀 `llmgw-`，聚合目录兼作 parent，根 POM 只列聚合层；api 依赖 core，实现显式依赖本域 api，不依赖同域其他实现，不共享 api 的裸 Java 包。未来多实现能力遵循相同规则。app 是组合根，轻量 OpenAI 认证与 SDK 校验分包，默认 app 为管理校验功能仍链接 SDK。

## 命名扩展

使用项目注解 `core.extension.ExtensionPoint` 声明接口，可指定默认名称：

```java
@ExtensionPoint("first")
public interface Strategy { String name(); }
```

`src/main/resources/META-INF/llmgw/extensions/com.example.Strategy` 为 UTF-8 文本，每行 `别名=实现全名`，支持注释与逗号别名：

```properties
# 无参公共构造器；不会在发现目录时构造所有实现
first,alias=com.example.FirstStrategy
```

```java
var catalog = Extensions.load(Strategy.class);
var selected = catalog.getExtension("first");
var defaultStrategy = catalog.getDefaultExtension();
```

- 目录读取名称映射，不扫描 Java 包，不初始化全部类；首次选择实现时才加载类、检查接口和公共无参构造器并构造。
- 同一目录中同一实现类的多个别名复用一个实例；并发 lookup 只构造一次。不同目录实例不共享缓存，没有静态全局实例表。
- 默认使用当前线程 context ClassLoader，缺失时回退到接口 ClassLoader；也可显式 `Extensions.load(type, loader)`。插件 ClassLoader 仍由调用者管理。
- 重复名称（即使映射到同一类）、非法描述、未知名称、缺失默认、类型不符和构造失败均明确报错；错误保留资源位置或真实原因，不按 classpath 顺序悄悄覆盖。
- 扩展实现保持无状态。涉及资源和依赖的服务，由 ExtensionModule 在 ExtensionContext 中显式构造；加载器不做反射注入或生命周期猜测。

| 接口 | 内建名称 |
|---|---|
| `core.extension.ExtensionModule` | `in-memory`、可选 `redis`、`jdk-http` |
| `core.protocol.Protocol` | 11 个 ProtocolKind wire names |
| `provider.UpstreamAuthenticator` | `openai`（别名 openai-compatible/default）、`anthropic` |
| `provider.ProviderVerifier` | `openai` |
| `tokenizer.TokenCounter` | `heuristic`、可选 `jtokkit` |

发现、组装和认证方言缓存位于启动路径，请求热路径使用已解析对象。纯协议默认目录仍为进程级缓存，`Protocols.register` 是显式全进程覆盖，不承载租户账本；引擎指标身份也仍是进程级。

## 原生组装和生命周期

classpath 选择 core、所需 api、治理默认包、heuristic tokenizer、JDK HTTP 与认证包后：

```java
var properties = new GatewayProperties();
try (var runtime = new ExtensionContext()) {
    runtime.external(GatewayProperties.class, properties);
    runtime.loadModule("in-memory");
    // 如链接 Redis 并配置 properties.getRedis()，此处 loadModule("redis")。
    runtime.loadModule("jdk-http");
    runtime.seal();
    var governance = runtime.get(GovernanceChain.class);
    var upstream = runtime.get(UpstreamProvider.class);
}
```

原生治理入口执行普通 POJO 的 validate；app 在 Boot 配置绑定后也执行校验。ExtensionContext 保存显式 typed suppliers，按需创建，每个 context 独立持有状态与资源。循环/重复/缺失定义、已创建对象的替换均失败；seal 后不能修改绑定。

关闭时逆创建顺序关闭已创建的 AutoCloseable，每个对象只关一次；external 注册的绑定由调用者管理，不触发容器关闭；不要将同一外部资源再以普通 bind 注册为 owned 别名。app 只导出该原生图给 HTTP 控制器，不再扫描治理/provider/tokenizer 或读取它们的 Boot 自动配置。快照恢复在应用入口显式执行。

自定义 factory 是无参、无状态 ExtensionModule，实现放入独立 child module：

```java
public final class CompanyRules implements ExtensionModule {
    public void configure(ExtensionContext runtime) {
        runtime.replace(TokenLimiter.class,
            () -> new CompanyLimiter(runtime.get(GatewayProperties.class)));
    }
}
```

在 `META-INF/llmgw/extensions/io.github.leohou0430.llmgw.core.extension.ExtensionModule` 注册 `company-rules=com.example.CompanyRules`，运行时配置 `gateway.extension-modules: [company-rules]`。它在内建模块后、服务解析前执行；替换需显式，不能用重名 metadata 或 `@Primary` 代替。

## 上游、tokenizer 与 Redis

UpstreamProvider 公开 JDK Flow.Publisher、CompletableFuture 和阻塞接口。流为冷源，订阅独立管理需求、取消及 watchdog；正常结束不取消 transport，取消/超时传到真实 HTTP future/body subscription。Reactor 转换与异步心跳只在 WebFlux，VT 使用阻塞 JDK HTTP。

认证实现按 route.provider 选择；空 provider 是 OpenAI 默认方言，未知显式名字在 IO 前失败，即使没有配置密钥。认证实现不保存运行凭据。OpenAI SDK 仅用于显式校验/连通性路径。

`gateway.tokenizer=heuristic` 默认复用已解析估算；`-Pjtokkit` 链接后再显式选择 jtokkit，未知/未链接实现失败。所选 tokenizer 作用于支持的文本准入估算，原始 JSON 不变；上游 usage 优先，无 usage 输出仍为启发式估算。

`-Predis` 链接 Jedis 7 与实现包，默认包无 Redis 客户端。六段 Lua 保持原子预留/结算与模型桶语义；EVALSHA 失效回退 EVAL，缓存按 SCAN 页失效。池归 ExtensionContext 关闭，仅支持单节点连接池，未提供 Cluster/Sentinel 自动发现或财务硬预算。

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

app 边缘有限兼容旧 spring.data.redis 独立字段，新 gateway.redis 字段优先；旧 URL 形式须迁移。基础构造函数不再接收 Spring Data 类型。

## 迁移、AOT 和验证

| 原位置/方式 | 当前 |
|---|---|
| core.upstream 的 provider 契约 | provider-api 中 provider 包，Flow/Future |
| core.governance.TokenCounter | tokenizer-api 的 tokenizer 包 |
| app.JdkHttpUpstream | provider-http-jdk，AutoCloseable |
| provider.openai.OpenAiSdkVerifier | provider.openai.sdk.OpenAiSdkVerifier，优先依赖 ProviderVerifier |
| Boot 自动配置 / Component 覆盖基础实现 | ExtensionModule 显式构造及 replace |
| v0.3.0 中间实现的 Dubbo 注解/描述文件 | 项目 ExtensionPoint / META-INF/llmgw/extensions；移除 Dubbo 依赖 |

v0.3.0 tag 保留中间源码，其发布已取消；v0.3.1 才对应本说明。原生 AOT 只读取项目描述文件并登记公共构造器/配置类型，不需要 Dubbo 常量、scope bean 或 Fastjson2 元数据。Netty/JDK 25 的 shared arena 支持仍需保留，二进制按引擎构建并实际回归。

Maven Enforcer 禁止所有模块直接或间接引入 Dubbo，并阻止基础包引入 Spring/Reactor。模块/源码 guard 与新增负例约束后续实现。验证范围和逐文件清单见 [当前审查报告](verification/architecture/native-extension-mechanism/README.md)；不沿用中间实现或旧性能数字作为当前证据。
