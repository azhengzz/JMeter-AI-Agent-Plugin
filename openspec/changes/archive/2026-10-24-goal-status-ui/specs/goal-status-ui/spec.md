# Spec Delta

## Purpose

/goal 持续目标的 GUI 呈现契约：goal 状态变更即时通知到聊天面板，以输入卡内状态条、卡内内联展开面板与呼吸光晕呈现 goal 生命周期（活跃/受阻/终态），并保证 goal 跨会话重置存活与懒创建/重启后的状态恢复。

## ADDED Requirements

### Requirement: goal 状态变更通知

每次 goal 状态的持久化变更（create、replace、终结 complete/cancel/block、冒烟尝试计数递增、续跑轮次递增、验证证据变更、清除）SHALL 在写入的同一动作内即时向进程内订阅者发出通知，通知载荷为不可变快照（status、ui_summary、objective、recap、冒烟尝试计数、续跑轮次、会话键）；goal 被清除时通知载荷 SHALL 为空（null 语义）。通知回调的线程 SHALL NOT 有任何保证（可能为工具线程、agent 回合线程或 EDT），订阅者 SHALL 以恰一次的 EDT 编组消费通知且 MUST NOT 在回调内取得任何锁。单个订阅者回调抛出异常 MUST NOT 阻断其它订阅者收到通知。通知 MUST NOT 依赖任何回合的存在（会话级事实，非回合呈现）。

#### Scenario: create_goal 触发即时通知

- **WHEN** 回合内 create_goal 工具执行成功、goal 状态被持久化
- **THEN** 订阅者在同一动作内收到快照通知（status=active、objective 与 ui_summary 与工具入参一致），无需等待回合终态

#### Scenario: 冒烟计数递增触发通知

- **WHEN** run_test smoke 执行使 goal 的冒烟尝试计数递增并持久化
- **THEN** 订阅者收到快照通知，冒烟尝试计数为新值

#### Scenario: 订阅者异常隔离

- **WHEN** 某订阅者在通知回调中抛出异常
- **THEN** 其它订阅者仍收到该次及后续通知，goal 持久化不受影响

#### Scenario: goal 清除通知

- **WHEN** goal 状态以空值被持久化（清除）
- **THEN** 订阅者收到空载荷通知，进程内最后快照缓存同步清除

### Requirement: goal 状态条渲染

goal 处于 active、blocked 或 paused 时，聊天面板输入卡内顶部（选中上下文条之上）SHALL 显示一条状态条：「Goal · {ui_summary}」；ui_summary 缺失时 SHALL 降级显示 objective 的首行。状态条 SHALL 以状态色的半透明淡染<b>全宽色带</b>呈现——贴输入卡顶铺满组件宽度，与卡身连成一体（圆角外形由输入卡外壳的圆角裁剪与描边统一负责，状态条自身 MUST NOT 再自绘圆角描边形成「盒中盒」），底部以状态色发丝线锚定与卡内其余行的分界，且按状态着色、明暗主题下均可读：active 为信息蓝、blocked 为警示琥珀（blocked 在 UI 层为常驻非终态，MUST NOT 不可见）、paused 为中性灰去强调（标签含 paused 语义，如「Goal paused」）。状态条悬浮 tooltip SHALL 展示冒烟尝试计数、续跑轮次（含上限口径，如 2/5）与开始时间，并随计数更新同步刷新。状态条右端 SHALL 提供暂停/恢复互斥图标钮与展开开关：⏸ 仅在 active 时可见、▶ 仅在 paused 时可见（见「暂停与恢复操作」需求）。状态条的内容更新（含冒烟计数、续跑轮次等高频路径）MUST 仅触发重绘，MUST NOT 触发布局重算，MUST NOT 扰动 JMeter 编辑器的焦点与单元格编辑状态；仅状态条的离散出现/消失/展开/收起边界 MAY 触发布局重算。无 goal（隐藏态）时输入卡布局 MUST 与变更前完全一致（零布局扰动）。

#### Scenario: goal 活跃时状态条出现

- **WHEN** goal 快照通知到达且 status=active
- **THEN** 输入卡内顶部出现蓝色「Goal · {ui_summary}」状态条（此前输入卡无该条且布局不变）

#### Scenario: blocked 状态琥珀呈现

- **WHEN** goal 快照通知到达且 status=blocked
- **THEN** 状态条保持可见并切换为警示琥珀着色，不消失、不渐隐

#### Scenario: paused 状态灰调呈现

