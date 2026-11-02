# Design

## Context

超时配置审计定位的 6 处结构性风险（R2-R7，见 proposal.md）分布在四条链路上：工具执行批（R2/R3）、IPC 传输与实例发现（R4/R6）、记忆整合（R5）、回合内测试执行（R7）。所有行号锚点经两轮对抗验证工作流核实：R2 由 8-agent 设计面板（4 视角 + 4 对抗判决）验证；R3-R7 由 5 个独立读码核查员验证（R3 原机制被推翻后已按修正方案重写，R6 按裁决改选方案 B）。

关键现状事实（均已核实）：

- `AgentRunner.executeToolCalls`（AgentRunner.java:880-902）：并行批（连续 concurrency-safe、size>1）走 `executeAsyncWithEvents(batch, toolTimeoutMs).join()`；其余单例批内联 `executeWithEvent`，无超时。孤立 safe 调用落入单例批（partitionByConcurrencySafety :909-928）。
- `EdtRunner.run`（EdtRunner.java:52-70）是 src/main 唯一 `invokeAndWait`，14 个调用点；动作抛 Error 时以 `java.lang.Error` 逃逸出 `catch(Exception)`。
- `ToolRegistry.executeAsyncWithEvent`（ToolRegistry.java:278-321）：`supplyAsync(cached 池) → orTimeout → exceptionally`，超时分支无任何机制中断在跑 supplier（泄漏）。该路径**仅并发安全只读工具**可达（spawn/run_test 均默认 unsafe，全库唯一生产调用点 = AgentRunner.java:886）。
- `IpcServer`：固定 4 线程池（:176）服务全部路由；`handleAgent` 在 :309 阻塞至 `future.get(timeout)`；`/tool` 同步执行（:260），`ALLOWED_TOOLS` 含 run_test（smoke 经 /tool 最坏阻塞 ~200s）。
- `InstanceRegistry`：`InstanceInfo` = pid/port/token/startedAt/bind/instanceId/jmxPath（:280-286）；`writeInstance`（:70-86）生产调用点仅 IpcServer:192；`updateJmxPath`（:94-107）是整对象序列化读改写（新字段不丢）；`@JsonIgnoreProperties(ignoreUnknown=true)` 自 v0.3.2 起。
- `MemoryConsolidator.consolidateWithAi`（:264-280）锁内含 LLM（:308-348）与整文件替换写（`writeLongTermMemory`，MemoryStore.java:94-124，temp+ATOMIC_MOVE 原子）；`lockLongTermMemory` 在 main 的唯一调用点即 :267（**不存在 save_memory 注册工具**——它是整合器内建强制 tool-call，SAVE_MEMORY_TOOL_DEF :43-60）；跨进程锁只保护 MEMORY.md 读改写，HISTORY 追加本就无锁（archiveSync 先例）。
- `RunTestTool`：smoke 完成死线 180s（:42/:409-431）；引擎派发在 :305（EDT invokeLater）、启动确认 :354-379；中断处理 :418-422 恢复中断位并返回结构化错误（不杀回合）；「用户运行抢占」拒绝先例 :387-391（明示不消耗冒烟预算）。
- Stop 取消链：AgentLoop.java:1211 abortFlag.set + :1230-1232 runnerThread.interrupt()；goal 收敛门 `convergeGoalOnUserStop`（:1047-1052）只认 USER_STOP 且非委派——**TIMEOUT 取消下 goal 保持 ACTIVE**。

## Goals / Non-Goals

见 proposal.md 的范围与 Non-goals。设计级边界补充：

- 不改变并行批 30s 默认超时值与单例批内联结构（AgentRunner.executeToolCalls 一行不动）。
- 不为 R3-R7 新增任何用户配置键（R2 的 `agent.tools.edt.wait.timeout.ms` 是唯一新键）。
- IPC 线/协议（请求/响应体、状态码语义）零变化；端口文件仅追加字段。
- 不触碰 `writeLongTermMemory` 的原子写实现。

## Decisions

### D1（R2）· EdtRunner 有界等待，单点收口

**决定**：只重写 `EdtRunner.run` 一个类，公开签名 `run(GuiPackage, EdtAction) → Exception` 不变，14 个调用点零改动。

