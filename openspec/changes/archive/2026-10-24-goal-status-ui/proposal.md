# Proposal

## Why

/goal 自主闭环已可长时间跨回合运行（冒烟修复、续跑片、结构化交回），但 goal 的生命周期对 GUI 完全不可见：状态变更全部是 `GoalState.store()` 的静默写，GUI 包对 GoalState 零引用，用户在 goal 长跑期间无法随时看到「目标是什么、还活着吗」，只能从聊天流文本间接推断。参照 Nanobot WebUI 的 goal-mode UI（`docs/goal-status-ui-design.md`），为聊天面板补上 goal 生命周期呈现。

## What Changes

- **goal 状态变更通知**：`GoalState.store()` 咽喉点新增进程内广播（新类 `GoalStateEvents`），每次 goal 状态写入（create/replace/终结/冒烟计数/续跑轮次/暂停/恢复）即时扇出到订阅者；新增不可变 `GoalSnapshot` 快照类型
- **Goal 状态条**：goal ACTIVE/BLOCKED/PAUSED 时在输入卡内顶部（上下文条之上）显示「◎ Goal · {ui_summary}」着色状态条（ACTIVE=蓝 / BLOCKED=琥珀 / PAUSED=中性灰，修 Nanobot「blocked 不可见」缺陷），状态色半透明淡染全宽色带贴输入卡顶（与卡身连成一体，不再自绘圆角芯片），tooltip 展示冒烟尝试与续跑轮次计数；高频计数更新仅重绘、不触发布局重算
- **暂停与恢复操作（GUI 直写）**：状态条右端新增 ⏸/▶ 互斥图标钮——⏸（仅 ACTIVE 可见）直写 `PAUSED` 状态，忙期同时以 USER_STOP 取消在跑回合（终止行照常渲染、输入区 Stop 按钮经正常终态链归位）；▶（仅 PAUSED 可见）直写回 ACTIVE 并投放内部续跑片（不渲染 You 行、续跑预算续算不重置）。**不经模型**：pause/resume 是 GUI→GoalState 直写，不构造消息进回合，模型侧 update_goal 不新增动作（对照 dsh：UI→域服务直写，模型 pause 反而需授权）
- **Stop 打断自动暂停（auto-converge，对照 dsh）**：用户 Stop 打断 goal ACTIVE 的回合时，回合终态后 goal 自动迁移为 PAUSED（落盘+广播，状态条转灰、光晕停）；静默取消（关闭整合）MUST NOT 触发；与 ⏸ 幂等。**已知行为变化**：「Stop 后插句话继续」流变为「Stop→暂停→须 ▶ 恢复」——已知情接受
- **暂停阻断注入与续跑**：goal PAUSED 期间，GoalRuntime 不向任何回合注入 goal 语境块、续跑调度不再投放（`isActive()` 严格语义下零成本成立）
- **/goal 创建门**：goal PAUSED 期间 `/goal 新需求`（create_goal）拒绝并提示先恢复（GUI ▶）或终结后重提；BLOCKED 复活路径维持现状
- **卡内内联展开面板**：点状态条 chevron 在输入卡内就地展开完整目标（objective，markdown 渲染；uiSummary 已在收起态 header 常驻，正文不重复），高度上限为面板高约 40%，可滚动，再点收起；不加验证证据行
- **呼吸光晕**：goal ACTIVE 时输入卡外围环绕全周呈缓慢呼吸的蓝色光环（四边可见，非仅四角；**零布局**：光环画在卡片外侧的既有页边距静区里、不遮卡内内容、点亮/熄灭不改布局尺寸——2026-10-22 视觉修订 3，取代壳层动态留边：固定像素宽度下动态边距挤窄输入卡 20px），goal 图标同节奏呼吸明暗（PAUSED/终态图标恢复静态）；BLOCKED 为静态琥珀环；PAUSED 与终态熄灭
- **终态表现**：goal 完成/取消时状态条短暂（~1.5s）显示「✓/✕」着色终态条后渐隐收起（recap 已按 goal-autonomous-loop 既有契约渲染在聊天流，状态条不承载）
- **跨会话存活与恢复**：/new 会话重置不清状态条（goal 比会话重置存活，含 PAUSED）；面板懒创建时从进程内快照缓存或 session 持久化状态 hydrate（含 JMeter 重启后仍 ACTIVE/PAUSED 的 goal）；终态历史不回放

