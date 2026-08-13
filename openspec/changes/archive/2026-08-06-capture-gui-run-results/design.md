# Design: 捕获 GUI 运行结果（Capture GUI-Run Results）

> 本设计经 5 路并行设计探针 + 完整性综合（对抗式核对插件与 JMeter 5.6.3 真实源码，引用 `file:line`）。裁决板：`timing(pre-listener)` 🟢sound · `provenance-skip 注入归属` 🟢sound · `JMX-save 剥离` 🟢sound · `registration` 🟢sound · `failure-mode 对抗` 🟢sound。一处关键澄清：预监听器与 `RunTestTool` 不在同一 EDT 派发（见 C1），该事实是 provenance-skip 设计的承重墙。

## Context

插件当前**只有一条**结果采集路径，且完全由 Agent 驱动。所有结果状态都是 `AgentResultCollector` 的**静态字段**（`AgentResultCollector.java:41-56`），读取工具 `get_test_status` / `get_test_results` 走静态读取器（`:200-252`）。收集器是一个 `AbstractTestElement`，实现 `SampleListener` + `TestStateListener` + `NoThreadClone` + `Remoteable`（`:33-34`），靠**被注入测试树**来接收 sample——`testEnded()` 时自动从 GUI 树移除（`:99-106`）。

**唯一注入点**：`RunTestTool.doStart`（`RunTestTool.java:139-187`）—— `reset()` → `new AgentResultCollector()` → EDT 内 `removeFromGuiTree()` → `gui.getTreeModel().addComponent(collector, testPlanNode)`（`:163`）→ 设属性 → `ActionRouter.getInstance().actionPerformed(... ACTION_START|NO_TIMERS)`（`:175-176`）。用户点 JMeter 工具栏 Run 按钮时**完全不经过**这条路径，因此 GUI 运行采集不到任何结果。

**JMeter 侧的关键事实**（决定了可行方案）：
- **没有全局 sample 总线**：sample 只流向被 `TestCompiler` 从测试树编译进每个 sampler 的 `SamplePackage` 的 `SampleListener`。要采到 sample，**收集器必须在被编译的树里**。
- `Start.doAction`（`Start.java:112`）→ `startEngine`（`:178`）→ `gui.getTreeModel().getTestPlan()`（`:180`，**doAction 时现读**）→ `convertSubTree`（`:185`，只剔除 disabled 元素，**不剔除 listener**）→ `cloneTree`（`:195`）→ `engine.configure(clonedTree)`（`:198`）→ `runTest()`（`:200`）。
- `ActionRouter.performAction`（`ActionRouter.java:80-84`）对每个 Command：`preActionPerformed(c.getClass(),e)` → `c.doAction(e)` → `postActionPerformed`，**同一 try 块内**。`addPreActionListener(Class<? extends Command>, ActionListener)`（`:195`）按 Command 类名挂监听器，JMeter core 的 `CheckDirty` 已用（`CheckDirty.java:68-69`）。
- `ActionRouter.actionPerformed` 把 `performAction` 包在 `SwingUtilities.invokeLater` 里（`ActionRouter.java:64-66`）—— **预监听器与 `RunTestTool` 的 EDT 块不在同一派发**（见 C1）。
- 插件唯一可用的 SPI bootstrap 是 `SelectionInitCommand`（`META-INF/services/org.apache.jmeter.gui.action.Command`），它在 `ADD_ALL`（GUI 初始化完成、`MainFrame` 可见后由 `ActionRouter` 派发）时经 `AtomicBoolean INSTALLED` 一次性安装 `SelectionTracker`（`SelectionInitCommand.java:33-58`）。

## Goals / Non-Goals

**Goals:**
- 任何**本地 GUI 内**发起的运行（用户点 Run / Run Thread Group / Agent 调 `run_test`）都被同一个内存收集器采集，`get_test_status` / `get_test_results` 对 GUI 运行也返回实时数据。
- 采集"跟随测试而非跟随触发者"——无论谁按下启动。
- 记录运行来源（USER / AGENT），Agent 可据此回答"展示我刚才在 GUI 跑的那次"。
- `run_test` 的现有采集能力**零回归**（含特性开关关闭时）。
- 收集器节点**绝不**泄漏进 `.jmx` 文件。

