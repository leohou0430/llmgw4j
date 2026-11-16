# 最新 WebFlux native 兼容性补验（2026-11-11，已完成）

在三步审查完成后，用当前源码重新构建 native，补齐原先仅有阶段1 native 的缺口。没有改动生产 Java 源码、POM 或 AOT 配置；新增 SDK 启动器的 native 入口，复用同一组兼容性场景。

## 产物与源码

- 平台：`macOS-15.8.3-x86_64-i386-64bit-Mach-O`，本机 WebFlux，GraalVM/JDK 25，默认 Serial GC。
- 固定产物：`bench/results/native-compatibility-2026-11-11/llmgw-sdk-webflux`。
- SHA-256：`d20b3bd6dd815e11a947a0cad83726188172b837d742f57f7cc639680e53c8d6`；大小 `287181336` 字节。
- Maven native 构建成功，实际总耗时 **11分44秒**，命令和完整警告保留于 `build.log`。
- 构建开始时捕获所有 main Java 源文件摘要，执行前复查 **0变化**；构建出的 **174个项目 class** 与阶段3冻结 JVM JAR 逐字节一致。详见 [native-results.json](native-results.json)。

## 验收

| 检查 | 实际结果 |
|---|---|
| 官方 Python SDK / native WebFlux | 26项全通过：JSON/SSE、工具调用、usage、截断、单一版本头、multipart、批任务、字节响应、租户隔离 |
| 原有 JVM SDK 入口 | 双引擎52项全通过，确认新增CLI分支没有破坏原入口 |
| native流式错误与计量 | 34/34，通过 |
| native多协议与资源 | 28/28，通过 |
| native租户隔离 | 通过 |
| native空闲读取进展 | 3种场景通过：持续片段、错误体持续读取、真正停顿 |
| 模块布局/原生边界/Python规则 | 通过 |
| CLI防误用 | --binary未指定构建期engine时退出2；不尝试启动错误引擎 |

SDK脚本支持 `--binary <path> --engine webflux`，JVM原来的 `--jar` 入口保留。首次通用启动器改动暴露 CLI args 被请求字典覆盖的问题，记录在 `jvm-sdk/`；改为独立 options 后，`jvm-sdk-fixed/` 52项通过。没有把脚本问题计为网关生产缺陷。

```bash
python bin/smoke-sdk-compatibility.py --binary /absolute/path/to/native \
  --engine webflux --out /tmp/native-sdk-evidence
```

`verify-native.py` 保存命令、状态、耗时、摘要和日志；[completion.json](completion.json)汇总本次证据。所有探针应用与本地上游在结束后关闭。

## 真实供应商与边界

另完成[实际 DeepSeek 两次验收](../../live-provider/2026-11-11/README.md)：JVM WebFlux JSON + JVM VT SSE，指定 deepseek-flash，合计20 tokens。native本轮仅使用受控本地上游，不声称已验证native公网TLS或其他供应商。

没有构建Linux/VT native，没有新native吞吐、2C2G慢读或长期SLO数据。旧阶段性能数据继续绑定旧产物。第三方native元数据警告保留在日志中，未为消除无关警告而扩大改动。没有提交、推送、发布，也未触发远端CI。
