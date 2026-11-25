# Design

## Context

动机见 proposal.md - Why。塑造本设计的现状事实（规划期已对照 JMeter 5.6.3 源码与插件代码核对）：

1. **JMeter 的 Save 动作有两个入口**。`Save.doAction` 在 `GuiPackage.getTestPlanFile()` 非空时直接写该文件（零交互）；为空时走 `computeFileName()` 弹文件选择框——自主回合无人应答，卡死 EDT。因此"另存为"免弹窗的唯一做法是先 `setTestPlanFile(目标)` 再派发 `SAVE`。`RunTestTool.pointUnsavedPlanAtAutosave` 对未保存计划用的是同一招。
2. **ActionRouter 吞异常**。`ActionRouter.performAction` 把每个 `Command.doAction` 包在 try 里：`IllegalUserActionException` 走 `JMeterUtils.reportErrorToUser`（模态错误框），其它异常只记日志，调用方拿不到任何失败信号。但 `postActionPerformed` 只在 `doAction` 正常返回后才执行，这给了一个精确的完成信号。
3. **走 ActionRouter 的价值在簿记**。派发 `SAVE` 会连带触发：`CHECK_DIRTY`（算脏）→ 脏则备份 → 写文件 → `SUB_TREE_SAVED`（清脏、刷新已保存快照）→ `FileServer.setScriptName` → 插件挂在 `Save.class` 上的预监听（剥离结果收集器节点）与后监听（端口文件 jmxPath 同步）。直写 `SaveService.saveTree` 这些都得重做，漏一项就是"存了但关窗仍问要不要保存"。
4. **performAction 的前置回写**。`performAction` 在命令前调用 `GuiPackage.updateCurrentGui()`，把当前面板状态回写当前节点（含 enabled 缓存）。这与 Ctrl+S、`run_test` 的 save-before-run 是同一风险面：各树工具收尾的 `refreshCurrentGui()` 已把面板重配为当前节点，窗口只在"用户手选了别的节点而面板未重配"时存在。用户在面板里尚未应用的编辑会被一并保存，这是 Ctrl+S 的期望行为。
5. **`isDirty()` 是惰性值**，只在 `CHECK_DIRTY` 派发时重算。工具无法廉价得知"计划相对磁盘有没有改动"，因此不做"无改动则跳过"。
6. **save-before-run 已写盘**。`jmeter.properties` 默认 `save_automatically_before_run=true`（本机无覆盖）。已关联文件的计划，每次 `run_test`（含冒烟）起跑前就已把当前树写进真实 `.jmx`。goal 完成时的存回对这类计划是等价重写（计划此时不脏，不产生备份）；对从未保存的计划才是唯一落盘点。
7. **goal 的 complete 有两种入口**。`GoalState.canComplete(limit)` = 有证据（静态 PASS + 冒烟 PASS）或 `smokeAttempts >= limit`。保存指引只针对前者：验证通过后由模型自己调 `save_test_plan`。存不了的三种情形（从未保存、关联在 autosave、写盘失败）里，"从未保存"在从零建脚本的 `/goal` 中是常态。
8. **goal 引导文本的载体分两处**。guidance 段来自 workspace 的 `goal.md`（`WorkspaceInitializer` 只补缺失文件，旧 workspace 不更新）；状态块由 `GoalRuntime.statusLines` 每回合在代码侧实时渲染。non_test_plan 的口径正是放在 statusLines 以覆盖旧模板。
9. **IPC 侧 registry 与 Agent registry 是两个实例**。`IpcServer.registry()` 用 `JMeterToolRegistry.registerDefaultTools(r)` 单参构造（sessionManager=null）。新工具无会话依赖，注册一次两边可达。
10. **CLI 是 thin client**。`JmeterCli` 零 JMeter 类依赖，动词 = 参数 Map + `execTool`，参数构建器是 package-private static 供单测。`IpcServerAllowedToolsTest.cliDispatchedToolCatalogMatchesAllowlistExactly` 钉定"CLI 投递目录 == 白名单"，新增动词必须双侧同步。工具错误经 IPC 回 HTTP 200 + success=false，CLI 退出码 1。
11. **autosave 目录** = `WorkspacePaths.resolveWorkspace().resolve(SnapshotNames.AUTOSAVE_DIR)`。`open_jmx_file` 打开其中的文件后，计划关联就指向该文件——这是 `plan-snapshots.md` 第 5 步告警的由来。
12. **测试现实**。真实 `Save` 经 ActionRouter 需要完整 GUI 启动（`populateCommandMap` 仅由 `JMeter.startGui` 调用），`mvn test` 覆盖不了真写盘路径。项目惯例是单测钉参数面与编排，真 GUI 走手工用例（`docs/test/jmeter-cli-test-cases.md`）。