**Non-Goals（明确排除）:**
- 远程/分布式运行（`RemoteStart.class`，独立 Command、sample 走不同路径）。
- 无界面/CLI 运行（`jmeter -n`，无 `ActionRouter`/`GuiPackage`）。
- `VALIDATE_TG`（单次校验运行，统计会误导 Agent 读 `get_test_status`）——初版排除。
- 不替换/继承 `StandardJMeterEngine`（在 `Start` 内 `new`，无注入点）。
- 不改 `AgentResultCollector` 的静态状态模型与现有读取器契约。

## Decisions

### D1. 注入触发点：`Start.class` 的 ActionRouter 预监听器

注册 `ActionRouter.getInstance().addPreActionListener(Start.class, listener)`。预监听器在 E2 派发中、`Start.doAction` **之前**、`cloneTree`（`Start.java:195`）**之前**运行，此时注入 `GuiPackage.getTreeModel()` 的节点会进入 `Start` 现读的 `getTestPlan()`（`JMeterTreeModel.java:221-237` 现枚举子节点），进而进入克隆树与 `engine.configure`。`addComponent` 需 EDT，而 `performAction` 本就在 EDT（`ActionRouter.java:64-66`），故预监听器可直接调用。

**否决**：① 注册一个竞争性 `Command`（`ServiceLoader`）—— `commands` 是 `Map<String,Set<Command>>`（`HashSet`，`:48/332`），`performAction` 在 `:80` 迭代该 Set 顺序未定义，**无法保证**插件 Command 在 `Start.doAction` 之前跑；预监听器按类名挂在 `Start` 的迭代上，顺序确定。② `addPostActionListener`——`Start.doAction` 已 `configure` 完克隆树（`:198`），为时已晚。③ 误传的"插件内已有预监听器先例"——`SelectionInitCommand` 是 `ADD_ALL` 的 Command，**不是** `addPreActionListener` 调用者（插件内 grep 零命中）；真正先例是 JMeter core `CheckDirty`。

### D2. 注入归属：`RunTestTool` 保留注入，预监听器仅 USER 注入（provenance-skip）

**不**做唯一注入点。`RunTestTool.doStart` 的注入路径**原样保留**，只把 `reset()` 改为 `reset(RunProvenance.AGENT)`（一行）。预监听器对 `e.getSource() instanceof RunTestTool`（AGENT）**直接返回不注入**——`RunTestTool` 已在 E1 注入（E1 先于 E2 完成，FIFO）。仅对 USER 执行 `removeFromGuiTree() → reset(USER) → injectIntoGuiTree()`。如此每条 start 恰一个注入器，且**开关关闭时** `RunTestTool` 仍采集（化解 R3）。

**否决**：① 预监听器作唯一注入点、剥光 `RunTestTool` 注入（Probe 3）——开关关闭即全失采集，回归。② 双方都注入 + 节点存在性幂等（Probe 4）——stale 节点与时序脆弱。provenance-skip 无状态、确定。

### D3. 运行来源：基于 `ActionEvent.getSource()` 的无状态判别

`RunProvenance p = (e.getSource() instanceof RunTestTool) ? AGENT : USER`。`RunTestTool` 构造 `new ActionEvent(this, ...)`（`RunTestTool.java:175-176`），source 即工具实例；用户点击的 source 是 GUI 组件。无状态、null-safe、按构造即无泄漏面，且**对 E1→E2 延迟免疫**（无可设可清的共享标志）。存为 `private static volatile RunProvenance lastProvenance`，在 `reset(RunProvenance)` 内写入，`getLastProvenance()` 供 `GetTestStatusTool` 显示。

**否决**：static volatile `pendingProvenance` 标志 / `ThreadLocal` / `AtomicReference`——均引入共享可变状态与排序推理，且 EDT 长线程有泄漏面。保留"marker 接口 `AgentInitiated`"为未来第二 agent 启动器的升级路径。

### D4. JMX 持久化：`Save.class` 预监听器剥离节点

注册 `addPreActionListener(Save.class, e -> stripIfPresent())`，在 `Save.doAction` 读活树（`Save.java:158/143`）之前移除收集器节点。覆盖 SAVE / SAVE_AS / SAVE_ALL_AS / SAVE_AS_TEST_FRAGMENT（`Save.java:104-109`），补上 `testEnded` 自动移除（`AgentResultCollector.java:99-106`，引擎结束后才触发且 `invokeLater` 异步）覆盖不了的"运行中保存"窗口。JMeter **无"不序列化"标记**——`NonTestElement` 仅菜单/分类用（`ProxyControl` 实现它却照常存盘）。GUI 树注入结构上不可避免（`Start.startEngine` 现读 `getTreeModel()`），故剥离时机选在 save 而非注入时。**（实现增补：仅 Save 预剥离会在 `popupShouldSave` 的 save-before-run 路径上把收集器剥光、使 `startEngine` 读不到——见硬约束 7 的 armed POST-reinject。）**

