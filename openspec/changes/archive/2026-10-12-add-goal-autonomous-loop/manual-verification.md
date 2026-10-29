# 手动验证记录 — add-goal-autonomous-loop

> 对应 tasks 2.3 / 2.4 / 6.2。前置：`mvn clean install`（配好 `JMETER_HOME`）装出最新 jar，
> JMeter GUI 配置可用的 AI provider（`anthropic.api.key` 等）。

## 2.3 R1/R2 路径（VALIDATE_TG × save/选择）

准备：一个已保存为 `.jmx` 的计划（含 ≥2 个线程组、断言、定时器），GUI 打开。

- [x] R1 save-before-run 剥离/重注入：对**有未保存改动**的计划执行 `run_test mode:"smoke"` →
  冒烟正常采集（`get_test_results` 非空）；保存出的 `.jmx` 打开后无 `__agent_result_collector__`
  结论：通过（用户确认 2026-10-12）
- [x] R1 回退是否需要（reinject 不生效时改设 `save_automatically_before_run=false`）：不需要 / 需要（说明）
  结论：不需要（剥离/重注入在 VALIDATE_TG 下直接生效）
- [x] R2 无选中 TG 兜底：不选中任何节点执行 smoke → 仍执行全部线程组（非静默不跑）
  结论：通过（用户确认 2026-10-12）
- [x] R2+ 选中残留不缩水（对抗审查修复锚点）：**用户先手工选中某一个线程组**，再让
  Agent 冒烟 → 确认全部线程组仍被执行（doActionNow 修复前该场景只会验证选中的那一个）
  结论：通过（用户确认 2026-10-12）
- [x] R4 未保存计划不弹窗：新 GUI 里 Agent 从零建计划（从未保存过）→ 冒烟不出现任何
  模态对话框，autosave/goal-smoke-*.jmx 生成且冒烟正常
  结论：通过（用户确认 2026-10-12）

## 2.4 冒烟全链路

- [x] 线程组配置 50 线程 × 100 迭代 → smoke 实际 1×1（sample 总数 = 每 TG 1 sampler 1 条）
- [x] 全部线程组被执行（多 TG 计划）
- [x] 跑后计划树里线程组仍为 50×100（用户配置未被修改）
- [x] `.jmx` 无收集器节点泄漏
- [x] 断言失败消息经 `get_test_results include_details=true` 可见（断言名 + 失败消息）

> 2.4 另有三段会话级实证（sessions/ 下 jsonl 留档）：
> - **P0 回归 + 1×1 强制**：2026-10-11 21:48 会话——不带 properties 的规范冒烟调法直接
>   PASS（修复前必抛 UnsupportedOperationException）；启用 TG 1 线程 1 循环 × 2 采样器
>   恰产 2 样本，禁用 TG 正确跳过
> - **超时→轮询→仲裁收口**：2026-10-12 13:54 会话（10408-1791784435718）——15s 窗口
>   超时返回 "still running"，模型轮询 get_test_status 至完成，update_goal 首次尝试即
>   经门禁点仲裁补章放行：`smoke_attempts=1`、`last_smoke_pass_at` 有值、一轮 COMPLETED
> - **演进对照**：2026-10-11 11:09（修复前 3 轮烧满预算）与 2026-10-12 13:36（仲裁已
>   部署但旧记忆短路）两段会话作为行为基线留档

## 6.2 /goal 端到端

- [x] `/goal`（无参）→ usage 提示
- [x] `/goal`（busy 时）→ 拒绝并提示先 Stop
- [x] 正常 `/goal 为 XX 接口建脚本并验证通过`：需求含糊时先问一次；create_goal 录制；
  改脚本 → validate_test_plan → smoke → 读断言失败 → 修复 → 复验 → update_goal complete 带 recap
- [x] 上限交回：故意制造不可修复错误（如指向不存在域名）→ 3 轮冒烟后注入结构化交回，
  complete 免证据但 recap 含遗留风险
- [x] Stop 后继续：goal 运行中 Stop → goal 仍 active，下一条消息继续（无自动续跑）
- [x] /new 后 goal 存活：`/new` 后首条普通消息的回合仍带 goal 语境（Agent 自述可确认）

## 结果汇总

| 任务 | 状态 | 备注 |
|---|---|---|
| 2.3 | ✅ 已验证 | 用户确认（2026-10-12），R1 回退不需要 |
| 2.4 | ✅ 已验证 | 用户确认 + 三段会话实证（含超时仲裁收口） |
| 6.2 | ✅ 已验证 | 用户确认（2026-10-12） |
