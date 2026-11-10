# 参考 Dubbo 机制的项目内原生 SPI

用户修订目标明确要求参考机制、不是直接使用 Dubbo。v0.3.0 的直接依赖实现及发布已取消；cf1edea / v0.3.0 源码和验证保持历史身份，不移动 tag、不冒充当前证据。

当前实现保留已完成的基础去框架、Flow/Future、Jedis、provider/tokenizer 多模块；以 ExtensionPoint / Extensions、项目描述文件替换 Dubbo 注解、ExtensionLoader、FrameworkModel 和依赖。命名、默认、惰性、别名和隔离机制依据 Context7 检索的官方说明，并仅实现项目需要的子集；没有 RPC/IoC/AOP/字节码生成。

新增六项加载器测试先失败后通过，见 extension-loader-red.log / extension-loader-green.log。覆盖并发单次构造、惰性发现、别名与目录隔离、ClassLoader 元数据隔离、重复名、非法/未知名称、接口/公共构造器检查及真实异常原因。原有资源逆序关闭与绑定替换测试继续适用。

历史性能数据不作为此次结果。当前结果与未覆盖范围如下。

当前文档审查以中英文 README、配置、使用、部署、贡献规范、OpenAPI、版本与发布工作流为范围。保留旧 tag 与旧测量的历史身份，不用文字替换把旧测试冒充新验证。

## 审查与当前结果

[逐文件清单](java-inventory.json)覆盖 162 个自有 Java 文件（main 120、test 34、bench 8），记录源码摘要、框架 import 与生命周期信号。范围是全源码结构/依赖/资源检查，加关键变更路径审查与行为测试，不宣称逐业务分支无缺陷。

[构建 manifest](build-results.json)记录默认、Redis、combined（redis/jtokkit/otel）各 148 项 JUnit，零失败/错误/跳过；真实 Redis 为任务独立进程并已清理。此前直接依赖实现的 142/139 计数不属于本轮。

[依赖检查](dependency-check.json)确认冻结默认 JAR 不含 Dubbo/Fastjson/Javassist；[实际 Enforcer 负例](enforcer-no-dubbo-negative.log)使用根 POM 的同一禁依赖 execution，确实拒绝 Dubbo。模块检查 71 项、基础边界 154 module Java / 20 modules / 9 descriptors / 21 names，Python 负例 6 项通过。

原有 URI cache 无上限、整包文件缓冲、进程级协议覆盖/指标身份及财务/分布式控制面限制仍保留。自定义扩展资源应经 typed binding 管理；external 与 owned 别名不可混用同一资源所有权，内建组合没有此混用。

## 双引擎 HTTP

[完整结果](contract-results.json)的九组均 exit 0，针对 build-results.json 的冻结 default.jar：流式 67、扩展 54、加固 46、多协议 56、provider 24、平台 48，以及租户隔离、管理与 JWT 双引擎回归。每组日志为 smoke-<suite>.log；没有使用中间版本的结果填补本轮。

日志中的随机测试 key ID / token 按原值摘要做一致性替换，避免被识别为第三方服务凭据；状态码、PASS/FAIL 和计量数据保持原样。

## 文档审查

重写扩展说明，逐项核对当前 README/英文版、配置、使用、部署、贡献规范、OpenAPI 与构建/发布资产名；修正真实 Dubbo、FrameworkModel、Spring 自动装配和旧 metadata 的失效说法。明确当前目录级实例与 ClassLoader 缓存范围，public 无参构造器、失败路径、typed binding 所有权，以及 native 必须按引擎构建的约束。旧 tag、旧审查和旧性能测量保留历史说明。

模块布局 71、源码 guard 和 6 项 Python 规则测试、严格 YAML/OpenAPI、8 项基准工具测试、8 个 bench Java 编译通过。全量源码及构建版本一致性以当前 inventory 与 POM 为准；Linux 完整发布构建由 main/tag workflow 对提交重新验证。

## Native

[编译日志](native-webflux-build.log)为 Maven native profile 的完整默认优化构建，构建宿主限制 4 CPU，不是 quick build。[二进制 manifest](native-results.json)记录 SHA256 与 GraalVM；health、JSON、SSE、退出清理通过，[流式](native-webflux-stream.log) 34 通过、[多协议](native-webflux-protocols.log) 28 通过，均零失败。整个过程没有直接 Dubbo 运行时或第三方 Dubbo 反射元数据。

本地覆盖 macOS WebFlux；未覆盖 VT/G1、可选 Redis/jtokkit native，也没有重测性能、真实付费上游或公网 TLS。Linux amd64 WebFlux 由 tag workflow 独立完整编译并运行相同契约；不能把本地结果当作 Linux 结果。
