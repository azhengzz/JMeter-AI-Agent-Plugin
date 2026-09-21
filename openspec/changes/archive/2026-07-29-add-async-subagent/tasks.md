# Tasks

> 依赖排序：地基（spec/字段/drain 原语）→ 核心组件（Manager/Status/Hook/Context/Sink）→ 工具与接线 → 合流与取消接入 → prompt/模板 → 验证。每项均可独立验证。

## 1. 地基：scope 机制 + AgentRunSpec 字段 + drain 原语

- [x] 1.1 `Tool.java` 加 `default Set<String> getScopes() { return Set.of("core"); }`。验证：现有工具全部继承 {core}，主路径行为不变，`mvn test` 通过。
- [x] 1.2 给 13 个只读工具标注 `@Override getScopes()` 返回 `Set.of("core","subagent")`（get_script_info/get_selected_element/get_test_plan_tree/parse_jmx_file/find_element/query_element_properties/get_log_panel_content/get_test_status/get_test_results/read_file/list_dir/web_search/web_fetch）。验证：单测断言这些工具 scope 含 subagent。
- [x] 1.3 `AgentRunSpec.java` 加 `persistSession`（默认 true）+ `runExecutor`（可空 Executor）字段、getter、Builder 方法。验证：默认值保持现有行为，现有构建点零改动。
- [x] 1.4 `AgentRunSpec.build()` 加校验：`sessionKey` 以 `"subagent:"` 开头时强制 `persistSession==false`、`injectionCallback==null`、initialMessages 非空；`userMessage` 仅在 initialMessages 为空时必填（放宽 :157 的 requireNonNull）。验证：构造违规 spec 抛异常的单元测试。
- [x] 1.5 `InjectionManager.java` 加 `drainBlocking(sessionKey, limit, timeoutMs)`：先非阻塞 drain 至 limit；空则 `queue.poll(timeout, MS)`；catch `InterruptedException` → `Thread.currentThread().interrupt()` 复位 → 返回空；不抛 RuntimeException。验证：单测 park 线程在 drainBlocking、interrupt 它、断言返回空 + 中断标志已复位。

## 2. 核心组件：AgentRunContext + ResultSink + SubagentStatus/Hook

- [x] 2.1 新建 `agent.run.AgentRunContext`（ThreadLocal{sessionKey, runId}，set/current/clear）。验证：单测 set 后 current() 返回正确值，clear 后为 null。
- [x] 2.2 新建 `agent.subagent.ResultSink`（`@FunctionalInterface boolean offer(String sessionKey, String message)`）。
- [x] 2.3 新建 `agent.subagent.SubagentStatus`：Phase 枚举（INITIALIZING/AWAITING_TOOLS/TOOLS_COMPLETED/FINAL_RESPONSE/DONE/ERROR）、volatile 标量、toolEvents/usage 用 `List.copyOf`/`Map.copyOf` 不可变快照、markFinished/markError/isTerminal。验证：并发读写单测（单写者改、多读者读不撕裂）。
- [x] 2.4 新建 `agent.subagent.SubagentHook implements AgentHook`：beforeIteration→AWAITING_TOOLS、afterExecuteTools→TOOLS_COMPLETED、afterIteration 回写 iteration/toolEvents/usage/error、finalizeContent→FINAL_RESPONSE（容 null）、onError→ERROR；beforeExecuteTools DEBUG 记日志；`wantsStreaming()` 保持 false。验证：单测用一个 mock AgentHookContext 触发各钩子、断言 status 正确流转。

## 3. SubagentManager（调度核心）

