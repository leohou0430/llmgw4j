# 统一容器代理基准

需要 JDK 25、Python 3、Docker、k6。仅访问本地模拟上游，不使用真实供应商凭据。

```sh
mvn -B clean verify
cp llmgw-app/target/llmgw-app-0.2.0.jar /tmp/llmgw-candidate.jar
# 镜像版本见 compare.py 的 IMAGES；base 是本项目含 Java 25 的运行镜像。
docker build --build-arg MAVEN_PROFILES=-Predis -t llmgw-audit:redis .
python3 -m unittest discover -s bench/cross-gateway -p 'test_*.py'
python3 bench/cross-gateway/compare.py --jar /tmp/llmgw-candidate.jar \
  --output docs/verification/perf/local --rounds 3 --duration 10s --warmup 3s
```

必须先拉取脚本列出的 LiteLLM、Bifrost、Portkey 和 Nginx 镜像。输出 manifest 固定实际镜像 ID、摘要、架构及 JAR SHA256；复现历史测量时以该 manifest 的摘要为准，不能依赖可移动标签。默认产物必须 clean 构建，避免 Maven 切换 profile 后复用已 repackage 的旧包。

可用 `--preflight-only` 仅验证 JSON/SSE/错误凭据；`--before-jar /tmp/previous.jar` 加入同资源 WebFlux 旧产物对照；`--arms` 选择实际参与者，`--profiles wire` / `--modes json` 可限定场景。两个配置时按 AB/BA 轮换；更多配置每轮旋转，完成一个全位置周期后再反序；建议轮数取配置数的整数倍。

运行形态：本项目每引擎一个 JVM（Xms256m/Xmx1024m）；LiteLLM 显式 4 个 worker；Bifrost 按容器 CPU 配额运行；Portkey 使用官方镜像默认 Node 启动方式，未自行包装多 worker 集群。因此比较的是这些具体单容器配置，不能推广为各产品任意部署的最优性能。

各被测网关 4 CPU/2GB，公共 Nginx 2 CPU/128MB，同上游 4 CPU/1GB。客户端从宿主机访问各自 Nginx 的随机 localhost 端口，网关及 mock 位于仅内部 Docker 网络，不依赖宿主机 bind mount。Nginx 统一执行静态 bearer 验证；各产品在代理基线关闭可关闭的治理、缓存及日志。LiteLLM 无 master key 的显式开发开关仅用于这个由 Nginx 鉴权的隔离测试，不能复制为生产配置。Bifrost 使用本地空价格/模型参数目录，治理关闭。

共享 mock 设置 TCP_NODELAY、10000 个空闲连接上限和 1800s 空闲超时，避免多个网关依次测量时默认连接淘汰干扰结果（所有参赛者相同）。设置依据：[JDK 25 HTTP server 文档](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.httpserver/module-summary.html)。

wire 为 0ms 模拟延迟；inference 为 JSON 20ms、SSE 首延迟 20ms 加 8 次 10ms 间隔。两种模式分别测量，多轮轮换顺序。SSE 指标是完整流耗时，不是首 token 时间。50 VU、短请求、闭环负载、无 TLS，不能解释为最大容量、生产 SLO 或全功能治理性能。

每轮保存 k6 完整 summary/log，对照上游真实收到的请求数量排除缓存或额外重试。只有全部轮次完整、零失败且数量吻合才入排名；直接上游是控制组，旧产物是改进对照，均不是独立网关产品。三轮 RPS 中位数和区间须一起看，接近值不能作稳定优胜推断。预检失败只记录为未测，不排末位。任一运行异常仍清理本次创建的容器和网络，不操作其他容器。

## 性能诊断与自身 A/B

`--governance on` 可测自身网关的真实本地治理路径，使用隔离的测试凭据及大配额避免 429；默认 `off`。该开关不代表启用了 Redis、远程审核或 embedding 服务。`--before-jar` 自动补充 WebFlux 对照；显式加入 `--arms llmgw-webflux-proxy,llmgw-vt-proxy,llmgw-vt-before` 可同时对照两种引擎。

`--jfr` 为自身 JVM 记录 profile JFR，退出时导出到 raw 目录；它会影响结果，仅用于定位，正式吞吐 A/B 必须去掉。可用 `bench/jfr/AnalyzeRecording.java` 流式分析录制，避免全量 JSON 展开。`--upstream-threads` / `--before-upstream-threads` 可单独扫描线程配置；比较代码改动时必须保持二者一致。线程实验中任何 SSE 截断都算失败，不能仅凭 JSON 吞吐提高修改默认值。

本次有治理/无治理的四配置、四轮证据见 [性能审查](../../docs/verification/perf/2026-11-10/perf-review/README.md)。