- **WHEN** goal 快照通知到达且 status=paused
- **THEN** 状态条保持可见并切换为中性灰着色与 paused 标签，呼吸光晕停摆
- **AND** 状态条右端出现 ▶ 恢复钮（⏸ 消失）

#### Scenario: Stop 打断后状态条自动转灰

- **WHEN** goal active 的回合被用户 Stop 打断（auto-converge 收敛为 paused）
- **THEN** 状态条经变更通知自动切换为 paused 灰调呈现，无需用户任何额外操作

#### Scenario: 冒烟计数刷新不扰动编辑器

- **WHEN** goal 活跃期间冒烟尝试计数递增（高频通知到达）
- **THEN** tooltip 中计数刷新、状态条文本不变时无布局重算，JMeter 编辑器中正在进行的单元格编辑不受影响

#### Scenario: ui_summary 缺失降级

- **WHEN** goal 快照的 ui_summary 为空且 status=active
- **THEN** 状态条显示 objective 首行（超宽时按像素截断加省略号），不显示空白

### Requirement: 卡内内联展开面板

状态条 SHALL 提供展开/收起开关（chevron）。展开时完整目标 SHALL 在输入卡内就地展开呈现（输入卡变高、聊天转录区相应让位，非悬浮浮层）：内容为完整 objective，按 markdown 渲染（ui_summary 已在收起态 header 常驻，展开正文不重复呈现）；正文区可滚动，展开高度 SHALL 以聊天面板高度的约 40% 为上限（内容不足时收缩适配）；再次点击收起。展开态 SHALL 仅在 goal 为 active/blocked/paused 时可用；goal 转入终态时展开体 SHALL 自动收起。展开正文 SHALL 为纯阅读区（不可编辑、不进入 Tab 焦点链）。

#### Scenario: 展开渲染完整目标

- **WHEN** goal 活跃且用户点击状态条 chevron
- **THEN** 输入卡内展开面板，markdown 呈现完整 objective（ui_summary 已在收起态 header 常驻，正文不重复），转录区相应变矮

#### Scenario: 展开高度上限

- **WHEN** objective 很长（数倍于面板高度）且面板处于展开态
- **THEN** 展开体高度不超过聊天面板高度的约 40%，正文区内部滚动

#### Scenario: 收起

- **WHEN** 展开态下再次点击 chevron
- **THEN** 展开体收起，输入卡恢复收起高度

#### Scenario: 终态自动收起

- **WHEN** goal 在展开态下转入 complete/cancel 终态（终态展示链启动）
- **THEN** 展开体自动收起（转入 blocked 时展开态保持可用，不自动收起）

### Requirement: 呼吸光晕与终态熄灭

goal 为 active 时，输入卡外围 SHALL 呈现环绕全周的「呼吸 + 扫掠」信息蓝光环——缓慢呼吸的信息蓝光环（周期性透明度起伏，贴卡缘最亮、向外逐级减淡、各环相位错开呈涟漪，四边均可见），叠加一道绕卡片匀速旋转的高亮扫掠弧（头亮尾隐，如流光绕框巡游）；光环 SHALL 绘制在卡片区域之外的既有静区（面板页边距与输入坞上方空隙），MUST NOT 覆盖状态条文本等卡内任何内容，且 MUST NOT 改变任何布局尺寸——光晕点亮与熄灭时输入卡、转录区与页边距 SHALL 逐像素恒定（零布局）；同时状态条的 goal 图标 SHALL 呈现同节奏的呼吸明暗。光晕动画 SHALL 仅在聊天面板可见时运行，面板隐藏时暂停、恢复可见时继续。goal 为 blocked 时 SHALL 呈现静态警示琥珀环（无动画、无扫掠）。goal 终态（complete/cancel）或清除时光晕与图标呼吸 SHALL 一并熄灭。goal 终态时状态条 SHALL 短暂（约 1.5 秒）呈现终态着色条（complete 为成功色 + 完成标记、cancel 为错误色 + 取消标记），随后以约 200ms 渐隐收起，渐隐期间 SHALL 保持占位高度不变（渐隐结束才移除占位）。终态展示期间（含渐隐）收到 active 快照（复活或新 goal）时 SHALL 立即取消全部终态计时并直接回到 active 呈现。

#### Scenario: active 呼吸光晕

- **WHEN** goal 快照 status=active 且聊天面板可见
- **THEN** 输入卡外围蓝色光环环绕全周呼吸（四边均可见，非仅四角，光环不遮卡内内容、状态条文本完整可读），goal 图标同节奏呼吸；面板隐藏后动画停止、重新可见后恢复

