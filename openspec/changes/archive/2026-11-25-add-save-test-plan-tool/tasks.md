# Tasks

## 1. 保存内核与工具

- [x] 1.1 实现 `PlanSaver`（读关联 → 保护规则 → `filePath` 形态校验 → 可写性预检 → EDT 内设关联、注册一次性 `Save.class` 探针、派发 `SAVE`、注销探针 → 复核目标存在 → 产出 `SaveOutcome`；EDT 派发留包内可见注入点供测试）与 `SaveTestPlanTool`（工具名 `save_test_plan`；参数 `filePath`、`overwrite`；description 写明"仅在用户明确要求时调用"与"goal 验证通过后由模型自己调用保存"；返回 `{filePath, fileName, mode, bytes}`；默认 core scope、非并发安全），并在 `JMeterToolRegistry.registerDefaultTools` 注册；验证：`mvn clean test-compile` 通过
- [x] 1.2 新增 `SaveTestPlanToolTest`（mock `GuiPackage` 加 `mockStatic`，注入假派发）覆盖：无关联且无 `filePath` 被拒且不派发；相对路径、非 `.jmx`、父目录不存在各自被拒；目标已存在无 `overwrite` 被拒且文件内容不变；`overwrite=true` 放行且 `mode=save_as`；关联在 `autosave/` 下无参被拒、显式 `filePath` 放行；正常无参保存 `mode=save` 且返回四字段；派发未置完成信号时返回失败且无 `bytes`；目标是目录或不可写时前置拒绝；description 含调用纪律；验证：`mvn test -Dtest=SaveTestPlanToolTest` 绿
- [x] 1.3 同步工具文档：内置 `skills/jmeter/SKILL.md` 工具索引加 `save_test_plan`；`references/plan-snapshots.md` 第 5 步改为用 `save_test_plan` 另存并说明无参保存会被拒；`AGENTS.md` 工具层增 `PlanSaver` 与 `SaveTestPlanTool`、回合快照段的恢复说明改措辞；`documents/agent-tools.md` 与 `documents/agent-tools_en.md` 的"JMX 与脚本工具"表各加一行；验证：`grep -rn "save_test_plan"` 的命中与 design 的 D9 矩阵一致，各处参数名与工具 schema 相同

## 2. goal 完成存回

- [x] 2.1 `UpdateGoalTool` 的 description 补"完成前自己调 save_test_plan"的指引（不写盘、不拼接保存结果）；验证：2.2 的单测绿
- [x] 2.2 `GoalToolsTest` 增补：证据齐全的 complete 调用存回恰一次且回执含"已保存"与路径；冒烟预算耗尽的 complete、`non_test_plan` 的 complete、cancel、block 均零次；存回返回未保存时 goal 仍 completed 且回执含原因；存回抛异常时 goal 仍 completed；验证：`mvn test -Dtest=GoalToolsTest` 绿
- [x] 2.3 引导文本三处同步：`GoalRuntime.statusLines` 的 test_plan 分支加自动存回说明（代码侧，旧模板生效）；`defaultSections()` 内嵌 fallback 与 `resources/templates/goal.md` 的 "Call update_goal…" 段各补一句；并仿 `GoalRuntimeRecordingHintTest` 加一条断言（test_plan 目标的状态块含该说明、`non_test_plan` 目标不含）；验证：`mvn test -Dtest='GoalRuntime*Test,GoalLoopTest,GoalStatePublishTest,AgentLoopGoalPauseTest,WorkspaceInitializerTest'` 绿
- [x] 2.4 `AGENTS.md` 的 goal 章节"工具"条目补一句：证据齐全的 complete 由模型自己保存；验证：表述与 goal-autonomous-loop 增量 spec 一致

## 3. IPC 白名单与 CLI 动词

