# 原生基础能力与 Dubbo SPI 重构

目标保持用户原始范围：审查全部 Java；实际使用 Dubbo SPI 替换基础扩展发现/自动装配；减少基础模块框架依赖；provider 统一聚合 + api + 实现模块，未来多实现同规则。

## 证据与决定

Context7 已检索 `/apache/dubbo-website` 和 `/apache/dubbo/dubbo-3.3.6`。官方机制为 `@SPI`、`META-INF/dubbo/<接口全名>` 的名称映射，以及 `ExtensionLoader.getExtensionLoader(...).getExtension(name)`。采用真实 `dubbo-common`，不引入 Dubbo RPC、registry、Spring starter，也不把自写 ServiceLoader 冒充 Dubbo SPI。锁定检索和实际验证版本，不宣称最新版。

官方来源：https://github.com/apache/dubbo-website/blob/master/content/en/overview/mannual/java-sdk/reference-manual/architecture/dubbo-spi.md

## 实施顺序（全部为目标的一部分）

1. 全 Java 清单、框架引用、模块依赖与装配路径审查，生成逐文件覆盖记录。
2. provider 拆为 `llmgw-provider/{llmgw-provider-api,llmgw-provider-openai,llmgw-provider-anthropic}`；认证/验证契约放 api，真实 Dubbo SPI 发现实现，不扫描 provider Spring component。
3. 配置 POJO/协议注册/tokenizer/治理默认包去 Spring；普通 Java 显式构造和生命周期，Dubbo 模块/命名扩展负责选择，app 仅桥接到 HTTP 引擎。
4. 上游契约使用 JDK Flow/CompletableFuture，Reactor 适配限制在 WebFlux 引擎；JDK HTTP 转发脱离 app 的 Spring 装配。
5. Redis 实现去 Spring Data/自动配置，使用独立原生客户端，保留 Lua 原子语义与共享资源归属；真实 Redis 契约回归。
6. 所有实际多实现能力域统一模块规则，补全架构自动检查、扩展替换/未知扩展/多实例隔离/关闭生命周期测试。
7. 更新配置与扩展文档、AOT/native 描述，默认/Redis/tokenizer 等 profile、双引擎行为与完整契约验证。没有全量证据前不标记完成。

## 约束

不改变协议原始字节、配额/租户安全、取消传播、错误/终止语义。禁止热路径反复查找扩展或扫描 classpath。未知显式实现不能悄悄降级。基础模块不导入 Spring 装配类型；HTTP 适配与 Boot 配置绑定留在 app/engine。关闭线程池/HTTP/Redis 资源由原生生命周期负责。

## 当前状态

生产重构已落地：provider/tokenizer 聚合、真实命名 SPI、原生配置/治理与资源上下文、JDK Flow/Future transport、Jedis Redis、依赖 Enforcer 与源码检查。默认、Redis、combined profile 与双引擎契约的实际结果见 [验证报告](../verification/architecture/native-spi/README.md)，不以计划代替验收证据。

后续用户明确授权全面更新文档、提交推送 main、构建新 tag 发布。因此本轮进入 v0.3.0 发布流程，包含内部 Java API 迁移说明；v0.2.0 历史证据保持原样，旧性能数字不冒充新版本测量。
