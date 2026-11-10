> 历史中间实现证据：此目录对应直接依赖 Dubbo 的 cf1edea/v0.3.0；发布已取消。当前原生 SPI 验证见 [新报告](../native-extension-mechanism/README.md)，不得将旧结果当作新实现证据。

# v0.3.0 原生基础与 Dubbo SPI 验证

审查基线为已发布的 `c119ecf` / v0.2.0；当前源码采用统一 provider/tokenizer 多模块、原生配置/治理/资源生命周期、真实命名 Dubbo SPI、JDK Flow/Future 及 Jedis。API 迁移见 [EXTENSIONS](../../../EXTENSIONS.md)。这里只记录实际执行结果，不将旧性能数字作为重构性能证据。

## 源码审查范围

[基线清单](../2026-11-10-java-inventory.json)记录基线 140 个自有 Java 文件；[当前逐文件清单](current-java-inventory.json)记录 159 个文件（main、test、bench），含行数、源文件 SHA256 和框架 import。覆盖方式是全源码依赖/装配/生命周期检查，加重点变更路径代码审查与行为套件；不是逐业务分支无缺陷的保证。

- 重点检查 core 配置校验与协议发现、ExtensionContext 绑定/替换/密封/逆序关闭、治理依赖图的实例隔离和实际替换、HTTP/Redis 客户端资源归属。
- 检查 Flow 冷源、背压、终止/取消竞态、首字节/空闲超时和 Future 取消传播；正常结束不能破坏同连接其他请求。
- 检查默认 tokenizer 不重复扫描、jtokkit 显式选择、SDK 从认证包分离、未知扩展失败路径。
- 六段 Redis Lua 正文与基线一致；客户端替换后保留原子预留/结算、模型桶退款，增加实际 Redis 共享实例与 SCAN 失效验证。
- app 只做配置绑定和 HTTP 桥接；基础库没有 Spring/Reactor/Jakarta DI 依赖。Maven Enforcer 检查传递依赖，源码检查防止重新引入扫描装配与平铺实现。

## JVM 与契约证据

第一批冻结构建为 v0.2.0 版本元数据、当前重构生产代码，见 [build-results.json](build-results.json)：default、Redis、combined 各 139 项 JUnit，零失败/错误/跳过；Redis 使用任务独立实例。随后增加三项 app 绑定/命名模块/兼容配置桥接测试，v0.3.0 发布构建结果单独记录，避免混淆计数。

第一批 HTTP 契约均针对 manifest 中冻结 JAR：

| 套件 | 实际结果 | 日志 |
|---|---|---|
| 流式错误/计量/并发 | 67 通过，双引擎 | [stream](smoke-stream.log) |
| 扩展 | 54 通过，双引擎 | [extensions](smoke-extensions.log) |
| 加固 | 46 通过，双引擎 | [hardening](smoke-hardening.log) |
| 多协议（修正旧断言后） | 56 通过，双引擎 | [protocols](smoke-protocols-corrected.log) |
| provider | 24 通过，双引擎 | [provider](smoke-provider.log) |
| 平台（修正旧脚本后） | 48 通过，双引擎 | [platform](smoke-platform-corrected.log) |
| 租户隔离 | 双引擎通过 | [tenant](smoke-tenant.log) |
| 管理 / JWT | 双引擎通过 | [admin](smoke-admin.log)、[JWT](smoke-jwt.log) |
| 可观测性 | 30 通过，双引擎 | [observability](smoke-observability.log) |
| 路由热更新 | 每引擎 10 通过 | [WebFlux](smoke-hotreload-webflux.log)、[VT](smoke-hotreload-vt.log) |
| 真实 Redis 双副本 | 7 通过 | [replicas](smoke-redis-replicas.log) |

首次协议/平台脚本失败被如实保留于 [初始结果](contract-results.json)；用重构前发行 JAR 复现相同失败，见 [基线结果](baseline-stale-results.json)。修正内容为未知任务归属的 404 断言、opaque key ID 的 JSON 解析、缺失价格配置和显式空 token；没有放宽业务安全契约。修正结果见 [corrected](corrected-contract-results.json)、[补充契约结果](remaining-contract-results.json)。

## 规则与发布验收