### D5. 注册点：`SelectionInitCommand` 的 ADD_ALL 钩子

两个监听器（`Start.class` + `Save.class`）在 `SelectionInitCommand.doAction` 现有 ADD_ALL 处理、已有的 `AtomicBoolean INSTALLED` CAS 守卫的 `EventQueue.invokeLater` 块内（`SelectionInitCommand.java:38-58`）、紧挨 `SelectionTracker.install()` 注册。`ActionRouter` 此时必然就绪（`ADD_ALL` 由它自己在 `populateCommandMap` 之后派发，`JMeterGuiLauncher.kt:101-119`）。监听器实例存静态单例字段，即便 CAS 失败 `ActionRouter` 的 `HashSet`（`:250-259`）也按实例去重。

### D6. 特性开关：仅门控注册，`RunTestTool` 永不读取

`agent.runcapture.enabled`（默认 `true`）只决定**是否注册预监听器**。关：`RunTestTool` 仍为 agent 运行注入采集（今天唯一可用路径），GUI 采集丢失（用户主动选择退出，可接受）。开：`RunTestTool` 注入 AGENT（E1），预监听器注入 USER（E2），provenance-skip 防重复。`RunTestTool` 不读开关——这是 R3 的硬保证。

### D7. 覆盖范围与 `isStartCommand` 白名单

`isStartCommand` 用**显式白名单**（非黑名单）：`ACTION_START`、`ACTION_START_NO_TIMERS`、`RUN_TG`、`RUN_TG_NO_TIMERS` 为是；`ACTION_STOP`/`ACTION_SHUTDOWN`/`VALIDATE_TG` 及其余为否。`Start` 注册了 7 个动作（`Start.java:84-90`），监听器按 `Start.class` 挂、对所有 7 个都触发——不过滤则 STOP 会 `reset()` 抹掉即将被查询的结果（R2，静默数据丢失）。

---

## ⚠️ 对抗验证产出的硬约束（实现时必须遵守）

### 约束 1（R1）：预监听器**绝不**抛异常，否则静默中止所有 start

`ActionRouter.performAction` 把 `preActionPerformed + doAction + postActionPerformed` 放在**同一 try**（`:80-101`），任何异常被 `catch(Exception){log.error}` 吞掉，`Start.doAction` **被跳过**——用户点 Run 没反应，仅一行日志。

**修正**：预监听器整体包 `try{...}catch(Throwable t){log.error(...);return;}`。加回归测试：强制 `addComponent` 抛异常（如 null TestPlan 节点），断言 `Start.doAction` 仍执行、引擎仍启动。**USER GUI 运行的 UX 绝不能依赖我们的收集器。**

### 约束 2（R2）：`isStartCommand` 是承重白名单

不过滤 STOP/SHUTDOWN → 停止测试时 `reset()` 抹结果 + 注入永不移除的节点。

**修正**：白名单实现 + 单测断言 STOP/SHUTDOWN 不变更收集器状态。

### 约束 3（R3）：`RunTestTool` 注入路径原样保留

唯一注入点方案在开关关闭时全失采集。`RunTestTool` 必须始终自注入（只把 `reset()` 改 `reset(AGENT)`）。回归测试：开关 OFF + `run_test` 仍采集（断言恰一个 `__agent_result_collector__` 节点、结果非空）。

### 约束 4（R4）：AGENT 运行靠 provenance-skip 防双重注入

`RunTestTool`（E1）与预监听器（E2）都注入 → 两节点共存 → 克隆树复制两份指向同一静态累加器的 listener → sample 双计。预监听器对 AGENT provenance **整体返回**（不 reset、不移除、不注入）。集成测试：`run_test` → 断言克隆引擎树恰一个收集器节点。

### 约束 5（R6）：运行中二次 start 的 running-guard

`Start.doAction` 无 running 守卫（`Start.java:112-156`）；`JMeterContextService.startTest()` 已开始时是 no-op（`:84-90`），二次 `ACTION_START` 会 `new` 第二个引擎。预监听器若在运行中 `reset()` 会**实时清零**计数器/队列。

**修正**：预监听器入口 `if (JMeterContextService.getTestStartTime()>0 || AgentResultCollector.isTestRunning()) return;`（仅 USER 路径，AGENT 由 `RunTestTool` 自己的 `:126` 守卫挡）。

