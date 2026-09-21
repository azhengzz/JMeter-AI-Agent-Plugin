# remove-code-refactorer

## Why

插件内置的"AI 代码重构"功能为一整个子系统：`CodeRefactorer` 服务 + `JSR223ContextMenu` 右键菜单，后者的初始化、共享 `AiService` 创建（`AiMenuItem`）、树选择监听器全部只服务于重构这一件事。该功能价值低而复杂度高（约千行窗口扫描代码），且常驻一个已知失败的测试（`CodeRefactorerTest` 的静态 mock 契约），拖累 `mvn test`。移除后 JSR223 脚本编辑器恢复 JMeter 默认右键菜单，冗余接线代码一并清理。

## What Changes

- **BREAKING** 删除 `JSR223ContextMenu` 整个右键菜单功能：JSR223 脚本编辑器恢复 JMeter 默认右键菜单；自定义的 "Refactor Code"、"Try, Catch, Finally"、"Format Code"、"Functions Dialog" 菜单项全部消失。
- **BREAKING** 删除 `CodeRefactorer` 服务及其 `refactorSelectedCode` / `refactorTryCatchFinally` / `cleanUpCodeResponse` 逻辑。
- 删除 `CodeRefactorerTest`（顺带消除 `mvn test` 中唯一的常驻失败）。
- 清理 `AiMenuItem` 中的冗余接线：构造函数里的右键菜单初始化块、私有 `createAiService` 方法、`addTreeSelectionListener` 方法，以及因此不再使用的 import（`AiService`、`OpenAiService`、`ClaudeService`、`OllamaAiService`、`AiServiceFactory`）。
- 删除样例配置项 `jmeter.ai.refactoring.enabled`。
- 同步更新 `AGENTS.md` / `CLAUDE.md` 架构文档，移除对 `CodeRefactorer`、`JSR223ContextMenu`、`CodeRefactorerTest` 的描述。

## Capabilities

### New Capabilities
- `jsr223-context-menu`: 记录被移除的 JSR223 编辑器自定义右键菜单（AI 代码重构）功能。openspec/specs 下无既有对应能力，故以新 delta 形式建档，内容为 **REMOVED Requirements**（附 Reason 与 Migration），归档后作为该功能移除的历史记录。

### Modified Capabilities

（无 —— 现有 OpenSpec 能力（async-subagent / langcat-provider / run-result-capture）均不涉及 JSR223 右键菜单或代码重构）

## Impact

- **删除文件**：`src/main/java/org/gitee/jmeter/ai/gui/JSR223ContextMenu.java`、`src/main/java/org/gitee/jmeter/ai/service/CodeRefactorer.java`、`src/test/java/org/gitee/jmeter/ai/service/CodeRefactorerTest.java`
- **修改文件**：`src/main/java/org/gitee/jmeter/ai/gui/AiMenuItem.java`（接线清理）、`jmeter-ai-sample.properties`（配置移除）、`AGENTS.md`、`CLAUDE.md`
- **不涉及**：`AiService` 接口、各 AI provider、Agent 工具层均不受影响（`CodeRefactorer` 仅被 `JSR223ContextMenu` 使用，无其他调用方）
- **验证**：`mvn clean package` 编译通过；`mvn test` 全绿（移除前常驻 1 个失败）
