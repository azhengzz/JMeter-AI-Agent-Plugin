# Tasks

## 1. 数据通道与 goal 状态机（store 咽喉点广播 + PAUSED）

- [x] 1.1 在 `GoalState` 嵌套 `GoalSnapshot` record（status/uiSummary/objective/recap/smokeAttempts/continuationRounds/sessionKey）与私有工厂 `snapshot(sessionKey)`；改造 `store()`：monitor 块内 putMetadataValue + 构造快照，块外 `GoalStateEvents.publish`。验证：`GoalStatePublishTest` 通过（store 触发 publish 且快照字段一致；store(null) publish null；load-only 不 publish）
- [x] 1.2 新建 `agent/session/GoalStateEvents.java`（Listener 契约 javadoc 对齐 TurnSubscriber：线程不保证/不取锁/O(μs)/恰一次 invokeLater；add/remove 幂等；publish 缓存 + 逐监听器异常隔离；lastKnown；resetForTest）。验证：`GoalStateEventsTest` 通过（送达+缓存/异常隔离/多线程 hammer/null 清除/幂等/resetForTest）
- [x] 1.3 `GoalState.Status` 新增 `PAUSED`（非终态）+ 迁移方法 `pauseFromActive()`（仅 ACTIVE→PAUSED）/ `resumeFromPaused()`（仅 PAUSED→ACTIVE），非法迁移抛 IllegalArgumentException；`isActive()` 保持严格 `==ACTIVE` 不变。验证：GoalState 状态机单测通过（active→paused→active 循环、非法迁移拒绝、PAUSED 的 toMap/load roundtrip、isActive 对 PAUSED 为 false）

## 2. 暂停/恢复引擎（AgentLoop + 注入门 + create 门）

- [x] 2.1 auto-converge：回合终态处理处（与 `isGoalContinuationDue` 对称的决策点），回合因用户停止终止（USER_STOP 语义）且 goal 为 ACTIVE → `pauseFromActive()` + store + saveSession；SILENT 取消与内部错误 MUST NOT 触发；isActive 前置检查保证与 ⏸ 幂等。验证：AgentLoop auto-converge 测试通过（testsupport 脚本钉用户中止 → goal 断言 PAUSED；静默取消 → 保持 ACTIVE；⏸ 先写 PAUSED → 收敛 no-op）
- [x] 2.2 从 `scheduleGoalContinuation` 抽出无 abortFlag 耦合的续跑片公共投放入口；新增 `resumePausedGoal(sessionKey)`（在 loop 执行器上执行：load goal → resumeFromPaused → store + saveSession → 投放续跑片，REPUBLISH、不渲染 You 行、continuationRounds 续算；goal 非 PAUSED 时 no-op）。验证：resume 单测通过（PAUSED→ACTIVE + 续跑片投放、预算续算不重置、非 PAUSED no-op）
- [x] 2.3 新增 `pauseActiveGoal(sessionKey)`（在 loop 执行器上执行：isActive 前置 → pauseFromActive → store + saveSession；若该会话存在活跃回合 → 以 USER_STOP 走 `cancelActiveTask` 同款取消链）。验证：pause 单测通过（空闲路径只写状态不开回合；忙期路径取消回合；goal 非 ACTIVE no-op）
- [x] 2.4 `CreateGoalTool` 查重门扩展：PAUSED → 拒绝并提示「先恢复（GUI ▶）或 update_goal(cancel) 终结后重提」（ACTIVE 指引 replace、BLOCKED 放行均维持现状）；`GoalRuntime.buildGoalContext` 补分支：goal 存在但 PAUSED → 不注入任何 goal 块（goalRequested 分支照常、由工具门拒绝）。验证：CreateGoalTool PAUSED 门测试 + GoalRuntime paused 不注入测试通过

## 3. GoalStrip 状态条组件

