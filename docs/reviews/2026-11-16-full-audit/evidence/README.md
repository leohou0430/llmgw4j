# 本轮证据说明

当前核心反例见 current-runtime-results.json；每个引擎分别建立独立本地 SQLite 和 mock，不使用外部模型。

- runtime_probe.py：设置 AUDIT_OUT 指向新建目录，该目录内放 audited.jar；不可复用已有测试数据库。脚本会临时更改自身数据库内的角色作为夹具，注入 audit_event 触发器，并重启自己启动的进程。stdout 对会话 cookie 脱敏。
- StructuralProbe.java：需将对应 Boot JAR 的 BOOT-INF/classes 和 BOOT-INF/lib/*.jar 展开到类路径；使用全新工作目录运行，包含并发回滚、草稿交错、分页和 WAL 探针。
- spool_crash_probe.py：仅对自己启动的 WebFlux 进程强杀；最后删除本次暂存原文。VT 只做源码核对，未强杀实测。
- run_contracts.py：本轮临时驱动器，不是新增 CI。最终管理/JWT 复测显式传 --jar；本轮排除了初次端口/启动超时/可变 JAR 批次，详见 verification.json。

不能把 stream-errors-retry 的部分 PASS 行计作整套通过。不能将 baseline 与 current 的摘要混为一个产物。JAR 只保留于本机 /tmp，不作为发布资产提交。

源码在审查期间有其他工作持续修改，current-source-hashes.json 是产物复制时的源码观察值，并非原子构建快照；后续差异见 verification.json。历史报告未改写。
