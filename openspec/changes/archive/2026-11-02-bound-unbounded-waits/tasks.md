# Tasks

> 纪律：全程不执行 git add/commit/push（改动就绪只报告）；全量 `mvn clean test` 单独跑、不与任何并行工作流同跑；测试结果只认输出文件里的 `Tests run`/`BUILD` 行。

## 1. R2 · EdtRunner 有界等待

- [x] 1.1 `AiConfig` 新增 `getEdtWaitTimeoutMs()`（键 `agent.tools.edt.wait.timeout.ms`，默认 120000，`Math.max(1000L, ...)` 下限钳，javadoc 写明单次等待口径与无 0=关闭语义）→ 验证：`AiConfigTest` 补缺省/钳制/非法值回退断言（反射 init JMeterUtils.appProperties 后设边界值）全绿
- [x] 1.2 重写 `EdtRunner.run`：`EventQueue.invokeLater + CountDownLatch + await(timeoutMs)`；`isDispatchThread` 守卫；动作体 `catch (Throwable)`（Exception 原样、Error 包 InvocationTargetException + log.error）；`finally` 恢复 TCCL + countDown；`InterruptedException` 单独 catch → `IllegalStateException(中止/结局未知文案, ie)` 不恢复中断位；新增 `EdtWaitTimeoutException extends TimeoutException`（三分真话契约文案：排队/执行中/已完成、禁盲重试、get_test_plan_tree 复查指引）；package-private `run(gui, action, timeoutMs)` 测试缝 → 验证：编译通过 + 既有 14 调用点零改动（git diff 仅 EdtRunner/AiConfig）
- [x] 1.3 新建 `EdtRunnerBoundedWaitTest`（headless 兼容：@BeforeAll invokeAndWait 空操作强制 EDT 启动；经测试缝注入 200ms 级超时）：快乐路径 null + TCCL 已切换、Exception 原样回传、Error→InvocationTargetException、超时返回 EdtWaitTimeoutException（含 UNKNOWN 与 get_test_plan_tree）且释放 EDT 后孤儿 action 确实执行、中断路径返回含 UNKNOWN 的 ISE（cause=IEx、中断位为清、孤儿照常执行）、EDT-on-EDT 守卫、契约拼接断言（"Failed to update element: " + 消息含 UNKNOWN/get_test_plan_tree）→ 验证：`mvn clean test -Dtest=EdtRunnerBoundedWaitTest` 全绿
- [x] 1.4 修复 `BatchUpdateJMeterElementTool.java:234-237` 吞错（改为与 BatchDelete:183-185 同款 `ToolResult.error` 早退）并补 batch_update 超时路径回归用例 → 验证：新用例断言超时错误透传给模型而非 "Updated 0 of N"
- [x] 1.5 javadoc 同步（`IpcServer.java:53-54` 与 `SnapshotTreeCapture` 的 invokeAndWait 措辞）+ 配置三处登记（jmeter-ai-sample.properties / documents/configuration.md / AGENTS.md，值 120000 与 AiConfig 逐字一致；顺带补录 AGENTS.md 缺失的姊妹键 agent.tools.timeout.ms 消除不对称）→ 验证：三文件 grep `agent.tools.edt.wait.timeout.ms` 均命中且值一致
- [x] 1.6 回归：`mvn clean test -Dtest='EdtRunnerBoundedWaitTest,TurnSnapshotTerminalStatesTest,SnapshotTreeCloneTest'` 全绿（snapshot 套件验证超时/中断下 best-effort 语义不变）

## 2. R3 · 并行批超时真中断（工作线程引用）

- [x] 2.1 `ToolRegistry.executeAsyncWithEvent`：`AtomicReference<Thread> workerRef` + **消费式 CAS**（supplier 首语句 `set(currentThread)`；超时分支 `t=workerRef.get(); if (t!=null && workerRef.compareAndSet(t,null)) t.interrupt()`；worker finally `compareAndSet(self,null)`——闭合跨任务迟到中断窗口，见 design D2）→ 验证：编译通过 + `ToolConcurrencyBatchingTest` 零改动全绿
- [x] 2.2 新增中断释放测试：safe 测试工具阻塞在 `CountDownLatch.await` → 批超时 → 断言工作线程真实被释放（latch 到点）且结果为超时错误、同批其他工具不受影响；另加「超时与完成同时发生」竞态用例（完成路径清引用后超时分支不得 interrupt）→ 验证：假实现（无 interrupt）下必红、真实现下全绿
- [x] 2.3 修正 `ToolRegistry.java:287-289` 过时 spawn 注释（并行道真实上下文消费者为 subagent_status 等）并在 javadoc 写明 best-effort 标准（HttpURLConnection 阻塞读不响应中断、行为同今天）→ 验证：注释审阅 + 全量编译

## 3. R6 · IPC handler 池扩容