- 等待机制：`EventQueue.invokeLater(动作体) + CountDownLatch + await(timeoutMs)`；`invokeAndWait` 移除。动作体逐字保留 ClassLoader 切换与 `action.run()`，`catch (Throwable t)`（Exception 原样进 `error[0]`，Error 包为 `InvocationTargetException` 并 `log.error`——对齐 invokeAndWait 的类型行为，且 Error 不再静默），`finally` 中恢复 TCCL 并 `done.countDown()`（countDown 不在 finally 会在动作抛 Error 时误报成超时）。
- `EventQueue.isDispatchThread()` 守卫：EDT 上调用返回 `IllegalStateException`（现状是 invokeAndWait 抛 `java.lang.Error` 逃逸杀回合；当前无 EDT 调用方，纯加固）。
- 中断路径**单独 catch** `await` 抛出的 `InterruptedException`：不恢复中断位（与 invokeAndWait 清位逐位一致，取消事实来源是 `turn.abortFlag`），返回 `IllegalStateException(中止/结局未知契约文案, ie)`。
- 超时返回 `EdtWaitTimeoutException extends TimeoutException`，`getMessage()` 单层自含完整契约：结局**三分真话**（仍在排队 / 执行中 / 已完成但调用方没等到）、禁止盲目重试（会叠加在迟到原操作之上）、先经 `get_test_plan_tree` / `find_element` 等不经 EdtRunner 的只读探针复查。**绝不撤回已投递的 EDT 任务**（孤儿照常执行 = 既有 Stop 语义的形式化）。
- 超时配置：`AiConfig.getEdtWaitTimeoutMs()`，键 `agent.tools.edt.wait.timeout.ms`，默认 120000，下限钳 1000ms，无 0=关闭语义（无界等待正是被修的 bug）。量纲口径为**单次等待**（批工具 deselectCurrentNode + 主块两次串行等待最坏 240s——如实写入 javadoc，不用回合级不等式论证）。package-private `run(gui, action, timeoutMs)` 重载为测试缝。
- 伴随修复：`BatchUpdateJMeterElementTool.java:234-237` 对 edtError 只 log 后继续、以成功形态返回 "Updated 0 of N"——改为与 BatchDelete:183-185 同款的 `ToolResult.error` 早退（新超时使该分支成为活路径，吞错会让契约在 batch_update 上整体缺失）。
- javadoc 同步：`IpcServer.java:53-54` 的 invokeAndWait 措辞、`SnapshotTreeCapture` 两处（行为不变：latch.await 与 invokeAndWait 对中断逐位一致，保位变体原样成立）。附带收益：回合起点/终点快照克隆从「EDT 卡死时无限阻塞」变为有界 best-effort 跳过。

**备选与否决**：① 单例批挪专用串行 executor（unified-pool）——判决列出 7 处必修（子代理只读工具被连坐吃「勿重试」文案、ExecTool 600s 需覆盖、7 处测试构造点破坏、Stop 语义漂移等），侵入面大一个量级；② 入口探活门 + 进程级熔断——探活门有价值但引入双配置键与忙而未死误报，熔断器判决空票未验证；列为二期观察项。③ 超时后跳过执行的 AtomicBoolean——check-then-act 竞态救不了结局可知性，否决。

### D2（R3）· 真中断替代 cancel(true)：工作线程引用 + 显式 interrupt()

**决定**：`executeAsyncWithEvent` 内以 `AtomicReference<Thread> workerRef` 捕获工作线程——supplier 首语句 `workerRef.set(Thread.currentThread())`；`exceptionally` 超时分支以**消费式 CAS** 取线程：`Thread t = workerRef.get(); if (t != null && workerRef.compareAndSet(t, null)) t.interrupt();`；worker `finally` 以 `compareAndSet(self, null)` 清引用。签名不变。

**为什么不是 `future.cancel(true)`**（原方案，被判决推翻）：`CompletableFuture.cancel(mayInterruptIfRunning)` 在 JDK 中**明文不中断任何线程**（参数被忽略，仅以 CancellationException 完结 future）；且 `orTimeout` 返回 `this`、超时任务把同一个 future 以 TimeoutException 完结——exceptionally 分支执行时 cancel 的 `(result == null)` 守卫必假，纯死代码。

