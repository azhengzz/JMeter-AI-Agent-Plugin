# Feather Wand (JMeter AI Agent) 代码结构详解

## 目录结构概览

```
src/main/java/org/qainsights/jmeter/ai/
├── claudecode/           # Claude Code 终端集成
├── gui/                  # 用户界面组件
├── intellisense/         # 智能提示功能
├── lint/                 # 代码检查和重命名
├── optimizer/            # 测试计划优化
├── service/              # AI 服务层
├── terminal/             # 终端相关
├── usage/                # 使用统计
├── utils/                # 工具类
└── wrap/                 # 元素包装功能
```

---

## 1. 服务层 (`service/`)

服务层是整个插件的核心，负责与各个 AI 提供商进行通信。

### 1.1 `AiService.java` - AI 服务接口

```java
public interface AiService {
    String generateResponse(List<String> conversation);
    String generateResponse(List<String> conversation, String model);
    String getName();
}
```

**职责**：定义了所有 AI 服务实现必须遵循的契约接口。

### 1.2 `ClaudeService.java` - Anthropic Claude 实现

**关键特性**：
- 使用 `anthropic-java` SDK 与 Claude API 通信
- 支持自定义 API base URL（用于代理或私有部署）
- 实现对话历史管理（默认保存最近 10 条消息）
- 系统提示词仅在首次发送时包含，节省 token
- 支持 token 使用量估算和记录

**重要配置**（通过 JMeter properties）：
| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `anthropic.api.key` | - | API 密钥 |
| `anthropic.api.base.url` | - | 自定义 API 地址 |
| `claude.default.model` | `claude-sonnet-4-6` | 默认模型 |
| `claude.temperature` | `0.5` | 响应随机性 |
| `claude.max.tokens` | `1024` | 最大 token 数 |
| `claude.max.history.size` | `10` | 对话历史大小 |
| `claude.system.prompt` | 内置提示词 | 自定义系统提示 |

**核心方法**：
- `generateResponse()` - 生成 AI 响应
- `setModel()` / `getCurrentModel()` - 模型管理
- `estimateTokens()` - Token 估算（字符数 / 4）
- `extractUserFriendlyErrorMessage()` - 友好的错误消息处理

### 1.3 `OpenAiService.java` - OpenAI GPT 实现

**关键特性**：
- 使用 `openai-java` SDK 与 OpenAI API 通信
- 支持自定义 API base URL
- 对话历史管理
- 准确的 token 使用统计（API 返回）

**重要配置**：
| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `openai.api.key` | - | API 密钥 |
| `openai.api.base.url` | - | 自定义 API 地址 |
| `openai.default.model` | `gpt-4o` | 默认模型 |
| `openai.temperature` | `0.7` | 响应随机性 |
| `openai.max.tokens` | `4096` | 最大 token 数 |

### 1.4 `OllamaAiService.java` - 本地 Ollama 模型

**关键特性**：
- 使用 `ollama4j` SDK 与本地 Ollama 服务通信
- 支持所有 Ollama 托管的本地模型
- 无需 API 密钥，完全离线运行

**重要配置**：
| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `ollama.default.model` | `llama3.1` | 默认模型 |
| `ollama.base.url` | `http://localhost:11434` | Ollama 服务地址 |

### 1.5 `CodeRefactorer.java` - 代码重构服务

**职责**：通过 AI 重构 JSR223 脚本代码，提高代码质量和可读性。

---

## 2. GUI 层 (`gui/`)

GUI 层负责所有用户界面组件的实现。

### 2.1 `AiChatPanel.java` - 主聊天面板

**核心组件**：
- `JTextPane chatArea` - 聊天消息显示区域
- `JTextArea messageField` - 用户输入框
- `JComboBox<String> modelSelector` - 模型选择器
- `TreeNavigationButtons` - 树导航按钮
- `ElementSuggestionManager` - 元素建议管理器

**关键功能**：

| 功能 | 说明 |
|------|------|
| 消息发送 | Enter 发送，Shift+Enter 换行 |
| 模型切换 | 动态加载 Claude、OpenAI、Ollama 模型 |
| 新对话 | 点击 "+" 按钮清空历史 |
| 撤销/重做 | Ctrl+Z / Ctrl+Shift+Z (Cmd+Z / Cmd+Shift+Z on Mac) |
| 主题适配 | 支持亮色/暗色主题，随 JMeter 缩放调整字体 |

