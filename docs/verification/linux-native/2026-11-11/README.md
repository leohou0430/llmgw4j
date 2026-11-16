# Linux amd64 / 双引擎 native 补验（2026-11-11 批次，已完成）

本轮补齐 Linux 与 VT native 的实际构建、启动和协议验证；不调用付费供应商，不提交、推送或发布。

## 输入与隔离

- 源码来自工作区快照，249个文件的摘要记录于 `inputs.json`；不挂载宿主源码目录写入，不复制用户 Maven settings 或凭据。
- 使用 Oracle 官方 Linux GraalVM 25 安装包，官方 SHA-256 校验通过，下载地址和摘要保留在 `inputs.json`。GHCR 镜像仓库的首次访问因 TLS 超时失败，未降低 TLS 校验。
- Linux x86_64 / Ubuntu 26.04.1 / glibc 2.43，Oracle GraalVM 25.0.4，Maven 3.9.16；构建容器限制12 CPU /16 GiB、禁swap，native builder堆上限10g。构建串行进行。
- Redis为本次独立创建的容器；构建与测试在自己的网络命名空间中运行。`run.json`记录所有容器ID，完成或失败均归档证据并清理自己的容器，不操作其他容器。

## 已有实际证据

首轮 Linux default、Redis、Redis/jtokkit/otel 分别 `clean verify`，各 **246项**、失败/错误/跳过均为0；Linux JVM双引擎官方SDK **52项**全通过。原始日志和快照在 `bench/results/linux-native-2026-11-11/attempt-1/`，不冒充后续新执行。

首轮 native 命令在 Maven 解析扩展时因 CycloneDX 构件下载 TLS 握手中断而失败，未进入 native 编译。错误日志完整保留。后续仅导入相关公共 Central 构件缓存及GraalVM构建工具缓存，不复制settings、凭据或整个用户仓库；文件清单和摘要在 `public-cache-seed.json`。新容器验证源码摘要未变，显式复用已通过的五个检查，继续 native 构建。

## 当前验收范围

WebFlux和VT分别构建自己的AOT镜像，再各自运行官方SDK、流式、多协议、租户隔离和读取进展检查。具体命令、结果及时间保存在容器 `/audit/status.json`；执行时宿主 `runner.log`显示当前步骤，结束后证据归档至 `bench/results/linux-native-2026-11-11/evidence/`。只有最终结果实际通过后才计为完成。

本轮为功能与平台兼容验收，不是2C2G性能、慢读容量或长期SLO测量。Linux二进制的动态库/GLIBC需求需按最终ELF检查记录，不能仅凭“amd64”认定兼容全部Linux发行版。之前DeepSeek两次实测与macOS native结果保持各自范围，不挪作Linux native公网TLS的证明。

## 最终结果

WebFlux与VT Linux native均构建成功，各26项官方SDK、34项流式、28项多协议、租户隔离及3种读取进展检查通过。两种构建各174个项目class与已验证Linux JVM产物一致，源码摘要未变。三种profile各246项及JVM SDK52项通过；原始首次依赖下载失败保持在attempt-1，不覆盖历史。

最终摘要见[completion.json](completion.json)，完整日志与二进制在 `bench/results/linux-native-2026-11-11/evidence/`。两个构建均无cgroup OOM，自己的构建/Redis容器已清理；没有追加供应商调用。ELF直接引用的GLIBC版本最高为2.34；仍需满足动态库、CPU指令集等要求，不保证任意Linux发行版可运行。

## 后续容量与TLS证据

上述构建批次之后，同一固定二进制完成[2026-11-12容量与TLS补验](../../native-capacity/2026-11-12/README.md)：62容量样本、4组慢客户端、8项本地TLS、两次授权真实DeepSeek调用。该独立报告补充覆盖范围，不更改本批次历史结果。