**安全性**（已核实）：该路径仅并发安全只读白名单可达；spawn 与 run_test 均默认 unsafe 不经此道；EdtRunner 调用点中仅 GetLogPanelContentTool 在并行道，其中断只会解开 R2 的 latch.await（EDT 任务照常排队，硬约束保持）。

**跨任务迟到中断**（评审 P2-5 修正）：朴素 get-then-interrupt 存在「超时线程取到 t → worker 清引用归池 → 池把该线程派给下一任务 → interrupt 命中下一个任务」的窄窗口——「无跨任务毒化」的绝对化结论不成立。消费式 CAS 闭合该窗口：interrupt 只在 ref 仍指向本任务线程时发出（CAS 赢 = worker 尚未清引用 = 仍在本 supplier/finally 内），worker 清引用先于线程归池。残余窗口（worker 已过最后语句但未归池时收到中断）无害；`runWorker` 每任务前清残标志仍是第二道防线。表述统一为 **best-effort**。

**best-effort 为正确标准并文档化**：WebFetch/WebSearch 的 `HttpURLConnection` 阻塞读不响应中断（照跑完、结果丢弃）——不改造为可中断通道；不响应中断的工具行为与今天完全一致。

**顺带**：修正 `ToolRegistry.java:287-289` 过时注释（把并行道上下文搬运的理由写成 "spawn still knows their session"——spawn 从不经此道，真实消费者是 subagent_status 等）。

**测试锚**：safe 测试工具阻塞在 `CountDownLatch.await` → 批超时 → 断言线程真实被释放且结果为超时错误——此测试恰能当场抓住 cancel-no-op 类缺陷。

### D3（R4）· 端口文件发布 agentTimeoutMs，仅 /agent 端点消费

**根因锚点**（用户质证后修正）：权威值是服务器侧**可配置项**而客户端是**编译期常数**——配置≠常数即复现窗口（服务器配 900s 时 CLI 310s 放弃、空跑 590s；双实例配置不一致互委派同理）。正常部署 CLI 与 GUI 同 jar 同时更新，纯版本错配只是次要触发面。发布使命 = 让等待方自动跟随权威值，常数退化为纯回退（未来调默认值无需再三处联动改常数）。

**决定**：

