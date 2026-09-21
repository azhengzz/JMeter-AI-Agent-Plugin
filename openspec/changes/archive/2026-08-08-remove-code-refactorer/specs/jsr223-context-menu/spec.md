# jsr223-context-menu Specification

## REMOVED Requirements

### Requirement: JSR223 编辑器注入自定义右键菜单

插件 SHALL 不再向 JSR223 脚本编辑器注入自定义右键菜单。此前 `JSR223ContextMenu` 在 `AiMenuItem` 初始化与树选择监听器中为 JSR223 编辑器挂载右键菜单，提供 "Refactor Code"、"Try, Catch, Finally"、"Format Code"、"Functions Dialog" 等菜单项；该菜单的初始化逻辑、共享 `AiService` 创建与树监听器全部只为 AI 重构服务，随功能整体移除。

**Reason**: AI 代码重构功能（`CodeRefactorer` + `JSR223ContextMenu`）价值低而复杂度高，且 `CodeRefactorerTest` 常驻失败拖累 `mvn test`。经决策整体移除该子系统，冗余接线代码一并清理，JSR223 脚本编辑器恢复 JMeter 默认右键菜单。

**Migration**: 插件不再修改 JSR223 编辑器的右键菜单，用户使用 JMeter 自带默认菜单。JMeter 内建 "Functions Dialog"（`ActionNames.FUNCTIONS`）仍可通过 JMeter 菜单访问。

### Requirement: AI 代码重构服务 CodeRefactorer

系统 SHALL 不再提供 `CodeRefactorer` AI 代码重构服务及其 `refactorSelectedCode` / `refactorTryCatchFinally` / `cleanUpCodeResponse` 行为。右键菜单中的 "Refactor Code" 与 "Try, Catch, Finally" 入口一并消失。

**Reason**: 该服务仅被 `JSR223ContextMenu` 使用，无其他调用方；功能移除后属冗余代码，随主类一并删除（含 `CodeRefactorerTest`）。

**Migration**: 无替代功能；需要重构脚本的用户改用手动编辑或通过 Agent 聊天面板完成。

### Requirement: jmeter.ai.refactoring.enabled 配置项

系统 SHALL 不再读取或暴露 `jmeter.ai.refactoring.enabled` 配置项（此前用于开关 JSR223 右键菜单中的 AI 重构入口，默认 `true`）。

**Reason**: 相关功能已整体移除，配置项失去意义，从样例配置与文档中删除以免误导。

**Migration**: 移除即可；无其他配置项受此影响。
