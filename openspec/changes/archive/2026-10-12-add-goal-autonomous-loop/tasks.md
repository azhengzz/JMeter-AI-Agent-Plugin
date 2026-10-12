# Tasks

## 1. 断言结果采集（run-result-capture 增量）

- [x] 1.1 `AgentResultCollector` 的 `SampleSnapshot` 增断言结果字段（断言名/失败消息/失败标志，复用 `truncate` 截断），`sampleOccurred` 从 `SampleResult.getAssertionResults()` 采集；单测覆盖：断言失败消息进快照、超长截断、无断言 sample 字段为空
- [x] 1.2 `GetTestResultsTool` 详情块对断言失败 sample 输出断言失败段；单测覆盖：详情含断言名+失败消息、无断言失败时输出与现状同构（无空断言块）
- [x] 1.3 跑 `mvn clean test` 确认 run-result-capture 既有测试零回归

## 2. 验证工具层（test-plan-verification）

- [x] 2.1 新建 `ValidateTestPlanTool`（只读、并发安全白名单）：结构完整性（线程组存在/类可解析/必填非空）+ schema 回扫（ComponentValidator 全量重校验）+ 逻辑 lint，输出整体 PASS/FAIL + 三类分组错误清单；单测覆盖三类各至少一正一反例 + EDT 线程契约。注：原 `${var}` 引用分析与 HTTP 采样器目标检查经用户复审移除（定义源不可静态枚举，假阳性卡死判定，冒烟仲裁），回归锚钉住「不查」形态
- [x] 2.2 `RunTestTool` 增 `mode:"smoke"`：`testplan_validation.*` 属性显式注入 + EDT 上保存/设置/恢复全线程组树选择 + 采集器注入 `reset(AGENT)` + `armForStartReinject` + 派发 `VALIDATE_TG` + 启动确认轮询；单测覆盖参数校验与守卫（已有运行拒绝/无线程组拒绝）
- [x] 2.3 手动验证 R1/R2 路径（真实 JMeter GUI）：save-before-run 剥离/重注入在 VALIDATE_TG 下生效、无选中 TG 时全选兜底不静默；结论（含 R1 回退是否需要）记入本变更目录手动验证记录
- [x] 2.4 手动验证冒烟全链路：1 线程 × 1 迭代强制、全部线程组执行、用户计划配置不被修改（跑后线程组原值）、`.jmx` 无收集器泄漏、断言失败消息经 `get_test_results` 可见；结果记入手动验证记录

## 3. goal 状态与命令（goal-autonomous-loop 基座）

- [x] 3.1 `Session` 增 `metadata` 映射（synchronized，对齐既有字段风格），持久化进 jsonl 首行既有 `metadata` 对象，`clear()` 保留 metadata；单测覆盖：写入/读回 round-trip、`/new`（clear）后 metadata 存活、无 metadata 键的旧文件向后兼容
- [x] 3.2 GoalState blob（status/objective/uiSummary/startedAt/endedAt/recap/smokeAttempts/continuationRounds/验证证据）+ 经 SessionManager 的访问器；单测覆盖状态机转移合法性（active→completed/cancelled/blocked、replace 重置计数、objective 4000 上限）
- [x] 3.3 `/goal` 命令注册（`exact` 无参 usage + `prefix` 带参 + busy 守卫）+ `CommandContext` goal 附件 + `AgentRunSpec.goalRequested` 透传（Phase 3 dispatch-null 后读取附件）；单测覆盖：无参 usage、busy 拒绝、正常触发时模型可见 `/goal` 原文且回合带 goalRequested
- [x] 3.4 `create_goal` / `update_goal` 工具：权限门（仅 goalRequested 回合可 create/replace）、活跃时重复 create 报错、complete 证据校验（静态 PASS + 最近冒烟 PASS，或 smokeAttempts≥3 放行）、终结必填 recap（≤8000）；单测覆盖全部拒绝/放行分支（复用 testsupport 脚手架）
- [x] 3.5 `cmdHelp` 与 `CommandIntellisenseProvider` 登记 `/goal`；单测覆盖建议表与 /help 输出含条目

## 4. goal Runtime Context 与护栏

- [x] 4.1 新建 `templates/goal.md` 两态模板（goal_start_requested：durable-objective 纪律；goal_active：状态行 + 分层验证纪律 + 硬标准 + 3 轮上限 + recap 要求），`ContextBuilder` 渲染 goal 块（Runtime Context 标签包裹，复用 `_runtime_context` 剥离），TurnOrigin 门控 {LOCAL_PANEL, IPC_CLI}；单测覆盖：两态渲染、IPC_DELEGATED 不注入、终结后不注入
- [x] 4.2 防收工 nudge：`AgentRunner` 无工具最终回复分支检查 goal active → 注入 announcement 合成消息（含验证证据现状）continue，连续两次无工具调用放行；单测覆盖：顶回一次、两次放行、合成消息不渲染 You 行（复用 RecordingSubscriber 断言）
- [x] 4.3 冒烟 3 轮上限：smoke 派发经回合携带的 goal 访问器递增 smokeAttempts（ToolRegistry AgentRunContext 搬运通道），达上限注入「结构化交回」合成消息，replace 重置计数；validate_test_plan PASS 与冒烟零 error 回写证据；单测覆盖：计数跨回合累计、上限注入、证据回写与 complete 校验联动
- [x] 4.4 跨回合续跑：`AgentLoop` max_iterations 分支判断 goal active 且未达 `agent.goal.max.continuations` → 递增计数 → `processMessage(续跑文本, origin=LOCAL_PANEL)`（内部续跑标记，面板不渲染 You 行），达上限强制结构化交回；`AiConfig` + `jmeter-ai-sample.properties` 登记新配置（默认 5，核对配置默认值同步清单 6 处）；单测覆盖：迭代耗尽自动续跑且无「达到最大迭代」用户可见失败、续跑片预算独立、上限强制交回、续跑不渲染 You 行
- [x] 4.5 Stop 中断后续跑语义验证：goal active 回合被 Stop 后用户消息可继续 goal（不触发自动续跑、goal 不清）；单测覆盖该路径

## 5. 提示词与项目文档

- [x] 5.1 `templates/AGENTS.md`、`templates/TOOLS.md` 增补 goal 工作流与验证纪律（含写端点自主判断语义）；核对 WorkspaceInitializer 同步到 workspace 的既有机制无需改动
- [x] 5.2 项目根 `AGENTS.md`：架构文档增补 goal 能力一节（命令/状态机/护栏/续跑），配置清单加 `agent.goal.max.continuations`；核对与 openspec spec 目录口径一致

## 6. 集成与回归

- [x] 6.1 `mvn clean test` 全量绿（重点关注 agent-turn-events、early-user-message-persist、per-instance-session 既有测试零回归）
- [x] 6.2 手动端到端（真实 JMeter GUI）：`/goal` 全流程走通——需求含糊先问、创建 goal、改脚本、静态校验、冒烟、断言反馈驱动修复、complete 带 recap；另走通上限交回路径（3 轮冒烟失败）与 Stop 后继续路径；结果记入本变更目录手动验证记录
