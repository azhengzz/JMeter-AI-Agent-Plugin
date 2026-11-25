# Spec Delta

## ADDED Requirements

### Requirement: 证据齐全的 complete 由模型自己保存测试计划

`update_goal(action='complete')` 因验证证据齐全（静态校验 PASS 且最近一次冒烟运行零 error）被接受前，goal 工作流指引 SHALL 要求模型自己调用 `save_test_plan`（不传 `filePath` = 存回关联文件）把测试计划落盘，并在最终答复中转述保存结果。保存语义与保护规则见 test-plan-save 能力。`update_goal` 本身 MUST NOT 写盘，也不向工具返回拼接任何保存结果。计划从未保存时，`save_test_plan` 按保护规则拒绝，指引 SHALL 引导模型向用户询问保存路径（MUST NOT 猜测路径）。

goal 活跃期间注入的验证纪律与状态块 SHALL 说明：验证通过后、调用 `update_goal(complete)` 之前，由模型自己调 `save_test_plan` 存回计划。该说明 SHALL 由代码侧实时渲染，workspace 的 goal 模板陈旧时同样生效；goal 模板同步收录。

#### Scenario: 验证通过后模型先保存再 complete
- **WHEN** test_plan 目标已取得静态 PASS 与冒烟零 error 证据，计划的关联文件为 `D:/plans/login.jmx`
- **THEN** goal 指引要求模型先调 `save_test_plan` 存回该文件，再调 `update_goal(action='complete')`
- **AND** 模型在最终答复中转述保存路径与结果

#### Scenario: 从未保存的计划指引模型询问路径
- **WHEN** 计划从未保存（无关联文件），goal 验证通过、准备 complete
- **THEN** `save_test_plan` 拒绝，指引引导模型向用户询问保存路径，不猜测路径

#### Scenario: 旧 workspace 的 goal 模板陈旧时说明仍然生效
- **WHEN** workspace 的 `goal.md` 是加入保存指引之前的旧模板，goal 活跃回合构建 LLM 上下文
- **THEN** 状态块仍然渲染出"验证通过后由模型自己调 save_test_plan"的说明

## MODIFIED Requirements

### Requirement: 结构化终结 recap

update_goal 的终结动作（complete / cancel / block）SHALL 必带 recap。goal 终结时的最终用户可见输出 SHALL 包含：目标、验证结果（静态校验/冒烟/用户验收条件各项状态）、冒烟迭代轮数、遗留风险。冒烟上限或续跑上限触发的交回 SHALL 满足同一内容契约。因验证证据齐全而 complete 时，最终用户可见输出 SHALL 另外转述测试计划的保存结果——模型自己调 `save_test_plan` 得到的已保存路径，或保存被拒的原因。

#### Scenario: complete 的 recap 内容
- **WHEN** 验证通过后 Agent 调用 update_goal(complete, recap)
- **THEN** 最终输出含目标、各项验证通过状态、迭代轮数、无遗留风险或其说明

#### Scenario: 上限交回的 recap 内容
- **WHEN** 冒烟 3 轮上限触发结构化交回
- **THEN** 交回输出含已尝试方案、卡点、验证结果、建议人工检查项

#### Scenario: 验证通过的完成转述保存结果
- **WHEN** 验证通过后 Agent 先调 save_test_plan 存回成功，再调 update_goal(complete, recap)
- **THEN** 最终输出含"已保存"及保存路径