**命令处理**：
```java
@this      // 返回当前选中元素信息
@optimize  // 分析并优化选中元素
@lint      // 使用 AI 重命名元素
@wrap      // 将 HTTP 采样器分组到事务控制器下
@usage     // 显示 token 使用统计
@code      // 已禁用，改用 JSR223 编辑器右键菜单
```

**设计模式**：
- **组合模式**：将 `MessageProcessor` 和 `ElementSuggestionManager` 作为独立组件组合
- **观察者模式**：监听 JMeter UI 缩放事件

### 2.2 `MessageProcessor.java` - 消息处理和 Markdown 渲染

**支持的 Markdown 语法**：
- 标题：`# H1`, `## H2`, `### H3`
- 粗体：`**text**`
- 斜体：`*text*`
- 行内代码：`` `code` ``
- 代码块：``` \```lang\ncode\n``` ```

**代码块特性**：
- 显示语言标签
- 一键复制按钮
- 自适应主题背景色

### 2.3 `ElementSuggestionManager.java` - 元素建议管理器

**职责**：解析 AI 响应中的 JMeter 元素名称，创建可点击的建议按钮。

### 2.4 `AiMenuItem.java` - 菜单项和工具栏按钮

**职责**：在 JMeter 菜单栏和工具栏中添加 AI 聊天面板的切换入口。

### 2.5 `JSR223ContextMenu.java` - JSR223 右键菜单

**职责**：在 JSR223 脚本编辑器中添加"Refactor with AI"右键菜单项。

### 2.6 其他 GUI 组件

| 类名 | 职责 |
|------|------|
| `AiMenuCreator` | 创建 AI 菜单结构 |
| `ChatUIManager` | 聊天 UI 状态管理 |
| `CodeCommandHandler` | 代码重构命令处理 |
| `ComponentFinder` | 查找 Swing 组件 |
| `ConversationManager` | 对话历史管理 |
| `JMeterElementManager` | JMeter 元素创建和管理 |
| `TreeNavigationButtons` | 树节点导航按钮 |

---

## 3. 智能提示层 (`intellisense/`)

### 3.1 `CommandIntellisenseProvider.java` - 命令提示提供者

**支持的命令**：
- `@code`
- `@wrap`
- `@lint`
- `@usage`
- `@optimize`
- `@this`

### 3.2 `InputBoxIntellisense.java` - 输入框智能提示

**职责**：在聊天输入框中绑定弹出式自动完成功能。

### 3.3 `IntellisensePopup.java` - 提示弹出窗口

**职责**：渲染命令建议的弹出 UI。

---

## 4. 命令处理器

### 4.1 `lint/` - 代码检查和重命名

**`LintCommandHandler.java`**
- 处理 `@lint` 命令
- 调用 `ElementRenamer` 执行重命名操作
- 支持撤销/重做功能

**`ElementRenamer.java`**
- 使用 AI 分析元素并生成有意义的名称
- 批量重命名测试计划中的元素
- 维护重命名历史栈

### 4.2 `optimizer/` - 测试计划优化

**`OptimizeRequestHandler.java`**
- 处理 `@optimize` 命令
- 分析选中元素的属性
- 针对不同元素类型提供定制化优化建议

**支持的元素类型优化**：
| 元素类型 | 优化重点 |
|----------|----------|
| HTTPSampler | 连接池、超时、header 管理、参数处理 |
| ThreadGroup | 线程数、启动时间、循环配置 |
| Timer | 延迟值、吞吐量影响、真实用户行为 |
| Assertion | 断言范围、模式匹配效率 |
| Extractor/PostProcessor | 提取效率、正则优化、变量命名 |

### 4.3 `wrap/` - 元素包装功能

**`WrapCommandHandler.java`**
- 处理 `@wrap` 命令
- 智能将相似的 HTTP 采样器分组到事务控制器下
- 支持撤销/重做功能

**`WrapUndoRedoHandler.java`**
- 单例模式管理包装操作的撤销/重做状态

**分组算法**：
1. 查找线程组下的所有采样器
2. 根据名称模式进行分组（替换数字 ID 和 UUID 为占位符）
3. 为每个分组创建事务控制器
4. 保持原始元素顺序

### 4.4 `usage/` - 使用统计

**`UsageCommandHandler.java`**
- 处理 `@usage` 命令
- 根据 AI 服务类型调用相应的使用统计

**`AnthropicUsage.java`** / **`OpenAiUsage.java`**
- 单例模式记录 token 使用情况
- 按模型分别统计
- 提供格式化的使用报告

---

## 5. Claude Code 集成 (`claudecode/`)

### 5.1 组件列表

| 类名 | 职责 |
|------|------|
| `ClaudeCodePanel` | 嵌入式终端面板（使用 JediTerm） |
| `ClaudeCodeLocator` | 查找系统中的 Claude Code 可执行文件 |
| `ClaudeCodeMenuItem` | Claude Code 终端的菜单入口 |
| `DarkTerminalSettingsProvider` | 暗色主题终端设置 |
| `JMeterActionBridge` | JMeter 操作桥接 |
| `TestPlanSerializer` | 将测试计划序列化为 Claude Code 上下文 |

---

## 6. 工具类 (`utils/`)

### 6.1 `AiConfig.java`

**职责**：统一的配置读取入口，封装 JMeter 属性访问。

```java
public static String getProperty(String key, String defaultValue)
```

### 6.2 `JMeterElementManager.java`

**职责**：
- 检查测试计划是否就绪
- 创建 JMeter 元素
- 获取元素描述信息

### 6.3 `JMeterElementRequestHandler.java`

**职责**：处理用户请求，判断是否需要创建 JMeter 元素。

### 6.4 `Models.java`

**职责**：获取各 AI 提供商的可用模型列表。

### 6.5 `VersionUtils.java`

**职责**：读取插件版本信息。

---

## 7. 终端相关 (`terminal/`)

### 7.1 `DisabledTtyConnector.java`

**职责**：为 JediTerm 终端提供禁用 TTY 的连接器。

---

## 设计模式总结

| 设计模式 | 应用位置 | 说明 |
|----------|----------|------|
| **策略模式** | `AiService` 接口及其实现 | 可切换不同的 AI 提供商 |
| **观察者模式** | `PropertyChangeListener` | 监听 UI 缩放事件 |
| **单例模式** | `AnthropicUsage`, `OpenAiUsage`, `WrapUndoRedoHandler` | 全局状态管理 |
| **组合模式** | `AiChatPanel` 使用独立组件 | 职责分离，提高可维护性 |
| **工作者模式** | `SwingWorker` | 所有 AI API 调用避免阻塞 UI |

---

## 数据流图

```
用户输入消息
    ↓
