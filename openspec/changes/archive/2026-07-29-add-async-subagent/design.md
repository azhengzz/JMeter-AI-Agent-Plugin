# Design: 异步子代理（Async Subagent）

> 本设计经 6 路并行设计探针 + 对抗式验证（对着真实代码，引用 `file:line`）。裁决板：
> `blocking-drain` 🟢sound · `session-isolation` 🟢sound · `session-affinity` 🟡risky · `executor-topology` 🟡risky · `observability` 🟡risky · `scope-mechanism` 🟢sound（三次 workflow 均未完成——两次 429、一次中途被回收；最终由作者逐工具读 `executeInternal` 对抗核实，13 个只读工具零误判）。

## Context

JMeter agent 当前是单条主循环：`AgentSwingWorker` → `AgentLoop.processMessage` → 单线程 `agent-loop` executor → `agentRunner.run(spec).join()` → `runAgentLoop` 同步 while 循环（6 个注入检查点）。已有基础设施：

- `AgentRunner.run(AgentRunSpec)` 已是可复用执行原语：支持 `initialMessages`、`failOnToolError`、`hook`、`maxIterations`、`injectionCallback`、`abortFlag`。
- `InjectionManager`：每会话 `LinkedBlockingQueue`，`offer`（EDT，非阻塞）/ `drain`（loop 线程，**非阻塞** poll）/ `cleanup`。已有 mid-turn 注入机制（供用户消息）。
- `AgentHook` / `AgentHookContext`：完整的生命周期钩子（beforeIteration/afterIteration/beforeExecuteTools/afterExecuteTools/finalizeContent/onError）。
- `Tool` / `AbstractTool`：工具靠构造器注入依赖（`SaveMemoryTool(MemoryStore)` 是先例），`JMeterToolRegistry` 用无参构造批量注册。
- `ContextBuilder.buildSystemPrompt`：identity + bootstrap(AGENTS/SOUL/USER/TOOLS.md) + memory + always-skills + skills-summary。

**核心翻译难题**：Nanobot 是 `asyncio`（spawn 立即返回 + `_drain_pending` 阻塞接结果）；这里是单线程 executor + 同步工具执行。本设计采用 Nanobot 的「异步派发 + 回合合流」混合模型，把阻塞藏在已有的 `injectionCallback` 闭包里，让 `AgentRunner` 对阻塞零感知。

## Goals / Non-Goals

**Goals:**
- 主代理可通过 `spawn` 工具非阻塞派发子任务；子代理在专用线程池跑独立 AgentRunner 循环。
- 子代理结果回合内回注主代理（阻塞式注入，~120s 超时），同一回合消费。
- 工具 scope 机制隔离子代理工具集（默认只读分析型），从源头防递归。
- 彻底会话隔离：子代理零字节写入主会话 jsonl，不参与记忆整合。
- 主代理可主动查询子代理状态；终端用户只看到完成态摘要。
- 跨池取消：主 run 取消时级联取消子代理。

**Non-Goals（明确排除）:**
- 不给主 run 也换专用 executor（保留 ForkJoinPool.commonPool）；2 核机器的 commonPool 占用是已知可接受限制，列为后续硬化项。
- 不实现 Java 版 `finalize_on_max_iterations`（子代理到迭代上限时返回固定占位文本，靠主代理自然转述）。
- 不支持子代理派生子代理（scope 硬禁止）。
- 不向终端用户实时暴露子代理进度（仅完成态摘要）。
- 不改子代理并发上限默认值（默认 1，沿用 Nanobot）。

## Decisions

### D1. 执行模型：异步派发 + 回合合流（Nanobot Option B）

**选择**：spawn 立即返回 taskId；子代理后台跑；主代理在注入检查点阻塞等结果。

**为什么不用同步 spawn（Option C）**：同步 spawn 更简单、也规避 GUI 并发，但用户明确要求 Nanobot 的异步合流模型——其核心价值是"主代理 spawn 后可继续当前回合的其它工作，结果按完成顺序回合内消费"。子代理**只读**（决策 2）已化解 GUI 树并发风险（读写都走 EDT 序列化，仅快照不一致），异步模型在此条件下安全。

