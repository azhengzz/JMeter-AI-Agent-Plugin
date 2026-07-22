# CLAUDE.md

此文件为 Claude Code (claude.ai/code) 在处理本仓库代码时提供指导。

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.


## 项目概述

Gitee Ai (JMeter Agent) 是一个 JMeter 插件，提供 AI Agent 驱动的聊天界面，用于创建、优化和排查 JMeter 测试计划。它集成了 Claude (Anthropic)、OpenAI 和 Ollama AI 模型，并实现了完整的 Agent Loop 架构（工具调用、技能系统、上下文管理、会话管理等）。

## 构建和测试命令

**构建项目：**
```bash
mvn clean package
```

**运行测试：**
```bash
mvn test
```

**运行特定测试：**
```bash
mvn test -Dtest=ClassNameTest
```

**安装到本地 JMeter（修改 pom.xml 的 antrun 配置路径）：**
```bash
mvn clean install                    # 复制 jar/skills/templates/CLI 脚本，默认不启动 GUI
mvn clean install -DskipTests
mvn clean install "-Dlaunch.gui=true"  # 同上 + 启动 JMeter GUI（仅 Windows；其他平台静默跳过）
```

**跳过测试构建：**
```bash
mvn clean package -DskipTests
```

## 高层架构

插件采用分层架构，职责分离清晰：

### AI Agent 框架 (`org.gitee.jmeter.ai.agent`)
核心 Agent 执行引擎，实现工具调用的闭环：

- **AgentLoop** / **AgentLoopFactory** - Agent 主循环，驱动 LLM 调用 → 工具执行 → 结果反馈的迭代
- **AgentConfig** - Agent 配置管理（模型、温度、最大轮次等）
- **GenerationSettings** - AI 生成参数的唯一来源

#### 命令路由 (`agent/command`)
- **CommandRouter** - 将用户命令路由到对应处理器
- **BuiltinCommands** - 内置命令定义
- **CommandContext** - 命令执行上下文

#### 上下文管理 (`agent/context`)
- **ContextBuilder** - 构建发送给 LLM 的消息上下文
- **ContextWindowManager** - 管理上下文窗口大小，控制 token 用量

#### 会话管理 (`agent/session`)
- **Session** - 单次 Agent 会话
- **SessionManager** - 管理多个会话的生命周期

#### Agent 运行 (`agent/run`)
- **AgentRunner** - 执行 Agent 运行
- **AgentRunSpec** - 运行规格定义
- **AgentRunResult** - 运行结果
- **InjectionManager** - 管理注入点和依赖注入

#### Agent 模型 (`agent/model`)
- **Message** / **ToolCall** / **ToolResult** - LLM 交互消息模型
- **ToolDefinition** - 工具元数据定义
- **LLMResponse** / **AgentResponse** - LLM 响应封装
- **LlmCallOptions** - LLM 调用选项
- **MessageOptimizer** - 消息序列优化
- **ProgressUpdate** / **ToolEvent** - 进度和事件通知

#### Agent 钩子 (`agent/hooks`)
- **AgentHook** / **AgentHookContext** - Agent 生命周期钩子
- **ProgressCallbackHookAdapter** - 进度回调适配器

#### Agent 记忆 (`agent/memory`)
- **MemoryStore** - Agent 记忆存储
- **MemoryConsolidator** - 跨会话记忆整合
- **SaveMemoryTool** - 保存记忆的工具

#### Agent 技能 (`agent/skills`)
- **SkillsLoader** - 从文件系统加载技能
- **SkillInfo** / **SkillMetadata** - 技能元数据

#### Agent Swing 集成 (`agent/swing`)
- **AgentSwingWorker** - SwingWorker 封装，在 UI 线程安全执行 Agent 操作

### 工具层 (`org.gitee.jmeter.ai.agent.tools`)

#### 工具基础设施
- **Tool** - 工具接口
- **AbstractTool** - 工具基类
- **ToolRegistry** / **JMeterToolRegistry** - 工具注册中心
- **ValidationResult** - 工具参数校验结果

