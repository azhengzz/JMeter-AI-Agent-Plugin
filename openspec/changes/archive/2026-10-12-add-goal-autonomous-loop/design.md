# Design

## Context

见 proposal.md（Why：闭环断在改与验之间）。当前事实约束：`CommandRouter` 的 prefix 层已内建带参命令机制（`ctx.setArgs`）；dispatch 返回 null 时原始消息进普通回合——但没有「命令向回合透传标记」的先例；会话 jsonl 首行为 metadata 头（含空 `metadata` 对象），`Session.clear()`（/new）只清消息；`ContextBuilder` 有现成 Runtime Context 标签机制（`_runtime_context` 剥离标记，@-mention 与早落盘共用）；注入队列条目带 announcement 标记（不渲染 You 行）；JMeter `Start` action 的 VALIDATE_TG 验证模式经 `testplan_validation.*` 属性族强制 1 线程 × 1 迭代、克隆树运行、断言保留定时器剔除、只跑选中线程组、启动前触发 SAVE。参照实现：Nanobot `/goal`（cmd_goal 打标记返回 None 放行进普通回合；goal_state 存 session metadata；两层延续：回合内 nudge + 跨回合续跑；完成判定纯模型自评——本设计在其上叠加确定性验证护栏）。

## Goals / Non-Goals

**Goals:**

- 完整移植 Nanobot goal 骨架（命令标记、状态机、元数据工具、权限门、两层延续），叠加本插件特有的确定性验证层（结构化 PASS/FAIL、冒烟轮次上限、complete 证据校验）
- 对既有能力零侵入复用：agent-turn-events（事件流原样承载续跑/nudge 回合）、early-user-message-persist（续跑触发消息走既有早落盘管线）、per-instance-session（goal 状态落 jsonl 头行既有 metadata 对象）
- 所有可调参数走 JMeter 属性惯例（`jmeter-ai-sample.properties` 登记）

**Non-Goals:**

- goal 跨 JMeter 重启存活——per-instance 会话键每次启动新建（既有架构语义），goal 寿命 = 实例内会话寿命；跨重启需 workspace 级存储，明确不做
- goal 专用 UI 组件（状态条/指示器/阶段标题行）——goal 状态经聊天文本与 recap 传达
- 写操作端点白名单、执行前确认门——用户显式接受 Agent 完全自主判断
- 完整运行（用户配置规模）的自动化——冒烟之外的真实规模运行仍由用户发起
- 对非 LoopController 线程组（stepping TG 等插件 TG）的冒烟规模强制——JMeter 验证模式不覆盖，标注为已知边界

## Decisions

### D1. /goal 挂载：prefix 命令 + 回合附件透传

`BuiltinCommands` 同时注册 `exact("/goal")`（无参 usage）与 `prefix("/goal ", handler)`。busy 守卫：handler 检查会话活跃回合（`TurnRegistry.hasActiveRun`），忙则返回提示字符串（命令结果事件）。通过校验后 handler 向 `CommandContext` 写入 goal 请求附件（goalRequested=true + 原始文本），返回 null——Phase 3 回合体在 dispatch 返回 null 后读取该附件，置入 `AgentRunSpec`（新增 goalRequested 字段），原始 `/goal <需求>` 文本照旧作为 userMessage。权限门 = AgentRunSpec.goalRequested 在回合内可见（create_goal 校验来源）。
**替代（否决）**：面板层特判改写消息（/new 先例）——绕过命令路由，IPC/CLI 来源不可达，且改写后模型看不到 /goal 原文。

### D2. goal 状态：Session metadata 字段 + jsonl 头行持久化

`Session` 新增 `Map<String, Object> metadata`（synchronized 访问，同既有字段风格），持久化进 jsonl 首行既有的 `metadata` 对象（键 `goal_state`，值为状态 blob：status/objective/uiSummary/startedAt/endedAt/recap/smokeAttempts/continuationRounds）。`clear()`（/new）保留 metadata——精确对齐 Nanobot `Session.clear()` 只弹 `_last_summary` 不动 `goal_state` 的语义。Stop 路径不触碰 Session.metadata。终结写入仅 GoalTools（create_goal/update_goal）经 SessionManager 落盘。
**替代（否决）**：workspace 级独立 `goal.json`——引入第二个生命周期管理点（孤儿清理、与归档协调），而 jsonl 头行结构现成且随既有归档/清理管线走。