- [x] 3.1 新建 `gui/GoalStrip.java` 骨架：JPanel(BorderLayout) {NORTH headerRow（自绘文本 + ⏸/▶ 操作钮 + QuietButton chevron）/ CENTER bodyWrap(默认不可见)}，固定高三连钉（UiTokens 新增 `GOAL_STRIP_HEIGHT=22`），paintComponent 像素级截断 + truncateUtf16Safe 代理对回退，五态着色（ACTIVE=info 蓝 / BLOCKED=warning 琥珀 / PAUSED=secondaryText 灰 + 「Goal paused」标签 / COMPLETED=success / CANCELLED=error）与 uiSummary 空降级，tooltip 计数（冒烟尝试、续跑片 y/max、开始时间）。验证：`GoalStripTest` 着色/降级/去重用例通过
- [x] 3.2 实现状态机（HIDDEN/ACTIVE/BLOCKED/PAUSED/TERMINAL_HOLD/FADING + 复活中断 + expanded 正交维度，PAUSED 可展开无终态链），TimerFactory 构造注入；`update()` 内容 key 去重 repaint-only、离散边界才 setVisible+revalidate；⏸/▶ 互斥可见（仅 ACTIVE 显 ⏸、仅 PAUSED 显 ▶、BLOCKED 无操作钮），点击经回调接口上抛（不直连 AgentLoop）；`dispose()` 停全部 Timer。验证：`GoalStripTest` 状态机与按钮互斥用例通过（终态链/复活中断/BLOCKED⇄ACTIVE/PAUSED 灰调/按钮互斥/dispose 后无 Timer 存活）
- [x] 3.3 展开正文：bodyWrap 内 JTextPane(setEditable(false)+setFocusable(false)) + MarkdownRenderer 渲染「uiSummary\n---\nobjective」，包 SlimScrollBarUI.scroller；`getPreferredSize()` 覆写展开 clamp（host.getHeight()*2/5，getHeight()==0 退固定上限）；`setExpanded()` 单次 revalidate。验证：`GoalStripTest` 展开/收起/高度 clamp 用例通过

## 4. 呼吸光晕

- [x] 4.1 新建 `gui/GoalGlowAnimator.java`：80ms Swing Timer、setGoalActive/setGoalBlocked/off/dispose（PAUSED 走 off）、ringAlpha(i)（ACTIVE 各环 0.28+0.32*sin、相位错开 2 帧成涟漪；BLOCKED 静态 0.3 Timer 停、off 0）+ sweeping()/sweepAngle()（ACTIVE 扫掠 6°/帧 ≈4.8s 一圈，取模 60 帧防浮点劣化）、glowColor() paint 时实时读、isAnimating() 观测面（2026-10-22 视觉修订：单计数器 ticks、幅度提升 + 涟漪 + 扫掠双动画）。验证：`GoalGlowAnimatorTest` 通过（正弦区间/涟漪相位错开/扫掠匀速/静态停帧/off 停）
- [x] 4.2 `RoundedBorderPanel` 加 `setGlow()`，`paint()` 描边段之前画边框内侧发光环（内缩 2/5/8 递减 alpha）+ ACTIVE 扫掠高亮（沿直径 LinearGradientPaint、正端朝扫掠角、头亮尾隐 + 向内淡回声），既有描边逻辑不动（2026-10-22 视觉修订：环画在边界内——外扩环被 Swing 组件裁剪只剩角弧可见）。验证：`mvn test -Dtest=GoalGlowAnimatorTest` 通过 + 既有 RoundedBorderPanel 相关布局锚（AiChatPanelInputAreaTest）无回归

## 5. AiChatPanel 接线

