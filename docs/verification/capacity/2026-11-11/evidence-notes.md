# 过程证据说明

- `default-verify.log`：第一批默认 profile 验证；其后增加了定时资源隔离测试，最终结果以本轮最终验证记录为准。
- `measurement/` 首批容量矩阵在 after-WebFlux / JSON / 64 VU 首轮停止，67/18429 非预期响应、未到上游。初版工具未采集异常正文，不能确定状态或根因；此轮不得用于零错误结论。
- 随后 `reprobe/` 在相同冻结 after JAR、相同 2C2G、64 VU 下新增错误状态/正文采样，2×15s 定点复测通过；复测通过本身不能抹去此前失败。
- `final-matrix/` 为带诊断采样的故障定位批次，before/after WebFlux 均出现 EOF/Broken pipe 的 502，已中止，未完成完整矩阵。它保留为上游连接关闭故障证据，不能作为正常容量结论。随后正常容量批次显式固定 mock 的空闲连接策略；两批不混算。
- 慢读取消为功能探针，执行期间另有 native 编译与 JVM 契约检查；只用于确认取消、active 清零、请求恢复与无 OOM，不据此评价延迟或吞吐。WebFlux 8 个上游均取消；VT 6 个取消、2 个已先完成。
- 原始数据目录均位于忽略构建产物的 `bench/results/capacity-2026-11-11/`；最终摘要和 SHA256 应复制到本目录，避免只依赖本地忽略产物。
- Python 系统环境缺少 PyYAML 的首次文档检查为环境失败，之后改用本机既有 `/tmp/llmgw-release-030-venv/bin/python`，文档检查通过，未修改全局 Python 环境。

- Native 首次隔离端口包装重复传入路由参数，导致 404；`native-*.log` / `native-contracts.json` 为启动配置失败，不计通过。包装改为仅补充未显式传入的默认参数后，同一新二进制的 `native-corrected-*` 通过 34 + 28 项。
- 首次租户回归因 mock 端口已占用而启动失败（`tenant-port-collision.log`）；其他检查结束后单独重跑，WebFlux/VT 均通过。没有重试生产请求或更改产品代码来掩盖这两类测试配置失败。

- 正常容量批次：110 样本，770,467 完整成功、750,151 明确容量拒绝、0 非预期错误、0 上游计数不符。早期已完成样本中的 67 / 75 个异常不与本批合并或删除，独立摘要在 `initial-probe/`、`connection-close-probe/`。
- WebFlux SSE p99 定点复核：同一冻结 JAR，15s 预热、4×15s 正反交错；11 样本（含预热/直连）73,519 成功、0 拒绝、0 非预期错误。作为独立复核，不拼接进主批。