### 约束 6（R5/R8）：防御性清理 + TCCL 复刻

- 预监听器 USER 注入前先 `removeFromGuiTree()`（镜像 `RunTestTool.java:160`），清掉上一轮 `testEnded` 未触发的 stale 节点（引擎 `run()` 在 PreCompiler 失败处早退 `StandardJMeterEngine.java:411-418`，`notifyTestListenersOfEnd`/`endTest` 不在 finally）。
- `addComponent` 周围复刻 `RunTestTool` 的 TCCL 交换（`:148-180`）——`GuiPackage.getGui` 走 `Class.forName`（`GuiPackage.java:232/236`），插件类加载器路径未验证，失败即 NPE→约束 1 吞掉→start 中止。代价微小，失败严重，先复刻。
- `removeFromGuiTree` 的 javadoc 误导（声称内部派发 EDT，实现 `:161-176` 并未 `invokeLater`）——两个调用点都在 EDT（经 `ActionRouter`），安全；本变更顺带修正 javadoc 或加 `assert EventQueue.isDispatchThread()`（R9）。

### 约束 7（对抗式审查发现 / 实现增补）：`popupShouldSave` 在 `startEngine` 前同步触发 SAVE

JMeter 的 `Start.doAction`（`Start.java:113-115`）对启动类动作**先**调 `popupShouldSave(e)` **再** `startEngine`；而 `popupShouldSave`（`AbstractAction.java:69-79`）在 `shouldSaveBeforeRun()`（默认 **true**，`GuiPackage.java:88/1007-1016`）且计划已保存时，**同步** `doActionNow(SAVE)`（`ActionRouter.java:119-121` 可重入）。这条 SAVE 会触发我们的 Save **预**监听器 `stripCollectorNode`——把刚注入的收集器从活树剥离，于是随后 `startEngine` 读 `getTestPlan()`（`Start.java:180` 现读活树）时已无收集器 → sample 不被采集。这在默认配置下对**任何已保存 .jmx** 静默失败：USER 运行采集不到（核心目标破），`run_test` 在已保存计划上回归（且因 Save 监听器无条件注册，与开关无关）。

**修正（armed POST-reinject）**：Save 预监听器仍剥离（保证 .jmx 干净），新增 Save **后**监听器 `reinjectIfArmed`——若本条 start 已"武装"，在 `Save.doAction` 之后、`startEngine` 之前把收集器重新注入活树；新增 Start **后**监听器 `clearStartArmed` 在 `startEngine` 克隆完树后解除武装。
- `onTestStartAction`（USER，开关开）通过守卫后置 `startArmed=true` 再注入。
- `RunTestTool.armForStartReinject()` 在 `fire ACTION_START` 前置 `startArmed=true`——保证 AGENT 运行在**开关关**时也能经 Save 后监听器重注入（化解 toggle-无关的 R3 回归）。
- 三条 Save/Start 监听器（strip 预 / reinject 后 / clear 后）**无条件**注册；Start 预监听器（`onTestStartAction`）仍受 `agent.runcapture.enabled` 门控。
- 恰一个注入器不变：注入 →（`popupShouldSave` → strip → reinject）→ `startEngine`，全程至多一个收集器节点。
- 失败 start 边缘：若 `Start.doAction` 抛异常，Start 后监听器不触发（`ActionRouter` 把 `doAction`+`postActionPerformed` 放同一 try），`startArmed` 残留 true → 下次普通 save 会多注入一个收集器节点；下一轮 start 的 `removeFromGuiTree` 清理。可接受。

---

## 执行流（注入时序全貌）

