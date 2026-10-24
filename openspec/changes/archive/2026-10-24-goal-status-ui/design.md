# Design

完整设计（含代码锚点表、组件 API 草图、逐边界处置一览）见 `docs/goal-status-ui-design.md`（初版实施设计；strip 高度、光晕形态与展开正文内容已被视觉修订 2/3 取代，以本文与代码为准，详见该文档顶部勘误）；本文提炼决策与理由。

## Context

goal 状态的全部写入都收敛于 `GoalState.store(Session, GoalState)`（GoalState.java:248-255，session monitor 下 putMetadataValue + saveSession），写入者 5 处（CreateGoalTool、UpdateGoalTool×3、ValidateTestPlanTool、RunTestTool、AgentLoop.scheduleGoalContinuation），线程不保证（工具池线程 / agent-loop 执行器 / EDT）；其中续跑调度路径的 store 发生在 `resetFenceLock` 内。「goal 变更 → GUI」通知链路目前不存在。

GUI 侧硬约束：输入卡（`inputArea → inputBorderPanel → bottomPanel → JSplitPane → MainFrame`）不在任何 validate root 内，任何 revalidate 直达全窗布局、触发 JTable cancelCellEditing——离散显/隐的单次 revalidate 已有先例（stopButton.setVisible，每回合两次），高频路径必须 repaint-only（SelectionContextBar 纪律）。`AgentLoop.getSessionManager()`（AgentLoop.java:794）已存在，面板可经此读 session 做 hydrate。

暂停/恢复设计的外部参照：dsh（DeepSeek Harness，`D:\WorkHome\git\github\deepseek-harness` 的 `packages/goal/*` 与 `packages/client/ui-goal/*`）的 goal 采用「durable phase（active/paused/blocked/complete）+ 进程本地 activation（armed/disarmed）」双轴事件溯源模型；其 pause 链路完全不经模型（UI 按钮 → 域服务 CAS 直写 → 事件广播 → round-driver 取消在飞回合），模型侧 pause 反而需 directHuman 授权、resume paused 一律拒绝；Stop 打断 goal 轮在 idle 收敛时自动落 durable paused（auto-converge，`goal-round-driver/src/index.ts:283-298`）。本设计的 D9-D14 决策由此证据驱动。

## Goals / Non-Goals

**Goals:**

- goal 状态变更即时（写入同动作内）到达面板，覆盖全部 5 类写入者及未来新增写入者
- 状态条 / 展开面板 / 光晕三件套对明暗主题与 LAF 实时切换自适应（颜色 paint 时实时读 ThemeColors）
- goal 跨 /new 存活、面板懒创建与 JMeter 重启后恢复呈现
- 高频更新（冒烟计数、续跑轮次）零布局扰动

**Non-Goals:**

- 不改 TurnEvent 回合事件流的 7 种 Kind 与 AgentLoop 事件契约（goal 通知走独立通道）
- 不改 UpdateGoalTool 动作集（模型侧不加 pause/resume 工具——GUI 直写已覆盖，避免引入 dsh 式授权判定复杂度；对照 dsh：模型 pause 需 directHuman、resume paused 拒绝）
- 不做 GUI 取消按钮（取消仍走聊天让 Agent 调 update_goal(cancel)，保住「终结必带 recap」契约；dsh 的「人类终态不注入 wrapup」路线留作后续独立决策）
- 不做 CLI/命令行暂停入口（暂停恢复为 GUI 状态条专属；dsh 的 /goal pause/resume 子命令不引入）
- 状态条不承载 recap（终局 recap 按既有契约渲染在聊天流）
- 展开面板不加验证证据行（正文仅 objective——uiSummary 已在 header 常驻，正文重复会以两种字号呈现同一段文字）
- 不做展开/收起的高度动画（终态条仅做 alpha 渐隐，见决策 D5）
- 不做悬浮浮层形态的展开面板（已拍板卡内内联展开）

## Decisions

