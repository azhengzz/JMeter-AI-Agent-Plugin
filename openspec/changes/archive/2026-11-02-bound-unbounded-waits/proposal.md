# Proposal

## Why

超时配置审计确认了 6 处结构性风险（编号 R2-R7，R1 经用户明确排除）。它们的第一性共因是两类：

1. **等待无界**——单例批工具经 `EdtRunner` 的 `invokeAndWait` 无限期等 EDT（R2）；并行批 `orTimeout` 只完结 future、工作线程照跑（R3）；记忆整合的跨进程写锁临界区**包含分钟级 LLM 调用**，锁持有时间≈LLM 时间，卡住其他实例在跑回合的内联记忆整合与关闭期提炼（R5）。
2. **等待方与权威预算脱钩**——CLI 的请求超时是**编译期常数**，而权威值 `jmeter.ai.ipc.agent.timeout.ms` 是**服务器侧用户可配置项**，两者无沟通渠道：配置≠常数即复现「客户端提前放弃报失败、服务器空跑至自取消」的窗口（服务器调大至 900s、双实例配置不一致互委派；本周默认值 120s→300s 已需三处联动改常数，每次调默认/调配置都会再错位）（R4；正常部署 CLI 与 GUI 同 jar 同时更新，纯版本错配只是次要触发面）；IPC handler 固定 4 线程池被 `/agent` 长等待逐个占满，`/tool`/`/health` 被连坐饿死（R6）；委派回合内 `run_test` smoke 每次等 180s、3 轮 540s 远超回合 300s 包络，回合对自身截止期零感知，只能被服务器到点硬杀（R7）。

本变更把每条等待链路收敛为**有界**，且让等待方**知道并尊重真正的预算**。

## What Changes

- **R2 · EdtRunner 有界等待**：`invokeAndWait` → `invokeLater + CountDownLatch + 有界 await`（新键 `agent.tools.edt.wait.timeout.ms`，默认 120s，下限钳 1000ms）。超时/中断只放弃调用方等待，**EDT 任务永不撤回**（保留既有 Stop 孤儿语义）；返回携带「结局未知 + `get_test_plan_tree` 复查指引」契约文案的结构化异常。伴随修复 `BatchUpdateJMeterElementTool` 对 EDT 错误的静默吞没（现状以成功形态返回 "Updated 0 of N"）。14 个调用点签名零改动。
- **R3 · 并行批超时取消工作**：`ToolRegistry.executeAsyncWithEvent` 超时分支经工作线程引用（supplier 首语句捕获的 `AtomicReference<Thread>`）显式 `interrupt()` 泄漏的工作线程——`CompletableFuture.cancel(true)` 在 JDK 中明文不中断线程，不可用。该路径**只有并发安全（只读白名单）工具**可达，中断只读操作安全；EDT 排队任务不受影响（不同线程）；不响应中断的工具（如 HttpURLConnection 阻塞读）照常跑完，best-effort 为正确标准。
- **R4 · 端口文件发布权威超时**：`port-{pid}.json` 新增 `agentTimeoutMs` 字段（本实例 `/agent` 服务端超时，IPC 启动时**定格**为快照字段——发布值与执行上界同源，运行时属性注入不致分歧）。**仅 `/agent` 端点**的等待按目标实例发布值 + 余量 10s（覆盖服务器超时分支取消收尾的有界等待）派生：CLI 的 `agent` 子命令与 `delegate_to_instance` 工具；`run --wait` 与经 `/tool` 路由的秒级命令维持既有缺省（其语义与 `/agent` 超时无耦合）。字段缺席（旧版本服务器）回退内置常数；超时报错文案回显实际生效的派生值。兼容边界：容忍未知字段的旧读者为 ≥v0.3.2（更早版本把新端口文件当损坏跳过——优雅降级、既有边界）。显式 `--timeout` 仍最优先。
- **R5 · 记忆整合锁临界区收缩**：LLM 调用移出跨进程锁；锁内只做「重读-验证-写」（两门写序与幂等语义原样保留）。写时发现记忆已被并发修改则乐观重试（与失败计数/rawArchive 严格隔离的独立冲突分支），重试耗尽退化为持锁完整执行（替换而非追加最后一次乐观尝试，锁住 LLM 总轮数上限）。锁持有时间从「≈LLM 时间（分钟级）」降为「≈文件 IO（毫秒级）」——收益锚点是**解除对端实例在跑回合内联整合的分钟级阻塞**（关机双实例墙钟本就≈各自预算、不以此为主张）。
- **R6 · IPC handler 池容量解耦**：`server.setExecutor` 从固定 4 线程换为无界 cached **daemon** 池（文档化用法、一行改动）。并发 `/agent` 长等待（及 `/tool` 上 ~200s 的 run_test smoke——今天的同池饥饿源）不再耗尽有界池饿死 `/health`/`/tool`；隐性「4 并发 IPC 回合上限」解除，实际闸门仍是 AgentLoop 按会话 busy 拒绝。全部 handler 逻辑（TOCTOU 200 分支/504/409）零改动。
- **R7 · 回合截止期下探**：`AgentRunSpec`/`AgentRunContext` 新增可空 `deadlineAtMs`，由 IPC `/agent` 入口按 `t0 + timeout` 注入（经 `processMessage` 重载沿既有 origin/turnId 同路透传；本地面板/goal 续跑回合天然为 null）。带截止期的回合内 `run_test` smoke：轮询截止时刻 = min(默认 180s 死线, 截止期)，且在引擎**启动前**做预算预检——剩余低于下限（60s，覆盖 EDT 启动路径 ~20s + 轮询窗 + 收尾 LLM 往返）时拒绝启动并返回结构化错误（不消耗冒烟预算、引导模型收尾），而非启动后被服务器超时硬杀。服务器超时取消下 goal 保持 ACTIVE（收敛门只认 USER_STOP），硬杀的真实伤害是冒烟半途而废与证据清零，拒绝启动消除之。
- **配置面**：仅新增 1 个键（`agent.tools.edt.wait.timeout.ms`），R3-R7 零新配置。

