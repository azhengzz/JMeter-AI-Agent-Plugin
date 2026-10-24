# 配置参考

本页是 Gitee Ai - JMeter Agent 的完整配置参考。基础安装与 API Key 配置见 [README 快速开始](../README.md#快速开始)。

将 `jmeter-ai-sample.properties` 的内容复制到 `user.properties`，按需修改。下表中的默认值取自 `jmeter-ai-sample.properties`；若未在 `user.properties` 中显式配置，部分项将回落至源码内置默认值（已注明）。

> **注意：** `user.properties` 只在 JMeter 启动时读取，修改后需重启 JMeter 生效。

## 全局 LLM 默认配置

以下配置适用于所有 AI 提供者（除非被提供者专属配置覆盖）：

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `jmeter.ai.temperature` | 温度参数（0.0-1.0），越低越确定性 | `0.7` |
| `jmeter.ai.max.tokens` | 单次响应最大 Token 数 | `4096` |
| `jmeter.ai.max.history.size` | 对话历史保留条数 | `120` |
| `jmeter.ai.reasoning.effort` | 推理强度：none / minimal / low / medium / high / xhigh / max | `high` |
| `jmeter.ai.default.model` | 默认模型（所有提供者共用，除非运行时切换） | `deepseek-v4-flash` |
| `jmeter.ai.default.provider` | 默认提供者（anthropic / openai / ollama / deepseek / zhipu / moonshot / minimax / langcat） | `deepseek` |
| `jmeter.ai.context.window.tokens` | 上下文窗口大小（供 ContextWindowManager、MemoryConsolidator、AgentRunner 使用） | `65536` |
| `jmeter.ai.max.tool.iterations` | 单次 Agent 循环最大工具迭代数 | `50` |
| `jmeter.ai.system.prompt` | 统一系统提示（注意：当前版本 Agent 主循环固定使用内置提示组装上下文，此配置仅对未注入系统消息的服务层兜底路径生效） | 空（使用内置提示） |

## 各模型推荐配置

下表按主流模型给出推荐值。

| provider | model | temperature | max.tokens | reasoning.effort | context.window.tokens |
|--------|---------|-------------|------------|-----------------|----------------------|
| deepseek | deepseek-v4-flash | `0.7` | `65536` | `[none, low, high, max]` | `512000` |
| deepseek | deepseek-v4-pro | `0.7` | `65536` | `[none, low, high, max]` | `512000` |
| zhipu | glm-5.2 | `1.0` | `65536` | `[none, minimal, low, medium, high, xhigh, max]` | `512000` |
| zhipu | glm-5.3 | `1.0` | `65536` | `[low, high, max]` | `512000` |
| zhipu | glm-5.3-flash | `1.0` | `65536` | `[low, high, max]` | `512000` |
| moonshot | kimi-k2.6 | `1.0`（API 强制） | `8192` | `medium` | `128000` |
| moonshot | kimi-k2.7-code | `1.0`（API 强制） | `8192` | `medium` | `128000` |
| moonshot | kimi-k3 | `1.0`（API 强制） | `65536` | `[low, high, max]` [其他值默认按"max"处理](https://platform.kimi.com/docs/guide/use-thinking-effort) | `512000` |
| minimax | MiniMax-M2.7 | `1.0` | `8192` | `medium` | `128000` |
| minimax | MiniMax-M3 | `1.0` | `65536` | `[none, medium]` | `512000` |
| langcat | LongCat-2.0 | `0.7` | `65536` | `[none, medium]` | `512000` |

**使用说明：**

- **max.tokens** — 取各模型 API 的单次输出上限；高于上限的值会被服务端裁剪。推理模型（`deepseek-reasoner` 等）的实际可见输出需扣除思维链 token。
- **reasoning.effort** — `none` 关闭思考（更快、更省 token），适合常规对话与简单工具调用；推理模型应使用 `medium` 或 `high` 以发挥深度分析能力。
- **MiniMax 思考开关** — 经 `thinking.type` 控制（`reasoning_effort=none` → `disabled`，其余 → M3 用 `adaptive` / M2.x 用 `enabled`）。**M3** 系列可真正关闭思考；**M2.x** 系列服务端会忽略 `disabled`，思考无法关闭（已知限制，非本端缺陷）。
- **context.window.tokens** — 建议设为模型上下文上限的 80% 左右，给工具结果回填、记忆整合和系统提示预留缓冲；过大会触发频繁的记忆整合，过小会过早丢弃历史。

## 提供者配置

### Anthropic (Claude)

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `anthropic.api.key` | API 密钥（必填） | — |
| `anthropic.api.base.url` | API 基础 URL | `https://api.anthropic.com` |
| `anthropic.log.level` | 日志级别（info / debug） | 空（禁用） |

### OpenAI

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `openai.api.key` | API 密钥（必填） | — |
| `openai.api.base.url` | API 基础 URL（可指向代理或 Azure OpenAI） | `https://api.openai.com` |
| `openai.log.level` | 日志级别 | 空（禁用） |

### Ollama（本地）

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `ollama.api.base.url` | API 基础 URL（OpenAI 兼容端点，需含 `/v1` 后缀） | `http://localhost:11434/v1` |

### 国产大模型

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `deepseek.api.key` | DeepSeek API 密钥 | — |
| `deepseek.api.base.url` | DeepSeek API 基础 URL | `https://api.deepseek.com` |
| `zhipu.api.key` | 智谱 GLM API 密钥 | — |
| `zhipu.api.base.url` | 智谱 GLM API 基础 URL | `https://open.bigmodel.cn/api/paas/v4/` |
| `moonshot.api.key` | Moonshot Kimi API 密钥 | — |
| `moonshot.api.base.url` | Moonshot API 基础 URL | `https://api.moonshot.cn/v1` |
| `minimax.api.key` | MiniMax API 密钥 | — |
| `minimax.api.base.url` | MiniMax API 基础 URL | `https://api.minimaxi.com/v1` |
| `langcat.api.key` | LangCat API 密钥 | — |
| `langcat.api.base.url` | LangCat API 基础 URL | `https://api.longcat.chat/openai/v1` |

每个提供者均支持 `*.temperature`、`*.max.history.size` 等覆盖全局默认值（例如 `deepseek.temperature=0.3`）。

## Agent Loop 配置

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `agent.enabled` | 启用 Agent Loop（工具调用、记忆、会话持久化） | `true` |
| `agent.tool.result.max.chars` | 工具结果截断长度（字符） | `16000` |
| `jmeter.ai.injection.queue.size` | 单个会话最大排队注入消息数 | `20` |
| `jmeter.ai.injection.max.per.turn` | 每个 Agent 回合处理的注入消息上限 | `3` |
| `agent.runcapture.enabled` | 捕获 GUI 手动启动的测试运行（自动注入结果收集器，供 `get_test_status`/`get_test_results` 读取；关闭后仅 `run_test` 发起的运行被捕获） | `true` |
| `agent.goal.max.continuations` | 单个 goal 的跨回合自动续跑次数上限（耗尽后强制结构化交回） | `5` |
| `agent.goal.smoke.limit` | 单个 goal 的冒烟修复轮次上限（耗尽后 complete 免证据但 recap 须含遗留风险） | `3` |
| `agent.session.per-instance` | 每个 JMeter 实例使用独立会话文件（`false` 回退到全局共享会话） | `true` |
| `agent.session.reap.ttl.days` | 孤儿会话文件回收 TTL（天；仅当归属实例已确认死亡且超过该时限才删除） | `7` |
| `agent.workspace.path` | 工作空间路径（保存 MEMORY.md、HISTORY.md、会话、技能、模板） | 三档回退：`agent.workspace.path` → `{jmeter.home}/bin/jmeter-agent`（默认）→ `{user.home}/.jmeter-ai/agent`（JMeter home 不可用时） |

## 记忆配置

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `agent.memory.enabled` | 启用记忆系统 | `true` |
| `agent.memory.consolidate-on-exit.timeout.ms` | 关闭时深度蒸馏的超时（毫秒；超时则保留已归档的 HISTORY.md 并直接退出。关闭时归档到 HISTORY.md 始终执行，不可配置） | `120000` |

## 工具配置

### 通用工具行为

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `agent.tools.timeout.ms` | 工具执行默认超时（毫秒） | `30000` |

### JMeter 工具

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `agent.tools.jmeter.enabled` | 启用 JMeter 工具 | `true` |
| `jmeter.ai.tool.max.string.length` | 序列化 JMeter 元素时单个字符串属性值截断长度（字符） | `2048` |
| `jmeter.loggerpanel.maxlength` | JMeter LoggerPanel 最大行数（用于 get_log_panel_content 容量提示） | `1000` |

### 文件系统工具

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `agent.tools.filesystem.enabled` | 启用文件系统工具 | `true` |
| `agent.tools.filesystem.allowed.dirs` | 允许访问的目录（逗号分隔） | 空（回落到用户主目录与当前工作目录） |
| `agent.tools.filesystem.denied.dirs` | 禁止访问的路径（逗号分隔，支持目录或具体文件） | — |

### Web 工具

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `agent.tools.websearch.enabled` | 启用 Web 工具 | `true` |
| `agent.tools.websearch.provider` | 搜索引擎（brave / tavily / jina） | `jina` |
| `agent.tools.websearch.max.results` | 最大搜索结果数 | `10` |
| `agent.tools.websearch.timeout` | 搜索超时（秒） | `30` |
| `agent.tools.websearch.tavily.api.key` | Tavily API 密钥（provider=tavily 时必填） | — |
| `agent.tools.websearch.brave.api.key` | Brave API 密钥（provider=brave 时使用，未配置时回落 Jina） | — |
| `agent.tools.websearch.jina.api.key` | Jina API 密钥（provider=jina 时必填） | — |
| `agent.tools.webfetch.timeout` | 网页抓取超时（秒） | `30` |
| `agent.tools.web.max.redirects` | 最大重定向次数 | `5` |
| `agent.tools.web.ssrf.protection` | SSRF 防护（拦截访问内网/本地地址） | `true` |

### 命令执行工具

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `agent.tools.exec.enabled` | 启用命令执行工具 | `true` |
| `agent.tools.exec.timeout` | 默认超时（秒，最大 600） | `60` |
| `agent.tools.exec.working.dir` | 限定工作目录（设置后仅允许该目录及其子目录） | — |
| `agent.tools.exec.deny.patterns` | 危险命令拦截规则（正则，逗号分隔） | 内置默认规则（rm -rf、del /f、format、mkfs、shutdown 等） |
| `agent.tools.exec.path.append` | 追加到 PATH 的额外目录 | — |

### 异步子代理（Subagent）

主代理可通过 `spawn` 工具把一个自包含的**只读分析任务**委派给后台子代理。子代理运行在专用线程池上，使用隔离的只读工具集（仅限只读的 JMeter / 文件 / Web 工具，无法改测试计划、执行测试、写文件或继续派生子代理），并使用与主会话隔离的临时会话——其结果在**同一对话回合内**回注给主代理。

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `agent.subagent.enabled` | 总开关。关闭时不注册 `spawn` 与 `subagent_status` 工具，主代理无法派发子代理 | `false` |
| `agent.subagent.max.concurrent` | 每个主会话的最大并发子代理数（`1` 时串行执行） | `1` |
| `agent.subagent.max.iterations` | 单次子代理运行的最大工具迭代数 | `50` |
| `agent.subagent.drain.timeout.seconds` | 主回合等待子代理结果的阻塞时长（秒，硬上限 300）；超时后子代理继续运行，结果可经 `subagent_status` 查询，或在下个回合投递 | `120` |
| `agent.subagent.status.retention.seconds` | 完成态子代理状态可经 `subagent_status` 查询的保留时长（秒）；晚到/未投递结果保留此窗口后被回收；0 = 永不按时长回收 | `60` |
| `agent.subagent.status.max.completed` | 每会话保留的完成态状态上限（超出按最旧淘汰）；0 = 永不按数量淘汰 | `10` |

## 聊天 UI 配置

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `ai.chat.show.tool.calls` | 显示工具调用信息（工具名、状态、执行时间、结果） | `true` |
| `ai.chat.show.thinking` | 显示模型思考/推理内容（关闭时移除 `<think>` 块） | `true`（源码内置默认 `false`） |
| `ai.chat.tool.result.max.length` | 工具结果在聊天面板与日志中的最大显示长度 | `500` |
| `ai.chat.font.size` | 聊天输入框字体大小（点，0 = 使用系统字体） | `0`（自动） |

## 链路追踪配置

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `langsmith.enabled` | 启用 LangSmith 追踪 | `false` |
| `langsmith.api.key` | LangSmith API 密钥 | — |
| `langsmith.project.name` | 项目名称（所有追踪归入该项目） | `jmeter-ai` |
| `langsmith.endpoint` | API 端点 | `https://api.smith.langchain.com` |
| `langsmith.sample.rate` | 采样率（0.0-1.0，1.0 = 全量追踪） | `1.0` |

## IPC / CLI 配置

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `jmeter.ai.ipc.enabled` | 启用内嵌 IPC HTTP 服务（仅 loopback + token 鉴权），供 jmeter-cli 驱动运行中的 GUI 与跨实例协作 | `true` |
| `jmeter.ai.ipc.bind` | 绑定地址（仅接受 loopback；`0.0.0.0`/`::`/`*` 会被拒绝） | `127.0.0.1` |
| `jmeter.ai.ipc.port` | 监听端口（0 = 自动分配，推荐） | `0` |
| `jmeter.ai.ipc.token` | 鉴权 token（空 = 启动时随机生成并写入端口文件） | 空（随机生成） |
| `jmeter.ai.ipc.agent.timeout.ms` | `/agent` 路由同步等待超时（毫秒） | `120000` |