#### JMeter 元素工具 (`tools/jmeter`)
- **AbstractJMeterElementTool** - JMeter 元素工具基类
- **CreateJMeterElementTool** - 创建新的 JMeter 元素
- **DeleteJMeterElementTool** - 删除 JMeter 元素
- **UpdateJMeterElementTool** - 更新现有元素的属性
- **BatchUpdateJMeterElementTool** - 批量更新多个 JMeter 元素
- **MoveJMeterElementTool** - 移动元素到不同父节点
- **CopyPasteJMeterElementTool** - 复制粘贴测试计划元素
- **GetTestPlanTreeTool** - 获取测试计划树结构
- **FindElementTool** - 查找测试计划中的元素
- **GetSelectedElementTool** - 获取当前选中的元素
- **QueryElementPropertiesTool** - 按属性查询 JMeter 组件
- **ToggleJMeterElementTool** - JMeter 组件启用/禁用/切换状态

#### JMeter 测试执行工具 (`tools/jmeter/execution`)
- **RunTestTool** - 运行 JMeter 测试
- **GetTestStatusTool** - 获取测试执行状态
- **GetTestResultsTool** - 获取测试结果
- **AgentResultCollector** - 测试结果收集器

#### JMeter 属性处理 (`tools/jmeter/property`)
- **SchemaBasedPropertyHandler** - 基于 schema 将参数应用到 JMeter 元素

#### JMeter 工具类 (`tools/jmeter/utils`)
- **JMeterTreeUtils** - JMeter 树操作工具

#### 文件系统工具 (`tools/filesystem`)
- **AbstractFsTool** - 文件系统工具基类
- **ReadFileTool** - 读取文件内容
- **WriteFileTool** - 写入文件
- **EditFileTool** - 编辑文件
- **ListDirTool** - 列出目录内容

#### Web 工具 (`tools/web`)
- **AbstractWebTool** - Web 工具基类
- **WebFetchTool** - 获取网页内容
- **WebSearchTool** - 搜索互联网

#### 执行工具 (`tools/exec`)
- **ExecTool** - 执行 shell 命令

### 服务层 (`org.gitee.jmeter.ai.service`)
- **AiService** 接口定义了 AI 提供者的契约
- **ClaudeService** - 使用 anthropic-java SDK 集成 Anthropic Claude
- **OpenAiService** - 使用 openai-java SDK 集成 OpenAI GPT
- **OllamaAiService** - 使用 ollama4j 集成本地 Ollama 模型
- **CodeRefactorer** - 通过 AI 处理 JSR223 脚本重构
- **RetryPolicy** - API 调用重试策略

#### 服务提供者 (`service/provider`)
- **AiServiceFactory** - AI 服务工厂
- **OpenAICompatibleProvider** - OpenAI 兼容提供者
- **ProviderRegistry** - 提供者注册中心
- **ProviderSpec** - 提供者规格定义

所有服务通过 JMeter 属性配置（`anthropic.api.key`、`openai.api.key` 等），通过 **AiConfig** 工具类访问。

### 链路追踪 (`org.gitee.jmeter.ai.tracing`)
- **LangSmithClient** - LangSmith API 客户端
- **TracedAiService** - 带追踪的 AiService 包装器

### GUI 层 (`org.gitee.jmeter.ai.gui`)
- **AI** - AI 集成入口
- **AiChatPanel** - 主 Swing 面板，包含聊天界面、模型选择器和元素建议（支持 Shift+Enter 换行、拖拽调整区域高度）
- **AiMenuItem** - 切换聊天面板的菜单项和工具栏按钮
- **AiMenuCreator** - 创建 AI 相关菜单
- **ChatUIManager** - 管理聊天 UI 状态
- **JSR223ContextMenu** - JSR223 脚本编辑器的右键上下文菜单
- **MessageProcessor** - 处理 markdown 渲染和消息显示（支持 reasoningContent 结构化思考内容展示）
- **ElementSuggestionManager** - 为 AI 响应中提到的 JMeter 元素创建可点击按钮
- **ComponentFinder** - 查找 JMeter 组件
- **JMeterElementManager** - GUI 层的 JMeter 元素管理
- **TreeNavigationButtons** - 测试计划树导航按钮

### 智能提示 (`org.gitee.jmeter.ai.intellisense`)
- **CommandIntellisenseProvider** - 提供命令建议（/new、/status、/help）
- **InputBoxIntellisense** - 将弹出补全附加到聊天输入框
- **IntellisensePopup** - 补全弹出框显示

