# Design

## Context

完整方案与决策记录（12 条已确认决策 + 2 条待确认假设 + 被否决备选）见 `docs/browser-recording.md`，本设计不重复其内容，只固化实现取向与仓库集成点。

约束（来自仓库现状）：

- Java 17、maven-shade 胖 jar；JMeter 父级类加载器压制 shaded 依赖（pom 中 Jackson 冲突前科）→ **新依赖要极度克制，本设计零新增**
- `ApacheJMeter_http` 被编译期排除 → HTTP 元素类（HTTPSamplerProxy 等）只能经 `JMeterElementManager` 的反射创建管线使用
- 工具注册点 `JMeterToolRegistry.registerDefaultTools`（已有 IPC/goal 条件注册先例）；GUI 集成点 `AiChatPanel` 输入卡按钮行
- 回合呈现统一走 TurnEvent 流（AgentLoop 驱动）；录制是**人工实时交互**，天然不属于回合模型

## Goals / Non-Goals

**Goals:**

- 录制引擎为**进程级单会话单例**，GUI 按钮与 Agent 工具操作同一实例（互斥而非并存）
- 骨架转换零 LLM、可单测：输入 jsonl 文件 → 输出元素定义列表，树插入为独立薄步骤
- CDP 客户端只实现本能力所需最小命令集，其余不碰

**Non-Goals:**

- 不进 IPC 注册表与跨实例协调（每实例独立会话）
- 不做录制暂停/多段拼场、WebSocket/SSE 帧捕获（方案文档已列为非目标）
- 不新建回合事件类型——录制状态呈现走自有 UI 通路（按钮态 + 悬浮条 + 聊天提示行），不占 TurnEvent 流

## Decisions

**D1 零依赖薄 CDP 客户端**（`org.gitee.jmeter.ai.recorder.cdp`）：`java.net.http.HttpClient.newWebSocketBuilder()` 直连 DevTools 协议，单 WebSocket 按 CDP `sessionId` 多路复用，自维护递增 id 与 pending-future 表。命令集：`/json/version`、`Target.setAutoAttach`(flatten + `waitForDebuggerOnStart`，兜新页注入竞态)、`Network.enable` + 三事件 + `getRequestPostData`/`getResponseBody`、`Storage.getCookies`（cookie 快照；`Network.getAllCookies` 已被 Chromium 弃用，仅老版本回退）、`Page.addScriptToEvaluateOnNewDocument` + `Runtime.enable` + `addBinding`/`bindingCalled`（域事件须 enable 才可达）。
*备选否决*：shade 现成 CDP 客户端库（类加载风险）、Playwright/Selenium（体积与依赖爆炸）、ProxyControl 反射驱动（MITM 证书 + 有损重建）。

**D2 引擎与呈现解耦、按钮直驱**（方案 D3/D4）：`RecorderEngine` 持状态机 `IDLE→ARMED→RECORDING→收官/取消`，single-flight 幂等收官；UI/工具仅是状态机的两个客户端。停录双通道在**流程上等价、数据保真分级**（控制面停止 = 连接仍活可刷新 cookie/补拉响应体；关浏览器 = browser 级 WebSocket onClose / 全页面关闭延迟复检 800ms——Windows Chrome 后台驻留，连接已断则 pending 响应体降级为无正文事件、cookie 用开始时基线，见 D3）。实例优先链的扫描腿命中后必须探活 `/json/version`：Chrome 136+ 起调试端口参数在默认 profile 下被静默忽略，命令行带 flag ≠ 端点可达——「附着日常浏览器」在现行版本大多不可行，attach 实际服务以非默认 profile 启动的实例，**新开专用实例是主路径**。JVM 退出经 shutdown hook 收尾活跃会话（attach 断连 / 自有实例关进程；先例：回合快照冲刷 hook）。

**D3 cookie 快照三源合并**：① ARMED→RECORDING 迁移时 `Storage.getCookies` 基线（此刻连接必然存活、预备态登录 cookie 必然在场——两阶段启动 D12 的必要配套）∪ ② 录制期 Set-Cookie 累积——**取自 `Network.responseReceivedExtraInfo`**（Chrome 裁剪 `responseReceived.headers` 的 Set-Cookie，多值以 `\n` 连接；第二轮评审 P0-2）∪ ③ 控制面停止且连接仍活时刷新快照（存在时为其权威终态，避免被服务端删除的条目残留）。三源均按只采域名过滤，cookie 身份 = 规范化 domain+path+name（含 Max-Age=0/过期删除语义）。关浏览器停止通道连接已断，①+② 仍闭环。本条为自研设计，dsh 无先例（其默认脱敏 cookie 头、无任何快照逻辑）。

**D4 悬浮控制条与 UI 事件共用注入通道**：同一段 `addScriptToEvaluateOnNewDocument` 注入脚本既注册捕获阶段监听（click/change/submit，密码只记动作）也渲染固定悬浮胶囊；点击经同一 `Runtime` binding 回传。CDP 注入脚本由渲染器求值、不受页面 CSP 限制；每页重注入保 SPA 存活。

**D5 骨架转换器纯函数化 + 树插入薄壳**：`SkeletonConverter`（`recorder.convert`）输入 jsonl + cookie 快照、输出元素定义序列（类型+属性 map），可全量单测；树插入步骤在 EDT 上经 `JMeterElementManager` 反射创建（走 schema 校验），busy 检测用 `TurnRegistry` 活跃回合判定，「插入当前树」选项据此禁用。另存 .jmx 用 JMeter SaveService 序列化。映射规则全表见方案文档 6.1（Defaults 抽取/固有头剥离/表单拆参/重定向取最终/UI 事件不生成元素）。