- [x] 3.1 `IpcServer.java:176` `newFixedThreadPool(4)` → `newCachedThreadPool`（**保留 daemon 线程工厂**），注释记录：饥饿是「固定小池×长阻塞 handler」结构性质（/agent 300s 与 /tool smoke ~200s 同罪）、隐性 4 并发上限解除、实际闸门=AgentLoop 按会话 busy 拒绝 → 验证：`IpcServerAgentTimeoutRaceTest`/`IpcServerAgentEnvelopeWireTest` 全绿（反射直调不受影响）
- [x] 3.2 新增并发不饿死回归：≥5 个并发 `/agent` 挂起（真实 start + HTTP POST）期间 `/health` 即时应答、`/tool` 不排队 → 验证：新测试全绿（现有测试零覆盖该场景）

## 4. R4 · 端口文件发布 agentTimeoutMs

- [x] 4.1 `InstanceInfo` 增 `agentTimeoutMs`（long 缺省 0）+ `writeInstance` 签名 +1 参；**定格快照**：IpcServer.start() 把 `AiConfig.getIpcAgentTimeoutMs()` 读入 volatile 字段，writeInstance(:192) 与 handleAgent(:293) 都读该字段（构造性保证发布值==执行上界；防 run_test properties 运行时注入造成分歧）；测试的运行时改属性（IpcServerAgentTimeoutRaceTest 等）改为 start() 前设置；更新 9 处测试调用点（SessionReaperTest×2、InstanceRegistrySerializationTest×4、ReadInstanceSessionToolTest、DelegateToInstanceToolTest×2）→ 验证：`mvn clean test -Dtest='InstanceRegistrySerializationTest,SessionReaperTest,ReadInstanceSessionToolTest,DelegateToInstanceToolTest,IpcServerAgentTimeoutRaceTest,IpcServerAgentEnvelopeWireTest'` 全绿
- [x] 4.2 序列化回归：新字段写入/读出 + `updateJmxPath` 读改写后 agentTimeoutMs 不丢 + 未知字段容忍既有用例仍绿 → 验证：断言用例全绿
- [x] 4.3 CLI 派生（仅 agent 子命令的 /agent 请求）：**单次解析**——同一次 `resolveInstance` 的 InstanceInfo 同时供派生与发送（显式传入 postIpc，防其内部 :731 二次解析的双实例切换错配）；effective = 显式 --timeout > (published>0 ? published+10_000 : DEFAULT)；生效值暂存 `Parsed` 供 `friendly()` 回显（修复 :135 catch 报错值与实际不符）；`run --wait` 与 /tool 命令维持 DEFAULT、/health 15s 不动 → 验证：新增 CLI 单测覆盖派生/回退/显式优先/回显四形态
- [x] 4.4 `DelegateToInstanceTool` 超时改 `target.getAgentTimeoutMs()>0 ? +10_000 : 既有值+10_000`（余量覆盖服务器 cancelActiveTask 的 5s 取消收尾等待，504 才能先于委派方超时到达）→ 验证：DelegateToInstanceToolTest 补两形态断言（发布值优先/缺席回退，均 +10s）
- [x] 4.5 文档：documents/configuration.md 与 skills/jmeter-cli/references/cli-reference.md 注明 CLI 默认等待=目标发布值+10s（缺席回退 310s）及 ≥v0.3.2 兼容门槛 → 验证：文档 grep 与代码默认一致

## 5. R7 · 回合截止期下探

- [x] 5.1 管道：`AgentRunSpec` + `deadlineAtMs` 可空字段（builder）；`AgentRunContext` + 4 参构造重载（旧 2/3 参构造器委托 null，保 3 处测试构造点）；`AgentLoop.processMessage` 新重载 → `doProcessMessage` → `startTurn` 7/8 参 → lambda → builder；IpcServer.handleAgent 按 `t0+timeout` 注入 → 验证：编译 + `mvn clean test -Dtest='SubagentIsolationTest,GoalToolsTest'` 全绿（构造器兼容）
- [x] 5.2 `RunTestTool` 预检拒绝：`doStart` 预检区（:184-192 附近、:228 引擎派发前）双层空守卫读 deadline，剩余 <60s 返回结构化错误（未启动、不消耗冒烟预算、建议收尾——镜像 :387-391 措辞）→ 验证：直构测试（无上下文）行为不变 + 带 deadline 剩余 30s 的用例断言拒绝且引擎未启动、冒烟预算未计
- [x] 5.3 `RunTestTool` 等待钳制：`finishSmokeRun` 入口轮询截止 = `min(now+180s, deadlineAt)`（重算）；超时结果改报**实际等待时长**（替换 :427-430 的常量 180000 格式化）并区分「回合预算耗尽（无判定、证据已清）」与「冒烟完成超时」两种措辞 → 验证：带 deadline 剩余 100s 用例断言等待钳至 ~100s 且文案报 ~100s/预算耗尽措辞；无 deadline 用例文案仍报 180s 完成超时
- [x] 5.4 边界回归：本地回合/goal 续跑（deadline null）行为逐字节不变；IPC_CLI 直连回合同样被钳制（预期收益，用例固化）→ 验证：既有 RunTestTool 套件全绿 + 新边界用例
- [x] 5.5 design.md 风险节已点名的两项在代码注释中落位：钳制提前退出会 recordSmokeEvidence(false) 清 PASS 证据且预算已计（与今天同语义）；goal 在 TIMEOUT 取消下保持 ACTIVE（收敛门只认 USER_STOP）→ 验证：注释自包含（无 openspec 编号）、审阅通过