**阻塞藏在哪里**：`AgentRunner` 对阻塞**零感知**。`injectionCallback` 是 `Function<Integer,List<String>>`，阻塞 = 回调"返回得慢一点"。`AgentLoop` 新增 `drainInjected(sessionKey, limit)` helper 替换 [AgentLoop.java:191](../../../../src/main/java/org/gitee/jmeter/ai/agent/AgentLoop.java) 的 lambda：若 `subagentManager.getRunningCountBySession(sessionKey) > 0` 则调 `drainBlocking`，否则走原 `drain`。**AgentRunSpec 不加任何字段**（决策 6 评审：最小爆炸半径）。

### D2. 阻塞 drain 放在 `InjectionManager`，不放 `SubagentManager`

**选择**：`InjectionManager.drainBlocking(sKey, lim, timeoutMs)`。`InjectionManager` 拥有队列+机制，`AgentLoop` 拥有策略（"是否阻塞"判定）。announce 文本经 `injectionManager.offer(mainSessionKey, text)` 入**同一队列**，`drainBlocking` 用 `queue.poll(timeout, MS)` 阻塞读。无需第二个队列，更贴近 Nanobot（InboundMessage → pending queue → `_drain_pending`）。

### D3. 工具 scope 机制（决策 1a）

**选择**：`Tool` 接口加 `default Set<String> getScopes() { return Set.of("core"); }`。子代理工具集由 `SubagentManager` lazy 构建一次后缓存（主 registry 启动后静态），遍历主 registry、只 INCLUDE `getScopes().contains("subagent")` 的工具。`spawn`/`subagent_status` 声明 `{core}` → 自动排除 → 防递归 + 防自省。工具**实例共享**（filter 自主 registry），executor 共享（避免每次 spawn 建新池）。

**替代方案（否决）**：在 `SubagentManager` 维护显式工具名白名单 `Set<String>`。更简单但不可扩展、易漏标；scope 机制随工具增长更可维护。

**语义澄清（observability 验证者抓的自相矛盾）**：是"INCLUDE 含 subagent 标签的"，**不是**"exclude non-core"（默认 scope 是 {core}，后者会把所有默认工具包括 spawn 都放进去）。

### D4. 会话隔离：`persistSession=false` + 临时 Session（决策 3）

**选择**：`AgentRunSpec` 加 `persistSession`（默认 true）+ `runExecutor`（可空）。子代理用 `.sessionKey("subagent:"+taskId).persistSession(false)`。`AgentRunner.run` 内 3 个分支点 gate 掉所有持久化：

- [AgentRunner.java:100](../../../../src/main/java/org/gitee/jmeter/ai/agent/run/AgentRunner.java) — `session = persistSession ? sessionManager.getOrCreate(key) : new Session(key)`
- [:103](../../../../src/main/java/org/gitee/jmeter/ai/agent/run/AgentRunner.java) — pre-run consolidation 由 `persistSession` 守卫
- [:124-128](../../../../src/main/java/org/gitee/jmeter/ai/agent/run/AgentRunner.java) — post-run `saveMessagesToSession` + consolidation 由 `persistSession && !isAborted` 守卫

grep 已确认 `AgentRunner` 内仅 2 处 `SessionManager` 调用（:100 getOrCreate、:622 saveSession）+ 整合路径；3 个分支点全覆盖。临时 Session 不入 `SessionManager.sessions`，`shutdown`/`loadSessions` 都碰不到 → JVM 重启也不残留。

**替代方案（否决）**：单独 `SubagentRunner` 子类。会 fork ~500 行 `runAgentLoop`（:217-483），维护灾难，且循环体本身不持久化，零收益。

### D5. 会话亲和：ThreadLocal `AgentRunContext`（决策 6）

**选择**：`SpawnTool.execute(Map)` 无请求上下文。新增 `AgentRunContext`（ThreadLocal{sessionKey, runId}），由 `AgentRunner.run` 在 supplyAsync lambda 内（runId 赋值后）set、finally clear。并发模式：`ToolRegistry.executeAsyncWithEvent` 包装器捕获并在 tool-executor 线程重放（[:280](../../../../src/main/java/org/gitee/jmeter/ai/agent/tools/ToolRegistry.java) 是唯一 choke point，批处理 [:325](../../../../src/main/java/org/gitee/jmeter/ai/agent/tools/ToolRegistry.java) 逐个委托，自动覆盖）。`SubagentManager` 经构造器注入（`SaveMemoryTool` 先例），通过 `AgentRunContext.current().sessionKey` 获得主会话 key。