## Goals / Non-Goals

**Goals:**

- 一套保存内核，工具与 goal 完成两条调用路径共用，保护规则只写一遍。
- 成功与失败如实回传给模型，不以"派发过"当成功。
- 所有拒绝路径零弹窗。
- 旧 workspace 的 goal 行为由代码承载。

**Non-Goals:**（proposal 非目标之外的设计层边界）

- 不抽通用的"JMeter 动作派发"框架，只做 Save 这一个动作。
- 不建 GUI 自动化集成测试脚手架。
- CLI 不理解保存语义：路径校验与覆盖保护全在工具端。

## Decisions

### D1：保存内核 `PlanSaver` 加两个薄壳

`PlanSaver`（`agent.tools.jmeter`，public final）承载全部语义：读关联 → 保护规则 → `filePath` 形态校验 → 可写性预检 → EDT 派发 → 成功信号 → 产出 `SaveOutcome`（成功：路径 + 字节数 + 模式；失败：原因 + 下一步）。`SaveTestPlanTool` 把 outcome 渲染成 JSON，`UpdateGoalTool` 把 outcome 渲染成回执文本。

- 理由：spec 要求 goal 存回等价于一次无 `filePath` 的 `save_test_plan`，两条路径的规则必须逐字相同，只写一遍才保证。
- 备选否决：goal 里直接执行 `SaveTestPlanTool` 再解析其返回文本——耦合面向模型的文本格式，不适合程序消费。

### D2：复用 JMeter 的 Save 动作，经 ActionRouter 派发

在 `EdtRunner.run` 的 EDT 动作内调用 `ActionRouter.getInstance().doActionNow(new ActionEvent(source, ACTION_PERFORMED, ActionNames.SAVE))`。另存为：先 `gui.setTestPlanFile(absPath)` 再派发。

- 理由：见 Context 第 3 条，簿记全部免费带上，与 Ctrl+S 逐位等价。
- 备选否决：直调 `new Save().doAction()`——能拿到异常且不弹框，但绕过预/后监听链（收集器剥离、jmxPath 同步），要手工复刻，未来新增的监听也会漏。
- 派发前关联非空由内核保证，因此不会进入文件选择框分支。
- EDT 等待超时或被中断沿用 `EdtRunner` 既有契约文案透传。保存是幂等重写，迟到执行不会放大副作用。

### D3：成功信号用 `Save.class` 的一次性后置探针

派发前在 EDT 上注册一个一次性的 `Save.class` post-action listener（置位一个 `AtomicBoolean`），`finally` 中注销。探针置位且目标文件存在才算成功。

- 理由：`postActionPerformed` 只在 `doAction` 正常返回后执行，恰好是"写盘完成且未抛错"的精确定义。对比按 mtime 或大小比较是启发式的，粗粒度时间戳的文件系统上同秒重写会误判失败。注册与注销都在同一次 EDT 派发内完成，不与 EDT 并发访问监听表。
- 可写性预检在 worker 线程做，目的只是把 JMeter 的模态错误框压到最小，不是正确性依据。目标是目录直接拒；目标已存在查其可写，不存在查父目录可写。
- 残留：预检漏网的写盘失败（如文件被其它进程独占）仍会留下 JMeter 自己的模态错误框，同时工具如实返回失败。

