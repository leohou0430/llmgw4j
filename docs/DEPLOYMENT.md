# llmgw4j 部署指南（v0.3.0）

> v0.3.0 使用原生 Dubbo SPI。内部 API、模块及 Redis 配置升级方式见 [扩展与迁移说明](EXTENSIONS.md)。

## TLS 立场

llmgw4j 与 LiteLLM、OneAPI 等应用级网关采取相同立场：**网关进程本身保持纯 HTTP，
TLS 在前置代理终止**。理由：

1. 字节保真是本网关的核心契约——TLS 属于传输层，与 SSE 契约/计费/容错正交，
   交给久经考验的 nginx/Envoy 比自建 JSSE 配置更可靠；
2. 前置代理同时承担 WAF、mTLS 客户端认证、全站限流——这些是 API 网关层的职责
   （见 gap-analysis「自研 vs 采用」一节）；
3. 将证书轮换和 TLS 策略集中在已有入口层。

需要网关进程内 TLS 的部署（如无法引入前置代理），用 Spring Boot 标准配置即可：
`server.ssl.*`（Boot 4 原生支持，无需改代码）。

## 推荐拓扑

```
clients ──HTTPS──> nginx (TLS/WAF/mTLS) ──HTTP──> llmgw4j (:8080) ──HTTPS──> providers
```

### docker compose（nginx 前置 + 网关 + 可选 Redis）

```yaml
services:
  tls:
    image: nginx:alpine
    ports: ["443:443"]
    volumes:
      - ./deploy/nginx.conf:/etc/nginx/conf.d/default.conf:ro
      - ./deploy/certs:/etc/nginx/certs:ro
    depends_on: [gateway]

  gateway:
    build: .
    environment:
      LLMGW_ADMIN_TOKEN: ${LLMGW_ADMIN_TOKEN}
      DASHSCOPE_API_KEY: ${DASHSCOPE_API_KEY}
    # 不对外暴露 8080：仅 nginx 可达
    expose: ["8080"]
```

`deploy/nginx.conf` 最小配置：

```nginx
server {
  listen 443 ssl;
  ssl_certificate     /etc/nginx/certs/fullchain.pem;
  ssl_certificate_key /etc/nginx/certs/privkey.pem;

  # SSE 长连接必须关掉缓冲，否则心跳/流式都会被攒到结束才吐
  location / {
    proxy_pass http://gateway:8080;
    proxy_http_version 1.1;
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 300s;   # 与 gateway.upstream.timeout-ms 对齐
    proxy_set_header Connection "";
  }
}
```

## 本地容器演示

`docker compose up --build` 仅绑定 `127.0.0.1:8080`，保留演示 key 与 mock。
不要将这个演示配置原样转为公网生产部署。

## Redis 治理包

```bash
# override 同时负责编译 Redis profile、启用治理包并配置 Redis 地址。
docker compose -f docker-compose.yml -f docker-compose.redis.yml up --build
# 等价镜像构建：Dockerfile 会实际消费此参数。
docker build --build-arg MAVEN_PROFILES=-Predis -t llmgw4j:redis .
```

Redis 包替换 `TokenLimiter` / `UsageLedger` / `CircuitBreaker` / `ApiKeyRegistry` /
`SemanticCache` / `TaskRouteSticky`，任务归属与路由绑定可跨副本共享。
Redis 服务使用 AOF 数据卷，但仍需自行定义备份、故障恢复和一致性验证方案。
**金额 `BudgetLedger`、请求日志与快照仍为本地实现，不提供分布式财务级预算。**
基础 compose 使用固定演示端口；实际扩容应由部署平台分配实例端口并在内网负载均衡，
不能直接对基础 compose 执行 `--scale gateway=2` 而继续共用一个宿主端口。

## 生产配置

在独立配置文件中显式启用生产模式，并替换整个演示 key 与路由列表：