**替代方案（否决）**：改 `Tool.execute` 签名传 `ToolContext`——blast radius ~26 工具 + 3 抽象中间层，且并发模式仍需同样的 capture-replay 包装器，纯成本无收益。`SubagentManager` 跟踪"active main session"——多会话下是 race（`activeTasks` 等全按 sessionKey 键）。

### D6. 执行器拓扑（专用 fixed 池）

**选择**：`SubagentManager` 拥有 `Executors.newFixedThreadPool(maxConcurrentSubagents)`（daemon `subagent-N`）。子代理 run 跑在此池。`AgentRunSpec.runExecutor` 把子代理 run 从 ForkJoinPool.commonPool 引到该池。

**死锁可证伪**：阻塞线程 T_main_loop（commonPool worker，park 在 `Future.get(120s)`）≠ 完成 future 的线程 T_subagent（专用池）。EDT 路径：只读子代理工具 + 主代理改树工具都经 `EdtRunner.invokeAndWait`（[:43-44](../../../../src/main/java/org/gitee/jmeter/ai/agent/tools/jmeter/utils/EdtRunner.java) 证伪死锁——EDT 不回调），仅快照不一致。

**每个 spawn new 独立 `AgentRunner`**（一号不变量，见硬阻塞 1）：各自 `volatile runningThread` 独立，interrupt 精准。

### D7. 可观测性（决策 4）

**选择**：`SubagentStatus`（volatile 标量 + 不可变快照 copy-on-write，单写者子代理线程）+ `SubagentHook`（afterIteration 回写 iteration/toolEvents/usage/error）+ `SubagentStatusTool`（scope={core}，主代理主动查询，输出格式移植 `self.py:227-241`）+ announce 模板。终端用户只看完成态：announce 文本作为注入 user 消息回合内被主代理消费，主代理自然转述为最终响应；announce 不发 `ProgressUpdate`（注入消息是 loop 内部的，[AgentRunner.java:185-196](../../../../src/main/java/org/gitee/jmeter/ai/agent/run/AgentRunner.java)）→ 对用户不可见。

---

## ⚠️ 5 条跨路硬阻塞（对抗验证产出，实现时必须遵守）

这些是单路设计者各自看不到、验证者读真实代码才揪出来的：

### 阻塞 1：`AgentRunner.runningThread` 共享字段被踩（3 路联合报警）

`AgentRunner.java:55` 是单个 `volatile Thread`，每次 `runAgentLoop` 入口（:225）覆写。若子代理复用单例 AgentRunner，并发子代理 run 会把主代理的 runningThread 冲掉 → 主代理 Stop 的 `interrupt()`（:633）要么打偏到子代理、要么在子代理 finally 置 null（:481）后彻底失效。

**修正（铁律 / 一号不变量）**：`SubagentManager.spawn` 每次 `new AgentRunner(...)` 独立实例；主 run 继续用 `AgentLoop` 共享实例。

### 阻塞 2：隔离是两层，缺一不可

- **层 A**：`persistSession=false` + 临时 Session（D4，gate 3 点）。
- **层 B（致命）**：`SubagentManager` **必须直接调 `agentRunner.run(spec)`，绝不能走 `AgentLoop.processMessage()`**——后者有 `getOrCreate`（[AgentLoop.java:146](../../../../src/main/java/org/gitee/jmeter/ai/agent/AgentLoop.java) Phase2、:172 Phase3），**不受 `persistSession` 约束**，会立刻写 stub jsonl。
- 子代理**不共享 `MemoryConsolidator`**（`maybeConsolidate` 会污染共享记忆；D4 分支点已跳过两次 consolidation）。

### 阻塞 3：`Function` 回调不能 `throws InterruptedException`

`injectionCallback` 是 `Function<Integer,List<String>>`，无法声明受检异常。`drainBlocking` 必须**内部 catch InterruptedException → `Thread.currentThread().interrupt()` 复位 → 返回空 list**。且 `cancelBySession` 必须**先 set abortFlag 再 interrupt**，下一次 `isAborted()`（:625）无论如何能跳出。`drainBlocking` 不得抛任何 RuntimeException（Java 端 `tryDrainInjections` 不吞回调异常，不同于 Nanobot runner.py:265-267）。

### 阻塞 4：Stop 必须先取消子代理

