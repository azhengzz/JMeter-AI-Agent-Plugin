# Design

## Context

回合执行链路现状（事实，来自代码勘察）：所有回合来源（LOCAL_PANEL / IPC_CLI / IPC_DELEGATED / REPUBLISH）汇入 `AgentLoop.startTurn` 后由 loop 专属执行器跑 `AgentRunner.run`；`run()` 在 `runAgentLoop` 之前已有「回合起点、首次 LLM 前做同步动作」的验收先例——触发消息早落盘（persist-early）。回合终态（完成/Stop/异常/重置）最终都以 `run()` 收尾路径收敛。GUI 树只可在 EDT 读取；`OpenJmxFileTool` 已有 EdtRunner 式 EDT marshal 先例。既有 `run_test` autosave（`pointUnsavedPlanAtAutosave`）解决的是未保存计划触发 `popupShouldSave` 弹窗卡死 EDT，与快照目标正交，原样保留。双实例共享 workspace `autosave/` 目录且现有文件名无实例区分。

## Goals / Non-Goals

**Goals:**
- 回合起点基线捕获 → 终态比对 → 变化才落盘，全链路对 EDT 与回合时延零侵扰（EDT 仅两次树克隆）
- 快照文件身份可精确回溯到「某实例的某一次回合」
- 关闭时有界冲刷，焊死「干完活立刻关 JMeter」丢最后一份基线的窗口

**Non-Goals:**
- 恢复工具、清理/TTL、审计元数据（proposal 非目标）
- 复用/重构 `run_test` 的「重指 setTestPlanFile + doActionNow(SAVE)」芭蕾——它为防弹窗而生，快照不需要其保存语义，直写序列化字节即可

## Decisions

### D1. 触发形态：内存基线 + 终态比对条件落盘（对比「回合开始即写文件」「首次改树前写文件」）

- 回合开始即写：纯聊天回合与净零回合浪费文件；优势仅「进程崩溃也有起点存证」——但崩溃同时带走树上未保存改动，无物可回退（唯一窄窗：改完且 run_test 已 autosave 坏状态后崩溃，接受）。
- 首次改树前写（CoW）：依赖每个改树工具正确挂闸；净零回合仍落盘。
- **选定（内存基线）**：起点克隆入内存，终态再采样比对，变化才写。不依赖工具闸门、净零回合正确跳过；代价为每回合两次「克隆 + 序列化」。

### D2. 无门控（不做「已保存且干净则跳过」）

朴素门控信「磁盘即基线」，但 JMeter 默认 `save_automatically_before_run=true` 会在回合中把修改后的树静默覆写回关联 .jmx（用户 Ctrl+S 同理），基线即丢。精细化门控（读文件字节当基线）引入异源比对假阳性且 CHECK_DIRTY 深比较开销与克隆相当。**每回合无条件取内存基线**是唯一无丢失路径的选型。

### D3. 钩子落点：`AgentRunner.run` 两端（非 `AgentLoop.startTurn`）

- 起点捕获放 `run()` 内 `runAgentLoop` 之前（紧邻 persist-early 先例，worker 线程上下文现成）；
- 终态比对放 `run()` 收尾路径——四终态（完成/Stop/异常/重置）都使 runner 退出并流经此处；「四路径全覆盖」以测试钉死（见风险 R1）。
- 放弃 `startTurn`：回调线程不保证（EDT/IPC worker/loop 线程），且有 [register→armFuture] 取消窗口竞态；命令回合不进 runner、不改树，不覆盖即无损失。

### D4. 线程模型：两端同步克隆（中断位保持），异步任务 EDT-free

```
回合起点（worker 线程, run() 内，先于任何工具）:
  [清中断位] → EDT: clone A（毫秒级，同步——基线必须先于本回合任何树修改）→ [回置中断位]
  executor.submit: serialize(A) → completeFuture(baselineBytes)   ← 起点序列化异步，首 token 零延迟
回合终态（run() 收尾 finally）:
  [清中断位] → EDT: clone B（同步采样终态时刻）→ [回置中断位]
  executor.submit: endBytes = serialize(B)                        ← 任务体不依赖 EDT
                   if !Arrays.equals(baselineBytes.join(), endBytes):
                     atomicWrite(baselineBytes → autosave/{name}.jmx)  ← 落盘的是起点字节，永不为错
```

