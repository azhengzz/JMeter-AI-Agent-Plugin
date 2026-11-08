# Configuration Reference

> English translation of [configuration.md](./configuration.md) (中文版).

This is the complete configuration reference for Gitee Ai - JMeter Agent. For basic installation and API key setup, see the [README Quick Start](../README_en.md#quick-start).

Copy the contents of `jmeter-ai-sample.properties` into `user.properties` and adjust as needed. Default values in the tables below are taken from `jmeter-ai-sample.properties`; if an item is not explicitly configured in `user.properties`, some entries fall back to the built-in source-code defaults (noted where applicable).

> **Note:** `user.properties` is read only at JMeter startup — restart JMeter for changes to take effect.

## Global LLM Defaults

The following settings apply to all AI providers (unless overridden by provider-specific configuration):

| Property | Description | Default |
|------|------|--------|
| `jmeter.ai.temperature` | Temperature (0.0-1.0); lower is more deterministic | `0.7` |
| `jmeter.ai.max.tokens` | Maximum tokens per response | `4096` |
| `jmeter.ai.max.history.size` | Number of conversation history messages kept | `120` |
| `jmeter.ai.reasoning.effort` | Reasoning effort: none / minimal / low / medium / high / xhigh / max | `high` |
| `jmeter.ai.default.model` | Default model (shared by all providers unless switched at runtime) | `deepseek-v4-flash` |
| `jmeter.ai.default.provider` | Default provider (anthropic / openai / ollama / deepseek / zhipu / moonshot / minimax / langcat) | `deepseek` |
| `jmeter.ai.context.window.tokens` | Context window size (used by ContextWindowManager, MemoryConsolidator, AgentRunner) | `65536` |
| `jmeter.ai.max.tool.iterations` | Maximum tool iterations per agent loop | `50` |
| `jmeter.ai.system.prompt` | Unified system prompt (note: in the current version the agent main loop always assembles context with the built-in prompt; this property only takes effect on service-layer fallback paths that do not inject a system message) | empty (built-in prompt) |

## Recommended Per-Model Configuration

Recommended values for mainstream models:

| provider | model | temperature | max.tokens | reasoning.effort | context.window.tokens |
|--------|---------|-------------|------------|-----------------|----------------------|
| deepseek | deepseek-v4-flash | `0.7` | `65536` | `[none, low, high, max]` | `512000` |
| deepseek | deepseek-v4-pro | `0.7` | `65536` | `[none, low, high, max]` | `512000` |
| zhipu | glm-5.2 | `1.0` | `65536` | `[none, minimal, low, medium, high, xhigh, max]` | `512000` |
| zhipu | glm-5.3 | `1.0` | `65536` | `[low, high, max]` | `512000` |
| zhipu | glm-5.3-flash | `1.0` | `65536` | `[low, high, max]` | `512000` |
| moonshot | kimi-k2.7-code | `1.0` (API-enforced) | `8192` | `medium` | `128000` |
| moonshot | kimi-k3 | `1.0` (API-enforced) | `65536` | `[low, high, max]` | `512000` |
| moonshot | kimi-k3-pro | `1.0` (API-enforced) | `65536` | `[low, high, max]` | `512000` |
| minimax | MiniMax-M3 | `1.0` | `65536` | `[none, medium]` | `512000` |
| minimax | MiniMax-M3.1-Flash | `1.0` | `65536` | `[low, medium, high, xhigh, max]` | `512000` |
| langcat | LongCat-2.0 | `0.7` | `65536` | `[none, medium]` | `512000` |

**Usage notes:**

- **max.tokens** — taken from each model API's per-response output cap; higher values are trimmed server-side. For reasoning models (e.g. `deepseek-reasoner`) the visible output is reduced by the chain-of-thought tokens.
- **reasoning.effort** — `none` disables thinking (faster, fewer tokens), suitable for routine chat and simple tool calls; reasoning models should use `medium` or `high` to leverage their deep-analysis capability.
- **MiniMax thinking switch** — controlled via `thinking.type` (`reasoning_effort=none` → `disabled`; otherwise → `adaptive` for M3 / `enabled` for M2.x). The **M3** series can truly turn thinking off; on **M2.x** the server ignores `disabled`, so thinking cannot be disabled (known limitation, not a defect on this side).
- **context.window.tokens** — recommended at roughly 80% of the model's context limit, leaving headroom for tool-result feedback, memory consolidation, and the system prompt; too large triggers frequent memory consolidation, too small discards history prematurely.

## Provider Configuration

### Anthropic (Claude)

| Property | Description | Default |
|------|------|--------|
| `anthropic.api.key` | API key (required) | — |
| `anthropic.api.base.url` | API base URL | `https://api.anthropic.com` |
| `anthropic.log.level` | Log level (info / debug) | empty (disabled) |

### OpenAI

| Property | Description | Default |
|------|------|--------|
| `openai.api.key` | API key (required) | — |
| `openai.api.base.url` | API base URL (can point to a proxy or Azure OpenAI) | `https://api.openai.com` |
| `openai.log.level` | Log level | empty (disabled) |

### Ollama (local)

| Property | Description | Default |
|------|------|--------|
| `ollama.api.base.url` | API base URL (OpenAI-compatible endpoint; must include the `/v1` suffix) | `http://localhost:11434/v1` |

### Chinese LLM Providers

| Property | Description | Default |
|------|------|--------|
| `deepseek.api.key` | DeepSeek API key | — |
| `deepseek.api.base.url` | DeepSeek API base URL | `https://api.deepseek.com` |
| `zhipu.api.key` | Zhipu GLM API key | — |
| `zhipu.api.base.url` | Zhipu GLM API base URL | `https://open.bigmodel.cn/api/paas/v4/` |
| `moonshot.api.key` | Moonshot Kimi API key | — |
| `moonshot.api.base.url` | Moonshot API base URL | `https://api.moonshot.cn/v1` |
| `minimax.api.key` | MiniMax API key | — |
| `minimax.api.base.url` | MiniMax API base URL | `https://api.minimaxi.com/v1` |
| `langcat.api.key` | LangCat API key | — |
| `langcat.api.base.url` | LangCat API base URL | `https://api.longcat.chat/openai/v1` |

Every provider supports `*.temperature`, `*.max.history.size`, etc. to override the global defaults (e.g. `deepseek.temperature=0.3`).

## Agent Loop Configuration

| Property | Description | Default |
|------|------|--------|
| `agent.enabled` | Enable the Agent Loop (tool calls, memory, session persistence) | `true` |
| `agent.tool.result.max.chars` | Tool result truncation length (characters) | `16000` |
| `jmeter.ai.injection.queue.size` | Maximum queued injection messages per session | `20` |
| `jmeter.ai.injection.max.per.turn` | Maximum injection messages processed per agent turn | `3` |
| `agent.runcapture.enabled` | Capture test runs started manually from the GUI (auto-injects a result collector for `get_test_status`/`get_test_results` to read; when off, only runs started by `run_test` are captured) | `true` |
| `agent.goal.max.continuations` | Maximum automatic cross-turn continuations per goal (exhaustion forces a structured hand-back) | `5` |
| `agent.goal.smoke.limit` | Maximum smoke-repair rounds per goal (after exhaustion, complete needs no evidence but the recap must state residual risks) | `3` |
| `agent.session.per-instance` | Use a separate session file per JMeter instance (`false` falls back to a global shared session) | `true` |
| `agent.session.reap.ttl.days` | Reaping TTL for orphaned session files (days; deleted only when the owning instance is confirmed dead and the TTL has passed) | `7` |
| `agent.workspace.path` | Workspace path (holds MEMORY.md, HISTORY.md, sessions, skills, templates) | Three-tier fallback: `agent.workspace.path` → `{jmeter.home}/bin/jmeter-agent` (default) → `{user.home}/.jmeter-ai/agent` (when the JMeter home is unavailable) |

## Memory Configuration

| Property | Description | Default |
|------|------|--------|
| `agent.memory.enabled` | Enable the memory system | `true` |
| `agent.memory.consolidate-on-exit.timeout.ms` | Timeout (ms) for deep distillation on exit (on timeout the already-archived HISTORY.md is kept and JMeter exits directly; archiving to HISTORY.md on close always runs and is not configurable) | `300000` |

## Tool Configuration

### General Tool Behavior

| Property | Description | Default |
|------|------|--------|
| `agent.tools.timeout.ms` | Default tool execution timeout (ms; only an `orTimeout` backstop for read-only tools in parallel batches) | `30000` |
| `agent.tools.edt.wait.timeout.ms` | Upper bound (ms) for waiting on the JMeter GUI event thread (EDT) to perform tree operations (per single wait; batch tools make two serial waits, worst case 240s; lower bound clamped to 1000). On timeout only the caller's wait is abandoned — the EDT task still executes later (outcome unknown; the error message advises re-checking the tree before acting) | `120000` |

### JMeter Tools

| Property | Description | Default |
|------|------|--------|
| `agent.tools.jmeter.enabled` | Enable JMeter tools | `true` |
| `jmeter.ai.tool.max.string.length` | Truncation length (characters) for a single string property value when serializing JMeter elements | `2048` |
| `jmeter.loggerpanel.maxlength` | Maximum lines in the JMeter LoggerPanel (used for the get_log_panel_content capacity hint) | `1000` |

### Filesystem Tools

| Property | Description | Default |
|------|------|--------|
| `agent.tools.filesystem.enabled` | Enable filesystem tools | `true` |
| `agent.tools.filesystem.allowed.dirs` | Allowed directories (comma-separated) | empty (falls back to the user home directory and the current working directory) |
| `agent.tools.filesystem.denied.dirs` | Denied paths (comma-separated; directories or specific files) | — |

### Web Tools

| Property | Description | Default |
|------|------|--------|
| `agent.tools.websearch.enabled` | Enable web tools | `true` |
| `agent.tools.websearch.provider` | Search engine (brave / tavily / jina) | `jina` |
| `agent.tools.websearch.max.results` | Maximum search results | `10` |
| `agent.tools.websearch.timeout` | Search timeout (seconds) | `30` |
| `agent.tools.websearch.tavily.api.key` | Tavily API key (required when provider=tavily) | — |
| `agent.tools.websearch.brave.api.key` | Brave API key (used when provider=brave; falls back to Jina when unset) | — |
| `agent.tools.websearch.jina.api.key` | Jina API key (required when provider=jina) | — |
| `agent.tools.webfetch.timeout` | Web fetch timeout (seconds) | `30` |
| `agent.tools.web.max.redirects` | Maximum redirects | `5` |
| `agent.tools.web.ssrf.protection` | SSRF protection (blocks access to intranet/local addresses) | `true` |

### Command Execution Tool

| Property | Description | Default |
|------|------|--------|
| `agent.tools.exec.enabled` | Enable the command execution tool | `true` |
| `agent.tools.exec.timeout` | Default timeout (seconds; max 600) | `60` |
| `agent.tools.exec.working.dir` | Restricted working directory (when set, only that directory and its subdirectories are allowed) | — |
| `agent.tools.exec.deny.patterns` | Dangerous-command blocking rules (regex, comma-separated) | Built-in default rules (rm -rf, del /f, format, mkfs, shutdown, etc.) |
| `agent.tools.exec.path.append` | Extra directories appended to PATH | — |

### Asynchronous Subagents (Subagent)

The main agent can delegate a self-contained **read-only analysis task** to a background subagent via the `spawn` tool. Subagents run on a dedicated thread pool with an isolated read-only toolset (read-only JMeter / file / web tools only — they cannot modify the test plan, run tests, write files, or spawn further subagents) and use a temporary session isolated from the main session — their results are fed back to the main agent **within the same conversation turn**.

| Property | Description | Default |
|------|------|--------|
| `agent.subagent.enabled` | Master switch. When off, the `spawn` and `subagent_status` tools are not registered and the main agent cannot dispatch subagents | `false` |
| `agent.subagent.max.concurrent` | Maximum concurrent subagents per main session (serial execution at `1`) | `1` |
| `agent.subagent.max.iterations` | Maximum tool iterations per subagent run | `50` |
| `agent.subagent.drain.timeout.seconds` | Seconds the main turn blocks waiting for subagent results (hard cap 300); after timeout the subagent keeps running — results can be queried via `subagent_status` or delivered on the next turn | `180` |
| `agent.subagent.status.retention.seconds` | Retention window (seconds) during which completed subagent status is queryable via `subagent_status`; late/undelivered results are reaped after this window; 0 = never reap by duration | `60` |
| `agent.subagent.status.max.completed` | Maximum completed statuses kept per session (oldest evicted beyond the cap); 0 = never evict by count | `10` |

## Chat UI Configuration

| Property | Description | Default |
|------|------|--------|
| `ai.chat.show.tool.calls` | Show tool call info (tool name, status, execution time, result) | `true` |
| `ai.chat.show.thinking` | Show model thinking/reasoning content (removes `<think>` blocks when off) | `true` (built-in source default `false`) |
| `ai.chat.tool.result.max.length` | Maximum display length of tool results in the chat panel and logs | `500` |
| `ai.chat.font.size` | Chat input box font size (points; 0 = system font) | `0` (automatic) |

## Tracing Configuration

| Property | Description | Default |
|------|------|--------|
| `langsmith.enabled` | Enable LangSmith tracing | `false` |
| `langsmith.api.key` | LangSmith API key | — |
| `langsmith.project.name` | Project name (all traces go into this project) | `jmeter-ai` |
| `langsmith.endpoint` | API endpoint | `https://api.smith.langchain.com` |
| `langsmith.sample.rate` | Sample rate (0.0-1.0; 1.0 = trace everything) | `1.0` |

## IPC / CLI Configuration

| Property | Description | Default |
|------|------|--------|
| `jmeter.ai.ipc.enabled` | Enable the embedded IPC HTTP service (loopback only + token auth), used by jmeter-cli to drive a running GUI and for cross-instance collaboration | `true` |
| `jmeter.ai.ipc.bind` | Bind address (loopback only; `0.0.0.0`/`::`/`*` are rejected) | `127.0.0.1` |
| `jmeter.ai.ipc.port` | Listen port (0 = auto-assign, recommended) | `0` |
| `jmeter.ai.ipc.token` | Auth token (empty = randomly generated at startup and written to the port file) | empty (randomly generated) |
| `jmeter.ai.ipc.agent.timeout.ms` | Synchronous wait timeout (ms) for the `/agent` route (frozen at IPC startup and published to the port file's `agentTimeoutMs` field; the CLI `agent` subcommand and the delegation tool derive their wait as "published value + 10s"; if the target doesn't publish it (older versions — readers ≥ v0.3.2 stay compatible) they fall back to the built-in default) | `300000` |