### D4：保护规则的判定位置与边界

- 关联文件与 autosave 判定在 EDT 动作内读权威值；`filePath` 形态校验与可写性预检在 worker 线程，纯文件系统操作。
- autosave 判定：关联路径规范化后 `startsWith` 规范化的 workspace `autosave/`，大小写语义交给文件系统。
- 传了 `filePath` 一律按另存处理：目标已存在就要 `overwrite`，不做"等于当前关联文件"的特例。一条规则，零特例。
- 另存失败不回滚关联：JMeter 自身的 Save As 同样先设关联后写盘，失败时关联留在新路径，保持等价。
- 关联为空且传了 `filePath` 是合法的，这正是从未保存的计划首次保存的路径。

### D5：调用纪律只写在工具描述里，不加运行期门

不在 `AgentRunContext` 上加"用户要求保存"的标记，也不对 goal 或委派回合做硬阻断。

- 理由："用户是否要求保存"是自然语言事实，运行期无法可靠判定。最需要主动保存的场景（goal 完成）已由宿主确定性执行。
- 备选否决：goal 活跃期硬阻断 `save_test_plan`——会挡掉"用户在目标里点名另存路径"的正当用法；且 save-before-run 本来就在写盘，单拦工具名不副实。

### D6：goal 完成保存由模型按指引执行，不写进宿主代码

`UpdateGoalTool` 不写盘，也不向工具返回拼接保存结果。保存时机由 goal 工作流指引约束：验证通过后、调用 `update_goal(complete)` 之前，模型自己调 `save_test_plan`（无 `filePath` = 存回关联文件），并在最终答复中转述保存结果。

- 理由：保存是模型的任务步骤之一，不是宿主的副作用。模型能根据保存结果决定下一步（重试、另存、向用户询问路径），宿主硬编码存回则剥夺了这种判断空间。
- 引导载体三处，代码侧为准：
  1. `update_goal` 的 description 写明"完成前自己调 save_test_plan"，到达所有 workspace。
  2. `GoalRuntime.statusLines` 的 test_plan 分支加一行，实时渲染，旧模板同样生效。
  3. `templates/goal.md` 与 `GoalRuntime.defaultSections()` 内嵌 fallback 同步一句，仅新 workspace 受益。
- `SaveTestPlanTool` 的 description 也写明 goal 场景下"验证通过后、complete 之前自己调用"。

### D7：CLI `save [--file <path>] [--overwrite]`，语义全在工具端

- dispatch 增加 `case "save"` → `cmdSave` → `buildSaveParameters`（package-private static）：`--file` 存在投 `filePath`，`--overwrite` 存在投 `overwrite: true`，皆缺省投空 Map。
- SPECS 帮助条目含用法、参数与示例（含覆盖示例）。
- `ALLOWED_TOOLS` 加名并归入新的"保存"注释组；拒绝文案的类别描述追加 save；`IpcServerAllowedToolsTest` 的 22 项目录同步为 23。
- 路径校验与覆盖保护不在 CLI 复制，与既有动词同一纪律。

### D8：测试分层

- 单测（无 GUI）：
  - `SaveTestPlanToolTest`：mock `GuiPackage` 加 `mockStatic(GuiPackage)`，沿 `ElementMutationRefreshFailureTest` 的手法；`PlanSaver` 的 EDT 派发经包内可见的注入点换成假实现，可编程"正常返回"或"未返回"。覆盖四条保护规则的每条拒绝路径、`overwrite=true` 放行、`filePath` 形态三条、成功结果的四个字段、探针未置位时返回失败。
  - `GoalToolsTest` 增补：验证通过的 complete 不写盘、不拼接保存结果；`update_goal` description 包含"完成前自己调 save_test_plan"的指引。
  - `IpcServerAllowedToolsTest`、`JmeterCliTest`（构建器形状、帮助打印、allowedFlags）。
  - `JmeterCliSkillPackageTest` 第 101 行的不变量翻转。