- [x] 5.1 构造器先订阅 `GoalStateEvents.addListener(this::onGoalStateChanged)`；新私有方法 onGoalStateChanged（EDT 外 invokeLater、sessionKey 过滤、驱动 goalStrip.update + goalGlow 迁移：ACTIVE→呼吸、BLOCKED→静态环、PAUSED/终态→off）。验证：`AiChatPanelGoalStripTest` 直发快照/会话外过滤/PAUSED 光晕灭用例通过
- [x] 5.2 goalStrip 贴输入卡外壳 NORTH（卡壳 RoundedBorderPanel 直挂、藏在 inputArea 内边距之外——2026-10-22 视觉修订：原 northWrapper{goalStrip/contextRow} 方案弃用，色带全宽贴卡顶与卡身连成一体），inputBorderPanel 挂 glow + HierarchyListener（SHOWING_CHANGED 暂停/恢复）；`applyControlBarTheme` 挂 goalStrip.applyTheme；`advanceRenderEpoch` 补「goal 跨 /new 存活（active 与 paused）」语义注释（不动 goalStrip）；`cleanup()` 摘监听 + dispose×2。验证：`AiChatPanelGoalStripTest` /new 存活锚 + cleanup 摘除锚 + 贴壳 NORTH 结构锚通过；`AiChatPanelInputAreaTest` 原样通过（无 goal 零布局扰动锚）
- [x] 5.3 ⏸/▶ 接线：GoalStrip 回调 → EDT handler → hop 至 loop 执行器调 `pauseActiveGoal` / `resumePausedGoal`（EDT 不持 session monitor）。验证：`AiChatPanelGoalStripTest` 按钮回调路径用例通过（⏸ 触发引擎入口、忙期取消后 Stop 按钮归位）
- [x] 5.4 hydrate：构造器 invokeLater 内 `GoalStateEvents.lastKnown` 优先、否则 `agentLoop.getSessionManager().get(key)` → `GoalState.load`；初始渲染只认 ACTIVE/BLOCKED/PAUSED（终态不回放）。验证：`AiChatPanelGoalStripTest` hydrate 用例通过（ACTIVE→蓝色出现、PAUSED→灰色出现含 ▶、COMPLETED→不出现）

## 6. 集成验证

- [x] 6.1 全量回归：`mvn clean test` 全绿（含既有 AiChatPanel 系/TranscriptView 系/goal 系行为锚）。验证：BUILD SUCCESS 且 Tests run 无 Failures/Error
- [x] 6.2 手动 GUI 冒烟（`mvn clean install "-Dlaunch.gui=true"`，lib/ext 只留最新 jar），逐项人工确认并记录：①/goal 后状态条浮现+光晕呼吸；②chevron 展开/收起完整目标；③冒烟计数 tooltip 刷新无布局抖动；④⏸ 空闲暂停→灰条+光晕停；⑤忙期 ⏸→终止行渲染+Stop 按钮归位+灰条+▶；⑥▶→续跑片继续 goal（无 You 行）；⑦Stop 打断 goal 回合→auto-converge 自动灰条；⑧暂停期间发普通消息→不注入 goal 语境；⑨PAUSED 期间 /goal 新需求→拒绝提示；⑩complete 终态条 1.5s→渐隐→消失、光晕灭；⑪/new 后状态条存活（active 与 paused）；⑫goal 活跃时重启 JMeter 再开面板状态条恢复；⑬Stop/上下文条/ToAI 回归正常

## 7. 非测试计划目标免证据完成（契约层扩展，2026-10-20 附记）

- [x] 7.1 `GoalState` 加 `Verification` 枚举（TEST_PLAN 默认/NON_TEST_PLAN）+ `markNonTestPlan()` + 持久化（toMap/load：缺失/未知回落 test_plan；replace 保留模式）。验证：GoalState 单测通过（roundtrip、旧 blob 回落、replace 保留）
- [x] 7.2 `CreateGoalTool` 加 `verification` 参数（schema/描述/解析，未知值拒绝）；`UpdateGoalTool` complete 门 non_test_plan 豁免 + description 更新 + 拒绝错误附非测试计划指引。验证：`GoalToolsTest` 新用例通过（non_test_plan 免证据 complete、默认目标仍受门、未知值拒绝、replace 保留）
- [x] 7.3 `GoalRuntime.statusLines` non_test_plan 分支（勿跑 validate/smoke、按自证完成）+ 内嵌默认与 `templates/goal.md` 纪律文本更新（载体以代码侧为准——存量 workspace 模板不覆写）。验证：GoalRuntime 注入口径测试通过（non_test_plan goal 的状态块含 non-test-plan 口径行）

## 8. B5 实测修复（2026-10-20 附记 2）

