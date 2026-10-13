# Tasks

## 1. 快照服务核心（新包 `org.gitee.jmeter.ai.agent.snapshot`）

- [x] 1.1 新建 `TurnPlanSnapshotService`（专用单线程执行器、`beginTurn`/`completeTurn` API、`CompletableFuture` 基线字节传递、全任务 catch-all 记日志、无 `GuiPackage` 时整体 no-op 降级）与纯函数命名工具（D7 四元格式 `{yyyyMMdd-HHmmss-SSS}-{instanceId}-{turnId}-{origin}.jmx`，turnId 缺席降级 `noturn`）；verify：单测覆盖命名格式/字典序=时间序/实例隔离/双实例同毫秒不撞名/no-op 降级（`mvn test -Dtest=TurnPlanSnapshotServiceTest`）
- [x] 1.2 实现树克隆与「克隆上剥收集器」helper：EDT 上克隆计划树（EdtRunner 式 marshal，起点/终点各一次），在**克隆副本**上按节点名剔除 `AgentResultCollector`（绝不摘活树），`SaveService.saveTree` 序列化为字节；verify：纯 HashTree 层单测——含收集器节点的树经 helper 后序列化字节不含收集器名、原树节点数不变（`mvn test -Dtest=SnapshotTreeCloneTest`）

## 2. 回合接线与身份透传

- [x] 2.1 `AgentRunSpec` 增加可空 `turnId` + `origin`（复用 `agent.presenter.TurnOrigin`）builder 透传，`AgentLoop.startTurn` 构造 spec 时从 `TurnHandle` 填入；缺省 null 不影响既有调用方；verify：编译通过 + 既有 AgentLoop/AgentRunner 测试回归（`mvn test`）
- [x] 2.2 `AgentRunner.run` 接线：`runAgentLoop` 之前（紧邻 persist-early 先例）调 `beginTurn`，`run()` 收尾路径调 `completeTurn`；服务可注入以便观测；verify：单测断言两调用点在回合两端各触发一次（含 GuiPackage 缺席 no-op 分支）
- [x] 2.3 四终态验收测试（规格验收标准）：用 testsupport（`GatedScriptAiService` 驱动 Stop 钉子）分别驱动正常完成/用户 Stop/异常中止/重置取消四条路径，断言比对任务均被提交执行；verify：`mvn test -Dtest=TurnSnapshotTerminalStatesTest` 四场景全绿

## 3. run_test 命名修复与关闭冲刷

- [x] 3.1 `RunTestTool.pointUnsavedPlanAtAutosave` 文件名补 instanceId（`{agent-run|goal-smoke}-{instanceId}-{ts}.jmx`，文件名生成抽为纯函数），其余重定向/恢复/IPC 回写逻辑零改动；verify：单测断言新命名格式与实例隔离，既有 RunTestTool 相关测试回归
- [x] 3.2 shutdown 有界冲刷：在既有 shutdown hook 注册点（与 CloseConsolidationCoordinator 同款挂法）追加 `executor.shutdown() + awaitTermination(2s)`，超窗放弃不阻断退出；verify：单测——队列挂一个慢任务触发冲刷，断言有界返回且已排空任务完成/超窗任务放弃

## 4. 文档与集成验证

- [x] 4.1 撰写 `skills/jmeter/references/plan-snapshots.md`（快照目录解析规则、四元命名、与 turnId 的对应、经 `open_jmx_file` 手动恢复的操作路径、与 run_test autosave 的关系）并在 `skills/jmeter/SKILL.md` 挂链接；verify：文档落盘且命名描述与 1.1 实现逐字一致
- [x] 4.2 更新 `AGENTS.md`：agent 框架层增加回合快照机制段落（触发形态/线程模型/关闭冲刷/目录命名）；verify：段落存在且与 design.md 一致
- [x] 4.3 全量回归：`mvn clean test` 全绿（注意既有守则：全量测试不与对抗审查工作流并行）；有条件时 `mvn clean install` + GUI 手动冒烟一轮（改树回合产生快照、纯聊天回合无文件、关 JMeter 冲刷不卡退出）

## 5. 对抗审计修复（8 维发现 + 3 镜头验证：确认 16 / 驳回 3 / 不确定 0）

- [x] 5.1 【major】关闭冲刷 EDT 死锁修复：终点克隆移回 runner 线程（中断位清位执行/结束回置），异步任务体去 EDT 化（序列化/比对/写盘），使 System.exit 冲刷窗口内任务真正可完成；design.md D4 同步第三版
- [x] 5.2 【major】文档恢复指引差一回合错误修正：plan-snapshots.md 改「最新=撤销最近改树回合、第k新=回退k回合」，废弃「时间戳早于目标回合」启发式；补 turnId 为进程内簿记的口径、republish 双来源、恢复后 Save As 另存警示（Ctrl+S 会覆盖基线 + jmxPath 广播）
- [x] 5.3 【major】比对/条件落盘真实路径测试补齐（TurnPlanSnapshotWriteTest）：改树→恰一份且内容=起点字节非终态字节、未变/净零→零文件、用户手改→落盘、原子写无 .tmp 残留
- [x] 5.4 【major】四终态测试强化：begin 先于 complete 的次序断言；异常用例注释纠偏（LLM 错误被循环收口而非冒泡）
- [x] 5.5 【minor】迟到中断 TOCTOU 修复：两端克隆中断位保持（captureDetachedPlanClonePreservingInterrupt），单测钉「克隆后中断位回置 + 无 GUI 降级」
- [x] 5.6 【minor】非原子写盘修复：临时文件 + ATOMIC_MOVE 原子落盘，进程死于写中途不留截断基线
- [x] 5.7 【minor】文档/工件一致性批修：design.md D6 按类型剥离（非按节点名）、noturn 语义（缺席即旁路）、R6 积压措辞、新增 R7（XStream 并发 marshal 分析）/R8（恢复后关联副作用）；proposal.md Impact 补 AgentRunSpec/AgentLoop；AGENTS.md 参考文档计数 133→134、快照段落同步
- [x] 5.8 EDT 采集路径补测：replaceNodeKeysWithElements 节点键换元单测（JMeter 特有变换此前零覆盖）