- `InstanceInfo` 增 `agentTimeoutMs`（long，缺省 0=未发布）；`writeInstance` 签名 +1 参。**定格快照**（评审 P1-2）：`IpcServer.start()` 把 `AiConfig.getIpcAgentTimeoutMs()` 读入 `volatile long` 字段，`writeInstance`（:192 一处生产调用点）与 `handleAgent`（:293 现读配置处）**都改读该字段**——构造性保证「发布值 == 执行上界」。不改快照的话 `run_test` 的 `properties` 参数可运行时注入 `jmeter.ai.ipc.agent.timeout.ms`（RunTestTool.java:268 `runProperties.forEach(JMeterUtils::setProperty)`），现读的服务器与只写一次的端口文件必然分歧。测试基础设施的运行时改属性（IpcServerAgentTimeoutRaceTest @BeforeEach 等）相应改为 **start() 之前**设置属性（或经包内缝注入）。9 处 `writeInstance` 测试调用点编译期可见、逐一更新。`updateJmxPath` 整对象读改写自动保新字段（已核实）。
- **CLI**：派生**只作用于 `agent` 子命令的 `/agent` 请求**（在 agent 命令构造请求处计算 effective = `published>0 ? published+10_000 : DEFAULT_REQUEST_TIMEOUT_MS`，显式 `--timeout` 最优先）；生效值暂存于 `Parsed` 供 `friendly()`（JmeterCli.java:135 catch 块）回显——否则报错文案会报 310s 缺省而实际生效派生值，违反 :153-154 亲定的回显契约。**单一解析**（评审 P3-3）：`postIpc`（:731）内部会再次 `resolveInstance`——派生与发送必须使用**同一次**解析结果（把 InstanceInfo 或生效超时显式传入 `postIpc`），否则双实例切换窗口内会出现「按 A 的发布超时请求 B」。
- **不派生面**（判决纠正）：`run --wait`（:552）与经 `/tool` 路由的全部命令维持 `DEFAULT_REQUEST_TIMEOUT_MS` 缺省——`/tool` 同步执行无 agent 超时语义，派生会把长跑测试的等待窗静默绑到 `agentTimeoutMs`（服务器配 60s 时 `--wait` 70s 即被截断）。`/health` 15s 快探活不动。
- **委派**：`DelegateToInstanceTool` 改为 `target.getAgentTimeoutMs()>0 ? target.getAgentTimeoutMs()+10_000L : AiConfig.getIpcAgentTimeoutMs()+10_000L`（目标 InstanceInfo 已在 :115 解析在手）。**余量 +5s→+10s**（评审 P1-1）：服务器超时分支顺序是「`cancelActiveTask`（内部 `waitForCancellation` 最坏等 **5s**，AgentLoop.java:1106-1109）→ TOCTOU 判断 → 发 504」，504 最晚在 targetTimeout+5s 才发出；委派客户端 +5s 且计时起点更早（请求发出即计时，早于服务器 `future.get` 一个网络+解析延迟）——挂死回合烧满取消等待时客户端几乎必然先死、拿不到结构化 504 退回裸 `HttpTimeoutException`，恰丢掉 R4 要保的收益。+10s 与 CLI 对齐，覆盖取消收尾等待。
- **兼容边界**（判决纠正）：容忍未知字段的旧读者 = **≥v0.3.2**（`@JsonIgnoreProperties` 加入时点）；v0.3.0/v0.3.1 读者遇新字段 `readSilently` 返 null → 实例被当损坏文件跳过（优雅降级、既已接受的边界，文档如实记录）。**陈旧性由定格快照消解**（见首条）：发布值与执行上界同源于 start() 时刻读入的同一 volatile 字段，`handleAgent` 不再现读——运行时属性注入不会造成两边分歧。

### D4（R5）· 乐观并发：锁只覆盖读-验证-写

**决定**：重构 `consolidateWithAi`：

```
尝试 1（乐观，无锁）: M0 = readLongTermMemory() → abort 检查 → LLM(M0) → 取锁
    → M1 = readLongTermMemory() → M1==M0 ? 锁内两门写(幂等比较 M1) : 解锁进入尝试 2
尝试 2（乐观，无锁，以 M1 为快照）: 同上，冲突则进入兜底
兜底（持锁完整流程）: 取锁 → 锁内 读→LLM→写（= 今天的行为）
```