- [x] 8.1 暂停指令注入：`GoalRuntime.buildGoalContext` PAUSED 分支由返回空串改为注入宿主暂停指令（禁止本轮推进 goal、仅回应用户消息、update_goal 终结仅限用户明确要求；不含状态数据）。验证：`AgentLoopGoalPauseTest` 暂停指令用例通过（指令注入/状态块缺席/objective 不泄漏）
- [x] 8.2 暂停期证据簿记：RunTestTool×2 与 ValidateTestPlanTool 的证据记录门由 `isActive()` 改为 `GoalState.isOngoing()`（active/paused 均记账，终态停账）。验证：`GoalToolsTest.evidenceBankedWhilePausedUnlocksComplete` 通过（带证据的 PAUSED goal 直接 complete）
- [x] 8.3 全量回归 + 重建 GUI 复验 B5/C1/B2/B4

## 9. B6 实测两收紧（2026-10-21 附记 3）

- [x] 9.1 replace 侧门封堵：UpdateGoalTool replace 分支加 PAUSED 拒绝（口径与 create_goal 互指 cancel）；create_goal 的 PAUSED 拒绝文案补「勿用 replace 绕行」。验证：`GoalToolsTest.replaceRejectedWhileGoalPaused` 通过（replace 拒、objective/暂停态保持）
- [x] 9.2 终态汇点：`GoalState.markTerminal` 加源状态校验（COMPLETED/CANCELLED 为源一律拒绝、含幂等重写；BLOCKED→终态保留）。验证：`GoalToolsTest.terminalVerdictIsFinal` + `blockedGoalMayStillComplete` 通过
- [x] 9.3 全量回归 + 重建 GUI 复验 B6

## 10. 视觉修订 2：外围光环壳 + 对抗评审修复（2026-10-22 附记 4）

- [x] 10.1 光环整体外移出卡（取代 4.2 的内发光方案）：新建 `gui/GoalGlowShell.java`——输入卡外围光环壳（CENTER 包卡），光晕非 OFF 时壳四向留 `GLOW_MARGIN`（10px）真实布局空间把卡片挤出一圈余量带、OFF 归零布局原状；光环画在卡片圆角矩形**外侧**余量带（涟漪三环外扩 2/5/8、扫掠弧 2/5.2），与卡内内容零重叠——修掉内侧发光划过 goal 状态条文本的遮挡，动画带同时向四周扩大一圈；`RoundedBorderPanel` 还原无光晕职责。验证：`GoalGlowShellTest` 4 用例通过
- [x] 10.2 对抗评审修复（15 代理多维评审 + 逐发现 2/2 对抗验证：4 项确认全 P2、2 项驳回归零）：①`GoalGlowAnimator.ringAlpha` 呼吸相位 `Math.floorMod` 取模后再进浮点——裸 ticks 超 2^24 后 int→float 24 位有效数字坍缩、呼吸劣化成卡顿（与扫掠侧既有取模对齐；整周期平移数学等价，纯防劣化）；②③ `dispose()` 粘性化——`GoalGlowAnimator.setMode` 与 `GoalStrip.refreshBreathing` 置销毁粘位，封死 cleanup 后三条复活路径（迟到的 invokeLater 派发、COW 快照迭代中摘除仍被调、未摘除的匿名层级监听显隐桥），Timer 不可逆释放；④面板级光环壳接线锚补钉（原 36 测试可证明地壳无关，拆壳全绿盲区）。验证：定向 59/59 通过（animator 7 / strip 19 / shell 4 / 面板 13 / 输入区 16）
- [x] 10.3 面板级接线锚：`AiChatPanelGoalStripTest` 新增 3 用例（光环壳包输入卡层级锚、BLOCKED 点亮→壳四向 GLOW_MARGIN→熄灭归零布局锚、cleanup 后粘性 dispose 拒绝复活锚）；`GoalGlowAnimatorTest`/`GoalStripTest` 各加粘性 dispose 单测，`disposeStopsAllTimers` 重排为先点火后清理（粘位下旧序列假失败）
- [x] 10.4 全量回归：`mvn clean test` 单独跑 935 tests / 0 failures / 0 errors / 11 skipped；`mvn clean install -DskipTests` 安装，lib/ext 仅 `jmeter-agent-0.4.2.jar` 最新版
- [x] 10.5 GUI 手动复验（顺带覆盖 8.3/9.3 遗留）：①`/goal` 后光环在输入卡**外侧**呼吸、状态条 goal 描述完整可读无遮挡；②光晕带四向扩大一圈（10px 余量带）；③⏸/▶/终态熄灭后卡片恢复原布局；④面板隐藏/恢复光晕暂停/恢复；⑤无 goal 时布局与原版完全一致

