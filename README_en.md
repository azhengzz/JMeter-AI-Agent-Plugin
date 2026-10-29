# Gitee Ai - JMeter Agent

English | [中文](README.md)

Gitee Ai is a JMeter AI assistant plugin: describe what you need in natural language, and the AI creates, modifies, runs, and debugs test plans directly on your test plan tree.

![Gitee Ai](./images/JMeter-Agent-Demo-EN.gif)

## Key Features

- **Build a test plan in one sentence** — Describe the goal (e.g. "10 threads sending GET requests to an endpoint and asserting the response code"), and the AI creates thread groups, samplers, assertions, and timers directly on the test tree — no form-filling
- **Works directly on the test plan tree** — Create, update, delete, move, copy, and batch-enable/disable elements with instant visual feedback; ask the AI to analyze an existing plan or troubleshoot it
- **Automatic parameter validation** — Schemas for 73 JMeter components: wrong property names, types, or value ranges are rejected immediately instead of failing at runtime
- **Test runs handled by the AI too** — Start/stop tests, read live status and results (response time, throughput, error rate); assertion failures are surfaced to the AI for diagnosis
- **8 providers, DeepSeek out of the box** — Anthropic Claude, OpenAI, DeepSeek, Zhipu GLM, Moonshot Kimi, MiniMax, LangCat, plus local Ollama
- **Selection sync** — The chat panel shows the element you selected in JMeter in real time and injects it into the AI context with one click
- **Cross-session memory** — Remembers your preferences and past operations; archives automatically when JMeter closes
- **Safety guardrails** — File access whitelisting, SSRF protection, dangerous command blocking

## Quick Start

Requires **JMeter 5.6.3 + JDK 17 or later**.

### 1. Download

From the [Releases](https://github.com/azhengzz/JMeter-Agent/releases) page, download the two assets of the latest version:

- `jmeter-agent-x.y.z.jar` — the plugin itself
- `jmeter-agent.zip` — skills and templates

### 2. Install

- Put the jar into `<jmeter-home>/lib/ext/`
- Copy **everything** extracted from the zip into `<jmeter-home>/bin/jmeter-agent/`

### 3. Configure

Recommended: append the **entire contents** of [jmeter-ai-sample.properties](jmeter-ai-sample.properties) to `<jmeter-home>/bin/user.properties` (or `jmeter.properties`) — this puts every tunable option at hand for later adjustments.

Then set your API key. The default provider is DeepSeek — configuring its key is all you need:

```properties
deepseek.api.key=your-api-key
```

To use another provider, also set the provider, e.g. Anthropic Claude:

```properties
anthropic.api.key=your-api-key
jmeter.ai.default.provider=anthropic
```

| Provider | Get an API Key |
|----------|----------------|
| DeepSeek | [platform.deepseek.com](https://platform.deepseek.com) |
| Zhipu GLM | [open.bigmodel.cn](https://open.bigmodel.cn) |
| Moonshot Kimi | [platform.moonshot.cn](https://platform.moonshot.cn) |
| MiniMax | [api.minimax.com](https://api.minimax.com) |
| LangCat | [longcat.chat](https://longcat.chat) |
| Anthropic | [console.anthropic.com](https://console.anthropic.com) |
| OpenAI | [platform.openai.com](https://platform.openai.com) |

### 4. Restart JMeter

Configuration is read only at startup — after editing `user.properties` you must restart JMeter.

### 5. Start chatting

Open the chat panel via the **AI** menu or the toolbar button (shortcut `Alt+V`), then just describe what you need:

> Create a thread group with 10 threads, send GET requests to http://example.com, and add an assertion for response code 200

Common commands: `/new` fresh session · `/status` bot status · `/goal <objective>` let the AI iterate autonomously until the goal is met · `/help` list all commands.

> Prefer building from source? Set the `JMETER_HOME` environment variable and run `mvn clean install`.

## Troubleshooting

- **Changed the config but nothing happened** — `user.properties` is read only at startup; restart JMeter.
- **How do I switch models** — Set the provider's key plus `jmeter.ai.default.provider` (see step 3 above); you can also switch at runtime via the model picker below the chat input.
- **Don't want to pay for API usage** — Run local models with [Ollama](https://ollama.com), no API key needed: set `jmeter.ai.default.provider=ollama` (default endpoint `http://localhost:11434/v1`).
- **Alt+V does nothing / no AI menu** — Check that the jar is in `lib/ext/`, the zip contents are extracted to `bin/jmeter-agent/`, and JMeter has been restarted.

## Going Deeper

| What you want | Where |
|---------------|-------|
| Tune temperature, context window, etc. · every config property and per-model recommendations | [Configuration Reference](documents/configuration.md) (Chinese) |
| What tools the Agent has and what they can do | [Agent Tools](documents/agent-tools.md) (Chinese) |
| Drive JMeter from the command line · integrate Claude Code/Codex and other external Agents | [jmeter-cli Guide](documents/jmeter-cli.md) (Chinese) |
| How the Agent Loop works inside | [docs/AGENT-LOOP.md](docs/AGENT-LOOP.md) |
| Add new JMeter components for the Agent (zero Java changes) | [SCHEMA-GUIDE.md](documents/SCHEMA-GUIDE.md) |

## Disclaimer

- **AI limitations:** AI can produce incorrect information — verify all suggestions before using them in production
- **Back up your test plans:** Always back up before applying large AI-suggested changes
- **API costs:** API usage incurs token costs; keep an eye on consumption
- **Security:** Do not share sensitive information (credentials, proprietary code) in the conversation
- **Performance impact:** Some AI-suggested configurations may affect test performance; monitor resource usage

## Acknowledgements

This project references the following open-source projects in its design and implementation:

- [nanobot](https://github.com/HKUDS/nanobot) — Agent design and implementation reference
- [jmeter-ai](https://github.com/QAInsights/jmeter-ai) — design inspiration

## License

MIT License

<br>
<p align="center">
  Thanks for visiting ✨ <b>JMeter Ai Agent Plugin</b>
</p>
<p align="center">
  <img src="https://visitor-badge.laobi.icu/badge?page_id=azhengzz.JMeter-AI-Agent-Plugin" alt="visitors"/>
</p>