### 使用统计 (`org.gitee.jmeter.ai.usage`)
- **AnthropicUsage** - Anthropic 用量统计
- **OpenAiUsage** - OpenAI 用量统计

### 工具类 (`org.gitee.jmeter.ai.utils`)
- **AiConfig** - AI 配置工具类
- **Models** - 模型常量定义
- **SystemPrompt** - 系统提示模板
- **TextUtils** - 文本处理工具
- **VersionUtils** - 版本比较工具
- **WorkspaceInitializer** - 工作空间初始化
- **JMeterElementManager** - JMeter 元素管理

### 组件参数校验系统 (`org.gitee.jmeter.ai.agent.validation`)
- **ComponentSchema** - 组件 schema 数据模型
- **ComponentSchemaLoader** - 从 YAML schema 文件加载组件校验规则
- **ComponentValidator** - 根据 schema 校验组件参数（必填项、类型、枚举值、范围、正则表达式等）

**Schema 文件位置：** `src/main/jmeter-agent/skills/jmeter/references/`

每个 JMeter 组件类型对应一个 `{ComponentName}.schema.yaml` 文件，定义了：
- 组件类型（`type`）和显示名称（`name`）
- 组件描述（`description`）
- 属性列表（`properties`），包括：
  - 属性名（`name`）
  - 数据类型（`type`）：String, Integer, Boolean, Number, Object, Array
  - 是否必填（`required`）
  - 默认值（`default`）
  - 枚举值限制（`enum`）
  - 数值范围（`min`/`max`）
  - 正则表达式模式（`pattern`）
  - 嵌套属性（`properties`）和集合项属性（`itemProperties`）

**部署位置：** 构建时复制到 `{JMETER_HOME}/bin/jmeter-agent/skills/jmeter/references/`

**Schema 目录结构：**（按**来源**分 3 个顶层目录，其下按**功能类别**分子目录；`ComponentSchemaLoader` 递归扫描，文件放在哪个 `source` 目录不影响加载，仅用于区分原生与第三方）
```
references/
├── native/              # Apache JMeter 原生 (50) — org.apache.jmeter.*，随 JMeter 自带
│   ├── assertions/      (6)  Response/JSONPath/XPath/JSR223/BeanShell/XML Assertion
│   ├── configuration/   (6)  CSVDataSet/CookieManager/HTTPRequestDefaults/HeaderManager/JDBCConnectionConfiguration/UserDefinedVariables
│   ├── controllers/     (10) Loop/If/While/Foreach/Transaction/Simple/OnceOnly/Random/Module/Include
│   ├── listeners/       (4)  ViewResultsTree/SummaryReport/AggregateReport/BackendListener
│   ├── post-processors/ (6)  Regex/JSON/Html/JSR223/BeanShell/Debug PostProcessor
│   ├── pre-processors/  (3)  JSR223/BeanShell PreProcessor, UserParameters
│   ├── samplers/        (7)  HTTPRequest/JDBC/JSR223/BeanShell/FlowControlAction/Debug/OSProcess
│   ├── test-fragments/  (1)  TestFragmentController
│   ├── thread-group/    (3)  ThreadGroup/setUpThreadGroup/tearDownThreadGroup
│   └── timers/          (4)  Constant/UniformRandom/ConstantThroughput/PreciseThroughput
├── gitee-qa/            # Gitee QA 扩展 (19) — com.gitee.qa.jmeter.*，本生态自研
│   ├── assertions/      (3)  JsonAuto/Value/Variable Assertion
│   ├── configuration/   (4)  ExcelDataConfig/S3ConfigElement/HTTPUDConfigElement/HTTPUDIncludeConfig
│   ├── controllers/     (5)  Case/DoWhile/VariableLoop/Probability/ParameterInclude
│   ├── samplers/        (3)  Git/HTTPUD/S3 Sampler
│   ├── test-fragments/  (1)  ParameterTestFragmentController
│   └── thread-group/    (3)  PerforAuto/PerforAutoStepping/PerforAutoUltimate ThreadGroup
├── third-party/         # 外部第三方插件 (4) — 需单独安装
│   ├── samplers/        (2)  SSHCommandSampler/SSHSFTPSampler (SSH Sampler 插件)
│   └── thread-group/    (2)  SteppingThreadGroup/UltimateThreadGroup (jmeter-plugins「Custom Thread Groups」)
└── functions/           # JMeter 函数参考 (58 个 .md 文档)
```