- [x] 3.1 `IpcServer.ALLOWED_TOOLS` 增 `save_test_plan`（归入新"保存"注释组），类 Javadoc 的路径告警补上保存工具，拒绝文案的类别描述追加 save；`IpcServerAllowedToolsTest.cliDispatchedToolCatalogMatchesAllowlistExactly` 的目录由 22 项同步为 23 项；验证：`mvn test -Dtest=IpcServerAllowedToolsTest` 绿
- [x] 3.2 `JmeterCli` 增 `save` 动词：dispatch `case`、`cmdSave`、`buildSaveParameters`（package-private static；`--file` 映射 `filePath`；`--overwrite` 沿 `--wait` 的存在型旗标语义，无值即 true，垃圾值以 usage 错误退出；两者皆缺省投空 Map）；SPECS 帮助条目写明用法、参数、示例、覆盖已有文件需 `--overwrite`、未保存计划须给 `--file`；验证：3.3 的单测绿
- [x] 3.3 `JmeterCliTest` 增补：无参构建为空 Map；`--file` 映射 `filePath`；`--overwrite` 投 `true`；`--overwrite yes` 以带旗标名的 usage 错误拒绝；`helpPrintsForAllNewCommands` 与 `allowedFlagsCoverAllConsumedFlags` 纳入 `save`；`save -h` 输出含 `--overwrite` 说明；验证：`mvn test -Dtest=JmeterCliTest` 绿
- [x] 3.4 `documents/jmeter-cli.md` 与 `documents/jmeter-cli_en.md` 同步：常用命令加 `save`，映射矩阵加一行（`--file`→filePath；`--overwrite`→overwrite）；验证：矩阵行与 `buildSaveParameters` 的投递形状一致
- [x] 3.5 `TODO/TODO.md` 的 🈹 沙箱条目描述补一句"保存工具 `save_test_plan` 同样适用"；验证：该条目提及 `save_test_plan`，清单其余条目不动

## 4. 外部 skill 文档同步

- [x] 4.1 `skills/jmeter-cli/SKILL.md`："What this CLI cannot do" 表删除 Save 与 Deliver 两行；Safety rules 增 `save` 条目（另存覆盖需 `--overwrite`、关联指向 autosave 快照时无参保存被拒、已关联计划在冒烟起跑前已被写盘）；Command choice 加 `save` 行；保留 save-before-run 的风险说明；验证：`JmeterCliSkillPackageTest` 的其余断言不受影响（`save_automatically_before_run` 仍在文中）
- [x] 4.2 `skills/jmeter-cli/references/cli-reference.md`：新增 `### save` 命令段（用法、返回字段、四条拒绝情形、示例）；Allowed Raw Tools 的 JMX Files 增 `save_test_plan`；`run` 段的写盘注记补一句指向 `save`；验证：Top-Level Commands 下出现 `save` 段，参数名与工具 schema 一致
- [x] 4.3 `skills/jmeter-cli/references/workflows.md`：Recipe E 的 "no save command" 改为 `save` 用法；Recipe F 回滚段改为 `save --file <正式路径> --overwrite`，并保留"不要无参保存"的告警；验证：`grep -rni "no save command" skills/jmeter-cli` 无命中
- [x] 4.4 翻转 `JmeterCliSkillPackageTest.packageCarriesJmeterDomainVocabulary` 第 101 行的不变量：由"必须声明没有保存命令"改为"必须文档化 `jmeter-cli save` 且写明覆盖保护"，保留 `save_automatically_before_run` 风险断言；验证：`mvn test -Dtest=JmeterCliSkillPackageTest` 绿

## 5. 集成验证与收尾

- [x] 5.1 `docs/test/jmeter-cli-test-cases.md` 新增 SAVE 用例组，并更新 §1.4 分组表、§6.2 拒绝文案、§6.3 白名单实况（22 → 23），同步 `documents/jmeter-cli{,_en}.md` 中的用例计数。用例至少覆盖：无参存回；未保存计划无参保存得 exit 1；另存后 `script-info` 显示新路径；覆盖已有文件无 `--overwrite` 被拒、带 `--overwrite` 成功；打开 autosave 快照后无参保存被拒且快照不变；相对路径、非 `.jmx`、父目录不存在；运行中保存的 `.jmx` 不含收集器节点；`tool save_test_plan` 透传；`/goal` 在已关联计划上完成后文件更新且回答复述"已保存"；`/goal` 在从未保存计划上完成后标注未保存；验证：用例逐条对应三份增量 spec 的全部场景
- [x] 5.2 `mvn clean install` 后在真 GUI 上执行 5.1 的 SAVE 用例，并手测本地对话里说"保存"（安装前先清理 lib/ext 的旧版本 jar）；验证：用例全绿、`jmeter.log` 无新增异常，结果回写用例文档。**CLI 部分 33/33 通过；GUI 场景（TC-SAVE-02/13/14/17/18）已用 pyautogui + CLI 组合验证通过，结果回写 docs/test/jmeter-cli-test-cases.md §5.6**
- [x] 5.3 运行 `openspec validate "add-save-test-plan-tool"` 与全量 `mvn test`；验证：validate 退出码 0，全量测试日志中无失败（以日志内容为准，不以退出码或后台通知为准）