### Addendum（2026-10-20）：非测试计划目标免证据完成（契约层扩展）

GUI 冒烟暴露的语义缝隙：非测试计划型目标（非测试计划任务（系统查询/分析/报告等））的 complete 证据门（静态 PASS+冒烟零 error）与其目标边界结构性冲突——模型只能以 block 终结已完成的工作（BLOCKED 琥珀条常驻）。本附记把该类目标纳入契约：

- create_goal 新增 `verification` 参数（`test_plan` 默认 / `non_test_plan`）：模型建 goal 时声明验证模式
- non_test_plan 目标的 complete 免测试计划验证证据，以 objective 自身声明的完成证据为准（recap 须呈现）
- 载体策略：**代码侧为主**（工具 schema/description、GoalRuntime.statusLines 每回合实时渲染、complete 拒绝错误的非测试计划指引）——WorkspaceInitializer 从不覆写存量 workspace 的 goal.md，模板更新只惠及新 workspace
- 持久化：goal blob 增 `verification` 键，旧 blob 缺失/未知值回落 test_plan；replace 保留模式

### Addendum 2（2026-10-20）：B5 实测两修复

B5 手动场景（暂停后发普通消息）暴露两处缺陷，均已修：

- **暂停指令注入**：纯静默抑制敌不过会话历史里的 goal 势能——模型从上下文「自发续推进」。PAUSED 回合改为注入宿主暂停指令（禁止本轮推进 goal、仅回应用户消息；不含 objective/计数）
- **暂停期证据簿记**：三处证据记录点原以 `isActive()` 门控，PAUSED 期间落地的 validate/smoke 全部不记账 → 门禁据实拒绝 complete → 模型被迫 block（工程已完成、证据被暂停吞掉）。记账门改为 `isOngoing()`（active/paused 均记账，终态停账）

### Addendum 3（2026-10-21）：B6 实测两收紧（dsh 正交设计对齐）

B6 手动场景（暂停期间发新 /goal）暴露 replace 侧门 + 终态可改写两处状态机不设防：

- **replace 侧门封堵**：create_goal 被 PAUSED 门拒后，模型转调 update_goal(replace) 绕行——replace 无状态门，同样达成「objective 静默替换 + 暂停态复活 + 计数清空」（dsh 的正交设计里换目标必须 clear→create 两步显式）。PAUSED 态 replace 现同样拒绝，两侧门拒绝口径互指 cancel；ACTIVE 重复 /goal→replace 的既有指引与 BLOCKED 复活路径维持不变
- **终态汇点**：markTerminal 原不校验源状态——终态判定/recap 可被后续回合任意改写（实测中出现「replace 后对已换目标打旧目标 recap 的 block，再 complete」的噪音）。COMPLETED/CANCELLED 定稿后不可再迁移（含幂等重写 recap）；BLOCKED 例外保留（受阻后完成/放弃，对齐 dsh complete 允许自任何非 complete 相位）

## Capabilities

### New Capabilities

- `goal-status-ui`: /goal 持续目标的 GUI 呈现契约——goal 状态变更通知（store 咽喉点扇出、监听器线程契约）、输入卡内状态条渲染与更新纪律（repaint-only 不扰动 JMeter 编辑器）、卡内内联展开面板、呼吸光晕与终态熄灭、跨会话重置存活与懒创建/重启 hydrate

### Modified Capabilities