- **LLM 轮数算术**（评审 P2-2 修正，原「外层不放大」不准确）：尝试数上限 3（乐观 1 + 乐观 2 + 兜底；兜底**替换**而非追加最后一次乐观尝试；现状 1 次）。按 LLM 调用计（每尝试含 forced+auto 内层两次）现状最坏 2、新方案最坏 6；外层放大后理论最坏 `archiveMessagesAsync` 3 次重试 6→18、`maybeConsolidate` 5 轮 10→30——**仅持续冲突+失败叠加才触达**（无冲突 = 1 次尝试 = 与现状逐字节同量），且 `distillSync` 墙钟预算始终封顶。3 次尝试上限即评审建议的显式总上限（含兜底）。
- **读失败 ≠ 空文件**（评审 P2-4）：`readLongTermMemory()` IO 异常时返回 `""`（MemoryStore.java:81-84）——乐观路径会把「读失败」误分类：M0 读失败被当空基线喂 LLM、M1 读失败与 M0 相等被当「快照新鲜」→ **写掉旧记忆**。该最坏结果在今天的锁内单读路径同样存在（顺手修复的既有隐患）。处理：MemoryStore 提供可区分失败的严格读（抛 IOException 或返回 null 哨兵），M0/M1 任一读失败即**放弃本次整合**（返回 false、不写盘），不进入冲突分类。
- **冲突分支严格隔离**：快照过期≠LLM 失败，独立的 conflict 分支，不递增 `consecutiveFailures`、不触 `handleConsolidationFailure`/rawArchive（混流会在累计 3 次后错误触发原始归档，叠加既有双重归档缺陷造成重复 HISTORY 条目）。每次尝试间保留 abort/timeout 检查点（`distillSync` 预算封闭性不变——重试在同一 supplyAsync 任务内）。
- **锁 IO 故障降级保持终局**：`lockLongTermMemory` 抛 IOException → 直接无锁完整流程，不与重试交错。
- **`consecutiveFailures` 并发注记**（评审 P3-6）：现为非原子实例字段，且同一 consolidator 被关闭提炼（commonPool 载体）与内联整合（run 线程）共享——既有竞态，本变更扩大失败路径并发窗口。触碰该类时顺手改 `AtomicInteger`。
- **语义变化点如实记录**：失败处理（handleConsolidationFailure → rawArchive → appendHistory）随 LLM 移出锁外——今天它持锁，但跨进程锁本就只保护 MEMORY.md 读改写（archiveSync 的 rawArchive 今天就无锁），HISTORY 追加无新敞口；spec 明示「HISTORY 追加不保证跨进程串行」。
- **收益锚点**（判决纠正后的诚实版）：锁持有分钟级→毫秒级，解除的是**对端实例在跑回合内联整合（maybeConsolidate 跑在 agent run 线程）被关闭提炼卡住分钟级**的互斥窗口；关机双实例墙钟本就≈各自 300s 预算（乐观方案在冲突时还多烧一轮 LLM）——不以墙钟为主张。
- 兜底路径会短暂重现分钟级持锁（恰是本设计要消除的场景）——高声 log.warn 记录冲突计数，仅多写者同刻冲突才触达，可接受且可观测。
- `writeLongTermMemory` 的 temp+ATOMIC_MOVE 原子性是无锁读方（系统提示注入等）的安全前提，不动。
- **测试锚**：冲突路径专属回归——mock `readLongTermMemory` 链式先返 M0 再返 M1，断言重试触发、最终写盘基于 M1、全程不重复 appendHistory（现有测试 mock 恒返同值，永远探测不到该路径）；另覆盖「读失败」输入（严格读抛错 → 放弃整合、不写盘、不计冲突）。

### D5（R6）· handler 池换 cached daemon 池（方案 B，非异步交接）

**决定**：`server.setExecutor` 从 `newFixedThreadPool(4)` 换 `newCachedThreadPool`（**必须传 daemon 线程工厂**——默认非 daemon 会挂住 JVM 退出，沿用 :176-180 的工厂模式）。一行改动，全部 handler 逻辑（TOCTOU 200 分支含 CompletionException→500 旁路、504、409、恰好一次响应）零改动。

**备选与否决**（方案 A：handler 把等待+响应交接到专用执行器后立即返回）：JDK 17.0.8 实现层逐行核实**可行**（Exchange.run 在 handler 返回后不关 exchange；连接等 WriteFinishedEvent 才重注册；默认无 maxRspTime 定时器；写路径 synchronized + happens-before），**但这是 sun 实现行为而非文档契约**——公开 API 对迟发响应零表述，且需守 3 个前提（禁设 `sun.net.httpserver.maxRspTime`、交接点必须在请求体 EOF 之后、handler 线程交接后绝不再发响应含拒绝兜底）；diff 面更大，且 `/tool` smoke ~200s 同池饥饿**不受 A 解决**。B 完全落在文档化用法内并同解两者。

**语义变化记录**（评审 P2-3 修正两处表述）：

- `/agent` 侧：隐性「4 并发 IPC 回合上限」解除，但实际闸门的准确描述是 **AgentLoop 的单线程 executor**（AgentLoop.java:196 `newSingleThreadExecutor`——**跨会话也全序串行**）+ 同会话第二回合的 REJECTED_BUSY 拒绝。「按会话 busy 拒绝」单独表述会误导维护者，文档统一写「单线程全序 + 同会话 busy 拒绝」。
- `/tool` 侧（如实记录的**行为变化**）：`/tool` 无会话、无 AgentLoop、无 busy 门——固定 4 池是其**唯一并发上限**，本变更解除后任意多个并发 mutating CRUD 可同时进入工具层。树变更本身仍在 EDT 全序（EdtRunner 单点收口），但 **EDT 之前的 `resolveNodeMap`/类型校验/schema 校验阶段变为无限并发**，TOCTOU 窗口随并发数放大（今天 ≤4 已存在该窗口，只是上限变化）。**决策：接受并记录**（依据：客户端皆本插件自身 CLI/委派、loopback+token；EDT 兜底树一致性；不加投机信号量——如后续观测到脏读问题再评估，届时一行 `Semaphore` 即可加回）。