- **中断位保持（清位执行、结束回置）**：起点侧防上一回合 Stop 的迟到中断落在入口清扫与 EDT 等待之间击穿克隆；终点侧让 Stop 中止的回合（finally 里仍带中断位）也能采到终态。回置保证 `isAborted`（flag ‖ isInterrupted）语义对运行循环完全不变，run() 的 `Thread.interrupted()` 清扫次序不变量（持久化链先读）不受触碰；
- **异步任务必须 EDT-free**（对抗审计确认项）：标准退出路径（ExitCommand 在 EDT 上 System.exit）期间 EDT join shutdown hooks 不再泵事件——任何依赖 EDT 的排队任务（曾在任务体内做 invokeAndWait 克隆的初版形态）在冲刷窗口内结构性无法完成，`awaitTermination` 只能烧满 2s 后放弃，「关闭冲刷焊死丢基线窗口」的目标随之不可达。终点克隆移回 runner 线程（由中断位保持化解其原风险）+ 任务体只做序列化/比对/写盘后，冲刷窗口内任务真正可完成；
- **落盘为原子写**（临时文件 + ATOMIC_MOVE）：进程死于写中途不留截断的半份基线——损坏基线比缺一份更糟（恢复流程按名挑文件，无有效性校验能筛掉它）；
- 单线程 FIFO 保证起点序列化任务先于终点比对任务执行，终点任务内 `join()` 必然已就绪（无死锁）；
- 失败处理：所有异步任务 catch-all 记日志（best-effort，/goal 也不 fail-loud）。

### D5. 落盘方式：直写序列化字节（非复用 SAVE 重定向）

比对与落盘用**同一份字节**（同源自洽，一次序列化两用）；不碰 `setTestPlanFile` / IPC 端口文件 / Save 前后置监听器。替代案（重指关联 + `doActionNow(SAVE)` + 恢复 + `syncJmxPathNow`）为一次性快照动用整套关联芭蕾，SAVE 同步跑 EDT 大计划卡 UI，且等于每回合双份序列化，弃。

### D6. 收集器剥离只作用于克隆副本

终态采样时若测试仍在运行，活动树含注入的 `AgentResultCollector`。剥离在 clone B 上做——**按类型识别**（`key instanceof AgentResultCollector`，非按节点名：同名非收集器节点不误伤、改名不漏判）后序列化——**绝不**调 `removeFromGuiTree` 摘活树（那是 Save 预监听器/`testEnded` 的职责）。起点克隆同理防御性剥离（正常时起点无收集器）。

### D7. 命名与目录

- 回合快照：`{yyyyMMddHHmmssSSS}-{instanceId}-{turnId}-{origin}-{计划文件名}.jmx`（origin ∈ local/cli/delegated/republish；计划文件名 = **回合起点**关联文件基名去 .jmx 扩展，未保存计划占位 `unsaved`，随基线一并捕获于 scope），紧凑定宽时间戳保证字典序即时间序；turnId 为进程内簿记。
- `run_test` autosave 顺带修复：`{agent-run|goal-smoke}-{instanceId}-{ts}.jmx`（仅文件名补 instanceId，其余行为不变）。
- 统一目录：workspace `autosave/`。无新增配置项/开关（含冲刷时限，硬编码常量 ~2s）。

### D8. 关闭冲刷：复用 shutdown hook 基础设施

在既有 shutdown hook 注册点（与 CloseConsolidationCoordinator 同款挂法）追加 `executor.shutdown() + awaitTermination(2s)`；超窗放弃、不阻断退出。

### D9. 代码落位

新包 `org.gitee.jmeter.ai.agent.snapshot`：`TurnPlanSnapshotService`（单线程执行器、`beginTurn`/`completeTurn` API、冲刷入口）+ 命名工具。基线字节经 `CompletableFuture` 在两个异步任务间传递（同一线程内 run() 的局部变量即可持引用，不污染 `Turn`/`AgentRunSpec`，天然并发回合安全——每回合自持 future）。无 `GuiPackage`（纯单测/无 GUI）时全程 no-op 记日志。

## Risks / Trade-offs

