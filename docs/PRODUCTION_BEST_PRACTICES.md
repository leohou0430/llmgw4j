# llmgw4j 生产环境最佳实践

> **适用版本**：`0.1.0-SNAPSHOT`（Unreleased） · **文档日期**：2026-11-08
> **上游端点核实时间**：2026-11-08（大模型厂商迭代极快，模型名与限额以各厂商官网当日为准）
> **前置阅读**：[使用手册](USER_GUIDE.md) · [配置说明](CONFIGURATION.md)

---

## 0. 先泼冷水：什么场景敢用，什么场景别用

这份最佳实践写给"**内部平台 / 单团队 LLM 接入层**"这类场景：流量可控、有运维能力、能接受自己补 TLS 与监控。如果您要的是"对外多租户 SaaS 网关"，本项目作者自己都说它是参考实现 + 测量台，换 Kong / Higress / OneAPI / Higress AI 网关更靠谱——这种时候硬上 llmgw4j，不是勇敢，是给别人递刀。

| 适合 | 不适合（除非你自己补齐） |
| --- | --- |
| 统一管理 2–4 家国产大模型的 key、配额、限流 | 面向不可信外部租户的多租户计费 |
| Java 技术栈团队想自主可控、按需改治理逻辑 | 需要 TLS/mTLS/WAF 开箱即用 |
| 流式为主（SSE）的对话/Agent 场景 | 需要跨多实例强一致的财务级配额 |
| 单实例或双实例 + 前置负载均衡 | 期望零运维 |

---

## 1. 运行环境选型

### 1.1 JVM vs Native Image

| 维度 | JVM（推荐） | Native Image |
| --- | --- | --- |
| 冷启动 | 3.5–7s（K8s 滚动更新需配就绪探针容忍） | 0.4–0.6s |
| SSE p99 | 更好 | 较差（macOS 只能 Serial GC；Linux 才可用 G1） |
| 产物 | fat jar 约 127MB | 单二进制约 283MB |
| 每二进制引擎 | 一个 jar 两种引擎 | **一个二进制只装一个引擎**（AOT 构建期求值 `@Profile`） |
| 构建成本 | 秒级 | 单二进制 6–9 分钟，峰值 RSS 约 11GB |
| 结论 | **生产默认选 JVM** | 只在 Serverless/极简镜像场景考虑，且必须 Linux + `NATIVE_GC=g1` |

### 1.2 基础环境

| 项 | 建议 |
| --- | --- |
| OS | Linux x86_64 或 ARM64（Ubuntu 22.04+/Rocky 9；macOS 仅开发） |
| JDK | **JDK 25**（Temurin 25 或 Oracle GraalVM 25）。不要用更老版本，代码用了 Java 25 特性 |
| GC 陷阱 | Oracle GraalVM 25 上 **`UseShenandoahGC` + JVMCI 会直接 VM 崩溃**（`hs_err_pid*.log` 那次就是）；Shenandoah 实验用 Temurin，生产用 G1 或 ZGC |
| 运行方式 | systemd unit 或容器，`Restart=on-failure`；不要 nohup |
| 时钟 | NTP 同步（审计与账本时间戳） |
| 构建 | CI 里 `./bin/build.sh` 产 jar；**native 编译后必须重新 `build.sh` 重建干净 jar 再压测**（AOT 类会污染 target） |

### 1.3 容器化要点

- 基础镜像 `eclipse-temurin:25-jre`（带 JRE 即可，不需要 JDK）。
- 内存限制用 `-XX:MaxRAMPercentage=75` + 容器 limit 1.5 倍堆，别只给 `-Xmx`。
- SSE 长连接多，注意容器 runtime 的连接跟踪与空闲超时（如 K8s 的 `--disable-keep-alive` 类问题不必担心，Netty/Tomcat 自管，但**云 LB 的空闲超时要大于 SSE chunk 间隔**，见 4.4）。
- 就绪探针：`GET /actuator/health`；JVM 启动按 10s 宽限配置。

---

## 2. 机器配置

llmgw4j 是**IO 密集 + 轻计算**型：转发不缓冲流式 body，CPU 主要花在 JSON/SSE 行解析。按并发规模给规格：

