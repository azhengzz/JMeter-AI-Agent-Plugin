# Tasks

> 依赖排序：收集器扩展（provenance + 注入/剥离入口）→ RunTestTool 改动 → 预监听器注册接线 → provenance 显示 → javadoc 修正 → 单测 → save-before-run 修复（对抗式审查发现）→ 端到端/人工 GUI 验证 → 配置文档。
> **实现纪要**：`mvn clean test` = **299 tests, 1 failure（已知常驻 `CodeRefactorerTest`，非回归）, 5 skipped**。新增 `AgentResultCollectorTest` 7/7 绿。对抗式审查发现并修复 1 个 blocking（`popupShouldSave` save-before-run，见 group 9）。

## 1. AgentResultCollector 扩展（provenance + 注入/剥离入口，全部 additive）

- [x] 1.1 加嵌套枚举 `RunProvenance { USER, AGENT }`。
- [x] 1.2 加 `lastProvenance`；`reset(RunProvenance)` + 无参 `reset()` 委托 `reset(USER)`。
- [x] 1.3 加 `getLastProvenance()`。
- [x] 1.4 加 `isStartCommand(String)` 白名单（start/start_no_timers/run_tg/run_tg_no_timers）。
- [x] 1.5 加 `isRunInProgress()`。
- [x] 1.6 注入逻辑（含 TCCL）落在 `addComponentSafely()`（group 9 重构：原 `injectIntoGuiTree` 合并进 `addComponentSafely`）。
- [x] 1.7 加 `onTestStartAction(ActionEvent)`（白名单→running-guard→武装→AGENT 跳过→USER 注入；整体 `catch(Throwable)`）。
- [x] 1.8 加 `stripCollectorNode(ActionEvent)`（Save 预监听器，`catch(Throwable)`）。

## 2. RunTestTool 改动（AGENT provenance + 武装）

- [x] 2.1 `reset()` → `reset(RunProvenance.AGENT)`（`RunTestTool.java`）。注入路径原样保留（R3 保证）。
- [x] 2.2 `fire ACTION_START` 前调 `AgentResultCollector.armForStartReinject()`（group 9：保证 AGENT 运行经 save-before-run 仍被采集，与开关无关）。

## 3. 预监听器注册接线（SelectionInitCommand 的 ADD_ALL 钩子）

- [x] 3.1 `agent.runcapture.enabled`（默认 true）门控注册 `Start.class` **预**监听器（`onTestStartAction`）。
- [x] 3.2 **无条件**注册 `Save.class` 预监听器（`stripCollectorNode`）——防泄漏优先于开关。
- [x] 3.3 **无条件**注册 `Save.class` 后监听器（`reinjectIfArmed`）与 `Start.class` 后监听器（`clearStartArmed`）（group 9：save-before-run 修复）。
- [x] 3.4 注册在 ADD_ALL 的 `INSTALLED` CAS 一次性守卫内（EDT）。

## 4. GetTestStatusTool provenance 显示

- [x] 4.1 State 行后加 `Started by: <getLastProvenance()>`（在"未运行"短路之后）。

## 5. javadoc / EDT 断言修正（R9）

- [x] 5.1 修正 `removeFromGuiTree` javadoc + 加 `assert EventQueue.isDispatchThread()`。

## 6. 单元测试（`AgentResultCollectorTest`，7 个）

- [x] 6.1 `isStartCommand` 白名单（stop/shutdown/validate_tg 不变更状态）。
- [x] 6.2 provenance（USER/AGENT/null/无参）。
- [x] 6.3 `onTestStartAction` 健壮性（stop 过滤、AGENT 跳过、无 GUI 安全返回；异常吞由 `catch(Throwable)` 保证）。
- [x] 6.5 防双注入 + 武装：AGENT 来源 `onTestStartAction` 跳过注入但置 armed。
- [x] 6.6 武装状态机：`armForStartReinject`/`clearStartArmed`/`isStartArmed` + `reinjectIfArmed` 未武装时 no-op。
- [ ] 6.4 running-guard（需 `JMeterContextService.testStart>0`，GUI/集成）— 见 group 7。
- [x] 6.7 开关 OFF 下 run_test 仍采集（需真实 GUI/run_test）— 见 group 7。
- [ ] 6.8 注册一次性（需真实 ADD_ALL）— 见 group 7。

## 7. 端到端 / 人工 GUI 验证（需真实 JMeter GUI）

- [x] 7.1 用户点 Run → `get_test_status`/`get_test_results` 有数据 + `Started by: USER`。
- [x] 7.2 `run_test` → `Started by: AGENT` 且树中恰一个收集器节点。
- [x] 7.3 运行中保存 → `.jmx` 不含 `__agent_result_collector__`。
- [x] 7.4 `agent.runcapture.enabled=false` → GUI 运行不采集；`run_test` 仍采集。
- [ ] 7.5 RUN_TG/RUN_TG_NO_TIMERS 路径采集确认（design Open Question 2）。
- [x] 7.7 **save-before-run 端到端**（group 9 修复）：已保存 `.jmx` + 默认 `save_automatically_before_run=true`，用户点 Run 与 `run_test` 两种路径，均断言 `startEngine` 克隆树恰一个收集器节点、`get_test_results` 非空。
- [x] 7.6 回归：`mvn clean test` = 299 tests，唯一失败为已知常驻 `CodeRefactorerTest` → **0 回归**。

## 8. 配置文档

- [x] 8.1 `jmeter-ai-sample.properties`：新增 `agent.runcapture.enabled`（默认 true）+ 说明。
- [x] 8.2 `CLAUDE.md` 配置章节：追加 GUI 运行结果采集说明。
- [x] 8.3 design Open Questions 裁决：Q7=`agent.runcapture.enabled`、Q8=`RunProvenance` 嵌套于 `AgentResultCollector` 已落地；Q1/Q2/Q3/Q5/Q6 留作实现/GUI 验证项（见 group 7）。

## 9. save-before-run 修复（对抗式审查发现的 blocking）

> `Start.doAction`（`Start.java:113-115`）在 `startEngine` 前同步 `popupShouldSave`→`doActionNow(SAVE)`（默认 `save_automatically_before_run=true`），触发 Save 预监听器剥离收集器 → `startEngine` 读不到。修复：Save 预剥离（干净 .jmx）+ Save 后重注入（若 armed）+ Start 后解除武装。

- [x] 9.1 `AgentResultCollector`：加 `startArmed` 字段 + `isStartArmed()`/`armForStartReinject()`/`reinjectIfArmed(ActionEvent)`/`clearStartArmed(ActionEvent)`；注入重构为 `addComponentSafely()`（TCCL）。
- [x] 9.2 `onTestStartAction`：通过守卫后置 `startArmed=true`（USER 与 AGENT 均武装）。
- [x] 9.3 `RunTestTool`：`fire ACTION_START` 前 `armForStartReinject()`（开关关时 AGENT 仍可重注入）。
- [x] 9.4 `SelectionInitCommand`：无条件注册 `Save` 后（`reinjectIfArmed`）+ `Start` 后（`clearStartArmed`）监听器。
- [x] 9.5 design.md 增"硬约束 7"记录该约束与修复。
- [x] 9.6 单测：武装状态机生命周期 + AGENT 跳过仍武装 + 未武装 reinject 为 no-op（`AgentResultCollectorTest`）。