`cancelActiveTask` 里 `subagentManager.cancelBySession(sessionKey)` 放在**最前面**（主 interrupt 之前）。否则主线程 park 在 `Future.get(120s)`，`latch.await(5s)` 先超时，Stop 看着失效长达 120s。前置 → 打断子代理 → 子代理 future 完成 → 主 drain 解阻塞 → 亚秒级响应。

### 阻塞 5：迟到 announce 会落进不相关后续回合

"无活跃队列"分支已处理（offer 返回 false → 退化为新 processMessage）。但有个更糟窗口：主回合已结束 cleanup 删了旧队列、**新回合已注册新队列**——announce 的 `offer(newQueue)` 返回 true，结果作为 user 消息注入无关新回合、把它带偏。

**修正**：announce 携带 **turn-token**（spawn 时捕获主 run 身份，如 activeTasks 的 future 身份或会话单调 turnId）；只在"当前活跃 run == 派发它的 run"时 offer；否则保留为 terminal 状态，等主代理用 `subagent_status` 主动捞。需要在 `AgentLoop` 或 `InjectionManager` 暴露 run-identity 访问器。

---

## 执行流（async 模型全貌）

```
SwingWorker(非EDT) ─→ AgentLoop.processMessage(msg, sessionKey)
  │
  ├─ agent-loop 单线程 (AgentLoop:88)
  │    Phase3: supplyAsync → [set AgentRunContext] → agentRunner.run(spec).join()  ← .join() 停住(单用户可接受)
  │
  ├─ ForkJoinPool.commonPool worker (T_main_loop)
  │    runAgentLoop while-loop, 6 注入检查点
  │    每点 → injectionCallback = drainInjected(sKey, lim)
  │      ① 先非阻塞 drain(用户消息优先)
  │      ② 空 && subagentManager.getRunningCountBySession(sKey)>0 ?
  │           是 → drainBlocking(sKey, lim, 120s)   ◀── park 在这
  │           否 → 立即返回
  │
  │    LLM 调 spawn ─→ SpawnTool.executeInternal:
  │       ctx = AgentRunContext.current()  (ThreadLocal, sessionKey 来源)
  │       并发校验 getRunningCountBySession(ctx.sessionKey) < limit ?
  │       manager.spawn(task, ctx.sessionKey, turnToken, …)  ── 非阻塞,返回 taskId
  │
  ├─ 子代理专用 fixed 池 (SubagentManager 拥有, "subagent-N")
  │    new AgentRunner(...)  ← 每 spawn 独立实例(铁律,解 runningThread)
  │    AgentRunSpec:
  │      sessionKey="subagent:"+taskId, persistSession=false(隔离)
  │      runExecutor=子代理池, failOnToolError=true
  │      injectionCallback=null(子代理不合流), hook=SubagentHook
  │      initialMessages=[精简system, user task]
  │    run() → … → announceResult:
  │      render subagent_announce.md
  │      if 同 turn 活跃: injectionManager.offer(mainSessionKey, 文本) ── 解阻塞 T_main_loop
  │      else: 保留 terminal 状态 / 退化 processMessage(新回合)
  │
  └─ cancelActiveTask(sKey):
       ① subagentManager.cancelBySession(sKey)  ← 最先! 解阻塞(阻塞4)
       ② 主 abortFlag + agentRunner.interrupt()
       ③ future.cancel + latch.await(5s)
```

**死锁可证伪**：T_main_loop（commonPool）≠ T_subagent（专用池），永不相同。EDT 序列化（EdtRunner.invokeAndWait），无撕裂。

---

## 组件清单

### 新增类（7）+ 1 模板

| 类 | 包 | 职责 |
|---|---|---|
| `SubagentManager` | `agent.subagent` | 调度核心：spawn(非阻塞)/专用 fixed 池/状态注册/cancelBySession/getRunningCountBySession/announceResult |
| `SubagentStatus` | `agent.subagent` | 线程安全状态：volatile 标量 + 不可变快照，Phase 枚举(initializing/awaiting_tools/tools_completed/final_response/done/error) |
| `SubagentHook` | `agent.subagent` | 移植 `_SubagentHook`：afterIteration 回写状态，beforeExecuteTools 打日志（不推 UI） |
| `AgentRunContext` | `agent.run` | ThreadLocal{sessionKey, runId}；AgentRunner.run set，ToolRegistry 异步包装器重放 |
| `ResultSink` | `agent.subagent` | `@FunctionalInterface offer(sKey,msg)`，打破 AgentLoop↔SubagentManager 循环依赖 |
| `SpawnTool` | `agent.tools.subagent` | 构造器注入 manager；读 AgentRunContext；并发校验；scope={core} |
| `SubagentStatusTool` | `agent.tools.subagent` | 主代理主动查询；scope={core} |
| `subagent_announce.md` | `resources/templates/subagent/` | 移植 Nanobot 模板（含"自然转述,别提 subagent/taskId"指令） |