```
GUI 启动: ActionRouter 派发 ADD_ALL
  └─ SelectionInitCommand.doAction (CAS 一次性, EDT invokeLater)
       ├─ SelectionTracker.install()
       ├─ if(agent.runcapture.enabled) ActionRouter.addPreActionListener(Start.class, AgentResultCollector::onTestStartAction)
       └─ ActionRouter.addPreActionListener(Save.class, e -> stripCollectorNode())

【用户点 Run】 单一 EDT 派发 E2:
  ActionRouter.performAction(e):
    updateCurrentGui()
    for Command c(=Start): preActionPerformed(Start.class,e) ──▶ onTestStartAction(e):
        cmd=e.getActionCommand(); if(!isStartCommand(cmd)) return;     // R2
        if(getTestStartTime()>0||isTestRunning()) return;               // R6
        p = (e.getSource() instanceof RunTestTool)?AGENT:USER;
        if(p==AGENT) return;                                           // R4: RunTestTool 自注入
        try{ TCCL交换; removeFromGuiTree(); reset(USER); injectIntoGuiTree(); }
        catch(Throwable){log.error;return;}                           // R1
    Start.doAction(e): startEngine ── getTestPlan()(现读,含收集器) ── convertSubTree ── cloneTree ── configure ── runTest
        引擎线程: SearchByClass<TestStateListener> → testStarted; TestCompiler 编译收集器为 SampleListener; sampleOccurred 累积
    postActionPerformed

【Agent run_test】 E1(RunTestTool EDT块) → E2(预监听器+Start) 两派发:
  E1: reset(AGENT) → removeFromGuiTree → addComponent(collector) → 设属性 → ActionRouter.actionPerformed(ACTION_START)
        (actionPerformed invokeLater performAction ⇒ E1 先完成, latch countDown)
  E2: onTestStartAction: isStartCommand✓; running-guard✓; provenance==AGENT ⇒ 直接返回(不注入)   ◀── RunTestTool 已在 E1 注入
      Start.doAction: getTestPlan() 现读(含 E1 注入的收集器) → cloneTree → ...

【保存】 Save.class 预监听器(EDT): stripCollectorNode() ── removeFromGuiTree (try/catch 同 R1) ──▶ Save.doAction 读活树(无收集器)
```

**死锁/竞态可证伪**：注入与 start 都在 EDT 单线程；读取工具走静态读 + EDT 快照（沿用现有约定，见 [[jmeter-gui-edt-threading]]）。E1→E2 由 EDT FIFO 保证顺序，provenance-skip 可靠。

---

## 组件清单

### 新增（无新类，全部落在 `AgentResultCollector` 内 + 注册接线）

| 改动 | 位置 | 职责 |
|---|---|---|
| `RunProvenance` 枚举 | `AgentResultCollector`（嵌套） | `USER` / `AGENT` |
| `lastProvenance` 字段 + `reset(RunProvenance)` + `getLastProvenance()` | `AgentResultCollector` | 记录/暴露运行来源；无参 `reset()` 委托 `reset(USER)`（additive） |
| `onTestStartAction(ActionEvent)` | `AgentResultCollector`（静态） | `Start.class` 预监听器入口：白名单过滤 → running-guard → provenance 判别 → AGENT 跳过 → USER 注入（try/catch 吞尽） |
| `isStartCommand(String)` / `injectIntoGuiTree()` / `isRunInProgress()` | `AgentResultCollector`（私有静态） | 白名单（D7）；注入（镜像 `RunTestTool` 的 `findTestPlanNode`+`addComponent`，EDT+TCCL）；running-guard |
| `stripCollectorNode(ActionEvent)` | `AgentResultCollector`（静态） | `Save.class` 预监听器入口：`removeFromGuiTree()`（try/catch 吞尽） |
| 两处 `addPreActionListener` 注册 | `SelectionInitCommand.doAction`（ADD_ALL 块内） | D5；`Start.class` 受 `agent.runcapture.enabled` 门控，`Save.class` 无条件 |

### 修改

| 文件 | 改动 |
|---|---|
| `AgentResultCollector.java` | +`RunProvenance` / `lastProvenance` / `reset(RunProvenance)` / `getLastProvenance()` / `onTestStartAction` / `isStartCommand` / `injectIntoGuiTree` / `isRunInProgress` / `stripCollectorNode`；修正 `removeFromGuiTree` javadoc（R9）。**静态状态模型与现有读取器不变。** |
| `RunTestTool.java` | 一行：`AgentResultCollector.reset()` → `reset(RunProvenance.AGENT)`（D2）。其余 doStart 原样（保留注入路径 = R3 保证）。 |
| `SelectionInitCommand.java` | ADD_ALL 的 `invokeLater` 块内、`SelectionTracker.install()` 旁注册两个预监听器（D5），`Start.class` 受开关门控。 |
| `GetTestStatusTool.java` | State 字段后加一行 `Started by: <provenance>`（读 `getLastProvenance()`）。 |

### 配置键

| 键 | 默认 | 说明 |
|---|---|---|
| `agent.runcapture.enabled` | `true` | 仅门控 `Start.class` 预监听器注册；关时 `run_test` 仍采集（D6）。`Save.class` 剥离监听器不受开关影响（防泄漏优先）。 |

---

## Risks / Trade-offs

