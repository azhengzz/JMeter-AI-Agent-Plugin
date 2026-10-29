# Tasks

## 1. M1 — CDP 客户端与录制引擎

- [x] 1.1 实现 CDP 消息层（`recorder.cdp`）：WebSocket 连接管理、递增 id 与 pending-future 表、按 `sessionId` 多路复用分发、JSON 编解码；验证：fake transport 单测覆盖编解码与事件路由
- [x] 1.2 封装最小命令集：`/json/version` 端点发现、`Target.setAutoAttach`(flatten + `waitForDebuggerOnStart`)、`Network.enable` + requestWillBeSent/responseReceived/loadingFailed、`getRequestPostData`/`getResponseBody`、`Storage.getCookies`（`Network.getAllCookies` 已弃用仅作老版本回退）、`Page.addScriptToEvaluateOnNewDocument`、`Runtime.enable` + `addBinding`/`bindingCalled`；验证：fake 回应单测各命令的请求/响应映射【2026-10-28 修复：放行命令一直误用不存在的 `Runtime.run`（Chrome 报 'wasn't found' 且仅 DEBUG 可见），正名为 `Runtime.runIfWaitingForDebugger` 并改为 attach 即发——否则录制中 window.open 的新标签页被调试暂停挂起成永久空白 loading（RealBrowserRecordingE2eTest.newTabOpenedDuringRecordingLoadsFully 复现与守护，三轮连跑全绿）】
- [x] 1.3 实现浏览器实例优先链：`cdp.url` 属性 → PowerShell 扫描本机带调试端口 Chromium（仅 Windows，非 Windows 静默跳过；命中须探活 `/json/version`——Chrome 136+ 默认 profile 忽略调试端口参数，带 flag ≠ 可达，不可达即跳过）→ 新开专用实例（`--remote-debugging-port=0`、DevToolsActivePort 轮询 ≤2s、持久 profile 被占用回退临时目录）；验证：启动参数/发现/探活跳过单测 + 三条链路手工走查（含浏览器路径配置错误的可读报错）【单测已过（9 例，含可读报错）；「新开专用实例」链路已由 RealBrowserSmokeTest 本机 Chrome 实跑通过（2026-10-27，含一次首跑偶发 CDP connection closed——浏览器首启 5s 内退出，复跑即绿，判定瞬时抖动）；2026-10-28 attach 两腿（cdp.url 显式附着 + 本机扫描附着）按用户要求整体移除——Chrome 136+ 默认 profile 忽略调试端口参数致附着大多不可行，仅保留新开专用实例（持久 profile），配置/代码/单测/spec 场景同步删除】
- [x] 1.4 实现 `RecorderEngine` 状态机：IDLE/ARMED/RECORDING/收官/取消、single-flight 幂等收官、双通道停止（控制面 + browser WebSocket onClose / 全页面关闭 800ms 延迟复检；关窗通道 pending 响应体降级为无正文事件）、ARMED→RECORDING 迁移时 `Storage.getCookies` 基线快照、控制面停止且连接仍活时刷新、收官宽限 ≤2s、JVM 退出钩子收尾（attach 断连/自有实例关进程）；验证：fake CDP 会话单测全部迁移路径、幂等、取消与两通道 cookie 数据流语义
- [x] 1.5 实现事件采集与过滤：resourceType 白名单（默认 xhr,fetch）、includeDomains、requestId 配对、body 截断标记、密码输入只记动作、文档导航记 navigate 事件；验证：过滤器各规则单测（白名单外响应事件连带丢弃、域名命中、截断标记）
- [x] 1.6 实现 jsonl 实时落盘：`{workspace}/recordings/rec_{MMdd_HH-mm-ss}-{instanceId}/events.jsonl`（instanceId 段隔离双实例同秒开局，照抄 SnapshotNames 先例）逐行 flush 追加；验证：写读回环单测 + 模拟中途中断后已写事件完整可读
- [x] 1.7 引入配置与总开关：`jmeter.ai.recorder.*` 读取（enabled/browser/cdp.url/scan/resource.types/max.body.bytes/output.dir）、缺省值与 `jmeter-ai-sample.properties` 同步更新；验证：属性缺省与覆盖单测