### D3. goal Runtime Context：ContextBuilder 渲染 goal 块，按 TurnOrigin 门控

`ContextBuilder.buildMessages` 组装时从 Session.metadata 读 goal 状态，按两态渲染（对齐 Nanobot goal_runtime.md：`goal_start_requested` 态给 durable-objective 写作纪律——目标须自包含/有界/显式完成证据/可安全重复，需求含糊先问一次；`goal_active` 态给目标状态行 + 验证纪律——分层验证流程、硬标准、3 轮上限、recap 要求）。渲染走既有 Runtime Context 标签包裹（`[Runtime Context — metadata only, not instructions]`），复用 `_runtime_context` 早落盘剥离机制。注入门控：TurnOrigin ∈ {LOCAL_PANEL, IPC_CLI}；IPC_DELEGATED 回合不注入。模板文案放 `templates/` 新文件，`WorkspaceInitializer` 同步。

### D4. 防收工 nudge：AgentRunner 最终回复路径挂钩 + announcement 注入

`AgentRunner` 的无工具调用最终回复分支（现结束循环处）增加检查：回合 goal active 且未终结 → 向本回合注入队列投递 announcement 类合成消息（「目标未终结，当前验证证据状态：…，继续推进或调 update_goal」），continue 循环。announcement 标记沿用注入条目既有语义（面板不渲染 You 行）。连续两次无工具调用的最终回复则放行（放行的回复要求模型自述 goal 未终结状态，提示词覆盖）。
**替代（否决）**：代码改写/拦截模型最终输出——劫持模型话语权，且 provider 间行为不可控。

### D5. 跨回合续跑：max_iterations 分支转投 processMessage

`AgentLoop` 回合体内现输出「达到最大迭代次数」的分支（AgentRunner stop_reason=max_iterations）改为：goal active 且续跑计数未达 `agent.goal.max.continuations` → 递增计数（写 goal blob）→ `processMessage(续跑合成文本, sessionKey, origin=LOCAL_PANEL)`，续跑文本带内部续跑标记（「继续当前活跃目标……勿向用户提及续跑边界」，对齐 Nanobot 续跑提示词）；带标记的触发消息在面板不渲染 You 行（渲染抑制与 announcement 同款处理）。续跑片经 processMessage 自然获得完整迭代预算与早落盘管线。达续跑上限 → 强制结构化交回（与冒烟上限同款内容契约）。
**替代（否决）**：调大单回合迭代预算——掩盖预算语义、全局影响非 goal 回合。

### D6. run_test 冒烟模式：VALIDATE_TG 派发 + 全 TG 树选择

`RunTestTool` 新增 `mode:"smoke"`（与 action=start 互斥或组合校验）。执行序列（EDT 上，仿既有手工注入模式）：① 经既有 properties 注入通道设 `testplan_validation.*`（默认值即 nb_threads=1/number_iterations=1，显式设一遍防用户环境覆盖）；② 保存当前 GUI 树选择 → 程序化选中全部线程组节点（VALIDATE_TG 只跑选中 TG 且无选中静默不跑，必须设）；③ 注入 AgentResultCollector + `reset(AGENT)` + `armForStartReinject`；④ 派发 `VALIDATE_TG`；⑤ 恢复原树选择；⑥ 复用既有启动确认轮询（testStartTime>0）。引擎运行于验证模式克隆树——用户计划元素不被修改。
**替代（否决）**：update 线程组改 1×1 跑完恢复——中断漏恢复脏配置、树变更污染撤销栈；`__P()` 参数化——字面量规模不可运行时覆盖（已证实）。

### D7. 断言采集：SampleSnapshot 增字段 + 详情块输出

