# Design

## Context

见 proposal.md 的 Why。当前架构下所有 OpenAI 兼容流量（openai/deepseek/zhipu/moonshot/minimax/langcat/ollama 七家）由 `AiServiceFactory.createServiceForSpec` 构造的 `OpenAICompatibleProvider` 执行（backend `openai_compat`），Anthropic 流量走 `ClaudeService`（backend `anthropic`）；全进程唯一 LLM 调用点是 `AgentRunner.java:731`。`OpenAiService` 是该架构取代前的旧服务，运行时死代码状态已经 7-agent 双对抗验证器 CONFIRMED（构造点唯一、成员调用仅 setModel×2、HTTP 路径不可达、无反射/ServiceLoader/字符串实例化）。本设计约束于 `code-hygiene` spec：逐一求证的删除清单 + `mvn clean test` 全绿门禁 + 残留引用清零。

行号为 2026-09-29 快照，实施时以符号锚点为准。

## Goals / Non-Goals

**Goals:**

- 删除 `OpenAiService` 及因它而死的全部代码（含两级级联），`src/main`+`src/test`+`AGENTS.md` 残留引用清零
- 行为零变化：模型加载、模型切换路由、工厂缓存、回退路径全部不动

**Non-Goals:**

- 不重构 `AiChatPanel` 的模型选择 switch / `updateRawServiceForModel` 整体结构（`claudeService.setModel` 簿记是否也该清是另一个 simplification 变更）
- 不动 `AiService` 接口、`OpenAICompatibleProvider`、`ClaudeService` 的任何逻辑
- 不动 `AnthropicUsage`（`ClaudeService:443` 活路径）、`pom.xml` 的 openai-java 依赖、`openspec/changes/archive/**` 历史档案

## Decisions

### 决策 1：删除清单（逐一求证版）

| # | 文件 | 编辑 | 证据 |
|---|------|------|------|
| 1 | `service/OpenAiService.java` | 整文件删除（557 行） | 唯一构造 `AiChatPanel.java:146`；成员调用仅 `:243`/`:1019` 两处 setModel；HTTP 路径 `:420` 不可达；私有静态 `OPENAI_COMPATIBLE_PROVIDERS`（:44-46）仅类内 :136/:155 消费；`toReasoningEffort`（:546）仅被其测试反射调用 |
| 2 | `gui/AiChatPanel.java` | ① 删 import（:48）② 删字段（:90）③ 删构造行（:146，保留 :144 注释与 :145 `claudeService` 行）④ 删 `:243` setModel 调用体（保留 case 标签，见决策 2）⑤ 删 `:1019` setModel 调用体（同前）⑥ 改写过时注释 :240 ⑦ 改写 javadoc :1006（去掉 openAiService 提及） | 接线六处全清单，grep 验证 `openAiService` 在本文件仅 :48/:90/:146/:243/:1019（+两注释） |
| 3 | `usage/OpenAiUsage.java` | 整文件删除（162 行） | 唯一生产调用方是 OpenAiService（:99 setClient、:425 recordUsage、:25 import）；`OpenAICompatibleProvider` 用量内联进 `LLMResponse` map（:386-410→:487）从不经过它；src/test 零引用；2026-08-20 档案当时保留 `setClient` 正因 OpenAiService:99 在调——前提随本次消失 |
| 4 | `utils/AiConfig.java` | 删 `getOpenAiApiKey()`（:378-381） | 二级孤儿：唯一调用方 `OpenAiUsage.java:38`；活路径 key 读取在 `OpenAICompatibleProvider.java:97` 经 `AiConfig.getProperty(spec.getEnvKey(), "")` |
| 5 | `test/.../OpenAiServiceTest.java` | 整文件删除（214 行） | 专测被删类（:70 构造、:76/:86 反射）；不删则编译失败 |
| 6 | `service/ClaudeService.java` | 注释 :179 去掉 "OpenAiService /" | comment-only |
| 7 | `test/.../OpenAICompatibleProviderTest.java` | javadoc :32 去掉 `{@code OpenAiServiceTest}` 引用 | `{@code}` 非链接不破编译，但成悬空提及 |
| 8 | `AGENTS.md` | 删 :230（服务层条目）与 :278（使用统计条目） | 文档同步；CLAUDE.md 是 @AGENTS.md 指针无需单改 |

