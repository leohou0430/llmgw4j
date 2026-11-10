# 项目工作规则

始终使用中文回复用户。优先使用已有 CodeGraph 做代码定位，再以当前文件和实际执行结果为准。

## 原生基础与扩展

- 基础模块（core、各能力域 api/实现包）不得依赖或引用 Spring、Spring Boot、Reactor；HTTP 框架只允许出现在 app、engine-webflux、engine-vt。
- 参考 Dubbo SPI 的命名、默认实现、惰性发现和作用域原则，以项目内 `ExtensionPoint` / `Extensions` 和 `META-INF/llmgw/extensions/<接口全名>` 实现；不直接依赖任何 Dubbo 包，不复制其 IoC/AOP、RPC 或字节码生成。
- 有依赖的实现由命名 `ExtensionModule` 使用显式构造函数组装；状态与资源属于每个 `ExtensionContext`。禁止静态全局的有状态治理实例/资源和隐式 Spring bean 优先级；纯无状态协议目录缓存不含运行凭据或账本。
- 修改基础绑定时，在 seal 前显式 `replace`；未知模块/显式扩展名必须失败。HTTP 适配桥仅导出原生图，不能重新做实现扫描。
- 上游公开异步契约使用 JDK Flow/CompletableFuture。正常流完成不能取消 HTTP transport；取消、首字节/空闲超时及 SSE 完整性都必须回归。

## 多实现模块规则（含以后新增的能力）

- 同一能力有多个实现时必须为 `llmgw-<domain>/` 聚合 parent + 唯一 `llmgw-<domain>-api` + 每个实现独立 child module。
- 根 POM 只列聚合目录。api 只依赖 core 及必要外部契约；实现依赖本域 api，不得依赖同域其他实现，不得共享 api 的裸 Java 包。
- 禁止在根目录追加一组 `llmgw-<domain>-x/y` 平铺实现。provider、tokenizer 与治理、引擎遵循同一规则。
- 所有基础 jar 必须继承或显式设置 `llmgw.foundation.enforcer.skip=false`，由 Maven Enforcer 检查传递依赖。新增能力域 parent 应设置此属性，HTTP 实现例外仍由源码检查严格限定。

## 验证

运行 `bash bin/check-module-layout.sh`、`python3 bin/check-native-boundaries.py`、`python3 -m unittest discover -s bin -p 'test_native_*.py'`。修改扩展必须覆盖命名发现、替换和失败路径；修改资源必须覆盖关闭与实例隔离。

默认、Redis、jtokkit/otel profile 分别验证；Redis 使用本次任务独立实例，清理自己的资源。协议/治理变更运行双引擎流式、扩展、加固、租户隔离、管理与 JWT 契约；AOT 变更还需验证 native。`LLMGW_TEST_JAR` 可固定 shell/租户回归的被测 JAR，防止 profile/AOT 重建覆盖产物。

不改写历史测量作为当前证据；新增验证应记录实际产物摘要、场景与未覆盖范围。不自动提交、推送或发布本轮变更，除非用户明确要求。