**测试锚**（现有测试零覆盖）：N 个并发 `/agent` 挂起时 `/health` 即时应答、`/tool` 不排队。`IpcServerAgentTimeoutRaceTest` 经反射直调 `handleAgent`，断言不受影响。

### D6（R7）· deadlineAtMs 四层透传 + 启动前预算预检

**管道**（比「+1 参数」深，已核实为 4 层）：`processMessage` 新增带可空 `Long deadlineAtMs` 的重载 → `doProcessMessage`（+1 参）→ `startTurn` 7/8 参两重载 → executor lambda 捕获 → `AgentRunSpec` builder 字段 → `AgentRunner.java:129` 构造 `AgentRunContext`（新增可空字段与 getter）。IpcServer.handleAgent 按 `t0 + timeout` 注入。本地面板入口（AiChatPanel:1439/:1441）与 goal 续跑/孤儿 re-publish（直接调 startTurn）天然为 null——正确（宿主本地回合无外部预算）。

- **构造器兼容**：保留既有 `(String,String)` 与 `(String,String,boolean)` 构造器委托（新 4 参重载，旧构造器 deadline 传 null）——src/test 有 3 处构造点（SubagentIsolationTest×2、GoalToolsTest×1）不破坏。
- **消费（RunTestTool）**：
  - **预检拒绝**：在 `doStart` 预检区（:184-192 的 hasThreadGroups/already-running 门附近、:228 引擎派发**之前**——:409 时引擎已启动，拒之已晚）读 `AgentRunContext.current()`（双层空守卫：context null 或 deadline null 均不钳制；直构测试依赖此行为，null 守卫先例 = countSmokeAttempt :547-549）。剩余 < **60s**（地板从 10s 上调：EDT 启动路径法定可耗 ~20s = latch 10s + 确认轮询 10s，加最小轮询窗与收尾 LLM 往返）→ 不启动引擎，返回结构化错误（镜像 :387-391 措辞：明示未启动、不消耗冒烟预算、建议收尾回合）。
  - **等待钳制**：轮询截止**时刻** = `min(now + 180s, deadlineAt)`，在 `finishSmokeRun` 入口重算（一次算死会被 EDT 启动耗时吃掉精度）。**超时文案修正**（评审 P2-1）：现状格式化的是常量 180000（:427-430）——钳制后必须改报**实际等待时长**，且「回合预算耗尽」用独立措辞（如 `turn budget exhausted before smoke completion; no verdict; smoke-PASS evidence cleared`），不得伪装成「冒烟慢了 180s」——否则模型误判等待已充分、且证据被清的原因不可见。
  - **时钟注记**（评审 P3-4）：deadline 用 `System.currentTimeMillis()` 与服务器 `t0` 同钟域（一致性优先于单调性——系统调时两侧同偏，不引入混合时钟的新偏差）；60s 地板是覆盖 EDT 启动路径上界的**保守启发式而非保证**，注释写明。
- **伤害论证修正**（判决纠正）：服务器超时取消传 `CancelCause.TIMEOUT`，goal 收敛门只认 USER_STOP 且非委派——**goal 保持 ACTIVE**（刻意设计，供下次会话续跑）。硬杀的真实伤害 = 冒烟半途而废（引擎照跑后台、判定丢失）+ 钳制提前退出会 `recordSmokeEvidence(false)` 清既有 PASS 证据且预算已计（与今天 180s 超时同语义，钳制只提高触发频率——风险节点名）。拒绝启动消除前者。
- **边界**：deadline 同覆盖 IPC_CLI 直连回合（CLI 触发的冒烟同样被钳制，预期收益）；normal 模式不钳制（启动即返回，监控走 get_test_status）；冒烟预算记账不受影响（预检在 countSmokeAttempt 之前）。
- **已接受残余**：冒烟判定返回后模型仍需一次 LLM 往返才能给委派方终答——deadline 不为收尾往返预留尾预算，剩余 20-30s 时仍可能 504。接受并文档化（预留尾预算会让钳制语义复杂化，收益边际）。