### Non-goals

- R1（anthropic/openai SDK 请求超时 10min 代码不可配）——用户明确排除。
- `RunTestTool.getTimeoutMs` 死覆盖与 `ToolRegistry.DEFAULT_TIMEOUT_MS` 死常数的清理——相关但独立，另案处理。
- 并行批 30s 默认超时值（`agent.tools.timeout.ms`）不变。
- 不中断/撤回任何 EDT 已排队或执行中的任务；不拆 `setProperties + refreshCurrentGui` 同块原子性（硬约束）。

## Capabilities

### New Capabilities

- `memory-consolidation`: 跨实例记忆整合行为域——MEMORY.md 跨进程写锁的**锁范围契约**（锁只覆盖读-验证-写，不覆盖 LLM 调用）、乐观并发重试、两门写序（先 MEMORY 后 HISTORY）与幂等语义、关闭期提炼的有界预算。

### Modified Capabilities

- `tool-concurrency`: 新增两条需求级行为——单例批工具经 EDT 执行的等待 SHALL 有界且超时/中断不撤回 EDT 任务（R2）；并行批超时 SHALL best-effort 取消仍在执行的工作（仅并发安全工具路径）（R3）。
- `instance-coordination`: `InstanceInfo`/`port-{pid}.json` SHALL 发布 `agentTimeoutMs`，CLI 与委派工具 SHALL 优先按目标发布值派生等待（R4）；`/agent` 长等待 SHALL NOT 占用/耗尽 IPC handler 线程池（R6）。
- `test-plan-verification`: 带回合截止期的 smoke 运行 SHALL 按剩余预算钳制等待、预算不足 SHALL 拒绝启动并返回结构化错误（R7）。

## Impact

- **代码**（按风险项）：`EdtRunner`、`AiConfig`（新 getter）、`BatchUpdateJMeterElementTool`（吞错修复）、`ToolRegistry`（超时取消）、`InstanceRegistry`（新字段）、`IpcServer`（发布字段 + /agent 解耦）、`DelegateToInstanceTool`（按目标值派生）、`JmeterCli`（按端口文件派生）、`MemoryStore`/`MemoryConsolidator`（锁范围重构）、`AgentRunSpec`/`AgentRunContext`/`AgentLoop.processMessage`（截止期透传）、`RunTestTool`（smoke 预算钳制）。
- **兼容性**：端口文件新字段对旧读者无害（JSON 忽略未知字段）；IPC 协议（请求/响应体）零变化；R5 写盘最终内容语义不变（仅并发窗口收缩）。
- **测试**：新增 `EdtRunnerBoundedWaitTest`、ToolRegistry 超时取消、InstanceRegistry 字段、CLI 派生、MemoryConsolidator 乐观并发、RunTestTool 预算钳制等单测；全量 `mvn clean test` 回归（遵既有纪律单独跑）。
- **文档**：`jmeter-ai-sample.properties`、`documents/configuration.md`、`AGENTS.md` 三处登记新键（核默认值以 AiConfig 代码为唯一权威）。
