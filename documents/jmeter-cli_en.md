# jmeter-cli Command-Line Client

> English translation of [jmeter-cli.md](./jmeter-cli.md) (中文版).

Besides the chat panel, you can drive a running JMeter GUI instance from the command line with **jmeter-cli** (over loopback HTTP/IPC) to create, query, update, and delete test plan components, or to push messages to the AI agent. CLI flags are **1:1 name-identical** with the underlying tool schemas (tool list in [agent-tools_en.md](agent-tools_en.md)). The IPC service is on by default (loopback only + token auth); set `jmeter.ai.ipc.enabled=false` to turn it off (full configuration in the [Configuration Reference](configuration_en.md#ipc--cli-configuration)).

## Common Commands

```bash
# Common commands (global options: --pid --token --json --jmeter-home --timeout)
jmeter-cli list                                                 # discover instances
jmeter-cli health                                               # liveness probe
jmeter-cli tree                                                 # full tree structure / TestPlan root id
jmeter-cli find --searchBy name --query "HTTP"                  # fuzzy-find elements by name
jmeter-cli query --propertyName HTTPSampler.domain --propertyValue example.com   # query by property
jmeter-cli create --elementType threadgroup --elementName TG1 --parentId <id>
jmeter-cli copy --elementId <id>                                # deep-copy an element (in-place by default)
jmeter-cli batch-toggle --elementIds 5,6,7 --action disable     # batch disable
jmeter-cli validate                                             # statically validate the current plan
jmeter-cli run --mode smoke                                     # smoke run (all TGs 1×1, blocks until verdict)
jmeter-cli agent "add another thread group with 5 users"
```

The full command list, 109 test cases, and a one-shot regression script are in [../docs/test/jmeter-cli-test-cases.md](../docs/test/jmeter-cli-test-cases.md) (Chinese); the implementation plan is in [../TODO/cli-support-plan.md](../TODO/cli-support-plan.md) (Chinese).

`skills/jmeter-cli/` is the skill documentation for **third-party agents** (external automation tools such as OpenClaw, Hermes, and Codex), guiding them to operate a running JMeter GUI instance via `jmeter-cli` commands. It is not loaded inside the plugin; it is read by the external agent's skill system.

## Integrating jmeter-cli into External Agents

`skills/jmeter-cli/` follows the open [Agent Skills](https://agentskills.io/) standard (`SKILL.md` + YAML frontmatter), natively supported by Claude Code, Codex, OpenClaw, and Hermes — **the same skill directory can be read by all four agents; only the placement directory differs**.

**Prerequisites** (common to all agents):

1. Start the JMeter GUI (IPC is on by default; if you explicitly disabled it, set `jmeter.ai.ipc.enabled=true` to re-enable it)
2. Make `jmeter-cli` callable: add `$JMETER_HOME/bin` to `PATH`, or set the `JMETER_HOME` environment variable (the CLI uses it to locate `jmeter-cli.bat` / `jmeter-cli.sh`)

**Per-agent installation:**

| Agent | Skill directory | Installation |
|-------|-----------|---------|
| Claude Code | `~/.claude/skills/` (global) or `<project>/.claude/skills/` (project) | Copy `skills/jmeter-cli/` there |
| Codex | `~/.codex/skills/` (global) or `.agents/skills/` (project) | Copy `skills/jmeter-cli/` there; the project-level directory must be a **real directory**, not a symbolic link |
| OpenClaw | `~/.openclaw/skills/` (global) or the workspace `./skills/` | `openclaw skills install ./skills/jmeter-cli --global` (drop `--global` to install into the workspace) |
| Hermes | `~/.hermes/skills/` | Copy `skills/jmeter-cli/` there |

Example (global install for Claude Code):

```bash
# Linux / macOS
cp -r skills/jmeter-cli ~/.claude/skills/

# Windows (PowerShell)
Copy-Item -Recurse skills/jmeter-cli $HOME\.claude\skills\
```

Once installed, the agent decides from the `SKILL.md` `description` when to call `jmeter-cli`, and follows its workflow: `list → health → get/find → create/update → run → results`. The full command list is in [../skills/jmeter-cli/references/cli-reference.md](../skills/jmeter-cli/references/cli-reference.md).

> **Optional:** to let the agent reference each JMeter component's property names and schemas directly, also copy the component skill `src/main/jmeter-agent/skills/jmeter/` into the same skills directory (the cli skill's `../jmeter/SKILL.md` points at it).

## Appendix: Command × Tool Parameter Mapping Matrix (Audit Baseline)

The table below maps CLI commands to IPC tools (`list`/`health`/`agent` do not go through `/tool`; `get` picks one of two tools by argument; `tool` reaches any whitelisted tool); parameter keys correspond one-to-one with the tool schemas. Two mapping conventions:

- **camelCase passthrough**: CLI flag name = tool parameter name (e.g. `--includeProperties` → `includeProperties`); omitted flags are not sent, so the tool default applies
- **snake_case conversion**: when a tool parameter is snake_case, the CLI converts explicitly from a camelCase flag (only three cases: `ignoreTimers→ignore_timers`, `includeDetails→include_details`, `statusFilter→status_filter`)

| CLI command | Tool | Parameter mapping (CLI flag → tool parameter) |
|---|---|---|
| `list` | — (reads port files directly) | — |
| `health` | — (GET /health) | — |
| `get` | `find_element` / `get_selected_element` | `--elementId`→query(searchBy=elementId); `--includeProperties`/`--maxDepth` passed through |
| `find` | `find_element` | `--searchBy`/`--query` required; `--exactMatch`/`--includeProperties`/`--maxDepth`/`--offset`/`--limit`/`--parentId` passed through |
| `query` | `query_element_properties` | all optional: `--elementType`/`--propertyName`/`--propertyValue`/`--matchMode`/`--parentId`/`--includeProperties`/`--maxDepth`/`--offset`/`--limit` passed through |
| `tree` | `get_test_plan_tree` | `--includeProperties`/`--maxDepth` passed through |
| `script-info` | `get_script_info` | no parameters |
| `open` | `open_jmx_file` | `--file`→filePath; `--merge`/`--includeProperties`/`--maxDepth` passed through |
| `parse` | `parse_jmx_file` | `--file`→filePath; `--elementType`/`--includeProperties`/`--maxDepth` passed through |
| `create` | `create_jmeter_element` | `--elementType`/`--elementName` required; `--parentId` passed through; `--properties` JSON |
| `copy` | `copy_paste_jmeter_element` | `--elementId` required; `--targetParentId`/`--position` passed through (default = in-place copy) |
| `update` | `update_jmeter_element` | `--elementId` required; `--properties` JSON required |
| `delete` | `delete_jmeter_element` | `--elementId` required |
| `batch-delete` | `batch_delete_jmeter_elements` | `--elementIds` CSV→elementIds (≤50) |
| `move` | `move_jmeter_element` | `--elementId`/`--targetParentId` required; `--position` passed through |
| `batch-move` | `batch_move_jmeter_elements` | `--elementIds` CSV + `--targetParentId` required; `--position` passed through |
| `toggle` | `toggle_jmeter_element` | `--elementId` required; `--action` passed through |
| `batch-toggle` | `batch_toggle_jmeter_elements` | `--elementIds` CSV required; `--action` passed through |
| `batch` | `batch_update_jmeter_elements` | `--elementIds` CSV + `--properties` JSON required |
| `validate` | `validate_test_plan` | no parameters (read-only lint; records no goal evidence) |
| `log` | `get_log_panel_content` | `--startLine`/`--endLine`/`--maxLines` passed through (defaults to tailing the last 50 lines) |
| `run` | `run_test` | action=start; `--mode`→mode (normal/smoke); `--ignoreTimers`→ignore_timers; `--properties` JSON; `--wait` = client-side polling |
| `stop` | `run_test` | action=stop |
| `shutdown` | `run_test` | action=shutdown |
| `status` | `get_test_status` | no parameters |
| `results` | `get_test_results` | `--format`/`--limit`/`--offset` passed through; `--includeDetails`→include_details; `--statusFilter`→status_filter |
| `agent` | — (POST /agent, natural-language turn) | positional argument / `--message`; `--session` |
| `tool` | any whitelisted tool | `--params` JSON passed through verbatim |

Maintenance convention: keep this matrix in sync when adding CLI commands or changing tool parameters; `run --wait` relies on the literal `"**State**: Running"` marker in `get_test_status` output (`JmeterCli.RUNNING_STATE_MARKER`, pinned by a unit test) — tool output format changes must be synchronized.