- 手工（真 GUI）：`docs/test/jmeter-cli-test-cases.md` 新增 SAVE 用例组，覆盖 spec 全部场景，含 autosave 保护、覆盖保护、运行中保存、goal 的两条路径。

### D9：文档同步矩阵

| 文件 | 改动 |
|---|---|
| `skills/jmeter-cli/SKILL.md` | "What this CLI cannot do" 表删除 Save 与 Deliver 两行；Safety rules 增 `save` 的覆盖保护与 autosave 告警；Command choice 增 `save` 行；save-before-run 的风险说明保留 |
| `skills/jmeter-cli/references/cli-reference.md` | 新增 `save` 命令段；Allowed Raw Tools 的 JMX Files 增 `save_test_plan`；`run` 段的写盘注记补一句指向 `save` |
| `skills/jmeter-cli/references/workflows.md` | Recipe E 的 "no save command" 改为 `save` 用法；Recipe F 回滚段改为 `save --file <正式路径> --overwrite` |
| `src/main/jmeter-agent/skills/jmeter/SKILL.md` | 工具索引增 `save_test_plan` |
| `src/main/jmeter-agent/skills/jmeter/references/plan-snapshots.md` | 第 5 步改为用 `save_test_plan` 另存，并说明无参保存会被拒 |
| `AGENTS.md` | 工具层增 `PlanSaver` 与 `SaveTestPlanTool`；goal 章节补完成存回；回合快照段的恢复说明改措辞 |
| `documents/agent-tools.md`、`documents/agent-tools_en.md` | "JMX 与脚本工具"表增一行 |
| `documents/jmeter-cli.md`、`documents/jmeter-cli_en.md` | 常用命令增 `save`；映射矩阵增一行 |
| `docs/test/jmeter-cli-test-cases.md` | 新增 SAVE 用例组；§6.2 拒绝文案；§6.3 白名单 22 → 23 |
| `TODO/TODO.md` | 只在既有 🈹 沙箱条目的描述上补一句"保存工具同样适用" |

## Risks / Trade-offs

- [IPC 放行扩大写盘面：持有 token 的本机进程可让 JMeter 覆写任意可写的 `.jmx`] → 仅 `.jmx` 扩展名加另存覆盖保护；目录沙箱沿既有 TODO 状态；守卫测试钉定白名单。
- [已关联脚本冒烟前已写盘，"测试通过后才保存"对它并不成立] → 文档如实说明；编排不动；原子落盘另立 TODO。
- [每次脏保存产生一份 JMeter 备份] → Ctrl+S 等价行为。goal 循环会加快备份轮替（同名文件默认留 10 份），文档提示可调 JMeter 的 `jmeter.gui.action.save.*` 属性。
- [证据新鲜度：冒烟通过后又改树，完成时存的是改后状态] → 沿用现有证据口径；recap 要求已约束模型。
- [performAction 的 `updateCurrentGui` 回写] → 与 Ctrl+S 同一风险面；树工具收尾已 `refreshCurrentGui`。
- [预检漏网的写盘失败留下 JMeter 模态错误框] → 工具如实返回失败，用户点掉即可。
- [另存到别的目录后 FileServer 基目录随之改变，相对路径改从新目录解析] → JMeter Save As 的固有行为，CLI 帮助写明。
- [autosave 保护依赖 workspace 解析] → `agent.workspace.path` 改动后旧目录不再受保护，接受。
- [旧 workspace 的引导文本陈旧] → 行为由 description、statusLines 与工具返回承载。

## Migration Plan

纯增量，无数据或协议迁移。部署 = 重新构建加 `mvn install`（注意 lib/ext 旧版本 jar 残留）。回滚 = 还原 jar；另存产生的关联路径是 JMeter 自身状态，随计划文件走，无插件状态残留。

## Open Questions

（无。）