### D1 通知通道：store 咽喉点广播，而非 TurnEvent 新增 Kind

`GoalState.store()` 是唯一写入口 → 在其 monitor 块外调用新增的 `GoalStateEvents.publish(sessionKey, snapshot)`（agent.session 包静态广播器，CopyOnWriteArrayList 监听表 + 每会话最后快照缓存）。对齐 Nanobot 独立 goal_state 控制帧的形态。

**备选（否决）**：TurnEvent 新增会话级 Kind（GOAL_STATE_CHANGED）+ AgentLoopFactory 静态扇出——需在 5 个写入点各加发射（未来写入者可能漏）、工具层反向依赖 presenter 层、且 resetFenceLock 内还需构造 presenter 事件。咽喉点方案 1 处接线、写入者零修改。

**监听器契约**（对齐 TurnSubscriber.onTurnEvent 的 javadoc 写法）：回调线程不保证（含 resetFenceLock 内）、不得取任何锁、O(μs)、恰一次 invokeLater；snapshot==null 表示清除；逐监听器 try/catch Throwable 隔离。

### D2 快照：不可变 record，构造在 session monitor 内、publish 在块外

`GoalState` 嵌套 `record GoalSnapshot(Status, uiSummary, objective, recap, smokeAttempts, continuationRounds, sessionKey)`。store() 在 monitor 块内完成 putMetadataValue 与快照构造（字段读取留在 monitor 内，与 monitor 释放的 happens-before 对齐），块外 publish（回调不得在锁内被同步执行之外再引入锁等待）。

### D3 hydrate：先订阅、后恢复，真源是 session、终态不回放

固定顺序：构造器先 `GoalStateEvents.addListener`（此后事件不丢）→ invokeLater 内 hydrate：`GoalStateEvents.lastKnown(key)` 非空用缓存，否则 `agentLoop.getSessionManager().get(key)` → `GoalState.load`（覆盖 JMeter 重启后缓存为空而磁盘有 ACTIVE goal 的场景）。初始呈现只认 ACTIVE/BLOCKED/PAUSED——PAUSED 态为 D9-D11 后补；缓存/磁盘里的终态历史一律渲染为 HIDDEN，不回放 1.5s 终态条（终态反馈已由聊天流 recap 承载，历史终态条无信息量）。

### D4 布局：全宽色带贴卡壳 NORTH；离散 revalidate、高频 repaint-only

goalStrip 挂 `RoundedBorderPanel`（输入卡外壳）的 NORTH、藏在 inputArea 内边距之外——色带 `fillRect` 铺满组件宽度，圆角外形由外壳既有的 paintChildren 圆角裁剪统一负责（不自绘圆角描边，消除「圆角芯片内嵌白底」的盒中盒割裂感），底部状态色发丝线锚定分界；BorderLayout 跳过不可见 NORTH 子组件，无 goal 时高度归零、输入卡与无 goal 布局完全一致。goalStrip 的 `update()` 以内容 key 去重、repaint-only；仅出现/消失/展开/收起四个离散边界 setVisible + 单次 revalidate（与 stopButton.setVisible 同风险级别，每 goal 生命周期仅数次）。

### D5 状态机：HIDDEN/ACTIVE/BLOCKED/TERMINAL_HOLD/FADING + 复活中断

EDT 上唯一可变；`update(snapshot)` 是唯一迁移入口（含 hydrate 初值），chevron 只切正交维度 expanded（ACTIVE/BLOCKED/PAUSED 可展开——PAUSED 态为 D9-D11 后补，见 D11；终态链自动收起）。关键迁移：BLOCKED ⇄ ACTIVE 直接换色（BLOCKED 是数据层终态但 UI 层非终态，且 create_goal/replace 均可从 BLOCKED 复活）；TERMINAL_HOLD(约1.5s) → FADING(约200ms alpha 渐隐，**保持占位高度**，渐隐结束才 setVisible(false)+revalidate，避免渐隐中缩高度抖动转录区) → HIDDEN；HOLD/FADING 期间收到 ACTIVE 快照 → 取消全部 Timer 立即回 ACTIVE；snapshot==null → 立即 HIDDEN 无终态条。Timer fire 前校验当前快照仍是预期终态，防串台。TimerFactory 构造注入使终态链可测可跳时。