`AgentResultCollector.sampleOccurred` 读 `SampleResult.getAssertionResults()`，快照增断言结果列表（name/failureMessage/isError，超长复用 `truncate`）；`GetTestResultsTool` 详情块对存在断言失败的 sample 追加断言失败段（断言名 + 失败消息）。success/errorCount 语义不变（现状已含断言失败影响）。

### D8. validate_test_plan：新只读并发安全工具

三类检查：① 结构完整性（线程组存在、元素类经 ELEMENT_CLASS_MAP 可解析、关键必填内容非空，如 JSR223 脚本）；② schema 回扫（ComponentValidator 对树上元素全量重校验，无 schema 跳过该元素）；③ 逻辑 lint（循环控制器缺条件等结构性错误）。输出结构化 Markdown（整体 PASS/FAIL + 三类分组错误清单，每条含元素定位/问题/修复提示）。树访问遵守既有 Tool EDT 线程契约（JMeter GUI EDT threading 模式）；只读、并发安全白名单。

**刻意不查（2026-09-30 用户复审裁定，替代原设计的「变量引用分析」项）**：未定义 `${var}` 引用与 HTTP 采样器目标（域名/绝对路径）。变量定义位置不可静态穷尽——JSR223 脚本、后置处理器均可在运行时造变量，代码无法枚举全部定义源；HTTP 请求默认值在运行时合并进采样器，采样器自身不设域名是合法形态。此两类检查假阳性会伪 FAIL 卡死 goal 判定，故静态层放弃、由冒烟运行（零 error 硬标准）仲裁。

### D9. 护栏证据与计数：goal blob 单一来源

goal blob 内含 `smokeAttempts` / `lastStaticPassAt` / `lastSmokePassAt`。`validate_test_plan` PASS 与冒烟零 error（RunTestTool smoke 完成后读采集器 error 计数）时经回合携带的 goal 访问器回写证据；`smokeAttempts` 在每次 smoke 派发时递增。工具层拿 goal 访问器的通道 = ToolRegistry 既有 AgentRunContext 搬运机制。`update_goal(complete)` 校验：证据齐备（静态 PASS + 最近冒烟 PASS）或 smokeAttempts ≥ 3，否则返回错误说明缺口。

### D10. 提示词与登记

新模板 `templates/goal.md`（D3 两态渲染源，Nanobot goal_runtime.md 中文化适配）；`templates/AGENTS.md`、`templates/TOOLS.md` 增补 goal 工作流与验证纪律条目；`cmdHelp` 与 `CommandIntellisenseProvider` 加 `/goal` 条目；`jmeter-ai-sample.properties` 加 `agent.goal.max.continuations=5`（配置默认值同步清单 6 处）。

## Risks / Trade-offs

- **[R1] VALIDATE_TG 的 save-before-run 剥离/重注入与 armForStartReinject 的耦合未实测**（现机制锚定 ACTION_START 语义，VALIDATE_TG 同走 Start.doAction 的 popupShouldSave）→ 实现期首个手动验证场景即覆盖此路径；若重注入不生效，回退方案：smoke 模式下临时设 `save_automatically_before_run=false` 并在工具输出标注。
- **[R2] VALIDATE_TG 无选中 TG 时静默不跑** → D6 步骤 ② 程序化全选 + 既有 testStartTime 轮询兜底确认；轮询失败时工具返回明确错误。
- **[R3] 非 LoopController 插件线程组不被验证模式限规模** → 冒烟输出标注该边界；后续可按 TG 类型过滤（本期 Non-Goal）。
- **[R4] 计数/续跑/证据的状态交互复杂**（跨片累计、replace 重置、Stop 中断后续跑）→ 单一来源（goal blob）+ 全部经访问器读写；tasks 中安排状态机专项单测。
- **[R5] goal 残留体感**（/new 后仍 active，用户只想要干净问答）→ goal 块提示词允许模型先回应再继续；连续两次无工具放行兜底；用户可随时让模型 update_goal(cancel)。
- **[R6] 续跑链在 provider 间行为差异**（长回合/token 上限）→ 续跑是代码侧机制（provider 无关）；提示词模板 provider 中立；`agent.goal.max.continuations` 提供逃生阀。
- **[R7] announcement/内部标记的 You 行抑制在续跑场景的复用点未定**（注入队列 vs Phase 3 直投）→ 实现期定，两路径渲染语义已在 ipc-agent-turn-gui-display 建立先例。

