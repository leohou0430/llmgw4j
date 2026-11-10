# llmgw4j 生产环境最佳实践

> v0.3.0 使用原生 Dubbo SPI。内部 API、模块及 Redis 配置升级方式见 [扩展与迁移说明](EXTENSIONS.md)。

适用版本：**0.3.0**。更新：2026-11-10。配置字段以 [配置说明](CONFIGURATION.md)和源码为准，完整端点见 [OpenAPI](openapi.yaml)。

## 部署边界

llmgw4j 提供 Java 模型数据面、协议透传和可替换治理 SPI。默认配置是本地 mock 演示。企业 OIDC/JWKS/RBAC、持久化财务预算、跨副本配置发布和完整资源目录尚未内建，不能把参考实现直接当作完整多租户计费平台。

推荐由 Nginx/Envoy/LB 终止 TLS、隔离管理入口。JVM 默认包支持 WebFlux 和 VT；native 必须按引擎构建。v0.3.0 Release 提供 Linux amd64 WebFlux native，其他平台自行编译。目标 OS、CPU、GC 和启动参数均需单独验证，历史 macOS 冷启动/RSS 数字不能推广到生产。

## 最小生产配置

将配置保存为受权限保护的 `application-prod.yml`，从环境注入密钥，替换整个演示 key 和 route 列表：

```yaml
server:
  address: 127.0.0.1
  port: 8080
gateway:
  production: true
  governance-enabled: true
  admin-token: ${LLMGW_ADMIN_TOKEN}
  max-request-bytes: 4194304
  max-response-bytes: 4194304
  max-concurrent-requests: 64
  mock:
    enabled: false
  upstream:
    type: openai-compatible
    base-url: ${LLMGW_UPSTREAM_URL}
    api-key: ${LLMGW_UPSTREAM_KEY}
    timeout-ms: 60000
    executor-threads: 32
  routes:
    - id: provider-a
      model: "*"
      target-model: ${LLMGW_MODEL}
      target-url: ${LLMGW_UPSTREAM_URL}
      api-key: ${LLMGW_UPSTREAM_KEY}
      weight: 1
      fallbacks: []
  api-keys:
    - id: svc-chat
      token: ${LLMGW_CLIENT_TOKEN}
      quota-tokens: 1000000
```

```sh
java -XX:MaxGCPauseMillis=10 -Xms256m -Xmx1024m \
  -jar llmgw-app/target/llmgw-app-0.3.0.jar \
  --spring.profiles.active=webflux \
  --spring.config.additional-location=file:./application-prod.yml
```

这是起始配置，不是容量承诺。容器内的 `server.address` 需按网络拓扑设置，通常监听 `0.0.0.0` 并仅在内部网络暴露；Compose 演示仅将宿主端口绑定到回环。使用 systemd/容器平台管理重启、日志和健康探针，持久数据目录单独挂载并限制权限。密钥不放命令行和提交记录。

## 身份与资源隔离

- 生产模式必须配置完整管理 token；审计只读 token 仅用于审计导出，并且不能与完整 token 相同。只配置只读 token 不会开放管理写入。管理控制台页面不代表其数据接口免鉴权。
- 可选 JWT 仅内建 HS256，明确配置共享密钥、issuer/audience；不是完整 OIDC/JWKS。key 的创建、轮换、吊销与重启恢复应纳入运行手册。
- 文件/任务及文件引用按稳定 key ID、协议和路由验证；未知归属返回 404。files/batches 列表仍返回 501，不应绕过网关向调用方开放共享上游目录。
- 外部强制审核故障返回 503，不把审核不可用当作允许；语义缓存是优化能力，不承担授权。
- 可能已经提交的付费操作，普通超时/5xx 不自动重发。fallback 不是幂等保证，业务端应处理未知提交结果。

## 配额、缓存和多副本

token 配额使用估算预留和实际结算，估算误差可使最终用量略超配额。金额账本是本地演示估算；生产模式不能开启金额预算。需要财务硬控时应先实现持久共享预留、崩溃恢复、未知状态对账和价格覆盖。

Redis 用 `-Predis` 构建，并启用 `gateway.redis.enabled=true`。它替换部分限流/使用量/熔断/key 表/响应缓存/sticky，不替换金额账本、请求日志、快照或完整控制面。模型桶按模型名跨 key 共享，不是 key×model 多级策略。Redis 运行时 key CRUD 暂返回 501；JSON key 文件只支持单进程写入，不能靠共享文件实现多副本管理。

缓存按租户、协议、模型和参数分区；语义检索仅覆盖适合的纯文本请求。内存向量实现与 Redis 缓存能力不等价，不能宣称统一分布式 ANN。明确缓存 TTL、旁路和失效策略，勿用缓存命中降低上游计数的压测来冒充代理吞吐。

## 超时与容量

- 默认整体超时 60s，connect/首字节/空闲读和心跳按实际上游间隔配置。LB 空闲超时应留出余量，Nginx 关闭 SSE 缓冲；先演练长推理与中途断连，再调整超时。
- 默认全生命周期并发 64，超出返回 503；请求和非流式响应分别默认限制 4 MiB。上传和二进制下载仍整包缓冲。提高任一边界前测峰值堆、RSS、GC、上游连接、取消与错误率。
- `executor-threads` 默认 32，不是所有 CPU 配额下的最优值。4 CPU 上缩到 4 的实验虽提高 JSON 吞吐，却有一次 SSE 截断，没有采用。按 CPU 配额及真实流长度测，不凭单一 RPS 调参。
- URI 缓存尚无容量上限，高基数动态路径存在长期堆增长风险；应观测堆趋势并设置外部资源生命周期策略，本轮没有修复缓存边界。
- [本轮性能审查](verification/perf/2026-11-10/perf-review/README.md)确认局部分配下降；整网关结果波动，不能作为生产 SLO、最大容量或统一性能优势。

## 运维与上线验收

采集 `/actuator/prometheus`、TTFT/上游/网关开销、拒绝原因与错误率，按引擎和 route 查看；使用请求 ID 关联响应、请求日志与审计。默认请求日志和审计环不是持久合规存储，需自行导出、脱敏和保留。健康检查使用 `/actuator/health`，配置足够启动宽限。

上线前执行默认及 Redis 的 `mvn -B clean verify`，双引擎流式/扩展/加固契约、租户隔离、管理鉴权和 JWT 回归。模拟依赖超时、取消、Redis 故障、重启恢复和滚动发布，观测真实计量与资源归属。备份并验证恢复，保留旧版本和配置回滚路径。tag 发布流程与资产校验见 [部署与发布](DEPLOYMENT.md#验证与发布)。