| 规模 | 并发 SSE 连接 | 规格 | 说明 |
| --- | --- | --- | --- |
| 小（内部工具/PoC） | ≤100 | 2 vCPU / 4GB / 5Mbps | 单实例足够 |
| 中（团队级） | 100–500 | 4 vCPU / 8GB / 50–100Mbps | 单实例，或双实例 + LB |
| 大（平台级） | 500–2000 | 8 vCPU / 16GB / 200Mbps+ | 双实例起步 + LB；治理态需 Redis（见 6） |

**带宽是第一瓶颈**，不是 CPU：SSE 每 chunk 约 100–200B JSON 包装，500 并发 × 30 chunk/s ≈ 3–6MB/s ≈ 30–50Mbps。云主机选固定带宽而非按流量猝死。

**内核与系统参数（必调）**：

```bash
# 每个并发请求占用 2 个 fd（入站连接 + 上游连接），1k 并发 ≈ 2k+ fd
ulimit -n 65535          # systemd 下用 LimitNOFILE=65535

# /etc/sysctl.d/llmgw.conf
net.core.somaxconn = 4096
net.ipv4.tcp_max_syn_backlog = 8192
vm.swappiness = 1        # 网关进程拒绝 swap
```

**JVM 参数基线**（`run-*.sh` 已默认带 GC 参数，可整体覆盖 `GATEWAY_GC_OPTS`）：

```bash
GATEWAY_GC_OPTS="-XX:MaxGCPauseMillis=10 -XX:+AlwaysPreTouch -XX:+UseStringDeduplication"
# 堆 2–4G 即可（非流式响应才整包缓冲，单请求 <4MB 上限兜底）：
java $GATEWAY_GC_OPTS -Xms2g -Xmx2g -jar llmgw-app-0.1.0-SNAPSHOT.jar ...
```

> `executor-threads` 保持默认 32（实测 8/16/32/64 扫描的拐点），机器只有 2 核时可降到 16。

---

## 3. 生产网关配置（治理参数推导）

### 3.1 安全基线（无商量余地）

```bash
export LLMGW_ADMIN_TOKEN="$(openssl rand -hex 24)"   # /admin/* 必须 401 之外不可达
export server 监听 --server.address=127.0.0.1        # 只监听回环，由 Nginx/Envoy 对外
```

- **必须前置 Nginx/Envoy/云 LB 做 TLS 终结**：llmgw4j 没有内建 TLS，裸奔 8080 等于把 `sk-` 密钥Broadcast到内网。
- **删除 `sk-demo`**，`gateway.api-keys` 全部换成强随机 token，按调用方分 key（账本/限流都按 keyId 分桶，一把大钥匙等于裸奔）。
- 上游厂商密钥只走环境变量；`/admin/routes` 只回 `apiKeyConfigured` 布尔值，但配置文件本身别进 git。
- 管理端口不建议对公网开放；如需远程运维，走 VPN/bastion 或由 Nginx 单独暴露 `/admin` 并加 IP 白名单 + BasicAuth 双层。

### 3.2 治理参数推导逻辑

| 参数 | 建议值 | 推导 |
| --- | --- | --- |
| `gateway.max-request-bytes` | `4194304`（保持 4MB） | 上下文再长也该由客户端分片；调大等于给 OOM 攻击开门 |
| `gateway.upstream.timeout-ms` | **150000–300000** | 默认 60s 是整体超时。思考模型（DeepSeek thinking、GLM-5、Kimi K3.1）长推理轻松超 60s，不改就是批量大模型请求被打成 502 |
| `governance.rate-limit-tokens-per-second` | 按厂商配额 ÷ 1.5 | 网关限流要低于上游限流，让请求在网关被温柔拒绝（429）而不是在上游被粗暴拒绝（502） |
| `governance.rate-limit-burst` | 2× per-second | 吸收突发 |
| `api-keys[].quota-tokens` | 按月预算折 token | 注意配额是**软上限**（估算预留 + 并发可略超），财务级硬控要靠预算告警兜底 |
| `circuit-failure-threshold` | `5`，`circuit-cooldown-ms` | 保持默认；开路零网络 I/O 直接走 fallback，比干等上游超时便宜 |
| `cache-max-size` / `cache-ttl-seconds` | `2000` / `600` | 缓存只对**非流式**生效（键 = keyId + 整个请求体哈希，含采样参数）；流式业务多就别指望缓存扛量 |
| `governance-enabled` | `true`，永不在生产关 | 关掉后 key/配额/限流全裸 |

