# Proposal

## Why

Agent 改完测试计划后，没有任何非人工通道能把改动落盘。工具集里没有保存工具；`skills/jmeter-cli` 在 4 处写明 "No save command / only the human can save"，外部 Agent 经 CLI 改完只能把保存交还人工；`/goal` 自主闭环跑完也不保证有文件落地。

目前唯一的落盘途径是 JMeter 的 save-before-run。它只对已关联文件的计划生效，而且只发生在运行之前，所以「改了没跑」「从未保存过的计划」「外部 Agent 要交付 `.jmx`」三类场景全部落空。

## What Changes

- **新增工具 `save_test_plan`**（`filePath?`、`overwrite?`）：
  - 不传 `filePath`：存回关联文件（Ctrl+S 语义）。
  - 传 `filePath`：另存到该路径并改关联（Save As 语义）。
  - 落盘复用 JMeter 自带的 Save 动作，与 Ctrl+S 完全等价，备份、清除"未保存"状态、结果收集器节点剥离、IPC jmxPath 同步全部一并带上。
  - 成功返回 `{filePath, fileName, mode, bytes}`。
- **四条保护规则**（拒绝路径一律不弹窗、不写盘）：
  - 计划从未保存且未传 `filePath`：报错并指引传 `filePath`，不猜路径。
  - `filePath` 必须是绝对路径、以 `.jmx` 结尾、父目录已存在。
  - 另存到已存在的文件必须显式 `overwrite: true`。
  - 关联文件位于 workspace `autosave/` 下时，无参保存拒绝。这既保护回滚快照，也防止"以为存好了，其实写进了隐藏的时间戳文件"。
- **调用纪律**：工具描述写明「仅在用户明确要求保存时调用」。goal 回合与委派回合不设硬阻断。
- **goal 完成时由模型自己保存**：goal 工作流指引明确要求「验证通过后、调用 update_goal(complete) 之前，由模型自己调 `save_test_plan`（无 filePath = 存回关联文件），并在最终答复中转述保存结果」。`update_goal` 本身不写盘；从未保存的计划按保护规则拒绝，指引模型向用户询问保存路径。
- **IPC 与 CLI**：`save_test_plan` 进入 `/tool` 白名单；新增 `jmeter-cli save [--file <path>] [--overwrite]` 与帮助条目。
- **文档全量同步**：
  - `skills/jmeter-cli` 3 个文件，翻转 "No save command" 陈述及其守门测试。
  - 内置 jmeter skill 的工具索引与快照恢复流程。
  - `AGENTS.md`、`documents/` 中英 4 份、CLI 手工用例文档。
- **测试**：工具校验与编排单测、goal 触发矩阵单测、白名单守卫、CLI 参数构建单测、真 GUI 手工用例。

非目标（明确不做）：

- 回合末自动保存、`/save` 斜杠命令、聊天面板保存按钮。
- "只写副本、不改关联"的导出模式。
- `filePath` 的目录沙箱（`agent.tools.filesystem.allowed.dirs` / `denied.dirs`）。与 `open_jmx_file` 现状一致，沿用既有 TODO 挂账项。
- 改 `run_test` 的 save-before-run 行为。已关联文件的计划在每次冒烟（含普通运行）起跑前就被 JMeter 写进真实 `.jmx`，没通过的中间态本就在磁盘上。本次保持现状，完成时的保存对这类计划是一次等价重写；另记 TODO。
- 证据新鲜度。冒烟通过后若又改了树，完成时保存的是改后状态，现有证据口径不拦截。
- 扩展 `get_script_info`。它的描述写着 "save status"，实际只返回路径，属既有的不准确。

## Capabilities

### New Capabilities

- `test-plan-save`: Agent 触发的测试计划落盘——`save_test_plan` 工具的保存/另存语义、四条保护规则、调用纪律、与 JMeter Save 动作等价的落盘与失败回传，以及 goal 完成存回所复用的保存语义。

### Modified Capabilities

- `cli-tool-surface`: `save_test_plan` 进入 IPC `/tool` 白名单；新增 `save` 动词（参数映射纪律与帮助体系）。
- `goal-autonomous-loop`: 证据齐全的 `complete` 由模型按指引自己调 `save_test_plan` 保存测试计划；最终用户可见输出包含保存结果。

## Impact

- **代码**：
  - 新增 `agent/tools/jmeter/PlanSaver.java`（保存内核：校验 → EDT 派发 JMeter Save → 成功信号 → 结果）与 `SaveTestPlanTool.java`。
  - `agent/tools/JMeterToolRegistry.java`：注册工具。
  - `agent/tools/goal/UpdateGoalTool.java`：description 补"完成前自己调 save_test_plan"的指引。
  - `agent/session/GoalRuntime.java` 与 `resources/templates/goal.md`：引导文本补一句。
  - `ipc/IpcServer.java`：`ALLOWED_TOOLS` 与拒绝文案。
  - `cli/JmeterCli.java`：`save` 动词（dispatch、参数构建器、SPECS 帮助）。
- **测试**：新增 `SaveTestPlanToolTest`；扩展 `GoalToolsTest`、`IpcServerAllowedToolsTest`、`JmeterCliTest`；翻转 `JmeterCliSkillPackageTest` 的一条不变量；`docs/test/jmeter-cli-test-cases.md` 新增 SAVE 用例组。
- **文档**：
  - `skills/jmeter-cli/{SKILL.md, references/cli-reference.md, references/workflows.md}`。
  - 内置 `skills/jmeter/SKILL.md` 与 `references/plan-snapshots.md`。
  - `AGENTS.md`、`documents/agent-tools{,_en}.md`、`documents/jmeter-cli{,_en}.md`。
  - `TODO/TODO.md` 只在既有 🈹 条目的描述上补一句。
- **行为变化**：证据齐全的 goal 完成时会写关联文件，此前只有 save-before-run 会写；新工具可覆写磁盘上的 `.jmx`。
- **兼容性**：纯增量，无配置项变更，无协议变更，旧版 CLI 客户端无感知。
- **安全**：IPC 放行后，持有 token 的本机进程可让 JMeter 覆写任意可写的 `.jmx`。写盘面由「仅 `.jmx` 扩展名」与「另存覆盖保护」约束；目录沙箱维持既有 TODO 状态，不随本变更引入。
