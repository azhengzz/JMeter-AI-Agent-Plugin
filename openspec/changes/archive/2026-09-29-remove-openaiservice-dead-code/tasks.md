# Tasks

> 行号为 2026-09-29 快照，实施时以符号锚点为准（详见 design.md 决策 1 清单）。

## 1. 摘除 AiChatPanel 接线

- [x] 1.1 删除 import（`AiChatPanel.java:48`）、字段声明（:90，`private OpenAiService openAiService;`）、构造行（:146，`openAiService = new OpenAiService();`；保留 :144 注释与 :145 `claudeService` 行）；验证：`openAiService` 在 AiChatPanel.java 仅剩注释位（:240/:1006）
- [x] 1.2 删除模型选择监听器中 `:243` 的 `openAiService.setModel(selectedModel);` 调用体，**保留** openai 族 case 标签（`case "openai", "deepseek", "zhipu", "moonshot", "minimax", "langcat", "ollama"`，:242）为空体 + 自包含注释（路由经 `AiServiceFactory`，无需本地簿记；勿并入 default，防误触 `claudeService.setModel`）；同步改写 :240 过时注释；验证：该 case 体仅含注释与 `break`
- [x] 1.3 删除 `updateRawServiceForModel`（:1009-1032）内 `:1019` 的 `openAiService.setModel(modelId);` 调用体，同样保留 :1018 case 标签为空体 + 注释；改写 :1006 javadoc 去掉 openAiService 提及；方法本体与 claudeService 分支（:1022-1031）、两个调用方（:724/:999）不动；验证：方法内不再出现 `openAiService`

## 2. 删除主类与专属测试

- [x] 2.1 删除 `src/main/java/org/gitee/jmeter/ai/service/OpenAiService.java` 整文件（557 行）；验证：文件不存在
- [x] 2.2 删除 `src/test/java/org/gitee/jmeter/ai/service/OpenAiServiceTest.java` 整文件（214 行）；验证：文件不存在
- [x] 2.3 `mvn clean test-compile` 编译通过（此时 OpenAiUsage 尚存但已零调用，编译不受影响；验证输出文件本体含 BUILD SUCCESS）

## 3. 级联删除二级死代码

- [x] 3.1 删除 `src/main/java/org/gitee/jmeter/ai/usage/OpenAiUsage.java` 整文件（唯一生产调用方已随组 2 消失）；验证：文件不存在
- [x] 3.2 删除 `AiConfig.getOpenAiApiKey()`（`AiConfig.java:378-381`；唯一调用方是 OpenAiUsage.java:38）；验证：`getOpenAiApiKey` 全库清零
- [x] 3.3 `mvn clean test-compile` 编译通过（验证输出文件本体含 BUILD SUCCESS）

## 4. 注释与文档同步

- [x] 4.1 `ClaudeService.java:179` 注释去掉 "OpenAiService /" 提及（仅保留 OpenAICompatibleProvider 对照）；验证：grep `OpenAiService` 在该文件清零
- [x] 4.2 `OpenAICompatibleProviderTest.java:32` javadoc 去掉 `{@code OpenAiServiceTest}` 悬空引用；验证：grep 清零
- [x] 4.3 `AGENTS.md` 删除服务层 `- **OpenAiService**` 条目（:230）与使用统计 `- **OpenAiUsage**` 条目（:278）；验证：grep `OpenAiService|OpenAiUsage` 在 AGENTS.md 清零

## 5. code-hygiene 门禁（集成验证）

- [x] 5.1 残留引用清零：全词 grep `OpenAiService|openAiService|OpenAiUsage|getOpenAiApiKey|OPENAI_COMPATIBLE_PROVIDERS` 于 `src/main`、`src/test`、`AGENTS.md`，命中数 = 0（含字符串/反射位甄别；`AnthropicUsage`、`OpenAICompatibleProvider` 等相近活符号不得误伤）
- [x] 5.2 `mvn clean test` 全绿：读输出文件本体确认 `BUILD SUCCESS` 与测试统计行，不与并行任务混跑；失败先单独复跑分流再归因
- [ ] 5.3 （可选）`mvn clean install` 后启动 JMeter GUI 冒烟：模型下拉出现默认 `openai:` 前缀条目、发送消息正常走 `OpenAICompatibleProvider`（日志可见服务创建 `Created and cached service for: openai:...`）