- **[throwing 预监听器中止 start（R1）]** → 整体 try/catch(Throwable) + 回归测试。**最高优先级。**
- **[STOP/SHUTDOWN 误触发 reset（R2）]** → `isStartCommand` 白名单 + 单测。
- **[开关 OFF + 削薄 RunTestTool = 零采集（R3）]** → `RunTestTool` 注入路径原样保留 + 回归测试。
- **[AGENT 运行双注入（R4）]** → provenance-skip + 集成测试断言单节点。
- **[testEnded 不触发（引擎早退）→ stale 节点 + testStart 楔死（R5）]** → 预监听器每次 USER 注入前 `removeFromGuiTree()`；楔定检测 `getTestStartTime()>0 && !isTestRunning()`（是否调公开 `JMeterContextService.endTest()` 解楔留 Open Question 1）。
- **[运行中二次 start 实时清零（R6）]** → running-guard。
- **[JMX 泄漏（R7）]** → `Save.class` 预监听器剥离；不修复已污染的旧 jmx（文档化一次性手动删节点）。
- **[TCCL 不匹配致 addComponent NPE（R8）]** → 复刻 `RunTestTool` TCCL 交换 + 集成检查日志无 ClassNotFoundException。
- **[RUN_TG 路径收集器存活未运行时验证]** `keepOnlySelectedThreadGroupsInHashTree`（`Start.java:235-258`）只移除 `AbstractThreadGroup`，TestPlan 级收集器应存活并重新挂到各 TG 的 test-level 元素（`StandardJMeterEngine.java:574`）——需集成断言（Open Question 2）。
- **[VALIDATE_TG 误纳入（R10）]** → 初版 `isStartCommand` 排除。
- **[GUI 双击防御]** `MainFrame` 中途禁用 Run 菜单（`:452/485`）是否可靠未验证；预监听器 running-guard 作主防御（Open Question 3）。
- **[运行中保存后 plan 显 dirty]** 剥离节点改树会触发 tree-model 事件，保存后可能显 dirty（保存的文件已无收集器）——可接受（正确性优先），见 Open Question 9。

## Migration Plan

1. **默认开**（`agent.runcapture.enabled=true`）—— 这是本变更的目的；但所有改动 additive：`reset()` 仍可用、`RunTestTool` 注入保留、`AgentResultCollector` 静态读取器契约不变。
2. **回归基线**：`mvn clean test` 扣除已知常驻的 `CodeRefactorerTest` 失败（见 [[coderefactorer-test-preexisting-fail]]）后 0 回归；新增单测：白名单、provenance、running-guard、吞异常、开关 OFF 下 run_test 仍采集。
3. **人工 GUI 验证**：用户点 Run → `get_test_status`/`get_test_results` 有数据且 `Started by: USER`；`run_test` → `Started by: AGENT` 且树中恰一个收集器节点；运行中保存 → jmx 无 `__agent_result_collector__`。
4. **回滚**：设 `agent.runcapture.enabled=false` → 预监听器不注册，GUI 采集停，`run_test` 行为完全恢复变更前。

## Open Questions

1. 楔定态（testStart>0 且 testEnded 未触发）是否可从插件调公开 `JMeterContextService.endTest()` 解楔，还是只清自身标志 + 抛用户可见错误？（G1/R5）
2. RUN_TG/RUN_TG_NO_TIMERS 下 TestPlan 级收集器是否经集成测试确认进入引擎树？（G2）
3. JMeter 是否可靠地运行中禁用 Run 菜单？若否，running-guard 是唯一防御。（G7）
4. `lastProvenance` 初值用 USER 还是显式 UNSET/UNKNOWN？（`GetTestStatusTool` 对"未运行"短路使 USER 不致误显，但需断言。G4）
5. `get_test_results` 是否也显示 provenance？（当前只 `get_test_status`。G5）
6. VALIDATE_TG 是否将来需采集（可能要独立 `USER_VALIDATION` 来源）？（R10）
7. 属性命名：`agent.runcapture.enabled`（带前缀）还是 `runcapture.enabled`（对齐 `isIpcEnabled()` 风格）？
8. `RunProvenance` 枚举嵌套于 `AgentResultCollector` 还是 execution 包顶层？
9. 运行中保存后显 dirty 是否可接受，还是 Save 监听器需还原 dirty 状态？
10. 远程/分布式采集是否永久排除？（注：远程 sample 走不同路径，静态收集器即便挂 `RemoteStart.class` 也未必收到。）