结构检查 71 项；原生边界检查 151 module Java / 20 modules / 9 descriptors / 21 named extensions，Python 规则负例 5 项、基准解析/Mock 测试 8 项。YAML/OpenAPI 严格解析覆盖工作流、compose 和 application 配置。Enforcer 的负向命令强制 HTTP WebFlux 模块执行基础禁依赖规则，确实拒绝 Spring/Reactor，未将依赖解析错误误判为成功。

v0.3.0 三组 JVM 构建见 [build-v030-results.json](build-v030-results.json)：default、Redis、combined 各 142 项，零失败/错误/跳过；为新增三项 app 桥接测试后的独立构建。该批为 shared arena 与精确 Dubbo 元数据补齐后的最终 JVM 构建；完整 tag workflow 对提交重新验证。远端 main CI 和 tag Release 对提交重新验证，资产以 GitHub Release 的 SHA256SUMS 为准。本地日志的工作目录/产物绝对路径只作执行证据，不是可移植下载地址。

## 保留的边界

URI cache 无容量上限，上传下载整包缓冲；协议注册覆盖/引擎标签仍是进程级；Redis 为单节点连接池，未提供财务级硬预算或完整分布式控制面。tokenizer 仅覆盖支持的文本准入估算，无 usage 输出仍为启发式。纯基础 jar 无 Spring/Reactor 不意味着 app fat jar 变小；默认 app 为管理校验仍链接 SDK。

没有性能复测、付费上游调用、公网 TLS/生产长流容量验证；native 契约通过也不是性能证明。历史性能与竞品测量保留原产物和原范围。

## AOT 排查记录

首次 native 编译因 Fastjson2 缓存类型初始化失败，之后定位到 Boot AOT 会覆盖同名默认 native-image.properties，以及过宽第三方方法反射调用导致额外可达路径。日志保留在 `native-webflux-*-failure.log`；后续收窄为构造器/方法检查/必要 setter，并独立放置第三方缓存类型初始化修正。

首次编译成功的二进制实际启动失败：Dubbo SystemPropertyConfigUtils 依赖的嵌套常量字段不可见，见 [失败日志](native-webflux-missing-constants-failure.log)。使用 GraalVM 25 agent 对冻结 JVM JAR 分别训练 WebFlux/VT 的启动、health、JSON、SSE；训练日志见 [WebFlux](agent-webflux-training.log)、[VT](agent-vt-training.log)。只保留 dubbo-common 中存在且依赖可用的 Dubbo 类型，补入 36 项精确字段/构造器元数据；未复制整个 Spring/JDK/vendor 配置。这两份 agent 日志是 JVM 训练证据，不是 native 成功证据。

本地 native 目标为 macOS WebFlux。VT native、可选 Redis/jtokkit native、G1 和其他平台不在本地本轮验收范围内。Linux amd64 WebFlux 的最终产物由 tag Release 独立编译与运行契约。

Netty 的 JDK 25 direct-buffer cleaner 在退出时需要 shared arena 支持。实际二进制请求虽已成功，但退出出现 UnsupportedFeatureError，见 [失败日志](native-webflux-shared-arena-failure.log)。native 构建参数增加 `-H:+SharedArenaSupport`；本地后续使用相同 AOT 输出、精确 Dubbo 元数据与该选项执行 `-Ob` quick build，验证运行契约，不用于性能结论。Release workflow 仍执行完整优化构建。

## 最终本地 native 验收

[本地二进制 manifest](native-results.json)记录实际平台、GraalVM 和 SHA256。`-Ob` WebFlux native 的 health、JSON、SSE 与退出清理通过；[实际预检日志](native-webflux-preflight.log)无 UnsupportedFeatureError。完整 [流式契约](native-webflux-stream.log)为 34 通过、0 失败，[多协议契约](native-webflux-protocols.log)为 28 通过、0 失败。quick build 日志见 [编译记录](native-webflux-quick-build.log)。

这证明本地该二进制的上述行为，不宣称本地验证了 VT/Redis native、Linux 或优化等级带来的性能。Release 的 Linux 完整优化二进制以 tag workflow 的实际编译与契约日志为准。

发布日志中的随机 `key-<hex>` ID / 测试 token 已替换为按原值摘要产生的一致性标记，避免被误识别为第三方服务密钥；保留 PASS/FAIL、状态码和计量数据，未绕过推送保护。