### D6 光晕：独立 animator「涟漪 + 扫掠」双动画 + 面板级零布局绘制；80ms 帧率

`GoalGlowAnimator`（80ms Swing Timer；单计数器 ticks 同时驱动两动画，停表保留相位）：ACTIVE 为「涟漪 + 扫掠」双动画——三圈递减环各圈相位错开 2 帧（`ringAlpha(i)=0.28+0.32*sin(相位-i*2帧)`，alpha ∈ [0.28,0.60]），读作沿圈序传播的涟漪；扫掠角 6°/帧匀速旋转（≈4.8s 一圈，取模 60 帧防浮点劣化）。BLOCKED 静态 alpha 0.3 且 **Timer 停**（零动画成本、无扫掠）；off 关闭；模式边沿回调（onModeChange，仅真实迁移时同步触发，dispose 不触发）供宿主 repaint 光晕带。绘制由 **`GoalGlowPainter`（2026-10-22 视觉修订 3：零布局）** 承担——光环由面板 `paintComponent`（子组件之前）画进输入卡外侧的**既有静区**（页边距 6px + 输入坞上方空隙 8px；外扩同心环：外扩 1/3/5、线宽 2.5/1.5/1、alpha 折减 100%/55%/25%，最外延 5.5px 恰好放得下），点亮/熄灭**不改任何布局尺寸**（卡片、转录区、页边距逐像素恒定）。演进史两版否决：①内发光划过卡内文本（遮挡状态条）；②壳层动态留边——独立壳组件四向 GLOW_MARGIN=10 真实布局空间，在固定像素宽度的分栏布局下拿不到新像素，多出的边距从卡片身上扣（光晕一点亮输入卡缩小 20px，即用户实测「一闪烁输入框变小」）。零布局的关键机理：面板 paint 先于子组件，输入卡随后铺下的圆角填充盖住环内侧重叠，环看起来只从卡边向外长；动画帧与模式边沿共用 `repaintGlowBand`（卡片矩形四周外扩 8px 区域 repaint），熄灭沿复用同一卡片矩形清残影。颜色与几何每帧实时读 ThemeColors（LAF 免重钉）。启停：面板 HierarchyListener(SHOWING_CHANGED) 控制隐藏暂停/恢复；终态/清除 off；面板 cleanup 强停（防 JMeter 会话全程空转）。

### D7 文本截断：像素级 + 代理对安全

状态条文本在 paintComponent 内用 FontMetrics 像素级截断加省略号（caption 字号随 LaF/缩放漂移，字符数截断假设不成立），复用 `GoalState.truncateUtf16Safe` 的代理对回退防劈 emoji/CJK 扩展。

### D8 展开正文：JTextPane + MarkdownRenderer，纯阅读区

复用 MessageCard 的渲染路径（`MarkdownRenderer.process(doc, objective, font)`）；JTextPane `setEditable(false) + setFocusable(false)`（纯阅读区，Tab 焦点链不加站）；包 SlimScrollBarUI.scroller；GoalStrip 覆写 `getPreferredSize()`：收起=固定高三连钉（UiTokens.GOAL_STRIP_HEIGHT=28，body 字号 + 24px 操作钮的行高），展开=header + max(100, min(bodyPref, host.getHeight()*2/5))（host 高度经构造注入引用读取；host 高度为 0 时退固定上限 200；正文另有 100px 下限地板，极矮宿主下实际占比可略超 40%）。

### D9 暂停/恢复走 GUI 直写，不经模型（dsh 证据驱动）