### 3.3 可观测性

- Prometheus 抓 `:8080/actuator/prometheus`，重点指标：`llmgw_ttft`（p99）、`llmgw_overhead`、`llmgw_denied_total{reason=...}`、`llmgw_circuit_*`、`llmgw_requests_total{route=...}`。
- 日志：默认只输出到 stdout，容器/系统平台收集即可；**没有日志轮转配置**，裸机部署用 systemd journald 兜底。
- 审计事件看 `GET /admin/snapshot` 的 `recentAudit`（环形队列 5000 条），接 ELB/Loki 需自己轮询导出。

---

## 4. 下游真实大模型配置（四厂商）

### 4.1 端点与模型速查（2026-11 核实）

| 厂商 | base_url（网关填到这一级） | 当前主力模型 | 环境变量 |
| --- | --- | --- | --- |
| 阿里百炼 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen3.8-max` / `qwen3.7-plus` / `qwen3.8-flash`（国际站：`dashscope-intl.aliyuncs.com`） | `DASHSCOPE_API_KEY` |
| DeepSeek | `https://api.deepseek.com` | `deepseek-flash`（`deepseek-chat/reasoner` 已于 2026-07 弃用；`deepseek-v4-pro` 正在退役中） | `DEEPSEEK_API_KEY` |
| Kimi（月之暗面） | `https://api.moonshot.cn/v1` | `kimi-k3.1-pro` / `kimi-k2.7-code` / `moonshot-v1-128k` | `MOONSHOT_API_KEY` |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | `glm-5.3` / `glm-5.1` / `glm-5` | `GLM_API_KEY` |

**四条硬约束（都来自网关实现，不是厂商规定）**：

1. `targetUri()` 会自动追加 `/chat/completions`——base_url **填到 `/v1` 或 `/v4` 这一级**，别把 `/chat/completions` 写进去，否则变成 `/v1/chat/completions/chat/completions`，厂商给你 404 的时候可别怀疑人生。
2. 网关**只支持 Bearer API Key 鉴权**：GLM 的 JWT 鉴权模式用不了，用 API Key 模式即可。
3. 路由匹配只有两档：**精确 model 名** 和 `*` 通配。不支持 `qwen-*` 这类前缀通配——想要"所有 qwen 模型走百炼"，就把每个模型名各写一条 route，或干脆按 `*` 兜底 + `target-model` 映射。
4. 请求体除 `model`/`messages`/`stream` 外**原样透传**：`thinking`、`reasoning_effort`、`temperature`、工具调用等扩展参数直接放在请求体里传即可，网关不认识但不拦截。

### 4.2 完整生产配置示例（`application-prod.yml`，四厂商混合 + 互备）

