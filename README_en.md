# Gitee Ai - JMeter Agent

English | [中文](README.md)

Gitee Ai is a JMeter AI Agent plugin powered by an Agent Loop architecture that drives iterative cycles of LLM calls, tool execution, and result feedback — enabling intelligent test plan creation, optimization, and debugging within JMeter.

![Gitee Ai](./images/JMeter-Agent-Demo-EN.gif)

## Key Features

- **Agent Loop Architecture** — Full iterative cycle of LLM call → tool execution → result feedback, supporting multi-turn tool calling for complex tasks
- **22 Agent Tools** — Covering JMeter element CRUD, test execution, filesystem, web search, and command execution
- **Skills System** — Dynamically loaded skill modules from filesystem, with built-in JMeter expertise (73 component references, 58 function references), API autotest, and more
- **7 AI Providers** — Anthropic Claude, OpenAI, DeepSeek, Zhipu GLM, Moonshot Kimi, MiniMax, Ollama
- **Component Schema Validation** — 67 YAML schema files providing type, required, enum, and range validation for JMeter component parameters
- **Memory System** — Two-layer memory architecture (long-term memory + event history) with cross-session consolidation
- **Live Selection Sync** — Chat panel shows the currently selected JMeter element and focused control in real time, with one-click injection into the AI context
- **Security Controls** — File access whitelisting, SSRF protection, dangerous command blocking
- **Tracing** — Optional LangSmith integration for LLM call tracing and monitoring

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
2. Copy all contents from the `src/main/jmeter-agent/` directory into the `jmeter/bin/jmeter-agent/` directory (includes skills, templates, etc.)
3. Append the contents of `jmeter-ai-sample.properties` to `jmeter/bin/user.properties` and adjust the configuration as needed

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
| `copy_paste_jmeter_element` | Copy and paste test plan elements |

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
| `web_search` | Search the web (supports Brave, Tavily, Jina, and more) |
| `web_fetch` | Fetch web page content, auto-stripping navigation and ads |

### Execution Tool (Must Enable)

| Tool | Description |
|------|-------------|
| `exec` | Execute shell commands with timeout and working directory configuration |

## Commands

### / Commands

Slash commands for session management:

| Command | Description |
|---------|-------------|
| `/new` | Start a fresh conversation (clears current session) |
| `/status` | Show bot status (version, model, token usage, session info) |
| `/help` | Show available commands |

### Command-Line Client (jmeter-cli)

In addition to the chat panel, you can drive a running JMeter GUI instance via **jmeter-cli** over loopback HTTP/IPC — performing CRUD operations on the test plan or delegating natural-language tasks to the AI Agent. CLI flags match underlying tool schema keys 1:1. Disabled by default for security.

```bash
# Prerequisite: start JMeter GUI with IPC enabled
jmeter -Jjmeter.ai.ipc.enabled=true

# Common commands (global options: --pid --token --json --jmeter-home --timeout)
jmeter-cli list                                                 # discover instances
jmeter-cli health                                               # health check
jmeter-cli run --ignoreTimers true --wait                       # run test and wait for completion
jmeter-cli status                                               # check test progress
jmeter-cli results --format both --limit 10                     # view test results
jmeter-cli find --searchBy elementType --query testplan         # find TestPlan root
jmeter-cli create --elementType threadgroup --elementName TG1 --parentId <id>
jmeter-cli agent "add a thread group with 5 users"
```

See [docs/jmeter-cli-test-cases.md](docs/jmeter-cli-test-cases.md) for the full command reference and regression scripts.

`skills/jmeter-cli/` is a skill intended for **third-party agents** (such as OpenClaw, Hermes, Codex, and other external automation tools). It teaches them how to operate a running JMeter GUI through `jmeter-cli`. It is not loaded by the plugin — external agents read it through their own skill systems.

#### Wiring jmeter-cli into External Agents