- `goal-autonomous-loop`: 三处 requirement 变更——①「goal 状态机与持久化」状态集新增 `paused`（非终态），新增 pause（active→paused，GUI 入口或用户 Stop 打断收敛）/resume（paused→active，GUI 入口）迁移，Stop 语义从「只停回合」修订为「停回合且 goal ACTIVE 时自动收敛为 paused（静默取消除外）」；②「create_goal 与 update_goal 工具及权限门」create 门扩展（goal paused 时拒绝并提示先恢复或终结）；③「goal Runtime Context 注入范围」新增 paused 期间 MUST NOT 注入。其余 6 条 requirement 不变
- （context-usage-indicator 仅覆盖 token 用量环，无重叠，不改）

## Impact

- **新增**：`agent/session/GoalStateEvents.java`（静态广播器 + 快照缓存）、`gui/GoalStrip.java`（状态条组件，含 ⏸/▶ 操作钮）、`gui/GoalGlowAnimator.java`（光晕动画）、`gui/GoalGlowPainter.java`（光环绘制器：面板 paintComponent 画进输入卡外侧既有静区，零布局）
- **修改**：`agent/session/GoalState.java`（嵌套 GoalSnapshot record + Status 新增 PAUSED + pause/resume 迁移方法 + store() 块外 publish）、`agent/AgentLoop.java`（Stop 打断的 auto-converge 收敛 + resume 续跑片投放入口，均挂回合终态决策点旁）、`agent/tools/goal/CreateGoalTool.java`（PAUSED 创建门）、`agent/tools/goal/UpdateGoalTool.java`（non_test_plan 免证据例外 + PAUSED 态 replace 拒绝门；动作集不变，不加 pause/resume）、`agent/session/GoalRuntime.java`（PAUSED 宿主暂停指令注入 + non_test_plan statusLines 渲染）、`agent/tools/jmeter/execution/RunTestTool.java` 与 `ValidateTestPlanTool.java`（验证/冒烟证据记账门 isActive→isOngoing——paused 期间继续记账）、`agent/turn/Turn.java`（cancelCause 携带，Stop 打断的 auto-converge 判别）、`gui/AiChatPanel.java`（订阅/hydrate/输入卡 NORTH 挂载/⏸▶ 接线/paintComponent 光晕绘制与 repaintGlowBand 挂接/cleanup/主题刷新挂接）、`gui/theme/UiTokens.java`（GOAL_STRIP_HEIGHT；2026-10-22 视觉修订 3 后 RoundedBorderPanel 与布局全链路无需改动）、`resources/templates/goal.md`（verification 指引，仅惠及新建 workspace）
- **不受影响**：TurnEvent 回合事件流（goal 通知走独立进程内通道，不动 7 种 Kind）、AgentLoop 事件契约、context-usage-indicator
- **已知权衡**：①状态条出现/消失/展开/收起属离散布局变化，各触发一次输入卡 revalidate（与既有 stopButton 显隐同风险级别，直达 MainFrame 布局）；高频路径严格 repaint-only 规避。②Stop 语义变化（auto-converge）：打断 goal 回合后 goal 落盘 PAUSED，「Stop 后插话继续」需先 ▶ 恢复——已知情接受。③旧版本回滚后读到 PAUSED 走坏 blob 容错丢 goal（warn 留痕）——接受。④取消按钮（GUI 终结 goal）不在本变更范围，取消仍走聊天让 Agent 调 update_goal(cancel)，保住 recap 契约
- **测试**：新增测试文件（GoalStateEventsTest / GoalStatePublishTest / GoalStripTest / GoalGlowAnimatorTest / GoalGlowPainterTest / AiChatPanelGoalStripTest / AgentLoop auto-converge 与 resume 用例 / CreateGoalTool PAUSED 门用例）；既有 AiChatPanelInputAreaTest 等布局锚在 goalStrip 默认隐藏态原样通过
