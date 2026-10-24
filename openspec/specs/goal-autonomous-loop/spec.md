# goal-autonomous-loop Specification

## Purpose

`/goal` 命令驱动的自主脚本开发闭环：goal 状态机与会话持久化、验证纪律注入、防收工 nudge、冒烟轮次护栏与跨回合续跑，使 Agent 不依赖用户人工执行反馈即可完成「改脚本 → 验证 → 修复」迭代，直至硬标准通过或结构化交回。

## Requirements

### Requirement: /goal 命令触发与守卫

系统 SHALL 提供 `/goal <需求描述>` 命令，需求描述为必填内联参数。无参数或空白参数时 SHALL 返回 usage 提示（`Usage: /goal <需求描述>`）且不开启任何回合。会话忙（存在活跃回合）时 SHALL 拒绝执行并提示用户先 Stop。命令本身 SHALL NOT 是独立执行器：通过校验后，命令 SHALL 给当回合打 goal 请求标记、授予 goal 变更权限，然后让 `/goal <需求描述>` 原文作为用户消息进入普通 Agent 回合（模型可见原文）。

#### Scenario: 无参调用返回 usage
- **WHEN** 用户输入 `/goal`（无参数或空白参数）
- **THEN** 返回 usage 提示文案，不创建回合、不设置任何 goal 标记

#### Scenario: 会话忙时拒绝
- **WHEN** 会话存在活跃回合时用户提交 `/goal 优化登录脚本`
- **THEN** 命令被拒绝，提示先停止当前任务再重新提交
- **AND** 原活跃回合不受影响

#### Scenario: 正常触发进入 goal 模式回合
- **WHEN** 空闲会话中用户提交 `/goal 为登录接口建脚本并验证通过`
- **THEN** 该消息以 goal 请求标记进入普通 Agent 回合，模型收到的用户消息为 `/goal 为登录接口建脚本并验证通过` 原文
- **AND** 本回合被授予 goal 变更权限（可调用 create_goal）

### Requirement: goal 状态机与持久化

系统 SHALL 维护会话级 goal 状态机，状态为 `active` / `paused` / `completed` / `cancelled` / `blocked`（`paused` 为非终态：仅可自 `active` 迁入，经 GUI 恢复入口迁回 `active`；pause/resume 属非终态迁移，不属于终结动作）。goal 状态 SHALL 持久化（含 objective、ui_summary、started_at、ended_at、recap、冒烟尝试计数、验证模式 verification），在实例生命周期内跨回合存活，随实例会话归档；verification 取值 `test_plan`（默认）或 `non_test_plan`，缺失或未知值（旧 blob）SHALL 回落 `test_plan`，replace SHALL 保留原验证模式。`/new` 会话重置 SHALL 清空会话消息但 MUST NOT 清除 goal（active 与 paused 均存活——goal 比会话重置存活更久，objective 自包含于状态记录中）。用户 Stop 打断 goal 处于 active 的回合时，回合终止后系统 SHALL 将 goal 自动迁移为 `paused`（落盘并广播状态变更）；静默取消（关闭整合取消）MUST NOT 触发该收敛；Stop MUST NOT 清除或终结 goal。goal 的终结 SHALL 仅经 update_goal 的 complete / cancel / block / replace 动作发生，MUST NOT 存在其它强制清除途径。终态为汇点：goal 已处于 completed 或 cancelled 后，其判定与 recap SHALL 视为定稿、MUST NOT 再被任何终结动作改写（含幂等重写 recap）；BLOCKED 例外保留——blocked→completed/cancelled 为「受阻后完成/放弃」的合法迁移。验证证据（静态/冒烟 PASS 时间戳）与冒烟尝试计数 SHALL 在 goal 为 active 或 paused 期间均记入该 goal——暂停不丢弃已做的验证工作（暂停期间落地的 validate/smoke 照样记账）；终态（completed/cancelled/blocked）后不再记录。objective 长度 SHALL 有上限（4000 字符），超限被拒绝或截断并告知。

#### Scenario: /new 不清除活跃 goal

- **WHEN** goal 处于 active 状态时用户执行 `/new` 重置会话，随后发送任意消息
- **THEN** 会话消息已清空，但 goal 仍为 active
- **AND** 新回合的上下文仍注入 goal 状态块

#### Scenario: paused 跨 /new 存活

- **WHEN** goal 处于 paused 状态时用户执行 `/new` 重置会话
- **THEN** 会话消息已清空，goal 仍为 paused（恢复入口仍可继续该 goal）

#### Scenario: Stop 只停回合不清 goal