- **[R1 四终态是否全流经 `run()` 收尾]** → 实现期以测试钉死四路径（正常完成/Stop/异常中止/重置取消）均触发比对；若发现旁路终态，把比对挂到该旁路收敛点（规格验收标准，不可省）。
- **[R2 序列化字节不确定（属性 Map 迭代序等）致假阳性]** → 接受（只会多写、不会漏写/写错）；不做字节稳定性工程。
- **[R3 大计划 EDT 克隆开销]** → 与 JMeter 自身每次 run start 的树克隆同量级（毫秒级），两端各一次；序列化/IO 均已移出 EDT。
- **[R4 文件无限累积（无清理）]** → 接受为 v1 取舍；后续可另立 TTL 清理变更（复用 SessionReaper 模式）。
- **[R5 关闭冲刷给退出加最多 ~2s]** → 有界且仅在队列非空时等待；正常场景写盘早已完成。
- **[R6 内存驻留：每回合一份字节 + 两份克隆]** → 驻留期通常限回合内；极端积压（EDT 被长操作占据/序列化变慢时连续回合堆叠）下起点克隆可在队列中跨回合滞留，量级 MB、受到达速率约束，非现实泄漏。
- **[R7 SaveService.saveTree 并发 marshal]**（快照线程序列化与 EDT 上用户 Ctrl+S / run_test 的 SAVE 同时进入共享静态 XStream JMXSAVER）→ 核验：`saveSampleResult` 的 synchronized 是文件 writer 交错问题而非 XStream 内部态；JMeter 转换器（HashTreeConverter 等）无实例字段、XStream 初始化后只读。残余风险接受（升级 JMeter 时复查转换器是否引入可变状态）。
- **[R8 恢复后的关联副作用]** open_jmx_file 打开快照会把计划文件关联指向快照路径——用户随后 Ctrl+S 静默覆盖该基线文件，IPC 端口文件随之广播快照路径，且失效是静默的（可能长期对着快照文件编辑而正式文件仍坏）→ 缓解为文档（plan-snapshots.md）明示「恢复确认后应 Save As 另存到正式路径，勿直接 Ctrl+S 写回快照」；更强的修复（恢复时把关联置 null，强制首次保存走 Save As 显式选择、IPC 清广播，覆盖工具与手动 File→Open 两路径）经评估可行，2026-10-13 用户拍板**不做**、维持文档警示。
- **[R9 回合内保存 + 回合尾段退出/崩溃窗口（维持现状，2026-10-13 复核后确认接受）]** 已接受例外 W3 的原论证（「树上未保存改动随进程消失，无物可回退」）存在一个缺口：回合内若发生过保存（用户 Ctrl+S，或已保存计划上 run_test 触发 JMeter 默认 save-before-run——**自动、无感知**），关联文件已被回合中（已改）状态覆写，天然基线不复存在；此后退出/崩溃若落在回合尾段（最后一次改树 → 终答在途 → 终点采样完成之前，秒级窗口），终点快照因 runner 在 EDT 克隆上随进程死亡而未写 → 终局为**坏状态在盘、任何位置无好基线**。缓解选项（Save 预监听器写透内存基线，两条保存路径均汇经 SAVE 动作、钩点现成）经评估可行但用户拍板**不做**；残余风险 = 事件叠加概率（回合内保存 × 回合尾段硬退出），已知且接受。

## Migration Plan

纯增量变更，无数据迁移。回滚 = revert 提交（快照文件留在磁盘无碍）。`run_test` 文件名格式变化仅影响新生成文件，旧文件不迁移不读取。

## Open Questions

（已查证消解）`AgentRunSpec` 现不带 turnId/TurnOrigin（仅有 `delegated` 布尔与 `hostSyntheticTrigger`），快照命名所需身份需新增透传：在 `AgentRunSpec` 增加可空的 `turnId` + `origin`（复用 `agent.presenter.TurnOrigin`），由 `AgentLoop.startTurn` 构造 spec 时从 `TurnHandle` 填入。**参与门即缺席旁路**：null 时不产生任何文件（非「降级命名落盘」）——`SnapshotNames` 中的 `noturn` 段仅是命名纯函数的防御完备性，生产不可达。
