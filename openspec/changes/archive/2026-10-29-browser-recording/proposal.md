# Proposal

## Why

接口/性能测试脚本的主要编写成本在「把业务操作翻译成 HTTP 请求序列」。增加浏览器录制能力——自动唤起浏览器记录人工操作期间的业务接口请求并转化为 JMeter 脚本——可把这一成本降到接近零。参考项目 dsh-web-recorder 已验证 CDP 录制模式（无代理、无 MITM 证书、读浏览器已解密视图），但其刻意不含 JMeter 转换层；本变更移植其录制层模式并从零补齐「录制 → 确定性骨架 → Agent 增强」全链路。完整方案与决策记录见 `docs/browser-recording.md`。

## What Changes

- **新增浏览器录制引擎**：自研薄 CDP 客户端（Java 17 内置 `java.net.http` WebSocket，零新依赖，仅 Chromium 系浏览器）；两阶段状态机 `IDLE → ARMED（预备态，登录等准备动作零采集）→ RECORDING（显式开始后采集）→ 收官/取消`；请求/响应/UI 事件实时落 `recordings/rec_*/events.jsonl`
- **新增浏览器实例策略**：新开专用实例唯一路径（attach 模式已移除——Chrome 136+ 在默认 profile 下静默忽略调试端口参数，附着日常浏览器大多不可行）；持久 profile 保留登录态，并行录制经跨进程占用声明（FileLock）仲裁——被占方不 spawn 进对方 user-data-dir、回退临时目录；收官杀自启实例进程树；JMeter 退出经 shutdown hook 收尾活跃会话
- **新增双控制面**：聊天面板 `●REC → ⏺PREP → ■STOP` 三态按钮 + CDP 注入的浏览器内悬浮控制条（开始/停止/请求计数，主控制面）
- **新增确定性骨架转换器**（零 LLM）：录制事件 → ThreadGroup（JMeter 引擎只从线程组执行采样，无启用线程组的计划不可运行）+ 内层 TransactionController + HTTPSampler ×N + HTTPRequestDefaults + HeaderManager（剥浏览器固有头）+ CookieManager（开始录制时 cookie 基线 ∪ 录制期 Set-Cookie ∪ 停止时刷新三源合并——关浏览器通道连接已断靠基线闭环，含预备态登录 cookie）；产物落点每次弹窗三选（插入当前测试计划 / 另存含 TestPlan 根的独立 .jmx / 放弃）
- **新增 Agent 工具面与增强层**：4 工具薄包装同一引擎（`open_recording_browser` / `begin_recording` / `stop_recording` / `get_recording_status`）；技能文档教 Agent 在骨架上做业务分组、`${var}` 关联提取、参数化、断言
- **goal 集成**：goal 回合可经工具面引用已有录制场次做增强与冒烟验证（录制本身仍需人工操作，不进自主循环）
- 新配置项 `jmeter.ai.recorder.*`（总开关/浏览器选择/resourceType 白名单/body 截断/输出目录）

**非目标**：WebSocket/SSE 帧捕获；Firefox/Safari 支持；浏览器扩展分发；HTTP 代理/MITM 证书方案；录制中途暂停与多段拼场。

## Capabilities

### New Capabilities

- `browser-recording`: 浏览器录制会话全生命周期——两阶段启动（预备/录制）、事件采集与过滤、jsonl 实时落盘与自动收官、双控制面状态同步、确定性骨架转换与产物落点选择、Agent 工具面及 goal 引用

### Modified Capabilities

（无——goal 引用录制、Agent 工具注册均为本能力自身的新增行为，不改变 goal-autonomous-loop、tool-concurrency 等既有能力的需求。）

## Impact

- 新增代码：`org.gitee.jmeter.ai.recorder`（`cdp`/`session`/`convert` 子包）、`agent/tools/recorder` 四工具、AiChatPanel 录制按钮与参数窗、产物落点弹窗
- 复用：`JMeterElementManager` 元素创建管线 + schema 校验（骨架元素质量兜底）；不新增第三方依赖（避开 JMeter 父级类加载冲突）
- 数据与目录：workspace 新增 `recordings/`（jsonl 落盘、无自动清理）与 `browser-profile/`（专用实例持久 profile，跨重启稳定；并行录制经跨进程占用声明 FileLock 仲裁——被占方不 spawn 进对方 user-data-dir、回退临时目录，防 Chrome 同 profile 单例转交挤进同一浏览器）
- 实施按四里程碑推进：M1 录制核心 → M2 骨架转换器 → M3 Agent 增强层与工具面 → M4 goal 集成（详见 tasks.md）