- **WHEN** goal active 的回合运行中用户点击 Stop
- **THEN** 回合被终止（终止行照常渲染），goal 未被清除、未终结（无 ended_at/recap）
- **AND** 回合终态后 goal 自动迁移为 paused 并持久化，用户经恢复入口可继续该 goal（续跑预算续算）

#### Scenario: 静默取消不触发收敛

- **WHEN** goal active 的回合被关闭整合以静默方式取消
- **THEN** 回合终止，goal 保持 active（不迁移 paused，供下次会话恢复呈现）

#### Scenario: goal 状态跨回合存活

- **WHEN** 一个 goal 创建后经历多个回合（含用户插话回合）
- **THEN** goal 状态（objective、冒烟计数、started_at）在回合间保持连续

#### Scenario: 终结仅经 update_goal

- **WHEN** goal 需要结束（完成/取消/阻塞/替换目标）
- **THEN** 唯一途径是 Agent 调用 update_goal 对应动作
- **AND** 终结时状态记录含 ended_at 与 recap

### Requirement: create_goal 与 update_goal 工具及权限门

系统 SHALL 提供 `create_goal(objective, ui_summary?, verification?)` 与 `update_goal(action, recap, objective?, ui_summary?)` 工具。create_goal SHALL 仅在 `/goal` 触发的回合内可用（权限门）；其它来源回合调用 SHALL 返回错误并提示用户以完整 `/goal <需求>` 重新提交。goal 已 active 时再次 create_goal SHALL 报错并指引使用 update_goal 的 replace 动作。goal 处于 paused 时 create_goal SHALL 拒绝并提示先恢复（GUI 恢复入口）或终结现有 goal 后重提；goal 处于 blocked 时的既有复活路径维持不变。verification 取值 `test_plan`（默认）或 `non_test_plan`，未知值 SHALL 被拒绝并提示合法取值。update_goal 的终结动作（complete / cancel / block）在 goal 活跃期间 SHALL 于本地与 CLI 来源回合可用，recap 为事实必填（上限 8000 字符）。complete 动作 SHALL 仅在本 goal 已取得验证通过证据（静态校验 PASS 且最近一次冒烟运行零 error）或冒烟尝试已达上限时被接受——**non_test_plan 目标（verification='non_test_plan'）例外**：其 complete SHALL 免测试计划验证证据（validate/smoke 与该类目标的边界冲突），以 objective 自身声明的完成证据为准（该证据 SHALL 呈现于 recap）；test_plan 目标无证据时返回错误说明缺口并附非测试计划模式指引，回合继续。replace 动作与 create_goal 受同一权限门约束，且 goal 处于 paused 时 replace SHALL 同样拒绝并提示先 cancel 终结后重提或恢复——防止「create 被拒后经 replace 静默丢弃暂停目标」的侧门（两侧门的拒绝口径 SHALL 互指 cancel）。

#### Scenario: 非授权回合调用 create_goal 被拒

- **WHEN** goal 未启动，用户发送普通消息，Agent 在该回合调用 create_goal
- **THEN** 工具返回错误，提示需用户以 `/goal <需求>` 提交
- **AND** 无任何 goal 状态被写入

#### Scenario: /goal 回合内正常创建

- **WHEN** `/goal` 触发的回合中 Agent 调用 create_goal（objective 含明确完成判据）
- **THEN** goal 记录写入会话持久化存储，状态置 active
- **AND** 工具返回「目标已记录，继续用普通工具推进，完成后调 update_goal complete」语义的确认

#### Scenario: goal 活跃时重复创建被拒

- **WHEN** goal 已 active，Agent 再次调用 create_goal
- **THEN** 工具报错并指引使用 update_goal replace

#### Scenario: goal 暂停时创建被拒并提示

- **WHEN** goal 处于 paused，`/goal 新需求` 回合中 Agent 调用 create_goal
- **THEN** 工具报错并提示先恢复（GUI ▶）或终结现有 goal 后重提
- **AND** goal 状态不变（仍 paused、objective 不被覆盖）

#### Scenario: goal 暂停时 replace 同样被拒（封侧门）

- **WHEN** goal 处于 paused，`/goal 新需求` 回合中 Agent 被 create_goal 拒绝后转调 update_goal(action='replace')
- **THEN** replace 亦被拒绝，提示先 cancel 终结后重提（或 ▶ 恢复），错误口径与 create_goal 的拒绝互指 cancel
- **AND** goal 保持 paused、objective、计数与暂停态全部不变（暂停目标的去留由用户显式选择）

#### Scenario: 终态判定不可改写

- **WHEN** goal 已 completed（或 cancelled），Agent 再调 update_goal 的任一终结动作（含幂等重写 recap）
- **THEN** 拒绝（verdict 与 recap 已定稿）；goal 状态不变
- **AND** blocked→completed/cancelled 不受此限（受阻后完成/放弃的合法迁移）