## 11. 视觉修订 3（零布局光环 + 全宽色带 + 呼吸图标）与对抗评审修复（2026-10-23 附记 5）

- [x] 11.1 光环零布局化（取代 10.1 的壳层动态留边方案）：壳组件废弃删除，改为 `gui/GoalGlowPainter` + 面板 `paintComponent`（子组件之前）把光环画进输入卡外侧**既有静区**（页边距 6 + 输入坞上方空隙 8；三环外扩 1/3/5、最外延 ≤5.5px 恰好放得下），点亮/熄灭**不改任何布局尺寸**——修掉壳方案「光晕一点亮输入卡缩小 20px」的布局挪动；ACTIVE 为「涟漪 + 扫掠」双动画（三环相位错开 + 绕边 LinearGradientPaint 扫掠高亮 ≈4.8s 一圈）；`RoundedBorderPanel` 还原无光晕职责
- [x] 11.2 状态条视觉修订：GoalStrip 贴输入卡壳 NORTH **全宽色带**（状态色淡染铺满组件宽 + 底部发丝线，圆角外形由 RoundedBorderPanel paintChildren 裁剪统一负责、不自绘圆角——消除「圆角芯片内嵌白底」盒中盒）；`UiTokens.GOAL_STRIP_HEIGHT=28`（body 字号 + 24px 操作钮行高）；操作钮 iconOnly(24) 加 2px 上下边距垫中（修 NORTH 首选高 24 顶贴造成的底部 4px 空洞）、GoalLabel 基线公式同归 28px 色带光学中心；goal 图标 ACTIVE 呼吸（80ms 帧 alpha [0.45,0.90]，⏸/PAUSED/BLOCKED/终态静态，面板隐藏停表保留相位）
- [x] 11.3 对抗评审处置（多维度评审因 429 配额坍缩，test-quality 维度死票按规转人工自查；确认 2 + 未决 11 全部裁决）：已修 8 项——spec.md:70 展开态可用条件补 paused（确认项）；GoalGlowAnimator 零布局语义 javadoc/注释重写（确认项，旧文本描述已废弃的壳层语义）；GoalGlowPainter.dispose 不经 setMode 无边沿回调 → AiChatPanel.cleanup() 补 repaintGlowBand 清残影；GoalStrip.applySnapshot objective 替换时展开体重渲染（P2 功能缺陷：header 已切新摘要、正文停留旧目标；新增用例含计数 bump 不重渲染反向锚）+ applyTheme 展开体重渲染（markdown 字号渲染时烘焙进文档）+ 操作钮垫中与基线修正；GoalGlowPainter.drawRing `arc=ARC+offset*2+2` 严格同心（角弧圆心恒与卡片描边重合；评审原提案 arc=ARC+offset+1 反而破坏互同心，按独立推导修正）；GoalGlowPainterTest OFF 用例由净画布恒真同义反复改为脏画布零接触契约。驳回 1 项——repaintGlowBand 改 4 条边缘窄条：RepaintManager 同组件只存一个脏矩形（多次 repaint 合并回包围盒），优化无效
- [x] 11.4 全量回归：定向套件 111/111（strip 20 / painter 4 / animator 7 / 面板 13 / 事件 6 / publish 8 / loop 17 / pause 10 / tools 26）；`mvn clean test` 单独跑 936 tests / 0 failures / 0 errors / 11 skipped；`mvn clean install -DskipTests` 安装，lib/ext 仅 `jmeter-agent-0.4.2.jar` 最新版
- [x] 11.6 评审续跑处置（429 死亡代理 2026-10-23 补跑收官：34 代理 32 成 / 2 verify:test-quality 再撞 429 按单票保守转人工；确认 6（全 P3）/ 杀 6 / 未决 2，6 条 killed 均为上批修复的过时快照、双验证者实锤「当前代码已含修复」）：已修 6+1 项——GoalStripTest 新增 `collapsedHeightCoversContentNeed` 行高守卫（GOAL_STRIP_HEIGHT 与内容实需零余量耦合的套套逻辑盲区：既有断言两侧读同一常量，钮径/边距单边加大时全绿而生产截断；GoalStrip headerRow 提升字段 + 观测面）、GoalGlowAnimatorTest →OFF 断言消息去壳层残留措辞（"reclaims the margin" → repaint 清残影语义）、design.md D3 hydrate 接受集补 PAUSED（与代码/spec.md:70 同类漏网）、proposal.md Impact 修改清单补 6 漏列文件 + 不受影响剔除 UpdateGoalTool 自相矛盾项、AGENTS.md goal-status-ui 指针改指 changes/ 路径（sync 前悬空 spec 路径）、design.md Non-Goals 终态渐隐交叉引用 D6→D5；另采纳未决 U0 加固——GoalGlowPainterTest d=6 容差断言（≤8/255），静区钉住界从 6.5px（d=7 单元几何上界）收紧到 ~6.1px，环表单边加宽越界时先红。驳回 1 项——未决 U1 角弧同心像素锚：角弧内接于外接矩形、静区不变式与 arc 无关已被直边扫描钉死，arc 回归唯一后果是 ≤0.41px 亚感知环距弓变，像素锚不值维护成本
- [x] 11.5 GUI 手动复验（覆盖 8.3/9.3/10.5 遗留）：①`/goal` 后光环环绕输入卡全周呼吸（四边均可见非仅四角）、状态条 goal 描述完整可读；②展开态下让 Agent 替换目标（create_goal replace）→ 正文跟随刷新不残留旧目标；③⏸ 后光环熄灭 + 状态条转灰 + ▶ 出现，▶ 恢复后呼吸复燃；④终态 ✓/✕ 条 ~1.5s → 渐隐收起；⑤无 goal 时布局与原版完全一致；⑥操作钮与文本在 28px 色带内垂直居中

