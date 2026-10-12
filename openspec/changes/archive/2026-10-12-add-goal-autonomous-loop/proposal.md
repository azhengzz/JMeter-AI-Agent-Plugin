# Proposal

## Why

当前 Agent 修改完 JMeter 脚本即停，验证依赖用户人工执行测试并把结果贴回来——开发闭环断在「改」与「验」之间。且行为不稳定：有时 Agent 会自己跑测试修问题，有时改完就停（根因：系统提示零验证指引 + 断言失败消息未被采集，反馈质量不足以支撑迭代）。需要一个目标驱动的自主闭环：Agent 按用户需求改脚本 → 静态校验 → 最小成本冒烟运行 → 读反馈 → 迭代修复，直至硬标准通过或结构化交回，取代人工审核-执行-反馈环节。

## What Changes

- **新增 `/goal` 命令**：`/goal <需求描述>` 必须内联参数（无参回 usage）；busy 时拒绝并提示先 Stop；命令进入普通 Agent 回合（打 goal 标记 + 授予 goal 变更权限），非独立执行器
- **goal 状态机**（完整移植 Nanobot /goal）：active/completed/cancelled/blocked，持久于会话 jsonl metadata；`create_goal`/`update_goal` 元数据工具（update 终结必带 recap）；权限门（仅 /goal 回合可 create_goal）；`/new` 不清 goal、Stop 只停回合不清 goal、终结仅经 update_goal
- **分层验证工具**：新增 `validate_test_plan` 静态校验（结构完整性 + schema 回扫 + 逻辑 lint，不含缺断言警告，也不查不可静态枚举的未定义 `${var}` 引用与 HTTP 采样器目标——交由冒烟仲裁）；`run_test` 新增 `mode:"smoke"`（内部走 JMeter VALIDATE_TG：全线程组树选择 + `testplan_validation.*` 属性强制 1 线程 × 1 迭代 + 采集器手工注入；接受每轮 SAVE 写盘副作用）
- **通过标准（硬标准）**：干跑零错 + 冒烟零 error + 用户内联验收条件叠加；冒烟修复上限 3 轮（以 goal 为单位累计，跨续跑片/插话），超限强制结构化交回
- **断言消息采集**：AgentResultCollector 捕获 AssertionResult（断言名/失败消息），`get_test_results` 暴露
- **确定性护栏**：验证工具返回结构化 PASS/FAIL；代码计数冒烟轮次；Agent 未过验证想收工时合成消息 nudge 顶回；迭代耗尽且 goal 活跃时跨回合自动续跑（每片 50 迭代，默认 5 片，新配置 `agent.goal.max.continuations`）
- **goal Runtime Context**：goal 活跃期间注入本地面板/CLI 回合（durable-objective 纪律 + 分层验证流程 + 硬标准），IPC 委派回合不注入
- **UI**：复用现有回合事件流 + 结构化终结 recap（目标/验证结果/迭代轮数/遗留风险），无新 UI 组件；命令补全表与 /help 加条目
- **安全边界**：Agent 基于上下文完全自主判断执行目标，无白名单、无事前声明（用户显式接受写操作端点在冒烟中真实发一次请求的风险）

## Capabilities

### New Capabilities

- `goal-autonomous-loop`：/goal 命令触发与守卫、goal 状态机与会话持久化、create_goal/update_goal 工具与权限门、防收工 nudge、迭代耗尽跨回合续跑、冒烟 3 轮上限护栏、结构化终结 recap、goal Runtime Context 注入范围（本地/CLI 回合，IPC 委派不注入）
- `test-plan-verification`：validate_test_plan 静态校验的检查范围与结构化 PASS/FAIL 输出契约；run_test smoke 模式的规模强制（1 线程 × 1 迭代、断言保留、定时器剔除）、全线程组执行与 SAVE 副作用、采集与 provenance

### Modified Capabilities

- `run-result-capture`：新增需求——AgentResultCollector 采集每 sample 的断言结果（断言名/失败消息/失败与否），`get_test_results` 的 samples 与 details 视图暴露断言失败信息（现状只存 success 布尔 + errorCount，断言失败原因丢失）

## Impact

- **代码**：`agent/command`（/goal 注册、busy 守卫）、`agent/run`（AgentRunner nudge/护栏/续跑钩子）、`AgentLoop`（goal 标记透传、合成续跑消息）、`agent/context`（goal Runtime Context 块）、`agent/session`（goal 状态 jsonl metadata 持久化）、`tools/jmeter/execution`（RunTestTool smoke 模式、AgentResultCollector 断言采集、GetTestResultsTool 展示、新 ValidateTestPlanTool）、`tools`（GoalTools 注册）、`gui`（CommandIntellisenseProvider 补全条目）、`utils/AiConfig`（新配置）
- **模板**：`templates/AGENTS.md`、`templates/TOOLS.md`（goal 工作流与验证纪律指引）
- **配置**：`jmeter-ai-sample.properties` 新增 `agent.goal.max.continuations`（默认 5）；`jmeter.ai.max.tool.iterations` 默认 50 不动
- **测试**：goal 状态机/权限门/护栏/续跑、smoke 模式、断言采集的单测；run-result-capture 既有场景零回归
- **参照实现**：Nanobot `/goal`（`D:\WorkHome\git\github\nanobot-zheng\nanobot` 的 command/builtin.py、agent/tools/long_task.py、session/goal_state.py、session/turn_continuation.py）；JMeter `Start` VALIDATE_TG 验证模式（5.6.3 源码）