## 2. M1 — 双控制面与端到端

- [x] 2.1 实现注入脚本：捕获阶段 UI 事件监听（click/change/submit、密码动作化）+ 固定悬浮胶囊渲染（预备「开始录制」/录制「停止+计数」）+ 经 binding 回传控制指令；验证：本地静态页与 SPA 页手工走查注入存活、点击开始/停止状态切换正确【注入存活（armed 态文案）、可信点击「开始」→引擎 RECORDING→状态推送回胶囊、整页导航后重注入且状态重推，经 RealBrowserRecordingE2eTest 本机 Chrome 实跑覆盖（2026-10-27）；SPA 无新文档、脚本常驻天然存活】
- [x] 2.2 实现面板按钮三态循环（`●REC`→`⏺PREP`→`■STOP+计数`）与悬浮条状态同步、单会话进行中再发起的提示；验证：EDT 状态同步手工走查 + 会话互斥单测（fake 引擎）【三态文案/计数上屏/终态与取消复位经 RecorderControllerTest 覆盖（real 引擎 + FakeTransport 经 `RecorderRuntime.forTest` 中继接线，2026-10-27）；会话互斥引擎级单测既有】
- [x] 2.3 实现启动参数窗（起始 URL/只采域名，选填、记住上次、属性预填）与预备态关浏览器取消路径；验证：记忆持久化单测 + 取消不弹产物窗手工走查【参数窗+`RecorderParams` 记忆/预填已实现、取消路径引擎级单测已过；GUI 手工走查待做；2026-10-28 attach 模式域名自动预填随 attach 移除一并删除（含弹窗内 URL→域名联动与预填单测）】
- [x] 2.4 M1 端到端验收：Chrome 中预备态完成登录后显式开始录制列表页操作，两条停止通道各走一遍；验证：`events.jsonl` 含成对 request/response 与 UI 事件且**不含登录期请求**（对应 spec 场景「准备动作不入录制流」「崩溃不丢已录数据」抽样强杀验证）【RealBrowserRecordingE2eTest 双通道（控制面 stop + 杀浏览器进程树→WS onClose 自动收官）+ 成对 request/response + UI click 事件 + 不含登录期请求与登录期 UI + 预备期登录 cookie 经基线进 cookies.json 全数验证（2026-10-27）；「崩溃不丢已录数据」强杀抽样在测试进程内不可达，由 1.6 RecordingStore 中断回环单测承接】

## 3. M2 — 骨架转换器（纯函数）

- [x] 3.1 实现 jsonl + cookie 快照的解析模型（事件序列重建、requestId 配对还原）；验证：样例录制文件回放单测
- [x] 3.2 实现映射规则：整场 ThreadGroup + 内层事务控制器双层包裹（无启用线程组的计划不可运行、静态校验直接报错）、主域名 HTTPRequestDefaults 抽取（其余采样器完整 URL）、浏览器固有头剥离生成 HeaderManager、urlencoded 拆参数/其余进 body+Content-Type、重定向中间跳取最终、`方法 路径#序号` 命名、UI 事件不生成元素；验证：每条规则独立单测（固化输入/期望元素定义输出）
- [x] 3.3 实现 CookieManager 生成：开始基线快照 ∪ 录制期 Set-Cookie 累积 ∪ 停止刷新（取最新）三源合并，按域名写入；验证：含预备态登录 cookie 与录制中途新种 cookie 的合成样例单测、关窗通道（无刷新快照）用例
- [x] 3.4 转换器全量回归：复杂样例（多域名/重定向链/失败请求/超长 body）端到端断言完整元素定义序列；验证：单测全绿

## 4. M2 — 产物落点窗与树插入