`GoalState.Status` 新增 `PAUSED`（非终态）+ 迁移方法：`pauseFromActive()`（仅 ACTIVE→PAUSED）/ `resumeFromPaused()`（仅 PAUSED→ACTIVE），均经 `store()` 咽喉点广播。**⏸ 按钮**：直写 PAUSED；若该会话有活跃回合，同时调 `cancelActiveTask(sessionKey, USER_STOP)`（与 Stop 按钮同链路——终止行照常渲染、输入区 Stop 按钮经正常终态事件链归位）。**▶ 按钮**：直写 ACTIVE + 投放内部续跑片（复用 `scheduleGoalContinuation` 的投放逻辑，抽出无 abortFlag 耦合的公共入口；REPUBLISH、不渲染 You 行、continuationRounds 续算）。EDT 上的按钮 handler 只做轻量编排，状态直写与投放入口 hop 到 loop 专属执行器（对齐 AgentLoop 既有线程模型，EDT 不取 session monitor）。

**备选（否决）**：注入合成用户消息让模型调工具——pause/resume 是非终态变更，不适用 recap 契约，绕经模型纯属多余且引入「UI 构造消息进回合」新链路。dsh 佐证：其 pause/resume 同样 UI→域服务直写不经模型。

### D10 auto-converge：用户 Stop 打断 goal 回合 → 终态自动落盘 PAUSED

挂点与 `isGoalContinuationDue`（AgentLoop.java:820-838）对称：回合终态处理处，若回合因用户停止而终止（USER_STOP 语义，含 Stop 按钮/⏸ 按钮/等价取消）且 goal 为 ACTIVE → `pauseFromActive()` + store（广播）。**触发范围**：仅用户主动停止；SILENT（关闭整合取消）MUST NOT 触发（关窗不是停 goal，goal 保持 ACTIVE 供下次 hydrate）；max_iterations 续跑、内部错误均不触发（对齐 dsh「错误 fail-closed 不伪装暂停」）。**幂等**：⏸ 按钮已先写 PAUSED 时，终态收敛检查 isActive() 为假即 no-op（同一迁移两个入口，session monitor 串行，状态条按内容 key 去重）。**已知行为变化（已知情接受）**：「Stop 后插话继续」流变为「Stop→PAUSED→须 ▶ 恢复」——插入消息将不带 goal 语境（与 D12 联动）。

### D11 PAUSED 视觉：中性灰 + 光晕停 + ⏸/▶ 互斥图标钮

PAUSED 状态条 = `ThemeColors.secondaryText()` 灰调 +「Goal paused」标签，呼吸光晕停摆（GlowAnimator 状态机：仅 ACTIVE 呼吸、BLOCKED 静态琥珀、PAUSED/OFF 无环）。⏸/▶ 用 QuietButton.iconOnly 图标钮，放状态条右端 chevron 左侧；⏸ 仅 ACTIVE 可见、▶ 仅 PAUSED 可见（blocked 无操作钮）；GoalStrip UI 状态机相应增加 PAUSED 态（灰调、可展开、无终态链）。计数仅 tooltip（冒烟尝试数、续跑片 y/max、开始时间），行内不显示。

### D12 暂停阻断：isActive() 严格语义零成本成立

`isActive()` 保持严格 `==ACTIVE` 不变，则两道闸免费获得 PAUSED 阻断：① 续跑调度——`isGoalContinuationDue`（AgentLoop.java:833）读 isActive()，PAUSED 自动不续跑；② 语境注入——`GoalRuntime.buildGoalContext`（GoalRuntime.java:52,58）对非 active goal 不注入 goal 块。`buildGoalContext` 的 goalRequested 分支需补一句判别：goal 存在但 PAUSED 时按「无注入」处理（/goal 新需求回合照常进入，create_goal 会被 D13 门拒）。**不改 activation 双轴**：dsh 的进程本地 armed/disarmed 轴不引入——我们单进程内 Stop 后本就不续跑（无独立驱动器），语义已由 PAUSED durable 状态承载。

