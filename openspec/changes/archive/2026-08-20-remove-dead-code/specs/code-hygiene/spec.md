## ADDED Requirements

### Requirement: 死代码清理范围确定性

代码库 SHALL 移除经逐一求证的死代码（零引用、零实例化、零反射/字符串依赖的类、方法与字段），并 SHALL 以 `mvn clean test` 全绿作为删除正确性的编译门禁。

#### Scenario: 删除后无残留引用导致编译失败
- **WHEN** 实施者完成任一死代码删除（类、方法或字段）
- **THEN** `mvn clean test` 编译通过且测试全绿
- **AND** 全库 `src/main` 与 `src/test` 中不存在对被删符号的任何引用（含 `Class.forName`/字符串查找）

#### Scenario: 同名活符号不被误删
- **WHEN** 被删符号与活符号同名（如 `AgentConfig.isFailOnToolError()` 与 `AgentRunSpec.isFailOnToolError()`、`AgentConfig.getMaxIterations()` 与 `AgentRunSpec.getMaxIterations()`）
- **THEN** 仅删除无调用方的那个符号
- **AND** 活符号的调用方（如 `AgentRunner` 对 `AgentRunSpec` 版本 getter 的引用）保持完整
