# Proposal

## Why

Agent 回合中的工具（create/update/delete/move 元素等）持续修改测试计划树，改动只存在于内存。一旦 agent 改坏脚本、或修改后的状态被保存覆写，用户没有任何「回到某一次回合之前」的磁盘基线可依托。现有 autosave 只在 `run_test` 启动且计划未保存时触发，覆盖面窄（不覆盖纯改脚本回合），且定位是防弹窗卡死，不是回退基线。需要回合级的快照基线，为后续回退/还原提供准确落点。

## What Changes

- **新增回合级快照机制**：每个 agent 回合（本地面板/IPC CLI/IPC 委派/goal 续跑，不含不改树的纯命令回合）启动时在内存持有测试树基线；回合到达终态（正常完成/用户 Stop/异常中止/会话重置四路全覆盖）时再次采样比对，**树发生变化才把回合起点基线落盘**为一份 `.jmx`。无任何门控（干净已保存计划也拍——磁盘文件可能在回合中被 save-before-run/Ctrl+S 覆写，不能当基线）；回合内用户手改同样算变化。
- **全异步落盘**：序列化、比对、写盘走专用单线程执行器；EDT 只承担回合两端的树克隆（毫秒级）；起点序列化也异步，首 token 零延迟。任何快照失败只记日志（best-effort），绝不影响 agent 运行。
- **关闭冲刷**：JVM 关闭时对未落盘的快照任务做有界排空（约 2 秒），防止「干完活立刻关 JMeter」丢失最后一份基线。
- **统一快照命名与目录**：回合快照与 `run_test` autosave 统一放 workspace `autosave/` 目录；文件名编码 `{时间戳}-{instanceId}-{turnId}-{origin}`，保证时间序、跨实例隔离（双实例共享目录已坐实）与「精确对应到某次回合」。顺带修复：`run_test` 现有 autosave 文件名补 instanceId（消除跨实例撞名）。
- **可发现性只走文档**：技能文档写明快照目录、命名规则与 turnId 对应关系；恢复靠既有 `open_jmx_file` 手动打开，不新增 `list_snapshots` 之类的工具。
- **`run_test` 既有 autosave 机制原样保留**：它兼任防 `popupShouldSave` 弹窗卡死 EDT 的职责，回合快照替代不了；仅统一目录/命名外围。

**非目标（明确不做）**：专用恢复工具/`/rollback` 命令（另立变更）；快照清理/TTL（接受累积）；审计轨迹（变更摘要/diff 元数据）；崩溃恢复（进程回合中途崩溃/回合尾段退出无文件——含已知缺口：回合内保存（Ctrl+S 或 run_test 的 save-before-run）已把回合中状态覆写上盘、天然基线消失的叠加情形，见 design.md R9，接受）；任何新增配置项/开关。

## Capabilities

### New Capabilities

- `turn-plan-snapshot`: 回合级测试计划快照——回合起点内存基线捕获、四终态比对与条件落盘、异步执行与关闭冲刷、快照文件身份命名（含 run_test autosave 命名修复）、内容纯净（不泄漏收集器、不动文件关联）、文档化可发现性。

### Modified Capabilities

（无——`run_result-capture`/`agent_turn_events`/`early_user_message_persist` 等既有规格的需求均不变化；run_test autosave 机制不在任何主规格中，其命名修复并入新 capability 的规格。）

## Impact

- **新增**：`org.gitee.jmeter.ai.agent.snapshot` 包（快照服务：单线程执行器、基线捕获/比对/落盘 API、命名工具、关闭冲刷）。
- **修改**：`AgentRunner`（回合起点捕获 + 终态比对接线）；`AgentRunSpec`（可空 turnId/origin 透传字段与 builder）；`AgentLoop`（startTurn 构造 spec 时从 TurnHandle 填入身份）；`RunTestTool.pointUnsavedPlanAtAutosave`（文件名补 instanceId）；shutdown hook 挂载点（复用 CloseConsolidationCoordinator 同款基础设施注册有界排空）；`skills/jmeter` 文档（新增快照参考文档 + SKILL.md 链接）。
- **不受影响**：`run_test` 的重定向/防弹窗逻辑、Save 前后置监听器剥注采集器舞蹈、IPC 端口文件回写路径、既有全部配置项。
- **测试**：四终态覆盖验收、异步顺序、无 GUI 环境 no-op 降级、run_test 命名回归。