#### Scenario: 非测试计划目标免证据完成

- **WHEN** goal 以 `verification='non_test_plan'` 创建（objective 为非测试计划的系统查询类任务，未跑任何 validate/smoke），目标自身的完成证据（如脚本 Exit 0 + 结果表格）已取得，Agent 调用 update_goal(complete, recap 含该证据)
- **THEN** 终结被接受，goal 置 completed（不再落入 blocked）
- **AND** goal 状态块向模型呈现「non-test-plan goal：validate/smoke 不适用」口径（statusLines 实时渲染，workspace 模板陈旧亦生效）

#### Scenario: 未知 verification 值被拒

- **WHEN** `/goal` 回合中 Agent 调用 create_goal 传 `verification='sometimes'`
- **THEN** 工具报错并提示合法取值 test_plan / non_test_plan

#### Scenario: 未过验证的 complete 被拒

- **WHEN** test_plan 目标下 Agent 调用 update_goal(complete) 但既无验证通过证据、冒烟尝试也未达上限
- **THEN** 工具返回错误（说明缺静态校验 PASS / 冒烟零 error 证据），goal 保持 active，回合继续
- **AND** 错误信息在该目标一发冒烟未跑时附「若 objective 不涉及测试计划变更，应以 verification='non_test_plan' 重建」的指引

#### Scenario: 达冒烟上限后 complete 放行

- **WHEN** 冒烟尝试已达 3 轮上限且仍存在错误，Agent 调用 update_goal(complete) 并附遗留风险 recap
- **THEN** 终结被接受，goal 置 completed，recap 落入状态记录与最终输出

### Requirement: goal Runtime Context 注入范围

goal 活跃（或当回合由 `/goal` 触发）时，系统 SHALL 向本地面板与 CLI 来源回合的 LLM 上下文注入 goal 块：objective、当前状态、冒烟尝试计数、验证纪律（分层验证流程、硬标准、3 轮上限、recap 要求、写端点自主判断语义）。goal 处于 paused 时系统 SHALL NOT 注入 goal 状态/纪律块，但 SHALL 注入一条宿主暂停指令：明确禁止本轮继续/推进/恢复 goal 工作（不修改测试计划、不跑 validate/smoke、不调其它 goal 相关工具），仅回应用户消息本身；仅当用户消息明确要求处置 goal 时允许 update_goal 终结动作。暂停指令 MUST NOT 携带 objective/计数等状态数据（纯静默抑制不足以对抗会话历史中的 goal 势能——模型会从上下文自发续推进）。恢复为 active 后按上列规则重新注入完整块，恢复入口投放的续跑片按 active 规则注入。IPC 委派回合（delegated 来源）SHALL NOT 注入 goal 块（委派任务独立，不承载宿主会话的 goal 语境）。goal 终结后 SHALL NOT 再注入。

#### Scenario: /goal 回合注入 goal 块

- **WHEN** `/goal` 触发的回合构建 LLM 上下文
- **THEN** 上下文含 goal 请求标记与 durable-objective 写作纪律（目标须自包含、有界、显式完成证据）

#### Scenario: goal 活跃时普通本地消息同享注入

- **WHEN** goal active 期间用户发送普通消息（非命令）
- **THEN** 该回合上下文注入 goal 状态块，Agent 可继续推进或调 update_goal 终结

#### Scenario: paused 期间普通消息注入暂停指令而非状态块

- **WHEN** goal paused 期间用户发送普通消息（非命令）
- **THEN** 该回合上下文含宿主暂停指令（禁止继续/推进 goal 工作、仅回应用户消息），不含 objective/计数的 goal 状态块
- **AND** goal 恢复 active 后的下一次回合注入恢复正常

#### Scenario: IPC 委派回合不注入

- **WHEN** goal active 期间收到对端实例的委派任务
- **THEN** 委派回合上下文不含 goal 块，委派任务照常执行

#### Scenario: 终结后不再注入

- **WHEN** goal 经 update_goal(complete) 终结后用户发送新消息
- **THEN** 新回合上下文不含 goal 块

### Requirement: 防收工 nudge

goal 活跃的回合作出无工具调用的最终回复、且 goal 未终结时，系统 SHALL 注入一条合成续跑消息把回合顶回（含 goal 状态与验证证据现状摘要），模型继续用工具推进或显式调用 update_goal 终结。注入的合成消息 SHALL 标记为内部续跑，MUST NOT 在聊天界面渲染为用户发言。同一回合内连续无进展的顶回 SHALL 有界（连续两次无任何工具调用即放行最终回复，避免死循环），放行的最终回复 SHALL 附带 goal 未终结的状态说明。

