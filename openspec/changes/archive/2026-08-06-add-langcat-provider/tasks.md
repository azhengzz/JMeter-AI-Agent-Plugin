## 1. 供应商注册（ProviderRegistry）

- [x] 1.1 在 `ProviderRegistry.java` 静态块（L20-120，DeepSeek 之后、MiniMax 之前）新增 LangCat 注册：`name("langcat")`、`displayName("LangCat")`、`defaultApiBase("https://api.longcat.chat/openai/v1")`、`envKey("langcat.api.key")`、`keywords("langcat", "longcat")`、`thinkingStyle("thinking_type")`；不配置 `backend`（保持默认 `openai_compat`）、不配置 `thinkingModels`
- [x] 1.2 验证注册生效：`ProviderRegistry.findByName("langcat")` 返回非空 spec；`AiServiceFactory.createService("langcat:LongCat-2.0")` 返回 `OpenAICompatibleProvider` 实例且其 base URL 解析为 `https://api.longcat.chat/openai/v1`（无 `langcat.api.base.url` 覆盖时）

## 2. GUI 与遗留路由

- [x] 2.1 `AiChatPanel.java` 三处 switch（L155-169 模型选择监听、L610-620 `updateRawServiceForModel`、L639-652 `setModelForProvider`）的 `case "openai","deepseek","zhipu","moonshot","minimax"` 各并入 `"langcat"`，使 `langcat:` 前缀路由到 `openAiService.setModel(...)` 而非落到 `default`（ClaudeService）分支
- [x] 2.2 `OpenAiService.java:44-46` 的 `OPENAI_COMPATIBLE_PROVIDERS` 数组追加 `"langcat"`，使遗留模型加载路径的 `extractProvider` 能识别 `langcat:` 前缀
- [x] 2.3 验证：GUI 模型下拉出现 `langcat:LongCat-2.0`；选中后 `getAiServiceForCurrentModel`（`AiChatPanel.java:581-596`）经 `AiServiceFactory` 返回 langcat 服务而非 Claude；`openAiService.setModel("langcat:LongCat-2.0")` 不触发任何"未知供应商"回退

## 3. 配置与样例

- [x] 3.1 `jmeter-ai-sample.properties` 新增 `langcat.api.key`、`langcat.api.base.url`（默认注释展示 `https://api.longcat.chat/openai/v1`），并更新 L47 provider 列表注释及 L5/L69/L113 头部注释，把 `langcat` 列入支持供应商
- [x] 3.2 验证：以 `langcat.api.base.url` 覆盖端点时 `OpenAICompatibleProvider` 构造器（`spec.getName() + ".api.base.url"`）读到覆盖值；未配置时回落默认端点

## 4. 思考与 reasoning 行为确认

- [x] 4.1 确认思考链路无需改动：LangCat `thinking: {"type":"enabled"|"disabled"}` 由 `thinking_type` 样式经 extra_body 注入（`OpenAICompatibleProvider.java:394-409`），`reasoning_content` 由现有响应提取逻辑处理（L593-603）；不写新代码，仅以测试/日志确认请求含 `thinking` 且响应 `reasoning_content` 进入展示管线
- [x] 4.2 验证：构造一次 `langcat:LongCat-2.0` 服务，`reasoning_effort` 非 none 时工具路径请求体含 `extra_body.thinking={type=enabled}`；响应含 `reasoning_content` 时被提取并走 MessageProcessor 展示

## 5. 构建与回归

- [x] 5.1 `mvn clean package -DskipTests` 编译通过（无新依赖，无需改 pom）
- [x] 5.2 `mvn test` 无新增失败（基线已知 1 个既有 CodeRefactorerTest 失败除外，见记忆 [[coderefactorer-test-preexisting-fail]]）
- [x] 5.3 确认对既有供应商无回归：`AiServiceFactory` 未改动、既有 switch case 未改动，openai/deepseek/zhipu/moonshot/minimax/anthropic/ollama 行为不变