```yaml
spring:
  threads:
    virtual:
      enabled: true

server:
  address: 127.0.0.1        # 只听回环，TLS 交给前面的 Nginx
  port: 8080

gateway:
  governance-enabled: true
  admin-token: ${LLMGW_ADMIN_TOKEN}
  max-request-bytes: 4194304

  upstream:
    type: openai-compatible
    base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
    api-key: ${DASHSCOPE_API_KEY}      # route 未配 key 时的兜底
    timeout-ms: 180000                 # 180s：给思考模型留足推理时间
    executor-threads: 32

  governance:
    default-quota-tokens: 10000000
    rate-limit-tokens-per-second: 50000
    rate-limit-burst: 100000
    cache-max-size: 2000
    cache-ttl-seconds: 600
    snapshot-path: /var/lib/llmgw/llmgw-snapshot.json   # 数据盘，别写工作目录
    circuit-failure-threshold: 5
    circuit-cooldown-ms: 30000

  mock:
    enabled: false            # 生产必须关 mock，否则抢 9090 端口

  routes:
    # ---- 精确 model 路由：谁家的模型走谁家 ----
    - id: dashscope-qwen
      model: "qwen3.7-plus"
      target-url: https://dashscope.aliyuncs.com/compatible-mode/v1
      api-key: ${DASHSCOPE_API_KEY}
      weight: 3
      fallbacks: [glm-qwen-sub]
    - id: deepseek
      model: "deepseek-flash"
      target-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_API_KEY}
      weight: 1
      fallbacks: [kimi]
    - id: kimi
      model: "kimi-k3.1-pro"
      target-url: https://api.moonshot.cn/v1
      api-key: ${MOONSHOT_API_KEY}
      weight: 1
      fallbacks: [deepseek]
    - id: glm
      model: "glm-5.3"
      target-url: https://open.bigmodel.cn/api/paas/v4
      api-key: ${GLM_API_KEY}
      weight: 1
      fallbacks: [dashscope-qwen]
    # ---- 模型改名映射：业务代码继续发 qwen-plus，网关改写成 qwen3.7-plus ----
    - id: glm-qwen-sub
      model: "glm-5.1"
      target-url: https://open.bigmodel.cn/api/paas/v4
      target-model: "glm-5.1"
      api-key: ${GLM_API_KEY}
      weight: 1
      fallbacks: []

  api-keys:
    - id: svc-chat
      token: ${GATEWAY_KEY_CHAT}          # 每个调用方一把 key
      name: 聊天服务
      quota-tokens: 50000000
      allowed-models: [qwen3.7-plus, deepseek-flash, kimi-k3.1-pro, glm-5.3, glm-5.1]
    - id: svc-agent
      token: ${GATEWAY_KEY_AGENT}
      name: Agent 服务
      quota-tokens: 200000000
      allowed-models: []                  # 空 = 不限模型
```

启动：

```bash
export LLMGW_ADMIN_TOKEN DEEPSEEK_API_KEY MOONSHOT_API_KEY GLM_API_KEY \
       DASHSCOPE_API_KEY GATEWAY_KEY_CHAT GATEWAY_KEY_AGENT
java -XX:MaxGCPauseMillis=10 -Xms2g -Xmx2g \
  -jar llmgw-app/target/llmgw-app-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=webflux --spring.config.additional-location=application-prod.yml
```

### 4.3 各厂商专属注意点

**阿里百炼（DashScope）**
- 仓库 `real` profile 已内置此端点，`target-model: qwen-plus`；想用新模型就 `--gateway.routes[0].target-model=qwen3.7-plus`。
- 免费额度与限流按模型分级，`qwen3.8-flash` 限流最宽，适合当 fallback。

**DeepSeek**
- 官方 base_url 是 `https://api.deepseek.com`（`/v1` 也兼容）——网关会追加 `/chat/completions`，两种写法都正确。
- `deepseek-flash` 支持思考模式（请求体加 `"thinking": {"type":"enabled"}`），思考模式下耗时显著变长，务必把 `timeout-ms` 调大。
- 官方并发上限约 2500（Flash）/500（Pro），网关限流应设得更低。
- 它家支持 Anthropic 协议端点 `/anthropic`，但**网关只走 OpenAI 兼容格式**，别填错。

**Kimi（Moonshot）**
- base_url 必须带 `/v1`：`https://api.moonshot.cn/v1`。
- `kimi-k3.1-pro` 支持 1M 上下文与视觉，`reasoning_content` 会在流式 chunk 里透传——网关不解析 chunk 内容，客户端自己处理。
- 国内站 `api.moonshot.cn`，国际站 `api.moonshot.ai`，key 不互通。

**智谱 GLM**
- base_url 到 `/api/paas/v4` 这一级。
- `glm-5.3` 默认深度思考（`"thinking": {"type":"enabled"}` 显式传），同样注意超时。
- 支持 JWT 鉴权但网关用不上；API Key 直连即可。

### 4.4 Nginx 前置配置（TLS + SSE 友好）