AiChatPanel.sendMessage()
    ↓
命令识别 (@this, @optimize, @lint, @wrap, @usage, @code)
    ↓
┌─────────────┬─────────────┬─────────────┬─────────────┐
│   @this     │  @optimize  │    @lint    │    @wrap    │
│ 直接处理     │ OptimizeReq │ LintCommand │ WrapCommand │
│             │ uestHandler │   Handler   │   Handler   │
└─────────────┴─────────────┴─────────────┴─────────────┘
    ↓
AiService.generateResponse()
    ↓
┌─────────────┬─────────────┬─────────────┐
│  Claude API │  OpenAI API │  Ollama API │
└─────────────┴─────────────┴─────────────┘
    ↓
返回 AI 响应
    ↓
MessageProcessor 处理 Markdown
    ↓
ElementSuggestionManager 创建建议按钮
    ↓
显示在聊天区域
```

---

## 扩展点

如果要添加新的 AI 服务提供商：

1. 实现 `AiService` 接口
2. 在 `AiChatPanel` 中注册新服务
3. 在 `Models.java` 中添加模型列表获取逻辑
4. 在配置文件中添加相应的配置项

如果要添加新的命令：

1. 在 `CommandIntellisenseProvider` 中注册命令
2. 在 `AiChatPanel.sendMessage()` 中添加命令检测逻辑
3. 创建相应的 Handler 类处理命令逻辑