## 12. GUI 实测两修复（2026-10-24 附记 6：手动冒烟发现，临时可视化复现装置实锚后修复）

- [x] 12.1 终态条文字裁切（图片1）：GoalStrip 终态（COMPLETED/CANCELLED）三钮全隐时 headerRow 首选高塌缩——FlowLayout 首选高忽略不可见子组件塌到边距 4px，GoalLabel 无 UI 委托首选仅 1×1，行高随内容塌陷把状态文字裁成条顶 2px 残迹（Robot 截屏逐行扫描：暗色文字行 539-540）。修复：headerRow 匿名子类覆写 getPreferredSize 地板钉住 `GOAL_STRIP_HEIGHT`（钮显隐与行高正交）。验证：修复后 label.bounds 高 28、文字行 547-557 居中于 539-564 淡染带
- [x] 12.2 展开/收起光晕残影（图片2）：光环画进面板既有静区（页边距 + 输入坞上方空隙）落在所有子组件界外，展开/收起挪动卡片矩形后布局变化的自动脏区域只盖组件自身界内，旧环像素无人清（转录区两侧竖向残条；Robot 实测暴露区 836 残留像素）。修复：AiChatPanel.paintComponent 绘制侧自愈——绘制帧发现卡片矩形相对上一**绘制**帧已变即对旧带补一次 repaint（80ms 动画帧保证及时到达）；`lastPaintedGlowCardRect` 仅 paintComponent 读写（EDT 私有）。ComponentListener/HierarchyBoundsListener 路线实证不可行：组件/层级边界事件经 EventQueue 异步投递，到达时动画帧已更新共享矩形（65 帧捕获 0 次迁移）。验证：修复后暴露区残留 0 像素；矩形稳定帧不产生清理 repaint（防循环）
- [x] 12.3 回归锚：`GoalStripTest.terminalHidesAllButtonsYetKeepsHeaderRowHeight`（终态/取消全钮隐行高仍钉 28）；`AiChatPanelGoalStripTest.paintSelfHealsExposedGlowBandWhenCardRectMoves`（记录型 RepaintManager 断言矩形变化帧补 paint 旧带 bandAround、稳定帧零 repaint）。临时可视化复现装置（Robot 截屏 + printAll 对比 + 像素扫描，类名避让 surefire `*Test`）验证后已删除
- [x] 12.4 全量回归：`mvn clean test` 单独跑 939 tests / 0 failures / 0 errors / 11 skipped；`mvn clean install -DskipTests` 安装，lib/ext 仅 `jmeter-agent-0.4.2.jar` 最新版

