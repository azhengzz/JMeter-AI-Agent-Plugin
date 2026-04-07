# CLAUDE.md

此文件为 Claude Code (claude.ai/code) 在处理本仓库代码时提供指导。

## 项目概述

Feather Wand (JMeter Agent) 是一个 JMeter 插件，提供 AI 驱动的聊天界面，用于创建、优化和排查 JMeter 测试计划。它集成了 Claude (Anthropic)、OpenAI 和 Ollama AI 模型。

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
mvn clean install
```

**跳过测试构建：**
```bash
mvn clean package -DskipTests
```

## 高层架构

插件采用分层架构，职责分离清晰：

### 服务层 (`org.qainsights.jmeter.ai.service`)
- **AiService** 接口定义了 AI 提供者的契约
- **ClaudeService** - 使用 anthropic-java SDK 集成 Anthropic Claude
- **OpenAiService** - 使用 openai-java SDK 集成 OpenAI GPT
- **OllamaAiService** - 使用 ollama4j 集成本地 Ollama 模型
- **CodeRefactorer** - 通过 AI 处理 JSR223 脚本重构

所有服务通过 JMeter 属性配置（`anthropic.api.key`、`openai.api.key` 等），通过 **AiConfig** 工具类访问。

### GUI 层 (`org.qainsights.jmeter.ai.gui`)
- **AiChatPanel** - 主 Swing 面板，包含聊天界面、模型选择器和元素建议
- **AiMenuItem** - 切换聊天面板的菜单项和工具栏按钮
- **JSR223ContextMenu** - JSR223 脚本编辑器的右键上下文菜单
- **MessageProcessor** - 处理 markdown 渲染和消息显示
- **ElementSuggestionManager** - 为 AI 响应中提到的 JMeter 元素创建可点击按钮

### 命令处理器
特殊命令由专用处理器处理：
- **@this** → 返回当前选中的 JMeter 元素信息（在 AiChatPanel 中处理）
- **@optimize** → **OptimizeRequestHandler** 分析和优化选中元素
- **@lint** → **LintCommandHandler** 使用 AI 重命名元素以更好地组织
- **@wrap** → **WrapCommandHandler** 将 HTTP 采样器分组到事务控制器下
- **@usage** → **UsageCommandHandler** 显示 token 使用统计
- **@code** → 已禁用（改用 JSR223 编辑器中的右键上下文菜单）

### 智能提示 (`org.qainsights.jmeter.ai.intellisense`)
- **CommandIntellisenseProvider** - 提供命令建议（@this、@optimize 等）
- **InputBoxIntellisense** - 将弹出补全附加到聊天输入框

### Claude Code 集成 (`org.qainsights.jmeter.ai.claudecode`)
- **ClaudeCodePanel** - 使用 JediTerm 的嵌入式终端，用于 Claude Code CLI
- **ClaudeCodeLocator** - 在系统上查找 Claude Code 可执行文件
- **TestPlanSerializer** - 将 JMeter 测试计划序列化为 Claude Code 上下文

## 关键设计模式

- **策略模式**：AiService 接口允许在 Claude、OpenAI 和 Ollama 之间切换
- **观察者模式**：树选择监听器触发 JSR223 编辑器的上下文菜单更新
- **工作者模式**：所有 AI API 调用使用 SwingWorker 以避免阻塞 UI

## 配置

所有配置通过 JMeter 属性完成（通常在 `user.properties` 或 `jmeter.properties` 中）：

- `anthropic.api.key` / `openai.api.key` - API 凭证
- `claude.default.model` / `openai.default.model` / `ollama.default.model` - 模型选择
- `claude.temperature` / `openai.temperature` - 响应创造力 (0.0-1.0)
- `claude.max.history.size` / `openai.max.history.size` - 对话历史限制
- `jmeter.ai.service.type` - 代码重构服务（"openai" 或 "anthropic"）
- `jmeter.ai.terminal.claudecode.enabled` - 启用/禁用 Claude Code 终端

## 开发参考

**JMeter 源码路径：**
```
D:\WorkHome\git\github\jmeter
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
- 对话历史受到限制以防止 token 耗尽（默认：10 条消息）
- 系统提示仅在第一条消息时发送以节省 token
- 下拉菜单中的模型 ID 带有前缀（例如 "openai:gpt-4o"、"ollama:llama3.1"）
