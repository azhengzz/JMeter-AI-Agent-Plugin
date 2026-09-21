# remove-code-refactorer Design

## Context

当前 JSR223 脚本编辑器被插件注入了自定义右键菜单（`JSR223ContextMenu`），其菜单项包括 "Refactor Code"、"Try, Catch, Finally"（均调用 `CodeRefactorer` 走 AI 重构）、占位性质的 "Format Code"、以及 "Functions Dialog"。整个子系统围绕 AI 重构设计：

- `JSR223ContextMenu.initialize(AiService)` 在 `AiMenuItem` 构造函数中被调用，`sharedAiService` 只服务于该菜单。
- `AiMenuItem.createAiService(String)` 仅为此初始化创建 provider 服务实例。
- `AiMenuItem.addTreeSelectionListener()` 的树选择监听器唯一目的是在选中 JSR223 组件时挂上该右键菜单。

经确认，用户选择**整体移除该右键菜单功能**（而非仅删除两项 AI 菜单项），因为菜单的初始化逻辑、共享 `AiService`、树监听器都只为重构服务；移除后 JSR223 编辑器恢复 JMeter 自带默认右键菜单。

## Goals / Non-Goals

**Goals:**
- 删除 `JSR223ContextMenu`、`CodeRefactorer` 及 `CodeRefactorerTest` 三个源文件。
- 清理 `AiMenuItem` 中因此不再使用的接线代码与 import，使该类无编译警告、无死代码。
- 移除 `jmeter.ai.refactoring.enabled` 样例配置，同步更新 `AGENTS.md` / `CLAUDE.md` 文档。
- `mvn clean package` 编译通过，`mvn test` 全绿（此前常驻的 1 个失败即来自 `CodeRefactorerTest`）。

**Non-Goals:**
- 不改动 `AiService` 接口及各 AI provider（`ClaudeService` / `OpenAiService` / `OllamaAiService` / 兼容 provider）。
- 不改动 JMeter 原生 JSR223 编辑行为——移除后交给 JMeter 默认右键菜单。
- 不重构 `AiMenuItem` 中与本次无关的既有逻辑（工具栏图标、聊天面板开合）。
- 不删除 `AGENTS.md`/`CLAUDE.md` 中仅属一般性措辞（如"refactor"指南字样）的内容，只删对 `CodeRefactorer` / `JSR223ContextMenu` / `CodeRefactorerTest` 的指称。

## Decisions

### D1. 整体删除右键菜单，而非仅删 AI 项
**决策**：删除 `JSR223ContextMenu` 全类，同时删除 `CodeRefactorer` 与 `CodeRefactorerTest`。
**理由**：`JSR223ContextMenu` 的存在意义即 AI 重构；其 `isAiRefactoringEnabled()`、`sharedAiService`、`addContextMenuToCurrentEditor()`、窗口扫描（`findRSyntaxTextAreaInAllWindows`）等约 450 行代码在去掉 AI 项后全部沦为死代码。仅保留的 "Cut/Copy/Paste/Select All/Format Code/Functions Dialog" 不构成保留这类复杂度的理由。用户已确认此范围。
**备选**：仅删两个 AI 菜单项 → 需保留大量只为挂菜单服务的扫描代码，与"冗余代码需要清理"的诉求相悖，弃。

### D2. `AiMenuItem` 接线清理范围
**决策**：
- 删除构造函数中 `JSR223ContextMenu.initialize(...)` 初始化块（含 `createAiService(aiServiceType)` 调用与 try/catch）。
- 删除私有方法 `createAiService(String)` 与 `addTreeSelectionListener()`。
- 删除因此不再使用的 import：`AiService`、`OpenAiService`、`ClaudeService`、`OllamaAiService`、`service.provider.AiServiceFactory`。
**理由**：这些成员只被右键菜单功能使用；保留即违反"冗余代码清理"。逐 import 核对后再删，避免误删仍被 `AiChatPanel` 等使用的类型。
**备选**：保留 `createAiService` 以备后用 → 无实际调用方，属死代码，弃。

### D3. 文档与配置同步移除
**决策**：
- `jmeter-ai-sample.properties` 删除 `jmeter.ai.refactoring.enabled` 及其注释块（约 4 行）。
- `AGENTS.md` 删除服务层 `CodeRefactorer` 条目与测试结构 `CodeRefactorerTest` 条目。
- `CLAUDE.md` 删除服务层架构描述中的 `CodeRefactorer` 行及 GUI 层中的 `JSR223ContextMenu` 行；测试结构部分含 `CodeRefactorerTest` 的描述按实际调整。
**理由**：文档须与代码一致，否则误导后续维护者。

## Risks / Trade-offs

- **JSR223 编辑器失去"Functions Dialog"快捷入口** → 该功能是 JMeter 内建（`ActionNames.FUNCTIONS`），用户仍可通过 JMeter 菜单访问，影响可接受；此项移除是本变更的固有语义。
- **误删仍在使用的 import 导致编译失败** → 逐一 grep `AiMenuItem` 内各 import 的实际引用后再删除；以 `mvn clean package` 兜底验证。
- **`AiMenuItem` 构造函数异常路径行为变化**：原先初始化失败仅记日志（不阻断），移除后无此分支 → 行为更简，无风险。
- **`mvn test` 基线变化**：移除 `CodeRefactorerTest` 后该常驻失败消失；判定回归时以其余测试通过为准。

## Migration Plan

- 直接删除文件 + 接线清理，属向后不兼容的主动移除（**BREAKING**），无灰度需求。
- 回滚：`git revert` 即可恢复全部文件与配置；`JSR223ContextMenu` 无状态持久化，无数据迁移。

## Open Questions

无。
