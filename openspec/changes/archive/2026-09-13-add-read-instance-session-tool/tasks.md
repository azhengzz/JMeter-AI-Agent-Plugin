# Tasks: add-read-instance-session-tool

> 实现约定：全程不执行 git add/commit/push（用户自行提交）；遵守 CLAUDE.md「Surgical Changes」——只新增工具类与一处注册，不动 SessionManager/IPC 现有代码。

## 1. 工具实现（ReadInstanceSessionTool）

- [x] 1.1 新建 `org.gitee.jmeter.ai.agent.tools.ipc.ReadInstanceSessionTool`（extends AbstractTool）：`NAME=read_instance_session`、description（说明用途/有界/不可信/先 list_instances 发现对端）、参数 schema（`instanceId` 必填、`query` 可选）、`isConcurrencySafe()=true`；公开构造经 `AiConfig.getWorkspacePath()` 取 workspace，包私有构造注入 `Path` 供单测。verify: `mvn clean test-compile` 编译通过
- [x] 1.2 参数校验与自身排除：`instanceId` 缺失/空白报错；`query ∈ {"*", ".*"}` 报"字面子串匹配、省略即读最近消息"；`instanceId` 等于 `InstanceContext.instanceId()` 报"当前对话已在上下文"（单测环境需 `InstanceContext.init()` 或对未初始化容错）。verify: 单测覆盖三个分支
- [x] 1.3 会话文件定位 + 流式解析：按 `SessionManager` 同款 `safeFileName` 规范化定位 `sessions/{key}.jsonl`；逐行 Jackson 仅取 `role`/`content`/`timestamp`（metadata 行取 `updated_at`），损坏/半截行跳过并 debug 日志；流式扫描 + 定长缓冲只保留**最近 8 条**匹配消息（user/assistant 且 content 非空；`query` 非空时 casefold 子串命中才算匹配），同时统计可见消息总数。verify: @TempDir jsonl fixture 单测——tool/system 跳过、撕裂行容忍、20 条只留最后 8 条、query 过滤命中集
- [x] 1.4 节选与输出渲染：移植 Nanobot `_excerpt`（空白压缩、命中点前 1/3 居中、4000 字符上限、两侧省略号）；头部输出 instanceId、live/not live 标注、last updated、可见消息总数、不可信数据告警、query 说明；消息块格式 `[i] role @ timestamp` + 节选内容。verify: 单测断言超长截断、命中居中偏移、告警与头部字段齐全
- [x] 1.5 边界与错误路径：文件不存在 → 明确错误并提示先 `list_instances`；query 无命中 → 明确"无匹配消息"；live 标注经 `InstanceRegistry.listInstances` 匹配 instanceId，注册表目录缺失或无该实例时标 `not live`（IPC 关闭不致错）。verify: 单测（无注册表场景断言 not live 降级）

## 2. 注册接线

- [x] 2.1 `JMeterToolRegistry.registerInstanceCoordinationTools` 在既有 IPC 块**外**新增注册：每实例会话模式开启（`InstanceContext.currentSessionKey()` != `LEGACY_SESSION_KEY`）时注册，并打日志说明门控；legacy 模式不注册。verify: 单测两态断言注册表含/不含 `read_instance_session`（IPC 开关两态 × per-instance 两态）

## 3. 集成验证

- [x] 3.1 跑 `mvn clean test` 全绿（防增量编译 stale class 掩盖问题，用 clean；含既有回归）—— 570 run / 0 fail / 11 既有跳过
- [x] 3.2 手动联调（可选，装到 JMETER_HOME 后）：起两个 JMeter 实例，实例 A 聊几轮 → 实例 B `list_instances` 后 `read_instance_session(instanceId=A)` 读到 A 的最近消息；关掉 A 后再读（应标 not live 仍可读）；`jmeter.ai.ipc.enabled=false` 重启 B 验证工具仍注册可用（用户 2026-09-13 确认已完成测试）
