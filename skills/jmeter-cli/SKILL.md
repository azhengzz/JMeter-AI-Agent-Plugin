---
name: jmeter-cli
description: Use when an external agent such as OpenClaw, Hermes, Codex, or another automation worker needs to interact with JMeter through the `jmeter-cli` command. Supports discovering a running JMeter GUI, checking IPC health, loading or parsing JMX files, inspecting test-plan trees, creating/updating/moving/deleting JMeter elements, running tests, reading status/results/logs, and delegating natural-language tasks to the jmeter-agent loop. Requires a JMeter GUI with jmeter-agent installed and IPC enabled via `-Jjmeter.ai.ipc.enabled=true`, plus `JMETER_HOME` or `--jmeter-home`.
---

# JMeter CLI

Use `jmeter-cli` to operate a running JMeter GUI that has the jmeter-agent plugin installed and IPC enabled. The CLI is a thin client: it discovers a GUI instance from `<JMETER_HOME>/bin/jmeter-agent/ipc/port-<pid>.json`, sends loopback HTTP requests with the IPC token, and the plugin performs the actual JMeter tree operations inside the GUI process.

For full command details and raw tool parameters, read [cli-reference.md](references/cli-reference.md).

## Preconditions

- Start JMeter GUI with IPC enabled: `-Jjmeter.ai.ipc.enabled=true`.
- Ensure `JMETER_HOME` points to the JMeter installation, or pass `--jmeter-home <dir>` on every command.
- Use the installed launcher: `jmeter-cli.bat` on Windows, `jmeter-cli.sh` on Linux/macOS, or `jmeter-cli` if it is already on `PATH`.
- If more than one JMeter GUI instance is running, select the target with `--pid <pid>`.

## Operating Workflow

1. Discover the target instance.
   ```bash
   jmeter-cli list --json
   jmeter-cli health --pid <pid> --json
   ```
   Continue only after `health` succeeds. `health` does not initialize the AI agent; it only checks IPC readiness.

2. Inspect the current script before making changes.
   ```bash
   jmeter-cli tool get_script_info --json
   jmeter-cli tool get_test_plan_tree --params "{\"includeProperties\":false,\"maxDepth\":2}" --json
   jmeter-cli find --searchBy elementType --query testplan --json
   ```
   Capture stable `elementId` values from output. Do not rely on GUI selection unless the user explicitly wants selection-relative behavior.

3. Modify the test plan with deterministic commands.
   ```bash
   jmeter-cli create --elementType threadgroup --elementName TG1 --parentId <testPlanId> --properties "{\"ThreadGroup.num_threads\":5,\"ThreadGroup.ramp_time\":10}" --json
   jmeter-cli create --elementType httpsampler --elementName "GET /api" --parentId <threadGroupId> --properties "{\"HTTPSampler.domain\":\"example.com\",\"HTTPSampler.path\":\"/api\",\"HTTPSampler.method\":\"GET\"}" --json
   jmeter-cli update --elementId <id> --properties "{\"name\":\"GET /health\"}" --json
   ```
   Use JMeter property names. For component-specific properties, consult the sibling JMeter skill component references in `../jmeter/SKILL.md` and its `references/` schemas.

4. Verify the tree after each meaningful change.
   ```bash
   jmeter-cli get --elementId <id> --includeProperties true --maxDepth 1 --json
   jmeter-cli find --searchBy name --query "GET /api" --exactMatch true --json
   ```

5. Execute only when the user asks for a run or validation requires it.
   ```bash
   jmeter-cli run --ignoreTimers true --wait --timeout 20000 --json
   jmeter-cli status --json
   jmeter-cli results --format both --limit 20 --json
   ```
   `run` starts the whole plan and returns once the engine confirms start; `--wait` polls `status` until the test finishes (bounded by `--timeout`). Use `--ignoreTimers true` for quick functional validation; use normal timers for load/performance runs. Stop a running test with `stop` (immediate) or `shutdown` (graceful) before making structural changes.

6. Troubleshoot failures with logs and sample details.
   ```bash
   jmeter-cli results --format samples --statusFilter failure --includeDetails true --limit 10 --json
   jmeter-cli tool get_log_panel_content --params "{\"maxLines\":100}" --json
   ```

## Command Choice

- Prefer top-level commands for common operations: `list`, `health`, `open`, `get`, `find`, `create`, `update`, `delete`, `move`, `toggle`, `batch`, `run`, `stop`, `shutdown`, `status`, `results`.
- Use raw `tool <name> --params <json>` for capabilities without top-level wrappers: `get_script_info`, `get_test_plan_tree`, `query_element_properties`, `parse_jmx_file`, `get_log_panel_content`, `copy_paste_jmeter_element`.
- Use `agent "<message>"` only when delegating a natural-language task to the jmeter-agent loop is acceptable. For reproducible automation, prefer explicit CLI commands.

## Safety Rules

- Always run `list` and `health` first. If multiple instances are listed, require or choose the intended `--pid`; never silently act on an ambiguous GUI.
- Pass `--parentId` to `create`. Omitting it uses the current GUI selection and is non-deterministic.
- Keep updates surgical. `update` and `batch` touch only listed properties; use them instead of rebuilding whole subtrees.
- Do not call IPC-blocked tools through `tool`; filesystem, exec, and web tools are intentionally unavailable over IPC.
- Treat `open --merge false` as replacing the current GUI test plan. Use `--merge true` only when the desired result is to merge another JMX into the current plan.
- Stop or shutdown running tests before making structural changes.

## Quoting Notes

On Windows PowerShell, prefer single quotes around JSON:
```powershell
jmeter-cli create --elementType httpsampler --elementName "GET /api" --parentId 5 --properties '{"HTTPSampler.domain":"example.com","HTTPSampler.path":"/api"}' --json
```

On `cmd.exe` and many POSIX shells, escape JSON quotes as needed:
```bash
jmeter-cli create --elementType httpsampler --elementName "GET /api" --parentId 5 --properties "{\"HTTPSampler.domain\":\"example.com\",\"HTTPSampler.path\":\"/api\"}" --json
```

## Failure Handling

- Exit code `0` means success, `1` means tool/agent/transport failure, and `2` means bad usage.
- If the CLI says `JMETER_HOME not set`, pass `--jmeter-home <dir>` or set the environment variable.
- If the CLI cannot reach IPC, run `jmeter-cli list` to remove stale port files and confirm the GUI was started with `-Jjmeter.ai.ipc.enabled=true`.
- If `tool not allowed over IPC` appears, choose an allowed JMeter IPC tool from [cli-reference.md](references/cli-reference.md).