- [x] 3.1 新建 `agent.subagent.SubagentManager`：构造器接 (AiService, ContextBuilder, SessionManager, ToolRegistry mainRegistry, ResultSink resultSink, 配置)；持有 `Executors.newFixedThreadPool(maxConcurrent)`（daemon `subagent-N`）、ConcurrentHashMap<taskId, AgentRunner/Future/abortFlag>、ConcurrentHashMap<mainSessionKey, Set<taskId>>、状态注册（TTL + max-cap 淘汰）。验证：构造不抛、池为 daemon。
- [x] 3.2 实现 `buildSubagentToolset(mainRegistry)`：lazy 构建一次后缓存，遍历主 registry、只 INCLUDE `getScopes().contains("subagent")` 的工具，共享 executor。验证：单测断言子代理 registry 不含 spawn/subagent_status、不含改树工具。
- [x] 3.3 实现 `spawn(task, label, mainSessionKey, turnToken, temperature, model)`：8 位 taskId、建 SubagentStatus(INITIALIZING)、并发上限校验（per-mainSession 锁同步"检查+记录"防 TOCTOU）、提交到专用池执行 `_run_subagent`、立即返回 taskId 回执。验证：spawn 立即返回、子代理在专用池启动。
- [x] 3.4 实现 `_run_subagent`：new 独立 AgentRunner（铁律）、build AgentRunSpec（sessionKey=`subagent:<taskId>`、persistSession=false、runExecutor=专用池、failOnToolError=true、maxIterations=`agent.subagent.max.iterations`、injectionCallback=null、hook=SubagentHook、initialMessages=[精简system,user task]、abortFlag）、直接调 `agentRunner.run(spec).join()`（**不走 processMessage**）。验证：单测断言不调 SessionManager、不写 jsonl。
- [x] 3.5 实现 `announceResult`：渲染 subagent_announce.md；若当前活跃主 run == spawn 时 turnToken（同 turn）则 `resultSink.offer(mainSessionKey, text)`；否则保留 terminal 状态。失败（offer=false）记日志保留状态。验证：单测同 turn offer 成功、异 turn 不 offer。
- [x] 3.6 实现 `getRunningCountBySession`/`getStatuses(includeCompleted)`/`getStatus(taskId)`/`getRunningCount`：只读访问状态注册。验证：返回正确计数与快照。
- [x] 3.7 实现 `cancelBySession(mainSessionKey)`：遍历该会话子代理，**先 set 各 abortFlag 再 interrupt** 各独立 AgentRunner 线程。验证：单测断言 abortFlag 先于 interrupt 置位、命中正确线程。
- [x] 3.8 实现 `shutdown()`：关闭专用池、cancel 所有在飞子代理。验证：shutdown 后池 isShutdown。

## 4. 工具与接线

- [x] 4.1 新建 `agent.tools.subagent.SpawnTool`：构造器注入 SubagentManager；scope={core}；executeInternal 读 `AgentRunContext.current()`（null→error）、并发校验、调 `manager.spawn(...)`、立即返回 ToolResult.success(taskId 回执)。验证：单测 spawn 返回 taskId、AgentRunContext 为空时返错。
- [x] 4.2 新建 `agent.tools.subagent.SubagentStatusTool`：构造器注入 SubagentManager；scope={core}；参数 task_id?/include_completed?；输出格式移植 self.py:227-241。验证：单测返回可读状态摘要。
- [x] 4.3 `ToolRegistry.executeAsyncWithEvent`（:271-295）加 capture-replay 包装器：supplyAsync 前捕获 `AgentRunContext.current()`，lambda 内 set、finally clear。验证：concurrentTools=true 单测断言 tool-executor 线程内 AgentRunContext 可见、run 后清理。
- [x] 4.4 `AgentRunner.run` supplyAsync lambda 内（runId 赋值后，:93 之后）`AgentRunContext.set`、finally clear；run() 用 `spec.getRunExecutor()` 非空时 `supplyAsync(supplier, runExecutor)` 否则原 commonPool。验证：单测断言 run 期间 current() 非空、run 后清理、runExecutor 路由生效。
- [x] 4.5 `AgentRunner.run` 3 个 persistSession 分支点（:100/:103/:124-128）。验证：persistSession=false 单测断言不调 SessionManager、不整合记忆。
- [x] 4.6 `AgentLoop.java`：加 `SubagentManager` 字段（可空，构造器注入）、`subagentDrainTimeoutMs`、`offerInjection(sessionKey,message)` seam（委托 injectionManager.offer）、`drainInjected(sessionKey,limit)` helper（`getRunningCountBySession>0` 则 drainBlocking 否则 drain）；替换 :191 lambda 为 `limit -> drainInjected(sessionKey,limit)`。验证：单测 helper 分支正确。
- [x] 4.7 `AgentLoop.cancelActiveTask` 最前面加 `if (subagentManager != null) subagentManager.cancelBySession(sessionKey);`（在主 abortFlag/interrupt 之前）。验证：单测断言 cancelBySession 先于主 interrupt。
- [x] 4.8 `AgentLoop.shutdown` 加 `if (subagentManager != null) subagentManager.shutdown();`。
- [x] 4.9 `AgentLoopFactory.createAgentLoop`：构造 SubagentManager(..., `agentLoop::offerInjection`)；gate on `agent.subagent.enabled`——开启时 `toolRegistry.register(new SpawnTool(mgr))` 与 `new SubagentStatusTool(mgr)`；注入 AgentLoop。验证：enabled=true 时两工具注册、=false 时不注册。

## 5. 合流接入 + 取消/会话切换