**来源判定**：`testClass` 为 `org.apache.jmeter.*` 且真实存在于 JMeter 源码 → `native`；为 `com.gitee.qa.jmeter.*` → `gitee-qa`；其余（如 jmeter-plugins `kg.apc.*`、SSH Sampler）→ `third-party`。

### 技能系统 (`src/main/jmeter-agent/skills/`)
Agent 的技能通过文件系统组织，每个技能包含一个 `SKILL.md` 和可选的 `references/` 目录：

- **jmeter/** - JMeter 核心技能，包含 73 个组件 schema 和 133 个参考文档（含 58 个 JMeter 函数文档）
  - `SKILL.md` - 主技能定义
  - `references/functions/` - 58 个 JMeter 函数参考文档（覆盖全部内置函数和自定义扩展函数）
  - `references/standards.md` - JMeter 编写规范
  - `references/bad-cases.md` - 常见反模式
  - 每个组件目录包含 `{Name}.md`（使用文档）和 `{Name}.schema.yaml`（参数 schema）
- **api-autotest/** - API 自动化测试技能（针对 Gitee-Scan OpenAPI）
- **memory/** - Agent 记忆管理技能
- **skill-creator/** - 技能创建工具，包含模板和工作流模式

### Agent 模板 (`src/main/resources/templates/`)
- `AGENTS.md` - Agent 系统指令
- `SOUL.md` - Agent 人格/上下文定义
- `TOOLS.md` - 工具使用指南
- `USER.md` - 用户交互指南
- `memory/MEMORY.md` - 记忆系统文档

## 测试结构 (`src/test/java/org/gitee/jmeter/ai/`)

- **agent/validation/** - Schema 加载和类型校验测试
  - `ComponentSchemaTypeTest` / `SchemaLoaderTest` / `YamlDebugTest`
- **agent/context/** - 上下文管理测试
  - `ContextWindowManagerTest`
- **intellisense/** - 智能提示测试
  - `CommandIntellisenseProviderTest` / `InputBoxIntellisenseTest` / `IntellisensePopupTest`
- **service/** - 服务层测试
  - `CodeRefactorerTest`
- **utils/** - 工具类测试
  - `VersionUtilsTest`

## 关键设计模式

- **策略模式**：AiService 接口允许在 Claude、OpenAI 和 Ollama 之间切换
- **观察者模式**：树选择监听器触发 JSR223 编辑器的上下文菜单更新
- **工作者模式**：所有 AI API 调用使用 SwingWorker 以避免阻塞 UI
- **工厂模式**：AiServiceFactory / AgentLoopFactory 创建服务和 Agent 实例
- **Agent Loop 模式**：AgentLoop 驱动 LLM 调用 → 工具执行 → 结果反馈的迭代循环
- **注册中心模式**：ToolRegistry / ProviderRegistry 管理工具和提供者的注册与查找

## 关键依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| anthropic-java | 2.18.0 | Anthropic Claude SDK |
| openai-java | 4.43.0 | OpenAI GPT SDK（ReasoningEffort 自 4.42.0 起含 MAX） |
| ollama4j | 1.1.6 | Ollama 本地模型 |
| langsmith-java | 0.1.0-alpha.24 | LangSmith 链路追踪 |
| ApacheJMeter_core | 5.6.3 | JMeter 核心 |
| snakeyaml | 2.2 | YAML 解析 |
| JUnit 5 | 5.10.1 | 单元测试 |
| Mockito | 5.8.0 | Mock 测试 |

## 配置

所有配置通过 JMeter 属性完成（通常在 `user.properties` 或 `jmeter.properties` 中）：

- `anthropic.api.key` / `openai.api.key` - API 凭证
- `claude.default.model` / `openai.default.model` / `ollama.default.model` - 模型选择
- `claude.temperature` / `openai.temperature` - 响应创造力 (0.0-1.0)
- `claude.max.history.size` / `openai.max.history.size` - 对话历史限制
- `jmeter.ai.service.type` - 代码重构服务（"openai" 或 "anthropic"）

## 开发参考

**JMeter 源码路径：**
```
D:\WorkHome\git\github\jmeter-5.6.3
```

关键类参考：
- **HTTPArgument** - `protocol/http/src/main/java/org/apache/jmeter/protocol/http/util/HTTPArgument.java`
- **Header** - `protocol/http/src/main/java/org/apache/jmeter/protocol/http/control/Header.java`
- **HeaderManager** - `protocol/http/src/main/java/org/apache/jmeter/protocol/http/control/HeaderManager.java`
- **HTTPSamplerProxy** - `protocol/http/src/main/java/org/apache/jmeter/protocol/http/sampler/HTTPSamplerProxy.java`

**注意事项：**
- 由于 `ApacheJMeter_http` 被排除在编译依赖之外，HTTP 相关类需要使用反射访问
- 插件运行时，JMeter 会提供完整的类路径

## JMeter 集成点

- **GuiPackage** - 访问 JMeter 的 GUI 上下文和树结构
- **JMeterTreeNode** - 导航和操作测试计划元素
- **TestElement** - 所有 JMeter 组件的基类

## 重要说明

- 插件使用 JMeter 5.6.3 作为依赖项（ApacheJMeter_core）
- **ELEMENT_CLASS_MAP** 注册了 172 个 JMeter 组件类映射（涵盖采样器、线程组、断言、定时器、前置/后置处理器、配置元件、监听器、控制器、测试片段等）
- 73 个组件拥有完整的参考文档和参数 Schema（覆盖 10 大类别：控制器 15、采样器 12、断言 9、线程组 8、配置元件 10、后置处理器 6、前置处理器 3、定时器 4、监听器 4、测试片段 2）
- 对话历史受到限制以防止 token 耗尽（默认：10 条消息）
- 系统提示仅在第一条消息时发送以节省 token
- 下拉菜单中的模型 ID 带有前缀（例如 "openai:gpt-4o"、"ollama:llama3.1"）
- **GenerationSettings** 是 LLM 默认参数的唯一来源
- Agent 通过 SkillsLoader 从文件系统动态加载技能
- 工具注册通过 JMeterToolRegistry 统一管理
- 聊天输入框支持 Shift+Enter 换行、拖拽调整消息区域与输入区域高度
- 支持 reasoningContent 结构化思考内容展示

## 添加新 JMeter 组件 Checklist

添加一个新的 JMeter 组件需要修改以下 5 处：

### 1. 新建参考文档（英文）

在 `src/main/jmeter-agent/skills/jmeter/references/{source}/{category}/` 下新建：

- `{ComponentName}.md` — 使用文档（描述、参数、示例、最佳实践、注意事项）
- `{ComponentName}.schema.yaml` — 参数 schema 定义（类型、必填、枚举、范围等）

`{source}` 按来源选：`native`（Apache JMeter 原生）/ `gitee-qa`（Gitee QA 扩展）/ `third-party`（外部第三方插件）。

`{category}` 对应子目录：`controllers`、`samplers`、`assertions`、`thread-group`、`timers`、`configuration`、`pre-processors`、`post-processors`、`listeners`、`test-fragments`

### 2. 更新 SKILL.md 组件索引

**文件：** `src/main/jmeter-agent/skills/jmeter/SKILL.md`

在对应的 Component Reference 表格中追加一行，包含 `elementType`、Description、Docs 链接和 Schema 链接。

### 3. 更新 JMeterElementManager.java（2 处）

**文件：** `src/main/java/org/gitee/jmeter/ai/utils/JMeterElementManager.java`

1. **`ELEMENT_CLASS_MAP`** — 添加 `elementType` → (模型类全限定名, GUI 类全限定名) 的映射
2. **`getDefaultNameForElement`** switch — 添加 `case "elementType":` 返回默认显示名称

### 总结

| 步骤 | 操作 | 文件 |
|------|------|------|
| 新建 | 使用文档 | `references/{source}/{category}/{Name}.md` |
| 新建 | 参数 schema | `references/{source}/{category}/{Name}.schema.yaml` |
| 追加 | 组件索引表 | `skills/jmeter/SKILL.md` |
| 追加 | 类映射 | `JMeterElementManager.java` → `ELEMENT_CLASS_MAP` |
| 追加 | 默认名称 | `JMeterElementManager.java` → `getDefaultNameForElement` |

前三步是 AI Agent 运行时所需的（技能文档和 schema 校验），后两步是插件代码层识别和创建组件所必需的。