### 修改文件（全部 additive）

| 文件 | 改动 |
|---|---|
| `Tool.java` | +`default Set<String> getScopes(){return Set.of("core");}` |
| `AgentRunSpec.java` | +`persistSession`(默认true) +`runExecutor`(可空) +build() 校验：`subagent:` 前缀强制 persistSession=false & injectionCallback=null & 非空 initialMessages；userMessage 仅 initialMessages 空时必填 |
| `AgentRunner.java` | 3 个 persistSession 分支点(:100/:103/:124-128) + run() 用 `spec.getRunExecutor()`(可空则 commonPool) + lambda 内 set/clear AgentRunContext |
| `InjectionManager.java` | +`drainBlocking(sKey,lim,timeoutMs)`（poll 超时，catch IE 复位返回空） |
| `ToolRegistry.java` | `executeAsyncWithEvent` 包装器捕获并重放 AgentRunContext（并发模式传播） |
| `AgentLoop.java` | 持有 SubagentManager；`offerInjection` seam；`drainInjected` helper 替换 :191 lambda；`cancelActiveTask` 最前面加 cancelBySession；shutdown 关闭子代理池 |
| `AgentLoopFactory.java` | 构造 SubagentManager(...,`agentLoop::offerInjection`)；注册 SpawnTool+SubagentStatusTool；gate on `agent.subagent.enabled` |
| `BuiltinCommands.java` | `cmdNew` → 先 `cancelActiveTask` 再 `session.clear()` |

### scope 分类表（已读真实工具核实）

| scope | 工具 | 判据 |
|---|---|---|
| `{core, subagent}` 只读 ✅ | `get_script_info` · `get_selected_element` · `get_test_plan_tree` · `parse_jmx_file` · `find_element` · `query_element_properties` · `get_log_panel_content` · `get_test_status` · `get_test_results` · `read_file` · `list_dir` · `web_search` · `web_fetch` | 只读树/文件/网络，无副作用 |
| `{core}` 改树/执行 ❌ | `create/update/batch_update/delete/move/copy_paste/toggle_jmeter_element` · `open_jmx_file` · `run_test` · `write_file` · `edit_file` · `exec` | 改 GUI 树/触发测试/写文件/执行命令 |
| `{core}` 编排/递归 ❌ | `spawn`（防递归）· `subagent_status`（主代理自省专用） | 不带 subagent 标签 → 自动排除 |

> filesystem/web/exec 受现有开关控制（默认 false），只有开启的才进主 registry、才可能被 filter 进子代理。

### 配置键

| 键 | 默认 | 说明 |
|---|---|---|
| `agent.subagent.enabled` | `false` | 特性开关 |
| `agent.subagent.max.concurrent` | `1` | 每主会话并发上限 |
| `agent.subagent.max.iterations` | `50` | 子代理迭代上限 |
| `agent.subagent.drain.timeout.seconds` | `120` | 阻塞 drain 超时（上限 300） |
| `agent.subagent.status.retention.seconds` | `60` | 完成态状态保留 TTL（晚到结果可查窗口） |
| `agent.subagent.status.max.completed` | `10` | 每会话保留的完成态状态上限（0 = 不设上限） |

---

## Risks / Trade-offs