#### Scenario: 未终结的最终回复被顶回
- **WHEN** goal active 的回合中模型输出无工具调用的最终回复且未调 update_goal
- **THEN** 系统注入合成续跑消息（含验证证据现状），回合继续而非结束

#### Scenario: 合成续跑消息不渲染为用户行
- **WHEN** 防收工 nudge 注入合成消息
- **THEN** 聊天界面不出现对应的 You 发言行

#### Scenario: 连续无工具调用放行
- **WHEN** 顶回后模型再次输出无工具调用的回复（连续两次）
- **THEN** 回合以该回复结束，回复中含 goal 未终结说明

### Requirement: 冒烟修复上限（以 goal 为单位）

系统 SHALL 以 goal 为单位计数冒烟运行尝试（run_test smoke 模式执行次数），计数跨续跑片与用户插话回合累计，goal 被 replace 时重置。达到上限（默认 3）后，系统 SHALL 注入「已达冒烟上限，必须结构化交回」的合成消息，交回内容 SHALL 包含：目标、已尝试的修复方案、当前卡点、验证结果、建议人工检查项；此后 update_goal(complete) 的验证证据要求解除（见 create_goal 与 update_goal 工具及权限门需求）。

#### Scenario: 达上限注入结构化交回
- **WHEN** 同一 goal 的第 3 次冒烟运行仍存在 error
- **THEN** 系统注入已达上限的合成消息，要求模型输出结构化交回并终结 goal

#### Scenario: 计数跨续跑片累计
- **WHEN** goal 的冒烟尝试分布在多个续跑片中
- **THEN** 计数按 goal 累计（第 4 次冒烟在达上限后被拒绝或按交回语义处理）

#### Scenario: replace 重置计数
- **WHEN** goal 经 update_goal(replace) 替换 objective
- **THEN** 冒烟尝试计数归零，重新享有完整上限

### Requirement: 迭代耗尽跨回合续跑

goal 活跃回合的迭代预算耗尽时，系统 SHALL NOT 以「达到最大迭代次数」的失败样式终结，而 SHALL 自动投放合成续跑消息开启新回合（续跑片），从会话上下文继续当前 goal；每续跑片 SHALL 享有完整的每回合迭代预算。续跑次数 SHALL 受 `agent.goal.max.continuations`（默认 5）约束，计数以 goal 为单位；达到续跑上限时 SHALL 强制结构化交回（与冒烟上限交回同款内容契约）。续跑消息 SHALL 标记内部续跑，MUST NOT 渲染为用户发言。

#### Scenario: 迭代耗尽自动续跑
- **WHEN** goal 活跃回合耗尽迭代预算且 goal 未终结
- **THEN** 系统自动投放续跑消息开启新回合，无「达到最大迭代次数」的用户可见失败
- **AND** 新回合从会话上下文继续 goal 工作

#### Scenario: 续跑片预算独立
- **WHEN** 续跑片开启后
- **THEN** 该片享有与普通回合相同的完整迭代预算

#### Scenario: 续跑上限后强制交回
- **WHEN** goal 的续跑次数达到 `agent.goal.max.continuations`
- **THEN** 不再续跑，强制输出结构化交回（目标、卡点、验证结果、建议）

#### Scenario: 续跑消息不渲染为用户行
- **WHEN** 续跑消息被投放
- **THEN** 聊天界面不出现对应的 You 发言行

### Requirement: 结构化终结 recap

update_goal 的终结动作（complete / cancel / block）SHALL 必带 recap。goal 终结时的最终用户可见输出 SHALL 包含：目标、验证结果（静态校验/冒烟/用户验收条件各项状态）、冒烟迭代轮数、遗留风险。冒烟上限或续跑上限触发的交回 SHALL 满足同一内容契约。

#### Scenario: complete 的 recap 内容
- **WHEN** 验证通过后 Agent 调用 update_goal(complete, recap)
- **THEN** 最终输出含目标、各项验证通过状态、迭代轮数、无遗留风险或其说明

#### Scenario: 上限交回的 recap 内容
- **WHEN** 冒烟 3 轮上限触发结构化交回
- **THEN** 交回输出含已尝试方案、卡点、验证结果、建议人工检查项

### Requirement: 命令补全与帮助登记

`/goal` SHALL 进入命令智能补全建议表（输入 `/` 或前缀匹配时出现）与 `/help` 输出的命令清单，含一句话用途说明。

#### Scenario: 补全建议出现
- **WHEN** 用户在输入框键入 `/go`
- **THEN** 补全弹出包含 `/goal` 条目

#### Scenario: /help 列出 /goal
- **WHEN** 用户执行 `/help`
- **THEN** 命令清单包含 `/goal` 及其用途描述
