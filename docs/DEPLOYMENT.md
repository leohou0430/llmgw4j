# llmgw4j 部署指南（v13）

## TLS 立场

llmgw4j 与 LiteLLM、OneAPI 等应用级网关采取相同立场：**网关进程本身保持纯 HTTP，
TLS 在前置代理终止**。理由：

1. 字节保真是本网关的核心契约——TLS 属于传输层，与 SSE 契约/计费/容错正交，
   交给久经考验的 nginx/Envoy 比自建 JSSE 配置更可靠；
2. 前置代理同时承担 WAF、mTLS 客户端认证、全站限流——这些是 API 网关层的职责
   （见 gap-analysis「自研 vs 采用」一节）；
3. native image / fat jar 的零依赖优势不被证书管理复杂化。

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

## 多副本（-Predis）

```bash
docker build --build-arg MAVEN_PROFILES="-Predis" -t llmgw4j:redis .
# 两个副本共享 Redis：令牌桶 / 配额 / 熔断 / key 表 / 响应缓存全部跨副本一致
SPRING_PROFILES_ACTIVE=webflux java -jar llmgw.jar \
  --gateway.redis.enabled=true \
  --spring.data.redis.host=redis --spring.data.redis.port=6379
```

注意：`StickyTaskRoutes`（视频任务粘性）仍为实例内存态——多副本部署视频协议时，
轮询请求可能落到非创建副本，走 wildcard 路由兜底（任务只存在于创建上游，轮询会 502）。
生产多副本 + 视频协议时，请用会话粘性（nginx `hash $http_x_llmgw_task` 之类）或等待
sticky 路由的 Redis 化。

## 生产清单（P2 差距清零后）

- [ ] `LLMGW_ADMIN_TOKEN` 必设（否则 /admin 全开）
- [ ] `gateway.governance.snapshot-auto-restore=false`（基准纯净）或 true（生产配额恢复）
- [ ] 前置 TLS + /admin 内网隔离
- [ ] 观测：`-Potel` + OTLP collector（trace 上下文传播无需 profile）
- [ ] 预算：`gateway.api-keys[].budget-micro-usd` + 模型价目 `gateway.models[]`
- [ ] 内容治理：`gateway.guardrails.deny-patterns` 或外接引擎 `http-url`