- **[ThreadLocal 泄漏]** AgentRunContext 在 commonPool/tool-executor 池化线程上若忘 clear → stale sessionKey 路由到错会话。→ 两处（AgentRunner.run + ToolRegistry 包装器）finally 强制 clear；单测断言同池化线程 run 后 current()==null。
- **[commonPool 占用（2 核机器）]** T_main_loop 阻塞占一个 commonPool worker；2 核机器 parallelism=1，是唯一 worker。→ 单用户单会话可接受（主回合等待时无事可做）；多会话场景给主 run 也换专用池（后续硬化项）。
- **[子代理 LLM 调挂死]** `callLLM` 无 per-run wall-clock 超时，子代理线程可能孤立。→ SubagentManager 加 per-subagent 最大运行时 watchdog（与 drain 超理解耦），到时设 abortFlag + interrupt。
- **[checkpoint 6（max iterations）]** Java 无 `finalize_on_max_iterations`，阻塞它会卡回合退出却无法把结果再喂 LLM。→ checkpoint 6 保持**非阻塞**，靠 Phase-3 finally 重发兜底（blocking-drain + executor-topology 一致建议）。
- **[drain 超时后反复重阻塞]** 若超时但子代理仍在跑，`hasPendingAnnouncement` 仍 true，下个检查点再阻塞 120s × 6 × 5。→ 超时后**移除 pending 条目**，迟到结果走 announce 退化路径（新回合）。
- **[GUI 快照不一致]** 只读子代理可能读到主代理改树中的中间快照。→ 每次 `invokeAndWait` 在 EDT 上原子，无撕裂；分析任务可接受。不行动，仅文档化。
- **[AgentLoopFactory 重建 orphan 子代理]** AiService 切换时重建 AgentLoop（:29-35），旧 SubagentManager 的 announce 进死 sink。→ `SubagentManager.shutdown()` 接入 `AgentLoop.shutdown()`。
- **[/new 不取消]** `cmdNew`（BuiltinCommands:23）只 `session.clear()` 不 cancelActiveTask；子代理把竞态窗口从亚秒拉长到分钟。→ `cmdNew` 先 `cancelActiveTask` 再 clear。
- **[并发上限 TOCTOU]** concurrentTools=true 时一批两个 spawn 调用竞态 getRunningCount 检查。→ per-mainSession 锁同步"限额检查 + 记录创建"（今日 concurrentTools=false 是潜伏，须随特性一起上）。
- **[scope-mechanism 已对抗核实]** 该路由三次 workflow 均未完成（两次 429、一次中途被会话回收），最终由作者**逐工具读 `executeInternal`** 对抗核实：13 个标只读的工具（get_script_info / get_selected_element / get_test_plan_tree / parse_jmx_file / find_element / query_element_properties / get_log_panel_content / get_test_status / get_test_results / read_file / list_dir / web_search / web_fetch）均确认无 `setEnabled/setName/setProperty/addTestElement/removeNode/setText/ActionRouter` 等突变调用；标改的工具亦确认有副作用（open_jmx_file=loadTree+insertLoadedTree、run_test=addComponent+ActionRouter、create/.../toggle 经 TestElement、write_file/edit_file/exec）。**分类零错误，设计 sound。** 透明声明：此 verdict 由作者自检得出（非独立 agent），分类已对代码经验证，但"独立怀疑者"视角的严谨度弱于其余 5 路。→ 残余**维护风险**（非 bug）：新增只读工具必须显式标注 `{core,subagent}` 才对子代理可见，否则静默不可见——需在开发文档/checklist 中写明。

## Migration Plan

1. **特性开关默认关**（`agent.subagent.enabled=false`）→ 零行为变化，主路径不受影响。
2. **additive 改动**：所有新字段默认值保持现有行为（`persistSession=true`、`runExecutor=null`、`getScopes()={core}`）。
3. **渐进启用**：内部测试开 `agent.subagent.enabled=true` + 单测覆盖（隔离零污染、阻塞 drain、cancel 级联、防递归、ThreadLocal 清理）。
4. **回滚**：设 `agent.subagent.enabled=false` 即恢复原状（SpawnTool/SubagentStatusTool 不注册，drainInjected 退化为非阻塞 drain）。

## Open Questions

1. checkpoint 6 是否阻塞？（当前定：不阻塞，靠 finally 重发。可调。）
2. drain 超时后是否立即把子代理标 hung？（当前定：靠 SubagentManager 独立 watchdog，drain 只 log+continue。可选 `onDrainTimeout` 钩子。）
3. 子代理工具集是 per-spawn 还是缓存一次？（当前定：缓存一次，主 registry 启动后静态。）
4. turn-token 用什么载体？（activeTasks future 身份 vs 会话单调 turnId——实现时定。）
5. 子代理 system prompt 模板（`subagent_system.md` 等价物）是否需要单独资源文件？（当前定：`SubagentPromptBuilder` 复用 `ContextBuilder` 段落，精简组装，不强制独立文件。）
