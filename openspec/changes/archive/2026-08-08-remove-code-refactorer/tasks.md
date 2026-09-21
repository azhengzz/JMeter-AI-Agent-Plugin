# remove-code-refactorer Tasks

## 1. 删除源码与测试文件

- [x] 1.1 删除 `src/main/java/org/gitee/jmeter/ai/service/CodeRefactorer.java`
- [x] 1.2 删除 `src/main/java/org/gitee/jmeter/ai/gui/JSR223ContextMenu.java`
- [x] 1.3 删除 `src/test/java/org/gitee/jmeter/ai/service/CodeRefactorerTest.java`

## 2. 清理 AiMenuItem 接线

- [x] 2.1 删除构造函数中 JSR223 右键菜单初始化块（`AiConfig.getDefaultProvider()` + `createAiService` 调用 + `JSR223ContextMenu.initialize(...)` + 相应 try/catch 与日志）
- [x] 2.2 删除私有方法 `createAiService(String)`
- [x] 2.3 删除私有方法 `addTreeSelectionListener()` 及构造函数中对它的调用
- [x] 2.4 删除因此不再使用的 import：`AiService`、`OpenAiService`、`ClaudeService`、`OllamaAiService`、`service.provider.AiServiceFactory`、`utils.AiConfig`（均仅被已删代码使用）

## 3. 配置与文档同步移除

- [x] 3.1 从 `jmeter-ai-sample.properties` 删除 `jmeter.ai.refactoring.enabled` 配置及其上方注释块
- [x] 3.2 更新 `AGENTS.md`：删除服务层 `CodeRefactorer` 条目与测试结构 `CodeRefactorerTest` 条目（勿删第 37 行等一般性指南措辞）
- [x] 3.3 更新 `CLAUDE.md`：删除服务层 `CodeRefactorer` 行、GUI 层 `JSR223ContextMenu` 行、测试结构 `CodeRefactorerTest` 行（勿删通用"refactor"指南字样）

## 4. 验证

- [x] 4.1 `mvn clean package` 编译通过
- [x] 4.2 `mvn test` 全绿（不再有 `CodeRefactorerTest` 常驻失败）
- [x] 4.3 grep 确认 `src/` 下无 `JSR223ContextMenu` / `CodeRefactorer` 残留引用
- [x] 4.4 GUI 冒烟：JSR223 脚本编辑器右键显示 JMeter 默认菜单，不再出现 "Refactor Code" / "Try, Catch, Finally" / "Format Code"