## Risks / Trade-offs

- [R2 合法慢 EDT 操作（巨计划克隆/大 jmx 打开）撞 120s 误报「结局未知」] → 默认取保守大值压低概率；操作本体仍会完成，LLM 按契约复查自愈；配置键逃生。
- [R2 孤儿任务迟到执行与下一回合树编辑交错] → 与今天 Stop 孤儿同构非新增；EDT FIFO 保证孤儿先于后续排队任务；契约强制复查。
- [R3 中断对 HttpURLConnection 类工具无效] → best-effort 为文档化标准，行为不劣于今天；不改造通道。
- [R3 迟到中断跨任务命中] → 消费式 CAS 闭合主窗口（见 D2）；「超时与完成同时发生」竞态入测试锚。
- [R4 v0.3.0/v0.3.1 旧读者跳过新端口文件] → 优雅降级（实例不可见、无数据损坏），既已接受的注解边界；文档记录版本门槛。
- [R5 兜底路径短暂重现分钟级持锁] → 高声 log.warn + 冲突计数；仅多写者同刻冲突触达。
- [R5 持续冲突下 LLM 轮数理论上界放大（单次 2→6、外层最坏 6→18/10→30）] → 尝试级显式上限 3（含兜底）；无冲突路径与现状同量；`distillSync` 墙钟预算封顶；conflict 分支不与失败计数混流。
- [R5 `consecutiveFailures` 既有非原子竞态（关闭 commonPool × 内联 run 线程共享实例）] → 触碰该类时顺手 AtomicInteger。
- [R6 无界线程池] → 仅 loopback + token 鉴权，客户端皆本插件自身；daemon 工厂保 JVM 退出。
- [R6 `/tool` EDT 前阶段并发上限解除（唯一闸门消失）] → 接受并记录（D5 决策：EDT 兜底树一致性、自有客户端；后续观测到脏读再加信号量）。
- [R5 HISTORY 追加移出锁外（今天失败路径持锁）] → 跨进程锁本就只保 MEMORY.md；spec 明示「HISTORY 追加不保证跨进程串行」。
- [R7 收尾 LLM 往返无尾预算，残余 504 窗口] → 接受并文档化；拒绝启动已消除主伤害（冒烟半途而废）。
- [R7 钳制提高「证据清零 + 预算已计」触发频率] → 与今天超时同语义；文案引导模型在预算内收尾。

## Migration Plan

实施顺序（每步独立可回滚，步骤末各自 `mvn clean test` 单独跑、不与并行工作流同跑）：

1. **R2**（EdtRunner + AiConfig getter + BatchUpdate 修复 + javadoc/文档 3 处）——后续 R3 的中断映射依赖它先落地。
2. **R3**（ToolRegistry workerRef 中断 + 过时注释修正 + 中断释放测试）。
3. **R6**（setExecutor 一行 + 并发不饿死回归测试）。
4. **R4**（InstanceRegistry 字段 + IpcServer 发布 + CLI agent 端点派生与回显 + delegate 一行 + 测试调用点 9 处更新）。
5. **R7**（四层管道 + RunTestTool 预检/钳制 + 构造器兼容）。
6. **R5**（consolidateWithAi 乐观重构 + conflict 测试 + AGENTS.md 过时表述修正）。

配置迁移：唯一新键 `agent.tools.edt.wait.timeout.ms` 默认 120000，登记于 jmeter-ai-sample.properties / documents/configuration.md / AGENTS.md 三处（核默认值以 AiConfig 代码为唯一权威）。无数据迁移、无协议破坏。回滚 = 各风险项改动相互独立，按步 revert 即可。

## Open Questions

无（R7 收尾尾预算、R2 入口探活门均已按「接受残余 / 二期观察」定档，不阻塞实施）。