#### Scenario: blocked 静态琥珀环

- **WHEN** goal 快照 status=blocked
- **THEN** 输入卡外围为恒定琥珀环，无动画帧产生

#### Scenario: paused 图标呼吸停止

- **WHEN** goal 快照由 active 切换为 paused
- **THEN** 状态条 goal 图标立即恢复静态（呼吸表停、全色显示）

#### Scenario: 终态展示链

- **WHEN** goal 快照 status=completed
- **THEN** 状态条切换为成功色完成标记条并保持约 1.5 秒，随后约 200ms 渐隐（占位高度不变），结束后状态条移除、光晕熄灭

#### Scenario: 终态期间复活中断

- **WHEN** 终态条展示或渐隐进行中收到 status=active 的新快照
- **THEN** 终态计时与渐隐立即取消，状态条立即回到 active 蓝色呈现与呼吸光晕

### Requirement: 跨会话存活与懒创建恢复

会话重置（/new、面板 "+" 清空、关闭整合清空）SHALL NOT 清除或复位 goal 状态条——goal 比会话重置存活更久（与 goal 状态机的持久化语义一致，active 与 paused 均存活）。聊天面板为懒创建（可能晚于 goal 建立才首次打开）时 SHALL 恢复呈现当前 goal：进程内已有快照缓存时从缓存恢复，否则从会话持久化状态恢复（含 JMeter 重启后仍为 active/blocked/paused 的 goal）。懒创建或恢复时的初始呈现 SHALL 仅接受 active/blocked/paused 状态：终态（completed/cancelled）的历史状态 MUST NOT 回放终态展示链（终态反馈已由聊天流的 recap 输出承载）。面板 SHALL 忽略会话键不属于当前实例会话的 goal 通知。

#### Scenario: /new 后状态条存活

- **WHEN** goal 活跃、状态条可见，用户执行 /new 会话重置
- **THEN** 聊天转录清空，goal 状态条保持可见且内容不变

#### Scenario: 重启后恢复

- **WHEN** goal 为 active 或 paused 时退出 JMeter，随后重新启动并打开聊天面板
- **THEN** 状态条从会话持久化状态恢复呈现（active 蓝色 + 呼吸光晕，或 paused 灰调 + ▶ 恢复钮）

#### Scenario: 终态历史不回放

- **WHEN** goal 已终结（面板尚未打开或进程重启后），聊天面板随后创建/打开
- **THEN** 状态条不出现（无终态条回放），输入卡保持无 goal 布局

#### Scenario: 会话外通知过滤

- **WHEN** 收到会话键不属于当前实例会话的 goal 通知
- **THEN** 面板不因此变更状态条呈现

### Requirement: 暂停与恢复操作

状态条 SHALL 提供互斥的暂停/恢复操作钮：⏸ 仅在 goal 为 active 时可见可点，▶ 仅在 goal 为 paused 时可见可点。**暂停（⏸）**：点击后系统 SHALL 将 goal 状态直写迁移为 paused（经状态持久化与变更通知；MUST NOT 构造用户消息进入回合、MUST NOT 经模型工具）；若该会话存在活跃回合，SHALL 同时以与 Stop 按钮相同的用户停止链路取消该回合——聊天流照常渲染终止行，输入区停止按钮经正常终态事件链归位为未运行态。**恢复（▶）**：点击后系统 SHALL 将 goal 直写迁移回 active，并自动投放一个内部续跑片继续该 goal（MUST NOT 渲染为用户发言），续跑预算 SHALL 续算不重置。

#### Scenario: 空闲时暂停

- **WHEN** goal active 且无回合运行，用户点击 ⏸
- **THEN** goal 迁移为 paused（持久化+通知），状态条转灰、光晕停摆，无任何回合被开启

#### Scenario: 忙期暂停走用户停止链路

- **WHEN** goal active 的回合运行中，用户点击 ⏸
- **THEN** goal 迁移为 paused，在跑回合以用户停止链路被取消
- **AND** 聊天流渲染终止行，输入区停止按钮归位为未运行态
- **AND** 状态条转灰且 ▶ 出现

#### Scenario: 恢复投放续跑片

- **WHEN** goal paused，用户点击 ▶
- **THEN** goal 迁移为 active（持久化+通知），系统投放内部续跑片继续该 goal
- **AND** 聊天流不出现用户发言行，续跑预算续算（不重置）

#### Scenario: 操作钮互斥

- **WHEN** goal 分别处于 active / paused / blocked 状态
- **THEN** 状态条右端分别只出现 ⏸ / ▶ / 无操作钮