## Migration Plan

纯增量变更：新命令、新工具、jsonl 头行 `metadata` 对象写入 `goal_state` 键（旧版本插件读到忽略，向后兼容；旧会话文件无该键按无 goal 处理）。无存量数据迁移。回滚 = 注销命令/工具注册与提示词条目，goal blob 随会话文件自然归档消亡。

## 对抗审查落地修正（2026-09-30，72 代理工作流 27 项 CONFIRMED 修复要点）

- D6 修正：VALIDATE_TG 派发必须用 `doActionNow`（`actionPerformed` 是 invokeLater 异步，
  finally 的树选择恢复会先于 Start.doAction 读选择执行——冒烟会只验证用户残留选中集）；
  未保存计划先指向 workspace `autosave/goal-smoke-*.jmx` 绕开 popupShouldSave 的模态弹窗
- 已知边界：`TreeClonerForValidation` 的 `testplan_validation.*` 为类加载期 static final——
  冒烟的属性强制仅对进程内首次验证前生效（JMeter 默认本就 1×1）；派发后恢复原属性值
- 续跑调度三层守卫：runner 仅在未中止时打 max_iterations 标；AgentLoop 检查
  !delegated && !abortFlag && 代数未翻转；排队点移到 republishLeftovers 之后（防续跑
  文本落入垂死注入队列被渲染成 "[Injected] You:"），排队前复查代数
- `/goal <需求>` 回合不再标记为命令回合（面板可见可取消）；Phase 2 忙期 TOCTOU 下
  cmdGoal 恒返回非 null（防权限标记随注入丢失）
- 委派回合禁 update_goal（宿主 goal 归宿主）；goal 证据通道对 subagent 键旁路且用
  get 不 getOrCreate（防孤儿会话文件）；冒烟超时清冒烟证据（证据口径=最近一次检查）
- ValidateTestPlanTool：JSR223 filename、TG 主控制器 0 循环、HTTPSampler.implementation
  schema 补录；断言采集递归 subResults（事务控制器 parent 模式）；注入合并保留 goal 块；
  goal.md 模板逐回合直读不缓存。
  注：该批次中「HTTP Defaults 域名合并语义 / UserParameters / CSV 头部 / refname 后缀变量
  （_matchNr/_g<n>）与含点 refname」五项属变量引用分析与 HTTP 目标检查的精度补丁，
  已随后续用户复审整体移除该两项检查而一并废弃（见 D8 刻意不查）

## 对齐修正：goal 提示的指令/数据分离（2026-10-09，用户裁定对齐 Nanobot）

初版把 goal 的纪律文本与状态数据一起塞进统一的 `[Runtime Context — metadata only, not
instructions]` 包裹内部——标签声明与内容性质矛盾（纪律是指令）。对齐 Nanobot
`goal_runtime.md` 的做法：

- **guidance（指令）**进独立 `[Goal Runtime Guidance — host instructions]` 包裹（标签由
  `GoalRuntime.buildGoalContext` 代码包裹，模板文件只存内容——防 workspace 旧模板缺标签静默退化）
- **状态数据**（objective/冒烟计数/证据，代码构建的 statusLines）进标准 Runtime Context
  metadata 包裹，尾随 guidance 之后；消息整体以 `[/Runtime Context]` 收尾
- 剥离契约随之泛化：`stripRuntimeContext` / `runtimeContextMarker` 从「最后一个 RC 块」
  改为「从结尾逐块回退认领」（RC 块与 guidance 块任意尾随组合），标记 suffix 覆盖整个
  复合块——持久化/公共视图/记忆整合的既有消费方零改动共享新语义
- 消息形态：`用户文本 → 环境 RC 块 → guidance 包裹 → 状态 RC 块`（Nanobot 顺序：
  guidance 在前、state 在后，一致）

