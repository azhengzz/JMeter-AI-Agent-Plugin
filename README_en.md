# Gitee Ai - JMeter Agent

English | [中文](README.md)

Gitee Ai is a JMeter AI Agent plugin powered by an Agent Loop architecture that drives iterative cycles of LLM calls, tool execution, and result feedback — enabling intelligent test plan creation, optimization, and debugging within JMeter.

![Gitee Ai](./images/Gitee-AI-Agent-JMeter.png)

## Key Features

- **Agent Loop Architecture** — Full iterative cycle of LLM call → tool execution → result feedback, supporting multi-turn tool calling for complex tasks
- **20+ Agent Tools** — Covering JMeter element CRUD, test execution, filesystem, web search, and command execution
- **Skills System** — Dynamically loaded skill modules from filesystem, with built-in JMeter expertise (60+ component references), API autotest, and more
- **7 AI Providers** — DeepSeek, Zhipu GLM, Moonshot Kimi, MiniMax
- **Component Schema Validation** — 46 YAML schema files providing type, required, enum, and range validation for JMeter component parameters
- **Memory System** — Two-layer memory architecture (long-term memory + event history) with cross-session consolidation
- **Security Controls** — File access whitelisting, SSRF protection, dangerous command blocking
- **Tracing** — Optional LangSmith integration for LLM call tracing and monitoring
- **Claude Code Integration** — Embedded terminal for using Claude Code CLI directly within JMeter

## Architecture Overview

```
User Message
  ↓
CommandRouter (command routing)
  ↓
AgentLoop (main loop)
  ├── ContextBuilder (build context)
  ├── LLM API Call (Claude / OpenAI / Ollama / ...)
  ├── ToolRegistry (tool registry)
  │   ├── JMeter Element Tools (element CRUD)
  │   ├── Test Execution Tools (run/status/results)
  │   ├── Filesystem Tools (read/write/edit)
  │   ├── Web Tools (search/fetch)
  │   └── Exec Tool (command execution)
  ├── SkillsLoader (skill loading)
  ├── MemoryStore (memory storage)
  └── SessionManager (session management)
  ↓
Response to Chat UI
```

**Tool Call Flow:** LLM decides to call a tool → ToolRegistry finds and executes it → SchemaBasedPropertyHandler validates parameters → Result fed back to LLM → Continue iterating or return final response

## Installation

### Prerequisites

- **JMeter** 5.6.3
- **JDK** 17 or higher

### Manual Installation

1. Place the `jmeter-agent-xxx.jar` file into the `jmeter/lib/ext` directory



## Quick Start

1. **Configure API Key** — Set your AI provider key in `user.properties`:
   ```properties
   # Using MiniMax (default provider)
   minimax.api.key=your-api-key

   # Or using Anthropic Claude
   anthropic.api.key=your-api-key
   jmeter.ai.default.provider=anthropic
   ```
2. **Open Chat Panel** — Right-click `Add > Non-Test Elements > Gitee Ai`
3. **Start Chatting** — Describe your needs directly, e.g., "Create a thread group with 10 threads sending GET requests to http://example.com"

## Agent Tools

### JMeter Element Tools (Enabled by Default)

| Tool | Description |
|------|-------------|
| `create_jmeter_element` | Create JMeter elements (thread groups, samplers, controllers, assertions, timers, etc.) |
| `update_jmeter_element` | Update properties of existing elements |
| `delete_jmeter_element` | Delete a specific element (TestPlan root cannot be deleted) |
| `move_jmeter_element` | Move element to a different parent node with precise positioning |
| `get_test_plan_tree` | Get complete test plan tree structure (JSON) |
| `get_selected_element` | Get detailed info about the currently selected element |
| `find_element` | Find elements by name, type, or path |

### AI-Enhanced Tools

| Tool | Description |
|------|-------------|
| `optimize_jmeter_element` | AI analyzes and optimizes the selected element's configuration |
| `lint_jmeter_elements` | AI renames elements for better readability and organization |
| `get_usage` | View token usage statistics |

### Test Execution Tools

| Tool | Description |
|------|-------------|
| `run_test` | Start, stop, or shutdown the current test plan |
| `get_test_status` | Get test execution status (running state, thread progress, sample counts) |
| `get_test_results` | Get test results (response times, throughput, error rate) |