## 6. R5 · 记忆整合乐观并发

- [x] 6.1 重构 `MemoryConsolidator.consolidateWithAi`：乐观两尝试（锁外 读→LLM→取锁→重读验证→新鲜则锁内两门写，幂等比较对象=重读值）+ 兜底持锁完整流程**替换**最后一次乐观尝试（尝试级显式上限 3）；conflict 分支独立（不递增 consecutiveFailures、不触 rawArchive）；每尝试间 abort 检查；锁 IO 故障降级保持终局；**严格读**——MemoryStore 提供可区分失败的读变体，M0/M1 任一读失败即放弃整合（不把 `""` 当快照基线，修复既有隐患）→ 验证：`mvn clean test -Dtest='MemoryConsolidatorTruncatedOutputTest,MemoryStoreWriteLockTest'` 全绿（既有断言 times(3)/never write/times(2) raw archive 原样存活）
- [x] 6.2 新增冲突路径回归：mock `readLongTermMemory` 链式先 M0 后 M1 → 断言重试触发、最终 writeLongTermMemory 基于 M1、全程不重复 appendHistory、consecutiveFailures 不变；另覆盖**读失败**输入（严格读抛错 → 放弃整合、不写盘、不计冲突不计数）→ 验证：新用例全绿（现有 mock 恒返同值探测不到该路径）
- [x] 6.3 兜底路径高声 log.warn（含冲突计数）+ 注释记录语义变化点（失败处理/rawArchive 随 LLM 移出锁外；HISTORY 追加跨进程本就无锁）→ 验证：审阅 + 日志断言
- [x] 6.4 修正 AGENTS.md 过时表述（「MemoryConsolidator 与 save_memory 工具共用」→「save_memory 为整合器内建强制工具调用（非注册工具）」）→ 验证：AGENTS.md grep 不再有「save_memory 工具共用」
- [x] 6.5 `consecutiveFailures` 改 `AtomicInteger`（既有竞态：同一 consolidator 被关闭提炼 commonPool 载体与内联 run 线程共享，R5 扩大失败路径并发窗口）→ 验证：编译 + 既有 MemoryConsolidator 测试全绿

## 7. 集成验证

- [x] 7.1 全量回归：`mvn clean test` 单独跑（不与并行工作流同跑），只认输出文件 `Tests run`/`BUILD SUCCESS` 行；偶发失败先单独复跑分流 → 验证：全绿
- [x] 7.2 手动 GUI 验证（`mvn clean install` 后 lib/ext 只留最新 jar）→ 2026-11-02 实机全部通过：① R2 EDT 停滞（jmeter.properties 临时 5s）→ 契约文案准点、WARN 留痕、孤儿迟到执行、模型自诊停试（2 个重复 stall-test 为有界残留，Stop 结构上不可点由 IPC 超时路径覆盖）；② R2 正常路径冒烟全绿；③ R7 委派预算拒绝实机复现（HISTORY.md 13:44 块：21.1s 剩余拒绝、未启动引擎、未计冒烟预算、validate 收尾）；④ R4 两腿：600000 发布 + CLI 等 365.7s（>旧常数 310s）收 success / 15000 发布 + 结构化 504（cancelReason:timeout）先于 25s 客户端上界到达，日志链 15.0s 精确撞线；⑤ R5 双实例并发关闭：两 distill 3s 内并发启动（锁外 LLM）、胜者 116,080 字符落盘、败者 abort 感知不覆盖 + RAW 兜底归档 + 会话 jsonl 留存、双标记（ALPHA-5/BRAVO-7）俱全；⑥ R6 饿死场景用户选择跳过（IpcServerRouteIsolationConcurrencyTest 双场景单测覆盖）。临时配置已全部清除，观察项留档：CLI 控制台乱码（已修：bat chcp 65001 + exec 解码启发式，独立于本变更）、exec 输出 GBK（同前修复）、jmxPath 端口文件尾随空格
- [x] 7.3 文档终检：三处配置值与 AiConfig 逐字一致（git -S 查不到纯新增行，逐文件人工核对）；cli-reference 与 configuration.md 的派生说明与代码一致 → 验证：核对清单留档