## 缺陷修复：/new 后 goal 丢失（2026-10-10，实测发现）

**症状**：goal 活跃时 /new，新会话首条 user 消息不带 goal 复合块；jsonl 头行 `metadata:{}`、
`created_at` 被刷新为 /new 后首条消息时刻。

**根因**：重置核心的第三步 `sessionManager.invalidate()`（摘缓存）与 `getOrCreate` 的实现
矛盾——invalidate 的 javadoc 声称「Next getOrCreate() will reload from disk or create fresh」，
但 getOrCreate 缓存未命中时只有 `new Session(key)` 全新建对象（无盘加载路径），且立即
`saveSession` 用空头行**覆写盘文件**，把 resetConversation 刚写下的 goal_state 不可逆销毁。
此前无感：/new 后历史本就为空、无跨重置会话级状态；goal 是第一个消费者（也是
2026-10-10 早上指出的「缺 /new-中断-goal-回合端到端测试」缺口 Exact 命中处）。

**修复**：`getOrCreate` 缓存未命中时先 `readSessionFile`（复用启动扫描的容错加载：
逐行容忍撕裂、还原 createdAt/metadata/messages，头行 key 与请求 key 不符回落新建），
文件存在即重载、真正的新键才新建。对齐 Nanobot——其 `Session.clear()` 后会话对象
留缓存、metadata 原样，下一条消息照常注入 goal。回归钉定：
`goalSurvivesInvalidateAndGetOrCreateReload`（管理层）+
`goalSurvivesNewAndInjectsIntoTheNextConversation`（loop 层 /new 全链）；
`atomicReplace_shorterResave` 的构造手段同步改为真实重置序列（invalidate→重载→clear）。

## 第二轮全局对抗审查落地修正（2026-10-11，8 维度工作流 + 逐发现反驳式验证）

两轮独立验证（恢复运行因限额完整重跑验证层，等同双盲复核）合计 15 项 CONFIRMED /
5 项 REFUTED / 0 死票；其中 2 项两轮裁决分裂，人工仲裁后均维持 REFUTED：
- 冒烟预算执行点强制——spec「被拒绝**或**按交回语义处理」为显式或，D4 刻意把护栏
  全部放在收工分支并明文否决代码拦截模型行为，计数+交回切换+complete 门即设计选定
  的强制形态；
- 空白 WhileController 条件报错——JMeter 源码语义 blank=仅失败退出，在 goal 关心的
  全通过域内确会无限循环挂死阻塞冒烟；D8/spec 明文列为逻辑 lint 检查族，测试双向钉定
  （blank→错误、显式条件→通过）是自觉契约。

去重后实修 11 项（回归钉 7 条，全量 757/0/0）：

1. **续跑调度三重围栏**（teardown 尾窗）：代数复查+goal 回写+排队整体入
   `resetFenceLock`（对齐 republishLeftovers）；abortFlag 在排队点复查（Stop 落在
   [运行返回→终态已发] 尾窗不再漏排续跑）；排队任务取出时栅栏内二次复查代数。
2. **续跑直投 startTurn**：不再经 processMessage（Phase 2 会把续跑文本并进并发后继
   回合的注入队列，以 "[Injected] You:" 泄漏渲染）；republishLeftovers 同款直投。
3. **头行时间戳容错**：created_at/updated_at 解析守卫 DateTimeParseException（对齐
   消息时间戳既有守卫）——否则异构头行把 getOrCreate 重载路径永久楔死。
4. **读失败≠无头行**：readSessionFile 读 IOException 改抛 UncheckedIOException，
   getOrCreate 响亮失败绝不新建空会话覆写盘面（瞬时锁文件不再销毁消息+goal_state）；
   启动扫描容忍跳过。
5. **nudge 交回条件收紧**：预算耗尽**且证据缺失**才切结构化交回——第 3 次冒烟+静态
   双 PASS 的 goal 收普通 nudge（「still failing」口径对证据已齐者是误报）。
6. **零样本冒烟不判 PASS**：`samples>0 && errors==0` 才是硬标准；零样本输出显式
   FAIL 说明并清陈旧 smoke 证据。