### Utility Tools

| Tool | Description |
|------|-------------|
| `wrap_http_samplers` | Wrap consecutive HTTP samplers under Transaction Controllers |

### Filesystem Tools (Must Enable)

| Tool | Description |
|------|-------------|
| `read_file` | Read file contents with pagination support |
| `write_file` | Write files, auto-creating directories |
| `edit_file` | Edit files (string replacement) |
| `list_dir` | List directory contents with recursive support |

### Web Tools (Must Enable)

| Tool | Description |
|------|-------------|
| `web_search` | Search the web (supports Brave, Tavily, DuckDuckGo, and more) |
| `web_fetch` | Fetch web page content, auto-stripping navigation and ads |

### Execution Tool (Must Enable)

| Tool | Description |
|------|-------------|
| `exec` | Execute shell commands with timeout and working directory configuration |

## Commands

### @ Commands

Use in the chat input with the `@` prefix:

| Command | Description |
|---------|-------------|
| `@this` | Get detailed info about the currently selected element; combine with natural language questions |
| `@optimize` | AI analyzes and optimizes the selected element's configuration |
| `@lint` | AI renames elements for better organization; supports undo/redo |
| `@wrap` | Intelligently group HTTP samplers under Transaction Controllers |
| `@usage` | View token usage statistics and cost information |

### / Commands

Slash commands for session management:

| Command | Description |
|---------|-------------|
| `/new` | Start a fresh conversation (clears current session) |
| `/stop` | Cancel all active tasks for the current session |
| `/status` | Show bot status (version, model, token usage, session info) |
| `/help` | Show available commands |

## Skills System

The Agent dynamically loads skill modules from the filesystem. Each skill contains a `SKILL.md` definition and optional `references/` documentation.

| Skill | Description |
|-------|-------------|
| **jmeter** | Core JMeter skill — 60+ component references, 46 parameter schemas, JMeter function reference, coding standards, anti-patterns |
| **api-autotest** | API autotest — Specialized skill for Gitee-Scan OpenAPI, covering 25+ endpoints |
| **memory** | Memory management — Two-layer memory (MEMORY.md long-term + HISTORY.md events) with grep-based recall |
| **skill-creator** | Skill creation — Meta-skill for creating and updating Agent skills |

## Configuration Reference

Copy the contents of `jmeter-ai-sample.properties` into `user.properties` and modify as needed.

### Global LLM Defaults

These settings apply to all AI providers unless overridden by provider-specific configuration:

| Property | Description | Default |
|----------|-------------|---------|
| `jmeter.ai.temperature` | Temperature (0.0-1.0); lower = more deterministic | `0.7` |
| `jmeter.ai.max.tokens` | Max tokens per response | `65536` |
| `jmeter.ai.max.history.size` | Conversation history size to retain | `10` |
| `jmeter.ai.reasoning.effort` | Reasoning effort: none / low / medium / high | `none` |
| `jmeter.ai.default.model` | Default model | `MiniMax-M2.7` |
| `jmeter.ai.default.provider` | Default provider | `minimax` |
| `jmeter.ai.context.window.tokens` | Context window size | `102400` |
| `jmeter.ai.max.tool.iterations` | Max tool iterations per agent loop | `50` |

### Provider Configuration

#### Anthropic (Claude)

| Property | Description | Default |
|----------|-------------|---------|
| `anthropic.api.key` | API key | Required |
| `anthropic.api.base.url` | API base URL | `https://api.anthropic.com` |
| `anthropic.log.level` | Log level (info / debug) | Empty (disabled) |

#### OpenAI

| Property | Description | Default |
|----------|-------------|---------|
| `openai.api.key` | API key | Required |
| `openai.api.base.url` | API base URL | `https://api.openai.com` |
| `openai.log.level` | Log level | Empty (disabled) |

#### Ollama (Local)

| Property | Description | Default |
|----------|-------------|---------|
| `ollama.enabled` | Enable Ollama | `false` |
| `ollama.host` | Server host | `http://localhost` |
| `ollama.port` | Server port | `11434` |
| `ollama.thinking.mode` | Thinking mode (ENABLED / DISABLED) | `DISABLED` |
| `ollama.thinking.level` | Thinking depth (LOW / MEDIUM / HIGH) | Follows global setting |
| `ollama.request.timeout.seconds` | Request timeout (seconds) | `120` |

