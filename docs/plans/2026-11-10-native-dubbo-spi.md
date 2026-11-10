# 原生扩展与多模块目标

目标修订明确：通过 Context7 参考 Dubbo 的命名、默认、惰性、别名机制，不直接使用 Dubbo 依赖；基础能力使用更原生方式，审查全部 Java，provider/tokenizer 等多实现统一模块规则，最后更新并审查全部当前文档。

已保留去 Spring/Reactor、显式构造/资源生命周期、Flow/Future、Jedis、provider/tokenizer 聚合/API/实现布局。直接依赖的中间实现已撤出：取消 v0.3.0 发布，保留 tag 和历史证据；当前以项目 ExtensionPoint/Extensions 和 META-INF/llmgw/extensions 加载，杜绝直接或间接 Dubbo 依赖。

原生加载器只做命名发现和按需无参构造，不复制 IoC/AOP/adaptive 代码生成。重复/未知/非法扩展明确失败，缓存按目录/ClassLoader 隔离；有状态资源通过 ExtensionContext 显式绑定与关闭。参考来源为 Context7 检索的 Apache Dubbo 官方 SPI 机制说明。

验收以 [当前审查报告](../verification/architecture/native-extension-mechanism/README.md) 的实际构建、单元/HTTP/native、依赖与逐文件清单为准；不能把已取消的中间实现或旧性能数据当作当前证据。文档需同步实现名、配置、依赖和未覆盖范围。