`skills/jmeter-cli/` follows the [Agent Skills](https://agentskills.io/) open standard (`SKILL.md` + YAML frontmatter), which Claude Code, Codex, OpenClaw, and Hermes all support natively — **the same skill folder is read by all four; only the target directory differs.**

**Prerequisites** (common to all agents):

1. Start the JMeter GUI with IPC enabled: `jmeter -Jjmeter.ai.ipc.enabled=true` (or set the parameter to `true` in the configuration file)
2. Make `jmeter-cli` invokable: add `$JMETER_HOME/bin` to `PATH`, or set the `JMETER_HOME` environment variable (the CLI uses it to locate `jmeter-cli.bat` / `jmeter-cli.sh`)

**Installation per agent:**

| Agent | Skill directory | How to install |
|-------|-----------------|----------------|
| Claude Code | `~/.claude/skills/` (global) or `<project>/.claude/skills/` (project) | Copy `skills/jmeter-cli/` into the directory |
| Codex | `~/.codex/skills/` (global) or `.agents/skills/` (project) | Copy `skills/jmeter-cli/` into the directory; the project-level dir must be a **real directory**, not a symlink |
| OpenClaw | `~/.openclaw/skills/` (global) or workspace `./skills/` | `openclaw skills install ./skills/jmeter-cli --global` (drop `--global` for workspace install) |
| Hermes | `~/.hermes/skills/` | Copy `skills/jmeter-cli/` into the directory |

Example (global install for Claude Code):

```bash
# Linux / macOS
cp -r skills/jmeter-cli ~/.claude/skills/

# Windows (PowerShell)
Copy-Item -Recurse skills/jmeter-cli $HOME\.claude\skills\
```

Once installed, the agent uses the `description` in `SKILL.md` to decide when to invoke `jmeter-cli`, then follows its workflow: `list → health → get/find → create/update → run → results`. See [skills/jmeter-cli/references/cli-reference.md](skills/jmeter-cli/references/cli-reference.md) for the full command reference.

> **Optional:** If you want the agent to reference JMeter component property names and schemas directly, also copy the component skill `src/main/jmeter-agent/skills/jmeter/` into the same skills directory (the cli skill points to it via `../jmeter/SKILL.md`).

## Skills System

The Agent dynamically loads skill modules from the filesystem. Each skill contains a `SKILL.md` definition and optional `references/` documentation.

| Skill | Description |
|-------|-------------|
| **jmeter** | Core JMeter skill — 73 component references, 73 parameter schemas, 58 JMeter function references, coding standards, anti-patterns |
| **memory** | Memory management — Two-layer memory (MEMORY.md long-term + HISTORY.md events) with grep-based recall |
| **skill-creator** | Skill creation — Meta-skill for creating and updating Agent skills |

> **Want to add new JMeter components for the Agent?** Component metadata (`testClass`/`guiClass`) is fully data-driven — adding a component requires **zero Java changes**, just a YAML Schema file. See the full authoring guide: [SCHEMA-GUIDE_en.md](src/main/jmeter-agent/skills/jmeter/SCHEMA-GUIDE_en.md)（[中文版](src/main/jmeter-agent/skills/jmeter/SCHEMA-GUIDE.md)）.

## Configuration Reference

Copy the contents of `jmeter-ai-sample.properties` into `user.properties` and modify as needed. The default values in the tables below are taken from `jmeter-ai-sample.properties`; when not explicitly configured in `user.properties`, some items fall back to source-code built-in defaults (noted where applicable).

### Global LLM Defaults

These settings apply to all AI providers unless overridden by provider-specific configuration:

| Property | Description | Default |
|----------|-------------|---------|
| `jmeter.ai.temperature` | Temperature (0.0-1.0); lower = more deterministic | `0.7` |
| `jmeter.ai.max.tokens` | Max tokens per response | `65536` |
| `jmeter.ai.max.history.size` | Conversation history size to retain | `10` |
| `jmeter.ai.reasoning.effort` | Reasoning effort: none / low / medium / high | `none` |
| `jmeter.ai.default.model` | Default model (shared by all providers unless switched at runtime) | `MiniMax-M2.7` |
| `jmeter.ai.default.provider` | Default provider (anthropic / openai / ollama / deepseek / zhipu / moonshot / minimax) | `minimax` |
| `jmeter.ai.context.window.tokens` | Context window size (used by ContextWindowManager, MemoryConsolidator, AgentRunner) | `102400` |
| `jmeter.ai.max.tool.iterations` | Max tool iterations per agent loop | `50` |
| `jmeter.ai.system.prompt` | Unified system prompt (overrides built-in default, applies to all providers) | Empty (uses built-in prompt) |

### Per-Model Recommended Configuration

The table below lists recommended values for mainstream models.

| provider | model | temperature | max.tokens | reasoning.effort | context.window.tokens |
|----------|-------|-------------|------------|-----------------|----------------------|
| deepseek | deepseek-v4-flash | `0.7` | `65536` | `[none, minimal, low, medium, high, xhigh]` | `512000` |
| deepseek | deepseek-v4-pro | `0.7` | `65536` | `[none, minimal, low, medium, high, xhigh]` | `512000` |
| zhipu | glm-5.1 | `1.0` | `65536` | `[none, medium]` | `128000` |
| zhipu | glm-5.2 | `1.0` | `65536` | `[none, minimal, low, medium, high, xhigh]` | `512000` |
| moonshot | kimi-k2.6 | `1.0` (API-enforced) | `8192` | `medium` | `128000` |
| moonshot | kimi-k2.7-code | `1.0` (API-enforced) | `8192` | `medium` | `128000` |
| minimax | MiniMax-M2.7 | `0.7` | `8192` | `medium` | `128000` |
| minimax | MiniMax-M3 | `0.7` | `65536` | `medium` | `512000` |

**Notes:**

- **max.tokens** — The single-response output cap from each model's API; values above the cap are clipped server-side. For reasoning models (e.g., `deepseek-reasoner`), the visible output is reduced by the chain-of-thought tokens.
- **reasoning.effort** — `none` disables thinking (faster and cheaper), suited for routine chat and simple tool calls; reasoning models should use `medium` or `high` to leverage deeper analysis.
- **context.window.tokens** — Recommend ~80% of the model's context window ceiling, leaving headroom for tool results, memory consolidation, and the system prompt. Too large triggers frequent consolidation; too small discards history prematurely.

### Provider Configuration

#### Anthropic (Claude)

| Property | Description | Default |
|----------|-------------|---------|
| `anthropic.api.key` | API key (required) | — |
| `anthropic.api.base.url` | API base URL | `https://api.anthropic.com` |
| `anthropic.log.level` | Log level (info / debug) | Empty (disabled) |

#### OpenAI

| Property | Description | Default |
|----------|-------------|---------|
| `openai.api.key` | API key (required) | — |
| `openai.api.base.url` | API base URL (can point to a proxy or Azure OpenAI) | `https://api.openai.com` |
| `openai.log.level` | Log level | Empty (disabled) |

#### Ollama (Local)

| Property | Description | Default |
|----------|-------------|---------|
| `ollama.enabled` | Enable Ollama | `false` |
| `ollama.host` | Server host | `http://localhost` |
| `ollama.port` | Server port | `11434` |
| `ollama.thinking.mode` | Thinking mode (ENABLED / DISABLED) | `DISABLED` |
| `ollama.thinking.level` | Thinking depth (LOW / MEDIUM / HIGH); only used when thinking.mode=ENABLED | Follows `jmeter.ai.reasoning.effort` |
| `ollama.request.timeout.seconds` | Request timeout (seconds); consider increasing when thinking mode is on | `120` |

#### Chinese LLM Providers

| Property | Description | Default |
|----------|-------------|---------|
| `deepseek.api.key` | DeepSeek API key | — |
| `deepseek.api.base.url` | DeepSeek API base URL | `https://api.deepseek.com` |
| `zhipu.api.key` | Zhipu GLM API key | — |
| `zhipu.api.base.url` | Zhipu GLM API base URL | `https://open.bigmodel.cn/api/paas/v4/` |
| `moonshot.api.key` | Moonshot Kimi API key | — |
| `moonshot.api.base.url` | Moonshot API base URL | `https://api.moonshot.ai/v1` |
| `minimax.api.key` | MiniMax API key | — |
| `minimax.api.base.url` | MiniMax API base URL | `https://api.minimaxi.com/v1` |

Each provider also supports `*.temperature`, `*.max.history.size`, etc. to override global defaults (e.g., `deepseek.temperature=0.3`).

### Agent Loop Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `agent.enabled` | Enable Agent Loop (tool calling, memory, session persistence) | `true` |
| `agent.tool.result.max.chars` | Tool result truncation length (chars) | `16000` |
| `jmeter.ai.injection.queue.size` | Max queued injection messages per session | `20` |
| `jmeter.ai.injection.max.per.turn` | Max injection messages processed per agent turn | `3` |
| `agent.workspace.path` | Workspace path (stores MEMORY.md, HISTORY.md, sessions, skills, templates) | `jmeter-agent` (source-code built-in default: `{user.home}/.jmeter-ai/agent`) |

### Memory Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `agent.memory.enabled` | Enable memory system | `true` |
| `agent.memory.consolidation.threshold` | Context window ratio threshold (0.0-1.0); exceeding it triggers consolidation | `0.5` |

### Session Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `agent.session.timeout` | Session timeout (milliseconds) | `3600000` (1 hour) |
| `agent.session.max.sessions` | Max active sessions | `100` |

### Tool Configuration

#### Common Tool Behavior

| Property | Description | Default |
|----------|-------------|---------|
| `agent.tools.concurrent.enabled` | Run independent tool calls concurrently | `false` |
| `agent.tools.fail.on.error` | Stop the entire agent loop on a tool error | `false` |
| `agent.tools.timeout.ms` | Default tool execution timeout (ms) | `30000` |

#### JMeter Tools

| Property | Description | Default |
|----------|-------------|---------|
| `agent.tools.jmeter.enabled` | Enable JMeter tools | `true` |

#### Filesystem Tools

| Property | Description | Default |
|----------|-------------|---------|
| `agent.tools.filesystem.enabled` | Enable filesystem tools | `true` (source-code built-in default: `false`) |
| `agent.tools.filesystem.allowed.dirs` | Allowed directories (comma-separated) | Empty (falls back to user home and current working dir) |
| `agent.tools.filesystem.denied.dirs` | Denied paths (comma-separated; supports dirs or specific files) | — |

#### Web Tools

| Property | Description | Default |
|----------|-------------|---------|
| `agent.tools.websearch.enabled` | Enable web tools | `true` (source-code built-in default: `false`) |
| `agent.tools.websearch.provider` | Search engine (brave / tavily / jina) | `jina` |
| `agent.tools.websearch.max.results` | Max search results | `10` |
| `agent.tools.websearch.timeout` | Search timeout (seconds) | `30` |
| `agent.tools.websearch.tavily.api.key` | Tavily API key (required when provider=tavily) | — |
| `agent.tools.websearch.jina.api.key` | Jina API key (required when provider=jina) | — |
| `agent.tools.webfetch.timeout` | Web fetch timeout (seconds) | `30` |
| `agent.tools.web.max.redirects` | Max redirects to follow | `5` |
| `agent.tools.web.ssrf.protection` | SSRF protection (blocks private/local network access) | `true` |

#### Execution Tool

| Property | Description | Default |
|----------|-------------|---------|
| `agent.tools.exec.enabled` | Enable exec tool | `true` (source-code built-in default: `false`) |
| `agent.tools.exec.timeout` | Default timeout (seconds, max 600) | `60` |
| `agent.tools.exec.working.dir` | Restrict working directory (only this dir and its subdirs allowed when set) | — |
| `agent.tools.exec.deny.patterns` | Dangerous command patterns (regex, comma-separated) | Built-in defaults (rm -rf, del /f, format, mkfs, shutdown, etc.) |
| `agent.tools.exec.path.append` | Additional directories to append to PATH | — |

### Chat UI Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `ai.chat.show.tool.calls` | Show tool call info (tool name, status, execution time, result) | `true` |
| `ai.chat.show.thinking` | Show model thinking/reasoning content (strips `<think>` blocks when off) | `true` (source-code built-in default: `false`) |
| `ai.chat.tool.result.max.length` | Max tool result display length in chat panel and logs | `500` |

### Tracing Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `langsmith.enabled` | Enable LangSmith tracing | `false` |
| `langsmith.api.key` | LangSmith API key | — |
| `langsmith.project.name` | Project name (all traces grouped under this project) | `jmeter-ai` |
| `langsmith.endpoint` | API endpoint | `https://api.smith.langchain.com` |
| `langsmith.sample.rate` | Sampling rate (0.0-1.0; 1.0 = trace all requests) | `1.0` |

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

## Acknowledgements

This project drew inspiration and implementation references from the following open-source projects:

- [nanobot](https://github.com/HKUDS/nanobot) — Reference for Agent design and implementation
- [jmeter-ai](https://github.com/QAInsights/jmeter-ai) — Design inspiration reference

## License

MIT License

<br>
<p align="center">
  Thanks for visiting ✨ <b>JMeter Ai Agent Plugin</b>
</p>
<p align="center">
  <img src="https://visitor-badge.laobi.icu/badge?page_id=azhengzz.JMeter-AI-Agent-Plugin" alt="visitors"/>
</p>