**D6 条件注册对齐既有模式**：`jmeter.ai.recorder.enabled=false` 时面板按钮与 5 个工具（4 录制 + `import_recording`）均不注册，注册形态仿照 IPC 工具的条件块；`get_recording_status` 为只读、覆盖 `isConcurrencySafe()=true`（对齐仓库只读工具白名单惯例），其余默认非并发安全。

**D7 凭证明文策略**（方案 D9）：落盘/Agent 读取/写树三处明文；不设独立「LLM 摘要」生成环节，token 经济性由 16KB 截断读取指引承担（非脱敏）——指引收敛为技能文档单一来源，GoalRuntime/TOOLS.md 仅指向它。风险已在方案文档第 10 节记档。

**D8 jsonl 实时追加、无清理**：对齐 autosave 累积策略；崩溃韧性靠逐行 flush。目录 `{workspace}/recordings/rec_{MMdd_HH-mm-ss}-{instanceId}/`（instanceId 段照抄 SnapshotNames 先例，隔离双实例共享 workspace 的同秒开局）。

**D16 控制通道防伪造 = 隔离世界**：注入脚本、UI 监听与控制 binding 全部运行在 CDP 隔离世界（`worldName`/`executionContextName` = `__jaiRecWorld`），binding 在主世界不可见不可调；`runImmediately` 覆盖 attach 时已加载的既有文档（第二轮评审 P1-5）。原一次性令牌方案可被页面覆盖全局 setter 窃取（第二轮评审 P0-1 真实 Chrome 实证），已弃用。

**D17 控制/状态注入与执行器**：悬浮条状态、计数与控制指令经隔离世界 evaluate/binding；控制动作一律转后台执行器（不在 WS 侦听线程跑收官）；JVM 退出钩子先落 cookies/summary 再关 store。

**评审假设落地说明**：参数窗（A1）与 Agent 工具面（A2）按方案文档 4.2 的推荐值实现；翻转均不触碰引擎与转换器，只裁 UI/注册层。

## Risks / Trade-offs

- [CDP 命令子集遇浏览器版本差异] → 只用多年稳定域（Network/Page/Runtime/Target/Storage）；未知事件忽略不炸，降级路径 = 面板按钮全流程兜底
- [attach 扫描 PowerShell 仅 Windows] → 非 Windows 静默跳过扫描退回 cdp.url/新开实例（规格已允许）；命中实例附着前探活 /json/version——136+ 默认 profile 忽略调试参数，带 flag ≠ 可达，不可达即跳过
- [新开标签页先加载后注入，丢该页早期 UI 事件] → `setAutoAttach` 带 `waitForDebuggerOnStart`，注入完成再放行
- [JMeter 退出遗留孤儿浏览器/敞开的调试端口] → shutdown hook 收尾活跃会话（attach 断连/自有实例关进程）
- [悬浮胶囊被深色页面/全屏遮挡或样式互扰] → 高 z-index、半透明深底、自绘不依赖页面样式；失效时 D2 的按钮兜底
- [busy 禁用判定与回合结束竞态] → 判定与插入都在 EDT 串行化；提示语保守（「稍后导入」而非自动重试）
- [Chrome 后台驻留致「关浏览器」误判] → 全页面关闭 + 800ms 延迟复检（dsh 验证过的模式）
- [凭证明文进 LLM 上下文与 tracing] → 既定取舍（D9），文档记档；后续收紧单点改技能读取指引
- [录制目录无限累积] → 接受（与 autosave 一致）；技能文档提示用户自行清理

## Migration Plan

四里程碑（M1 引擎/控制面 → M2 转换器/落点窗 → M3 工具面/增强技能 → M4 goal 引用）顺序合入，每个里程碑独立可交付、可验收（验收标准见方案文档第 12 节）。软回滚：`jmeter.ai.recorder.enabled=false` 即整体关停；硬回滚 = revert 对应里程碑提交，无数据迁移（recordings/ 目录自管理）。

## Open Questions

- 悬浮控制条的具体视觉规格（配色/尺寸/折叠态）——实现期在 M1 内定，不影响任何需求
- ~~`includeDomains` 匹配语义（精确匹配 vs 后缀匹配）~~ **已定**：大小写不敏感后缀匹配（`example.com` 命中 `example.com` 与 `*.example.com`，尾点 FQDN 等价归一；不誊战通配符语法），已写入技能文档

## 2026-10-28 变更：移除 attach 模式

按用户要求删除附着既有浏览器的两条获取路径（`jmeter.ai.recorder.cdp.url` 显式附着 + 本机扫描附着），仅保留「新开专用实例」（持久 profile 保留登录态）。并行录制者经持久 profile 上的跨进程占用声明（FileLock，进程退出自动释放）仲裁：被占方不 spawn 进对方 user-data-dir（Chrome 同 profile 单例域会被转交、起始页开进对方窗口），直接回退临时目录并记录原因。动因：Chrome 136+ 在默认用户配置目录下静默忽略 `--remote-debugging-port`，附着日常浏览器在现行版本大多不可行（原 D 决策中已把专用实例定为主路径，attach 实为低可用性附赠）。连带删除：两项配置键、参数窗 attach 域名预填与 URL→域名联动、引擎 own=false 断连分支、PowerShell 扫描腿及其单测；spec「实例获取」requirement 重写为专用实例唯一路径。