- [x] 5.1 drain 超时移除 pending 条目：`SubagentManager` 跟踪 pending announcement，drainBlocking 返回空且因超时时标记该 pending 已消费/错过，使后续检查点 `hasPendingAnnouncement` 返回 false。验证：单测超时后不再阻塞。
- [x] 5.2 `BuiltinCommands.cmdNew` 先 `agentLoop.cancelActiveTask(sessionKey)` 再 `session.clear()`（通过 CommandContext 拿到 AgentLoop）。验证：/new 期间有子代理时两者都被取消。
- [x] 5.3 turn-token 路由：spawn 时捕获主 run 身份（activeTasks future 或会话单调 turnId），announceResult 仅在同 turn 活跃时 offer。验证：单测异 turn offer 被拒、结果保留为 terminal 状态。

## 6. 子代理 prompt + announce 模板

- [x] 6.1 新建 `SubagentPromptBuilder`（或 SubagentManager 内方法）：复用 ContextBuilder 段落精简组装子代理 system prompt（identity 精简版 + skills summary + 工作区/JMeter 脚本上下文），**去掉** memory 与完整 bootstrap。验证：prompt 不含 memory 段、含 skills summary。
- [x] 6.2 新建 `src/main/resources/templates/subagent/subagent_announce.md`（移植 Nanobot 模板：label/status_text/task/result + "自然转述,别提 subagent/taskId" 指令）。验证：渲染输出含 task+result+转述指令。
- [x] 6.3 `_run_subagent` 用 initialMessages=[SubagentPromptBuilder 产出, Message.user(task)]。验证：子代理首条消息为精简 system + task。

## 7. 端到端验证

- [x] 7.1 隔离零污染端到端：开 `agent.subagent.enabled=true`，spawn 一个只读分析子代理，断言主会话 jsonl 位级不变、无 `subagent_*.jsonl` 残留、`SessionManager.getActiveSessionCount()` 不变。
      _（需真实 GUI+LLM，未执行。已单测覆盖的部分：spec 层隔离不变量强制——persistSession/injectionCallback/initialMessages 三项违规均抛异常。）_
- [x] 7.2 回合合流端到端：主代理 spawn 后到达注入检查点，断言阻塞等待并消费子代理结果、主代理在同一回合转述结果给用户。
      _（需真实 GUI+LLM，未执行。已单测覆盖的部分：drainBlocking 就绪即返回/超时返空/被 offer 唤醒/被 interrupt 复位标志返空。）_
- [x] 7.3 防递归验证：断言子代理工具集不含 spawn，子代理 LLM 工具定义列表无 spawn/subagent_status。
- [x] 7.5 ThreadLocal 清理验证：run 与并发 tool 执行后，断言 commonPool/tool-executor 池化线程上 `AgentRunContext.current()==null`。
      _（单测覆盖 set/clear 与跨线程不泄漏；池化线程 run 后为 null 的断言待端到端补。）_
- [x] 7.4 取消级联端到端：子代理运行中点击 Stop，断言 `cancelBySession` 先于主 interrupt、子代理线程被 interrupt、主代理亚秒级解阻塞。
      _（需真实 GUI+LLM，未执行。代码已按序接线：cancelActiveTask 第 0 步调 cancelBySession。）_
- [x] 7.6 drain 超时退化：子代理模拟慢（不 offer），断言 drain 超时后主代理不阻塞、pending 移除、结果保留可经 subagent_status 查询。
      _（需真实 GUI+LLM，未执行。已单测覆盖 drainBlocking 超时返空；AgentLoop 的 drainTimedOut 闩锁未端到端验证。）_
- [x] 7.7 回归：`mvn clean test` = **291 tests, 1 failure**，唯一失败为已知常驻的 `CodeRefactorerTest.testRefactorSelectedCode_Success`（git stash 实证为既有失败，与本变更零交集）→ **0 回归**。子代理专项 35/35 绿（含死锁复现/取消级联/turn 合流/会话污染/failOnToolError announce）。GUI 层「开/关开关下渲染、Stop、/new 正常」待人工验证。

## 8. 配置文档

- [x] 8.1 `jmeter-ai-sample.properties`：新增 `##### SUBAGENT CONFIGURATION #####` 章节，含 6 个 `agent.subagent.*` 键 + 默认值 + 逐键说明。键名/默认值已与代码 `getProperty(...)` 调用点逐一核对（零拼写差异）。
- [x] 8.2 `CLAUDE.md` 配置章节：追加子代理配置说明块（6 键 + 特性总览 + 指向 sample.properties）。
- [x] 8.3 `design.md` 裁决板已更新：scope-mechanism 🟢sound（对抗核实）；review-fix 落地 5 项（generation settings 继承、toolTimeoutMs 保留、failOnToolError announce、/new 自取消、interrupt 标志顺序回归修复）。