7. **委派回合 goal 写入门**：countSmokeAttempt/recordSmokeEvidence/recordStaticEvidence
   补 DelegationGuard 早退；create_goal 补 update_goal 同款拒绝。
8. **末迭代终结 recap**：goal 在末迭代被 update_goal 终结时，终态换 "(Goal <status>.
   Recap: …)" 而非通用迭代上限失败文案（回合起点活跃+结束后已终结的对比判定）。
9. **冒烟 autosave 解除劫持**：未保存计划重指的 autosave 路径在 finally 恢复原关联
   （同步 SAVE 已落盘快照，只恢复「未保存」语义与 Save As 行为）。
10. **启动确认轮询竞态**：采集器已记录「结束且不在跑」同样视为已启动——超短冒烟
    跑完（endTest 清零 testStartTime）不再误报 "Test did not start"。
11. **宿主合成消息归档跳过**：goal nudge/交回/续跑触发的 USER 合成文本挂
    `_host_synthetic` 标记（jsonl 顶层布尔，往返还原），MemoryConsolidator 公共视图
    整条跳过——宿主指令不再作为用户话语进 MEMORY/HISTORY；续跑触发经
    AgentRunSpec.hostSyntheticTrigger 在早落盘点挂标（终局保存按早落盘去重，标记版
    即终版）。goalContextFor 注入合并改为与回合起点同构（/goal 首迭代请求态 guidance
    不再丢失）。

## 第三轮修复：/code-review 十维度审查落地（2026-10-11 晚，20 项存活裁决）

十维度代理审查（逐行扫描/移除行为审计/跨文件追踪/Java 陷阱/包装正确性/复用/效率/
简化/高度/规范）逐项人工复核后 20 项存活（17 CONFIRMED / 3 PLAUSIBLE），当日修复
13 项正确性/健壮性（全量 774/0/0，+17 回归钉），2 项设计取舍待定，余为清理项：

1. **冒烟 Map.of() 崩溃（P0）**：normalizeRunProperties(null) 返回不可变 Map，冒烟
   testplan_validation 强制 put 必抛 UnsupportedOperationException——GUI 冒烟腿自
   09-30 属性强制引入起全程不可用（单测被 GUI 守卫挡在 put 之前，真实冒烟又从未
   跑过）。null 分支改返回可变 LinkedHashMap。
2. **续跑注册击穿后继回合**：收尾窗口内后继回合已接管槽位时，续跑任务 startTurn
   的无条件 register 会 latest-wins 击穿其条目（Stop/注入路由错位）。排队任务栅栏
   内补 hasActiveRun 让位守卫——单线程执行器保证此刻自家条目必已摘除，为真即真有
   后继，放弃续跑语义无损。
3. **Forever 循环误报**：loops==0 静态检查不查 continue_forever——合法浸泡配置
   （Forever 勾选 + 存储 loops=0）被判 FAIL 卡死 goal。两分支补 forever 豁免。
4. **冒烟收养用户 GUI 运行**：guard→派发窗口内用户运行抢引擎后，启动确认后校验
   采集 provenance==AGENT，否则明确报错——不当作冒烟判定、不计预算、不盖证据。
5. **前代引擎迟到事件幻影判定**：采集器引入运行代数（reset 翻新、实例构造捕获
   绑定代、testStarted/testEnded/sampleOccurred 写点校验）——垂死引擎的迟到事件
   整体丢弃，不伪造「已结束未在跑」启动启发式、不污染统计、不摘当前收集器节点。
6. **autosave 预设失败改拒跑**：原先 catch 只 log 仍派发 → popupShouldSave 弹无人
   应答的 should_save 模态框卡死 EDT；现在响亮报错拒绝进入该分支。
7. **监听器文件存在人闸预检**：resultcollector.action_if_file_exists 生效动作会弹
   模态框（默认 ASK，AbstractAction 类加载期冻结、运行时无法定向关闭）且计划含
   输出文件已存在的 ResultCollector 时，派发前拒跑并给出修法。