```nginx
server {
    listen 443 ssl http2;
    server_name llmgw.example.com;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header X-Real-IP $remote_addr;

        # SSE 关键：关闭缓冲、放宽空闲超时（思考模型首 token 可能等很久）
        proxy_buffering off;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;

        # /admin 单独收紧
        location /admin/ {
            deny all;               # 或 allow <运维网段> + BasicAuth
            proxy_pass http://127.0.0.1:8080;
        }
    }
}
```

云 LB（ALB/CLB）同理：空闲超时 ≥ 300s，关闭响应缓冲/开启流式透传。

---

## 5. 验证与验收（上线前必跑）

```bash
# 1) 四条上游连通性（各打一发真模型）
curl -sS http://127.0.0.1:8080/admin/verify -H "X-Admin-Token: $LLMGW_ADMIN_TOKEN"
for M in qwen3.7-plus deepseek-flash kimi-k3.1-pro glm-5.3; do
  curl -sS -X POST http://127.0.0.1:8080/v1/chat/completions \
    -H "Authorization: Bearer $GATEWAY_KEY_CHAT" -H 'Content-Type: application/json' \
    -d "{\"model\":\"$M\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":8}" | head -c 300
  echo " <- $M"
done

# 2) 流式 + 思考模型超时验证（重点！默认 60s 会挂）
curl -N -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H "Authorization: Bearer $GATEWAY_KEY_CHAT" -H 'Content-Type: application/json' \
  -d '{"model":"deepseek-flash","messages":[{"role":"user","content":"证明根号2是无理数"}],"stream":true,"thinking":{"type":"enabled"}}'

# 3) 熔断/fallback 演练：把某条 route 的 target-url 改成死端口，确认自动切换且无内容拼接
# 4) 全量契约回归（67 项）
./bin/smoke-stream-errors.sh
# 5) 压测验收：BENCH_* 大阈值 + 真实比例 json:sse
```

**验收标准建议**：p99 TTFT ≤ 厂商官方首 token 延迟 + 50ms；`llmgw_overhead` p99 ≤ 20ms；错误率（排除客户端 4xx）< 0.1%；fallback 演练零内容重复。

---

## 6. 多实例与高可用

- **治理态默认单实例内存态**：配额、限流、缓存、熔断、审计全部进程内。多实例 = 各管各的账。
- 起第二步再上 `-Predis` 产物 + `--gateway.redis.enabled=true`（只覆盖令牌桶/账本/熔断三个 SPI，`ApiKeyRegistry`/缓存/快照仍是本地；每请求多 2–4 次 Redis 往返）。
- 负载均衡用 Nginx `least_conn` 或云 LB，健康检查打 `/actuator/health`。
- 快照 `snapshot-path` 是本地文件：多实例时它**不是**共享状态，只做单机重启后的用量参考，别当账本。
- 发布顺序：新实例起 → `/actuator/health` 通 → 打一条真请求 → LB 摘旧实例。JVM 冷启动 3–7s，滚动更新别太快。

---

## 7. 上线检查清单

- [ ] `LLMGW_ADMIN_TOKEN` 已设置且 `/admin` 未对公网开放
- [ ] 前置 TLS（Nginx/云 LB），网关 `--server.address=127.0.0.1`
- [ ] `sk-demo` 已删除，调用方按服务分 key，`allowed-models` 白名单收口
- [ ] 四厂商 key 走环境变量注入，配置文件无任何 `sk-` 明文，`.env` 不进 git
- [ ] `mock.enabled=false`；`timeout-ms ≥ 180s`（用思考模型时）
- [ ] 限流/配额 < 各厂商官方限额；月预算告警独立于网关配额
- [ ] `ulimit -n ≥ 65535`；`proxy_buffering off`、LB 空闲超时 ≥ 300s
- [ ] Prometheus 已抓 `/actuator/prometheus`，TTFT p99 / 502 率 / 熔断事件有告警
- [ ] `smoke-stream-errors.sh` 全绿；fallback 演练通过；思考模型流式超时验证通过
- [ ] 明确知晓并接受：配额是软上限、headers 不透传（`x-ratelimit-*`、`OpenAI-*`）、缓存仅非流式、多实例治理需 Redis