## 13. /new × goal 两修复（2026-10-24 附记 7：手动复验发现，Nanobot 源码实读定语义）

- [x] 13.1 PAUSED goal → /new → ▶ 无反应：resetConversation 尾部 `sessionManager.invalidate` 摘除会话缓存后，`resumePausedGoal` 用裸 `sessionManager.get` 恒返 null → 静默 return（无日志、无状态迁移、无续跑）。修复：goal 引擎路径会话访问换 `goalSession`（getOrCreate 触发磁盘重载，goal_state 随头行存续；UncheckedIOException 兜底返 null 静默放弃，对齐面板 resetConversationAny 的 RuntimeException 捕获先例），覆盖 resumePausedGoal/convergeGoalNow/scheduleGoalContinuation 三处。goal 磁盘本体无损：Session.clear 只清消息、saveSession 头行含 metadata（getOrCreate javadoc 明写 reload contract）
- [x] 13.2 ACTIVE goal 回合中 /new → 新会话条亮但无人推进（goal 僵尸）：垂死回合的旧代数续跑片被 epoch 栅栏（正确地）拦下后，全仓无任何机制给 goal 重新点火——续跑唯一触发点挂在垂死回合自己的 finally 上。修复：resetConversation 尾部（invalidate 后、栅栏锁内）`rearmGoalAfterReset`——磁盘重载 goal 仍 ACTIVE 即以翻转后新代数 `enqueueGoalSlice` 发新租约（排队任务取出时复查代数/槽位/goal 活跃性，垂死回合收尾天然先行）；预算耗尽且已交回的 goal 由终结闸拦截（/new 不复活已交回目标）；PAUSED/终态不自动恢复。关闭整合清空腿（archiveSnapshot=false，JVM 退出期）豁免不重火。Nanobot 实读定语义：WebUI new_chat 为全新 chat_id、老会话原封不动（goal 回合继续跑）——「goal 不因新会话停摆」；Nanobot 无 paused 状态（⏸/▶ 为本插件扩展，13.1 无参考可循）
- [x] 13.3 回归锚：`AgentLoopGoalPauseTest.resumeWorksAfterNewResetInvalidatesSessionCache`（缓存摘除窗口下 ▶ 必须经重载恢复 ACTIVE 并投放续跑片——显式 invalidate 重建窗口，不依赖重火恰好暖缓存）；`newResetRearmsActiveGoalContinuationInFreshSession`（ACTIVE → reset → 新会话自动续跑，rounds≥1 + 续跑文本到达 LLM）；`closeConsolidationResetDoesNotRearmGoal`（archive=false 清空 rounds 恒 0 零回合，确定性锚=重火会同步递增 rounds 落盘）。既有锚适配：AiChatPanelNewConversationTest「重置后缓存应保持失效」机制断言改行为契约（缓存可被重火重载回填已清空会话，内容必须为空）——重火无 goal 时也会先 load 再判空，回填是无害副作用
- [x] 13.4 全量回归：`mvn clean test` 单独跑 942 tests / 0 failures / 0 errors / 11 skipped（基线 939 + 新增 3）；`mvn clean install -DskipTests` 安装，lib/ext 仅 `jmeter-agent-0.4.2.jar` 最新版

## 14. ⏸ 即时收敛（2026-10-24 附记 8：手动复验发现「点了没反应，等一会儿才显示暂停」）