```yaml
gateway:
  production: true
  governance-enabled: true
  admin-token: ${LLMGW_ADMIN_TOKEN}
  api-keys:
    - id: team-a
      token: ${LLMGW_CLIENT_TOKEN}
      quota-tokens: 1000000
  mock:
    enabled: false
  upstream:
    type: openai-compatible
    base-url: ${LLMGW_UPSTREAM_URL}
    api-key: ${LLMGW_UPSTREAM_KEY}
  routes:
    - id: provider-a
      model: "*"
      target-url: ${LLMGW_UPSTREAM_URL}
      api-key: ${LLMGW_UPSTREAM_KEY}
```

容器将文件挂载到 `/app/config/application.yml`；提供非空强随机管理与客户端凭据、
真实上游地址和密钥。`SPRING_PROFILES_ACTIVE` 仍只选择 `webflux` 或 `vt` 数据面。
不要使用占位演示 key，不要关闭生产治理，也不要启用当前内建账本无法可靠支持的金额预算。
生产校验不是财务对账保证；对供应商最终收费仍须保留对账与告警。

## 生产清单

- [ ] `gateway.production=true`，管理 token 和客户端 key 已配置且不是示例值
- [ ] 前置 TLS、`/admin` 内网隔离，数据面端口不直接暴露公网
- [ ] 明确 token 配额允许估算偏差；没有把内存金额账本当作硬预算
- [ ] 多副本明确使用 Redis 构建与运行配置，并验证跨副本 token 配额及资源归属
- [ ] 审核依赖故障时返回 503；缓存、向量检索等优化能力可独立降级
- [ ] 超时结果未知时不自动重复提交付费操作
- [ ] 观测：`-Potel` + OTLP collector；配好指标、告警和审计保留策略
- [ ] 按实际长连接、外部依赖延迟和文件大小做容量测试，不以 mock 性能替代

## 验证与发布

`main` CI 执行默认、Redis 和 Redis/jtokkit/otel 组合 profile 的 clean verify、真实 Redis JUnit、YAML/OpenAPI/模块布局、Python 基准测试，以及双引擎契约、隔离和管理/JWT 鉴权回归。

tag 必须与 Maven 版本一致（本版 `v0.3.0` / `0.3.0`）。推送 tag 后，Release workflow 先验证默认 JAR 及契约，再验证 Redis JAR；随后构建 Linux amd64 WebFlux native 并运行其流式错误与多协议契约。全部通过后才统一发布默认/Redis JAR、native、文档包及 SHA256SUMS。VT native 及其他平台需自行构建；native 契约不等于性能复测。

```sh
git push origin main
# 等待 main CI 通过后再标记已经验证的 commit
git tag -a v0.3.0 -m 'llmgw4j v0.3.0'
git push origin v0.3.0
gh run list --workflow release.yml
```

[版本说明](releases/v0.3.0.md)列出资产和兼容边界。发布产物下载后校验 SHA256SUMS；日志和 Actions 的 commit SHA 是验收依据，不能只看 tag 存在。本流程不发布容器镜像或 Maven Central；后者需要另行配置签名、命名空间、凭据及部署插件并独立验收。

### 资源和内存限制补充

文件与任务归属按稳定 key ID 和协议分区；遗留未分区绑定不授权访问，未知或失效归属返回 404。
请求体里的 `file_id` / `input_file_id` 也必须属于调用方，并位于当前路由；携带文件引用的请求不做跨上游 fallback。
文件和批任务列表暂返回 501，不能列出共享上游的所有资源。
默认 `gateway.max-response-bytes=4194304`、`gateway.max-concurrent-requests=64`；响应整包缓冲，超过大小上限报上游错误，超过并发上限报 503。
这两项限制同样作用于演示/压测，增加压测 VU 前应显式调整并检查错误率，历史吞吐数据不代表本次修复后的性能。
JSON 密钥文件仅支持单进程写入。Redis 包的运行时密钥管理接口暂返回 501；不能用共享 JSON 文件补成分布式密钥管理。