**明确不动**（求证过的活符号）：`AiChatPanel.claudeService` 字段（活用 :248/:254/:762-763/:979/:1023/:1029——其中 :762-763 是工厂失败时交给 `AgentLoopFactory.getAgentLoop` 的真实 LLM 回退路径）；`updateRawServiceForModel` 方法与其两个调用方（:724/:999，claudeService 分支 :1022-1031 存活）；`THINKING_STYLE_MAP`（唯一声明在 `OpenAICompatibleProvider.java:40`，`LangCatProviderTest:121` 与 `OpenAICompatibleProviderTest:413` 的反射目标都是 `OpenAICompatibleProvider.class`，与被删类无关）；`AnthropicUsage`。

### 决策 2：openai 族 case 标签保留为空体，不并入 default 分支

两路 sweep 结论分歧点。删掉 `:243`/`:1019` 的 setModel 语句后有两种收法：

- **并入 default**（sweep 2 的字面描述）：openai 兼容前缀（`openai:`/`deepseek:`/…）会落入 default 分支调用 `claudeService.setModel(modelName)`——把 openai 系模型名簿记到本地回退 Claude 实例上，是行为变化；
- **保留空体**（本设计采用）：case 标签留 `break` + 一行注释说明"路由经 AiServiceFactory，无需本地簿记"，选中 openai 族模型时什么都不发生——与现状（setModel 无 observable 效果）严格等价，零行为变化。

选后者的根因：本变更是死代码移除，每个行为差异都是缺陷；重构整个 switch 属 Non-Goal。空体注释须自包含写明保留原因，防未来"顺手清理"误并入 default。

### 决策 3：OpenAiUsage 与 getOpenAiApiKey 级联删除，不为"对称性"保留

备选是与 `AnthropicUsage` 保持 usage 包对称而保留。拒绝：`AnthropicUsage` 有真实数据流（`ClaudeService:443` 喂入），`OpenAiUsage` 删除 OpenAiService 后零消费者——对称性不是死代码的保留理由。`getOpenAiApiKey()` 是 public 访问器，但零引用 + 零反射 + 零字符串查找，满足 `code-hygiene` spec 的删除判据；活路径的 key 读取另有其径（决策 1 表 #4）。

### 决策 4：测试整删，无覆盖损失

`OpenAiServiceTest` 四类覆盖在活路径均有镜像：`testStripProviderPrefix_*`（含 Ollama tag 风格）、`testToReasoningEffort`、`testIsToolChoiceUnsupported_Throwable/String` 均在 `OpenAICompatibleProviderTest`。唯一语义缺口是死类私有 helper `extractProvider` 的"未知前缀默认 openai"断言——该语义在活路径不存在（`stripProviderPrefix` 只剥等于本 provider 名的前缀，无默认回退），属死行为而非活行为，不移植。`THINKING_STYLE_MAP` 反射耦合（项目记忆有案）目标在 provider，不受影响。

### 决策 5：行为中立性依据（显式记录）

- 选中 openai 族模型不再触发本地 setModel 簿记 → 中性：模型解析按调用发生（`getAiServiceForCurrentModel` → `AiServiceFactory.createService(modelId)`），簿记本就无消费者；
- `new OpenAiService()` 构造器副作用消失（向 `OpenAiUsage` 装client + verbose 客户端初始化日志）→ 中性：两者本就无效（write-only 字段、死单例）；
- `AiConfig.getOpenAiApiKey()` 消失 → 中性：配置属性 `openai.api.key` 的读取路径在 provider 构造器，不受影响。

## Risks / Trade-offs

- [行号漂移致编辑错位] → 清单以符号锚点为主、行号为快照辅助；实施按文件分组原子编辑
- [grep 误伤相近符号] → `AnthropicUsage`/`OpenAICompatibleProvider` 含相近子串；残留检查用全词模式 `OpenAiService|openAiService|OpenAiUsage|getOpenAiApiKey|OPENAI_COMPATIBLE_PROVIDERS`，范围 `src/` + `AGENTS.md`；被删符号均无同名活符号（code-hygiene 同名甄别场景）
- [空体 case 被未来清理误并入 default] → 决策 2 的自包含注释写明原因
- [`OpenAICompatibleProviderTest:32` 非链接引用漏改] → 纳入 grep 门禁模式，漏改即残留
- [`mvn clean test` 结果误判] → 只读输出文件本体确认 `Tests run`/`BUILD` 行，不与并行任务混跑（既有教训）；偶发失败先单独复跑分流
- 无运行时风险：删除对象均为不可达路径或无效簿记

## Migration Plan

无配置/数据迁移。实施为单一批次编辑（8 个文件），验证门禁（见 tasks）通过后即就绪；git 提交由用户自行执行（项目惯例）。回滚 = revert 单个提交。部署走常规 `mvn clean install`（如需 GUI 验证）。