> **勘误（2026-10-24 评审）**：上段「PAUSED 按『无注入』处理」已被 Addendum 2（B5 实测修复）推翻——纯静默抑制敌不过会话历史势能，实现为**注入宿主暂停指令**（`GoalRuntime.buildGoalContext` 对 PAUSED 注入「PAUSED by the user … do NOT continue」明确禁令，/goal 回合同口径无例外；spec delta「goal Runtime Context 注入范围」为准，测试锚 `AgentLoopGoalPauseTest.pausedGoalInjectsPauseDirectiveInsteadOfStateBlock` / `buildGoalContextReturnsPauseDirectiveForPausedGoal`）。未来读者勿按本段旧文案把指令注入「修」掉。

### D13 create 门：PAUSED 期间 create_goal 拒绝并提示

CreateGoalTool 查重门从 `existing.isActive()` 扩为：ACTIVE → 现状（指引 replace）；PAUSED → 拒绝并提示「目标已暂停：先恢复（GUI ▶）继续，或让 Agent update_goal(cancel) 终结后重新 /goal」；BLOCKED → 维持放行（复活路径现状）。防 PAUSED 下 /goal 新需求静默覆盖旧目标。

### D14 兼容：接受旧版读 PAUSED 走坏 blob 容错

旧版本 `Status.valueOf("PAUSED")` 抛 IllegalArgumentException → 既有坏 blob 容错把 goal 置 null 并 warn 留痕（GoalState.java:238-244）。回滚场景罕见，接受降级丢 goal；不引入「未知枚举降级 ACTIVE」——那会掩盖真实坏数据。

## Risks / Trade-offs

- [离散 revalidate 撞上用户正在 JTable 编辑] goal 出现/消失由工具执行时机驱动，有小概率撞上「用户发完 /goal 后切去编辑 JTable」，一次 cancelCellEditing 丢编辑中内容 → 接受（每 goal 生命周期仅数次，与 stopButton 同级；规避成本远超收益，AGENTS.md 最小实现原则）。高频路径已严格 repaint-only。
- [publish 在 resetFenceLock 内被同步调用] 监听者若违反契约取锁会卡死 /new 重置 → Listener javadoc 显式契约 + AiChatPanel 回调为无锁 invokeLater（对齐 onTurnEvent 既有实践）。
- [GoalStateEvents 静态表跨测试污染] → resetForTest() 供测试隔离。
- [glow Timer 泄漏导致 JMeter 会话全程空转] → HierarchyListener + 状态迁移处 stop + cleanup 强停三重兜底。
- [进程内缓存与磁盘不一致]（如外部编辑 jsonl）→ 缓存仅用于「先订阅后 hydrate」窗口内的即时态，hydrate 真源是 session；无版本号仲裁，接受该边缘。
- [Stop 语义变化（auto-converge）] 用户习惯「Stop 后随手发消息继续 goal」的流被切断（消息不再带 goal 语境，须 ▶）→ 已知情接受；灰条 + ▶ 是显式恢复入口，且消除了「goal 还挂着但看不见」的原有焦虑。
- [PAUSED 落盘后回滚旧版本] 旧版 valueOf 抛异常 → goal 静默丢弃（warn 留痕）→ 接受（D14）。
- [⏸ 与 auto-converge 双写入口竞态] 两入口写同一 PAUSED 迁移 → session monitor 串行 + isActive() 前置检查幂等（D10），后到者 no-op。
- [EDT 直写 goal 的线程纪律] ⏸/▶ handler 在 EDT，直写与续跑投放 hop 到 loop 执行器，EDT 绝不持 session monitor → 与 AgentLoop 既有「回路跑 loop 专属线程」模型一致。

## Migration Plan

纯增量变更：无数据迁移（goal_state blob 结构不变，快照为运行时类型）；回滚 = 还原本变更代码即可，无持久化残留。