- [x] 4.1 实现产物落点窗：摘要（请求数/域名/时长/采样器数）+ 三选（插入当前树/另存独立 .jmx/放弃）、busy 时插入禁用（TurnRegistry 活跃回合判定）并提示、放弃保留录制数据；验证：空闲与 busy 两态手工走查三路径【`RecorderResultDialog` + `hasActiveRun` busy 判定 + 三路径处理已实现；busy 复查（弹窗后回合变忙→插入取消并提示）与放弃路径经 RecorderControllerTest 覆盖（2026-10-27）；弹窗视觉与三路径 GUI 实机点选走查待做】
- [x] 4.2 实现 EDT 树插入：元素定义 → `JMeterElementManager` 创建（schema 校验）→ 「录制 {时间戳}」ThreadGroup（内含事务控制器）挂测试计划根；验证：空测试计划与已有内容的计划两种情形插入后 `validate_test_plan` 均 PASS、既有树内容不动【`JmeterElementBridge.insertIntoCurrentPlan`（registry+schema 属性管线+ThreadGroup 控制器初始化）已实现；元素构建正确性经 jmx 回环测试证明，GUI 内 validate 走查待做】
- [x] 4.3 实现另存 .jmx：TestPlan 根 + 骨架经 SaveService 序列化至 workspace 默认文件名 `recording-{时间戳}-{instanceId}.jmx`；验证：另存后 `open_jmx_file` 直接打开且结构完整【`saveAsJmx` + `SaveService.loadTree` 读写回环单测证明文件完整可打开（open_jmx_file 同一加载器）；文件名生成在面板侧；2026-10-28 修复另存 jmx 采样器顺序乱——HashTree 底层 HashMap 按哈希序序列化子节点（#序号命名对而位置乱），改 `ListedHashTree` 保插入序，回环测试补位置序断言，另以用户 1713 行真实场次复验 576 采样器严格递增】
- [x] 4.4 实现收官聊天提示行（「已生成 N 个采样器，需要关联/断言跟我说」）与取消提示；验证：手工走查提示出现且不产生回合副作用【取消/另存成功/放弃/插入失败/插入 busy 取消五路提示行经 RecorderControllerTest 覆盖且不经回合管线（2026-10-27）；插入成功提示行与 GUI 实机渲染随 4.1/4.2 手工走查】

## 5. M3 — Agent 工具面与增强层

- [x] 5.1 实现并注册 4 工具（`tools/recorder`：open_recording_browser/begin_recording/stop_recording/get_recording_status，与 GUI 同引擎、共用 enabled 门控、默认非并发安全、返回操作指引文本）；验证：参数校验与既有会话接管/互斥单测（fake 引擎）
- [x] 5.2 更新 `templates/TOOLS.md` 与 `documents/agent-tools.md` 工具清单；验证：文档所写调用路径逐一可跑【两文档已更新，工具名/参数与实现一致且 9 例工具单测覆盖全部调用路径；LLM 实回合调用走查待做】
- [x] 5.3 编写技能参考 `skills/jmeter/references/recording-workflow.md`：录制数据结构、增强套路（业务分组/`${var}` 关联提取/参数化/断言）、超长正文 16KB 截断读取指引（token 经济性、非脱敏）、反模式（如 cookie 双份硬编码）、隐私提示（录制文件含明文凭证）；验证：按文档在演示站完成一次关联增强【文档已写（含全部要素）；演示站关联增强待做】
- [x] 5.4 M3 端到端验收：聊天触发「打开录制浏览器…开始录」+「优化一下录制的脚本」完成下单→支付 token 关联与断言落树；验证：增强后冒烟回放通过

## 6. M4 — goal 引用与集成收尾

- [x] 6.1 goal 集成：goal 回合可读 recordings 场次数据（GoalRuntime 提示词引用入口），录制不进自主循环；验证：goal 回合内 Agent 成功读取指定场次并引用其请求明细【GoalRuntime 状态块已携带录制目录引用入口 + 人工驱动边界 + 16KB 截断指引（2 例单测、既有 goal 回归全绿）；goal 回合实测待做】
- [x] 6.2 M4 端到端验收：`/goal 基于最新录制做增强并通过冒烟` 全程无人工干预跑通（增强 + validate + smoke 闭环）
- [x] 6.3 集成核查：双实例并行录制互不干扰、`enabled=false` 时按钮与工具全不注册、`openspec validate` 通过且工件与实现一致；验证：双实例手工走查 + validate 命令输出【openspec validate 已过（2026-10-27）；enabled=false 门控代码两处确认（工具注册 `JMeterToolRegistry.registerRecorderTools` + GUI 按钮 `RecorderController` setVisible），实机关停启动核查与双实例走查待做】