- [x] 14.1 根因：⏸ 点击本身未丢（signalCancel 非阻塞：abort 位 + 定向 interrupt + future.cancel 瞬间落地），但 PAUSED 收敛写盘 `executorService.execute(convergeGoalNow)` 排队在单线程执行器的垂死回合任务体之后——中止是协作式的（AgentRunner 检查点在迭代顶/LLM 前后/工具批前后），在跑 LLM 长响应 HTTP 调用或阻塞至完成的工具批（run_test 冒烟可达分钟级）须跑到自然结束才轮到收敛，状态条全程保持 ACTIVE 呼吸。修复：收敛改 `CompletableFuture.runAsync`（commonPool 即时直写，深度提炼同载体先例）；被停回合终态的自动收敛（convergeGoalOnUserStop，外层 finally 全退出路径）保留为第二幂等来源，并兜住迟到 goal 工具写入的再收敛（runner 等批 ⟹ 工具写在收尾前落定 ⟹ 收尾 isActive 门再收敛，无僵尸 ACTIVE 窗口）。enqueueGoalSlice 内「⏸ 收敛与本切片同在单线程执行器上排队」的过时注释同步改为栅栏互斥表述
- [x] 14.2 回归锚：`AgentLoopGoalPauseTest.pauseConvergesImmediatelyWhileTurnHangsInLLMCall`——回合挂死于门控 LLM 调用期间 ⏸，PAUSED 须在门闩放行前即落盘（`release.getCount()==1` 为「回合仍挂起」探针；hasActiveRun 不可作探针——signalCancel 摘槽即 false，本测试第一版即踩此坑）；放行后回合照常经取消链收尾
- [x] 14.3 全量回归：`mvn clean test` 单独跑 943 tests / 0 failures / 0 errors / 11 skipped（基线 942 + 新增 1）；`mvn clean install -DskipTests` 安装，lib/ext 仅 `jmeter-agent-0.4.2.jar` 最新版

## 15. 评审四项修复（2026-10-24 附记 9：docs/agent-sessions/2026-10-24-goal-status-ui-code-review.md 逐项核实后全修）

- [x] 15.1 P1 字符串损坏：CreateGoalTool 回执「done and ve」被误删粘成 donerified（git diff HEAD 实证原文），每次 goal 创建都进模型上下文。已还原为 done and verified；GoalToolsTest.createGoalOnGoalTurnRecordsActiveGoal 断言从 contains("update_goal") 收紧为回执全文 assertEquals（宽断言曾放行损坏）
- [x] 15.2 D12 文档漂移：design.md「PAUSED 按『无注入』处理」与实现（注入宿主暂停指令，Addendum 2 B5）矛盾，已补勘误段（指向 spec delta 与双测试锚，防未来按旧文案误修）
- [x] 15.3 tooltip 冒烟计数补上限口径：`Smoke attempts: N` → `N/上限`（AiConfig.getGoalSmokeLimit），对齐模型侧 statusLines 的 X/上限；GoalStripTest 断言同步收紧为 "Smoke attempts: 1/3"
- [x] 15.4 keyOf 追加 startedAt：replace 逐字同目标且计数归零时内容键相等会短路掉 tooltip 的 Started 刷新；startedAt 只进 tooltip，入键仅让 replace 类离散事件多走一次 update，无高频代价
- [x] 15.5 回归：定向 GoalToolsTest 26 + GoalStripTest 22 全绿；全量 `mvn clean test` 943/0/0/11（首轮 TranscriptCardsTest 一项布局断言偶发失败，单独复跑 11/11 绿后全量复跑通过——字体度量偶发，与本四修复无接触面）；`mvn clean install -DskipTests` 安装，lib/ext 仅 `jmeter-agent-0.4.2.jar`

> 2026-10-24 归档前确认：6.2/8.3/9.3/10.5/11.5 全部手动 GUI 冒烟项经用户实测通过后勾选（本会话逐轮实测：状态条/光晕/终态链/⏸/▶/展开收起/残影修复//new 交互/tooltip 口径/⏸ 即时收敛，含 2026-10-24 三轮问题修复的复验）。