#### Chinese LLM Providers

| Property | Description | Default |
|----------|-------------|---------|
| `deepseek.api.key` | DeepSeek API key | — |
| `zhipu.api.key` | Zhipu GLM API key | — |
| `moonshot.api.key` | Moonshot Kimi API key | — |
| `minimax.api.key` | MiniMax API key | — |

Each provider supports `*.api.base.url` for custom endpoints, and `*.temperature`, `*.max.history.size` to override global defaults.

### Agent Loop Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `agent.enabled` | Enable Agent Loop | `true` |
| `agent.tool.result.max.chars` | Tool result truncation length | `16000` |
| `agent.workspace.path` | Workspace path | `jmeter-agent` |

### Memory Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `agent.memory.enabled` | Enable memory system | `true` |
| `agent.memory.consolidation.threshold` | Consolidation trigger threshold (0.0-1.0) | `0.5` |

### Session Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `agent.session.timeout` | Session timeout (milliseconds) | `3600000` (1 hour) |
| `agent.session.max.sessions` | Max active sessions | `100` |

### Tool Configuration

#### JMeter Tools

| Property | Description | Default |
|----------|-------------|---------|
| `agent.tools.jmeter.enabled` | Enable JMeter tools | `true` |

#### Filesystem Tools

| Property | Description | Default |
|----------|-------------|---------|
| `agent.tools.filesystem.enabled` | Enable filesystem tools | `true` |
| `agent.tools.filesystem.allowed.dirs` | Allowed directories (comma-separated) | User home directory |
| `agent.tools.filesystem.denied.dirs` | Denied paths (comma-separated) | — |

#### Web Search Tools

| Property | Description | Default |
|----------|-------------|---------|
| `agent.tools.websearch.enabled` | Enable web tools | `true` |
| `agent.tools.websearch.provider` | Search engine (brave / tavily / duckduckgo / jina / serpapi) | `brave` |
| `agent.tools.websearch.max.results` | Max search results | `10` |
| `agent.tools.websearch.timeout` | Search timeout (seconds) | `30` |
| `agent.tools.webfetch.max.length` | Max fetch content length (chars) | `50000` |
| `agent.tools.web.ssrf.protection` | SSRF protection | `true` |

#### Execution Tool

| Property | Description | Default |
|----------|-------------|---------|
| `agent.tools.exec.enabled` | Enable exec tool | `true` |
| `agent.tools.exec.timeout` | Default timeout (seconds) | `60` |
| `agent.tools.exec.working.dir` | Restrict working directory | — |
| `agent.tools.exec.deny.patterns` | Dangerous command patterns (regex) | Built-in defaults |

### Chat UI Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `ai.chat.show.tool.calls` | Show tool call information | `true` |
| `ai.chat.show.thinking` | Show model thinking content | `false` |
| `ai.chat.tool.result.max.length` | Max tool result display length | `500` |

### Tracing Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `langsmith.enabled` | Enable LangSmith tracing | `false` |
| `langsmith.api.key` | LangSmith API key | — |
| `langsmith.project.name` | Project name | `jmeter-ai` |
| `langsmith.endpoint` | API endpoint | `https://api.smith.langchain.com` |

## API Setup Guide

### Chinese LLM Providers

| Provider | Get API Key |
|----------|------------|
| DeepSeek | [platform.deepseek.com](https://platform.deepseek.com) |
| Zhipu GLM | [open.bigmodel.cn](https://open.bigmodel.cn) |
| Moonshot Kimi | [platform.moonshot.cn](https://platform.moonshot.cn) |
| MiniMax | [api.minimax.com](https://api.minimax.com) |

## Disclaimer

- **AI Limitations:** AI may produce incorrect information. Always verify suggestions before using them in production.
- **Backup Test Plans:** Always back up your test plans before implementing major AI-suggested changes.
- **API Costs:** Using API incurs token-based costs. Monitor your usage.
- **Security:** Do not share sensitive information (credentials, proprietary code) in conversations.
- **Performance Impact:** Some AI-suggested configurations may affect test performance. Monitor resource usage.

## License

Apache License 2.0