8. **冒烟 autosave 解除劫持后回写 jmxPath 端口文件**：内部 SAVE 的 post-listener
   已把 autosave 路径写进 port-{pid}.json，恢复原关联后补 syncJmxPathNow（自守卫：
   IPC 关闭/端口文件不存在时 no-op），对端委派不再解析到废弃快照。
9. **EDT 裸 getOrCreate 三处收口**（Phase 1 优先命令、Phase 2 dispatchable、面板
   「+」重置）：读失败的 UncheckedIOException（防覆写的故意设计）转聊天可见错误/
   放弃本次重置，不再以未捕获异常砸 EDT。
10. **末迭代 recap 门禁补 goalRequested**：/goal 回合内 create+complete 一气呵成时
    回合起点快照 goalActiveAtStart=false 的缺口。
11. **hostSynthetic 标记挪到消息创建点**（run 入口对末位触发消息），删除早落盘
    特例块——toPersistableMessage 原样保留 metadata，早落盘/终局/中止三路落盘
    全部携带标记，早落盘失败路径不再漏标。
12. **agent.goal.smoke.limit 读取点下界钳 1**：0/负值原会令完成门禁
    （smokeAttempts >= limit）恒真——零证据即可 complete。
13. **GoalState.load 坏 blob 补 warn 日志**：容错语义（按无 goal 处理）不变，但
    不再静默——活跃 goal 无声蒸发至少有痕。
14. **截断代理对安全 truncateUtf16Safe**：recap/ui_summary 截断切点劈开增补字符
    时回退一位；ui_summary 上限收敛为 GoalState.MAX_UI_SUMMARY_CHARS。
15. **门禁点仲裁**（2026-10-12，实测后撤销「延迟仲裁暂缓」裁定）：冒烟阻塞等待
    超时而引擎事后完成且通过时，update_goal complete 门禁当场向采集器对账补章。
    五条件缺一不可：AGENT 来源、冒烟运行标记（reset(provenance, smoke) 原子置位）、
    已完成、发过流量零 error、本 goal 冒烟预算确消耗过（防跨 goal 陈旧数据冒名）；
    只补冒烟章不绕 canComplete 复评（静态仍缺照拒）。nudge 交回条件同款联动——
    晚到的 PASS 不再触发「still failing」交回口径。慢冒烟一轮即干净 complete，
    写端点不再重复执行。已知皱褶：对端委派的 run_test 冒烟会写采集器，宿主 goal
    可能以其数据补章（同一计划的真实验证，结果无害，文档化）。

**设计取舍裁定**（2026-10-11 定、10-12 修订）：冒烟完成等待最初以 B+ 方案属性化
（`agent.goal.smoke.completion.timeout.ms`，默认 180s）+ 工具守卫带参化
（`Tool.getTimeoutMs(parameters)`：smoke = 完成等待 + 30s 派发余量、其余动作
15s，消除 120s 一刀切对高频动作的 8 倍守卫退化）。10-12 门禁点仲裁落地后，该参数
的证据职责被接管、可配置性失义，遂**删除参数、回归常量 180s**（`RunTestTool.
SMOKE_COMPLETION_TIMEOUT_MS`）——常量保留的原因是控制权归还机制：永不完成的
冒烟（黑洞端点/无限循环计划/引擎楔死）到达上限即返回 "still running"，模型重获
控制权去诊断、stop 引擎、修计划；超窗完成且通过的运行由仲裁补章（见上表 15）。
已知取舍：180s 阻塞窗口超过 IPC 委派超时 120s，冒烟阻塞期对端委派仍会超时。
Stop 对进行中冒烟引擎的停机接线同样裁定**不做**：Stop 是用户发起的，引擎停止由
用户以 JMeter 自带停止操作手动完成——回合取消后引擎跑完、判定落进无人读的回合，
均为已知边界。

## Open Questions

- 续跑触发消息走注入队列还是 Phase 3 直投的布线细节（R7）——不改变 spec 行为（两种路径均已保证不渲染 You 行），实现期定
- goal blob 的字段命名与序列化细节（ISO 时间格式等）——实现期定，不影响契约
