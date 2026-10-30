# JMeter CLI Reference

This reference summarizes the CLI implemented by `org.gitee.jmeter.ai.cli.JmeterCli` and the IPC allowlist implemented by `org.gitee.jmeter.ai.ipc.IpcServer`.

## Global Options

- `--pid <pid>`: Target one JMeter GUI instance. Required when `list` shows more than one instance.
- `--token <t>`: Override the IPC token. Normally the CLI reads it from the port file.
- `--json`: Print the raw `IpcResponse` JSON with `success`, `content`, `error`, `durationMs`, and agent metadata when applicable.
- `--jmeter-home <dir>`: Override `JMETER_HOME`.
- `--timeout <ms>`: HTTP timeout. Default is `130000`.
- `-h`, `--help`, `help [command]`: Show help.

## Top-Level Commands

### `list`

List live JMeter AI GUI instances discovered under `<JMETER_HOME>/bin/jmeter-agent/ipc/`.

```bash
jmeter-cli list --json
```

### `health`

Probe the selected IPC server and report readiness.

```bash
jmeter-cli health --pid <pid> --json
```

### `open`

Load a `.jmx` file into the running GUI. Default replaces the current plan; `--merge true` merges into it.

```bash
jmeter-cli open --file <path.jmx> --includeProperties false --maxDepth -1 --json
jmeter-cli open --file <path.jmx> --merge true --json
```

Options: `--file <path>` required, `--merge <bool>`, `--includeProperties <bool>`, `--maxDepth <n>`.

### `parse`

Parse an external `.jmx` and return its tree WITHOUT loading it into the GUI (the open plan stays untouched).

```bash
jmeter-cli parse --file <path.jmx> --json
jmeter-cli parse --file <path.jmx> --elementType httpsampler --limit 50 --json
```

Options: `--file <path>` required; `--elementType <t>` switches to query mode — output becomes a paginated `{total, offset, limit, elements}` list (default 20, max 50) and `--maxDepth` then defaults to 0 (tree mode: -1); `--includeProperties <bool>`; `--maxDepth <n>`; `--limit <n>`/`--offset <n>` (query mode only, require `--elementType`). Property-name/value filters and `matchMode` are reachable via `tool parse_jmx_file --params {...}`.

### `get`

Get one element by `elementId`, or the current GUI selection when omitted.

```bash
jmeter-cli get --elementId <id> --includeProperties true --maxDepth 1 --json
jmeter-cli get --json
```

Options: `--elementId <id>`, `--includeProperties <bool>`, `--maxDepth <n>`.

### `find`

Find elements by `name`, `elementType`, `path`, or `elementId`.

```bash
jmeter-cli find --searchBy elementType --query testplan --json
jmeter-cli find --searchBy name --query "HTTP Request" --exactMatch false --limit 10 --json
jmeter-cli find --searchBy elementType --query JSR223Sampler --parentId <id>
```

Options: `--searchBy <name|elementType|path|elementId>` required, `--query <q>` required, `--exactMatch <bool>`, `--includeProperties <bool>`, `--maxDepth <n>`, `--offset <n>`, `--limit <n>`.

### `query`

Search elements by property content instead of name/type/path.

```bash
jmeter-cli query --propertyName HTTPSampler.domain --propertyValue example.com --json
jmeter-cli query --elementType httpsampler --matchMode exact --propertyValue "example.com" --json
```

Options: `--propertyName <n>` / `--propertyValue <v>` — at least one REQUIRED (server-enforced; use `tree`/`find` for unfiltered listing); `--elementType <t>` (secondary filter), `--matchMode exact|contains` (default `contains`), `--parentId <id>`, `--includeProperties <bool>`, `--maxDepth <n>`, `--offset <n>`, `--limit <n>`.

### `tree`

Dump the whole current test-plan tree (discover `elementId`s before create/move/copy).

```bash
jmeter-cli tree --json
jmeter-cli tree --includeProperties false --maxDepth 2 --json
```

Options: `--includeProperties <bool>` (default `true`), `--maxDepth <n>` (-1 = unlimited).

### `script-info`

Show the currently-open `.jmx` path (empty when unsaved) plus JMeter/Java versions and JMeter home.

```bash
jmeter-cli script-info --json
```

### `log`

Read the JMeter LoggerPanel content by line range; default is tail mode (most recent 50 lines). Line numbers match the GUI log row numbers.

```bash
jmeter-cli log --json
jmeter-cli log --startLine 100 --endLine 120 --json
```

Options: `--startLine <n>`, `--endLine <n>`, `--maxLines <n>` (default 50, cap 500).

### `create`

Create a JMeter element.

```bash
jmeter-cli create --elementType threadgroup --elementName TG1 --parentId <testPlanId> --properties "{\"ThreadGroup.num_threads\":5}" --json
```

Options: `--elementType <type>` required, `--elementName <name>` required, `--parentId <id>` strongly recommended, `--properties <json>`.

### `copy`

Deep-clone an element (with all descendants) and paste it. Omit `--targetParentId` to duplicate in place.

```bash
jmeter-cli copy --elementId <id> --json
jmeter-cli copy --elementId <id> --targetParentId <parentId> --position first --json
```

Options: `--elementId <id>` required, `--targetParentId <id>`, `--position first|last|before:<id>|after:<id>`.

### `update`

Update selected properties on an existing element.

```bash
jmeter-cli update --elementId <id> --properties "{\"HTTPSampler.domain\":\"example.com\"}" --json
```

Options: `--elementId <id>` required, `--properties <json>` required.

### `delete`

Delete an element and its descendants.

```bash
jmeter-cli delete --elementId <id> --json
```

### `batch-delete`

Delete several elements in one shot (max 50 ids). Descendants of another listed id are skipped.

```bash
jmeter-cli batch-delete --elementIds 5,6,7 --json
```

Options: `--elementIds <csv>` required.

### `move`

Move an element under another parent.

```bash
jmeter-cli move --elementId <id> --targetParentId <parentId> --position last --json
```

Options: `--elementId <id>` required, `--targetParentId <id>` required, `--position first|last|before:<id>|after:<id>`.

### `batch-move`

Move several elements to one shared parent at a shared position in one shot (max 50 ids). All-or-nothing: any validation violation aborts the whole batch.

```bash
jmeter-cli batch-move --elementIds 5,6 --targetParentId <id> --position first --json
```

Options: `--elementIds <csv>` required, `--targetParentId <id>` required, `--position first|last|before:<id>|after:<id>`.

### `toggle`

Enable, disable, or toggle an element.

```bash
jmeter-cli toggle --elementId <id> --action disable --json
```

Options: `--elementId <id>` required, `--action enable|disable|toggle`.

### `batch-toggle`

Apply one shared enable/disable/toggle action to several elements (max 50 ids) with a single GUI refresh.

```bash
jmeter-cli batch-toggle --elementIds 5,6,7 --action disable --json
```

Options: `--elementIds <csv>` required, `--action enable|disable|toggle` (default `toggle`).

### `batch`

Apply the same properties to several elements.

```bash
jmeter-cli batch --elementIds 5,6,7 --properties "{\"HTTPSampler.domain\":\"example.com\"}" --json
```

Options: `--elementIds <csv>` required, `--properties <json>` required.

### `validate`

Statically validate the current test plan (structure + logic lint). Read-only: no plan mutation, no test execution, no goal evidence.

```bash
jmeter-cli validate --json
```

### `run`

Start the current JMeter test plan (whole plan; cannot target a sub-tree). Returns once the engine confirms start; does not block until completion unless `--wait` is given. `--mode smoke` runs ALL thread groups at minimal cost (1 thread x 1 iteration, timers always skipped, assertions kept) and blocks until the verdict returns. NOTE: smoke actually EXECUTES the plan and sends real requests (write endpoints will be hit once) — it is not a static check.

```bash
jmeter-cli run --ignoreTimers true --wait --timeout 30000 --json
jmeter-cli run --mode smoke --json
```

Options: `--ignoreTimers <bool>` (skip timer delays; ignored in smoke mode), `--mode normal|smoke` (default `normal`), `--wait` (poll `status` until the test finishes, bounded by `--timeout`).

### `stop`

Force-stop the running test immediately (JMeter `ACTION_STOP`). Errors if no test is running.

```bash
jmeter-cli stop --json
```

### `shutdown`

Gracefully stop the running test (JMeter `ACTION_SHUTDOWN` — lets in-flight samples finish). Errors if no test is running.

```bash
jmeter-cli shutdown --json
```

### `status`

Show the current test execution status: running state, elapsed, thread progress, and sample counts.

```bash
jmeter-cli status --json
```

### `results`

Show test results: summary statistics and/or individual sample details.

```bash
jmeter-cli results --format both --limit 20 --json
jmeter-cli results --format samples --statusFilter failure --includeDetails true --limit 10 --json
```

Options: `--format summary|samples|both` (default `summary`), `--limit <n>` (max 50, default 20), `--offset <n>`, `--includeDetails <bool>`, `--statusFilter all|success|failure`.

### `agent`

Send a natural-language message to the jmeter-agent loop running in the GUI.

```bash
jmeter-cli agent "add a thread group with 5 users named TG2" --session jmeter-ai-chat --json
```

Options: positional message or `--message <text>`, `--session <key>`, `--timeout <ms>`.

### `tool`

Run an allowed IPC tool directly.

```bash
jmeter-cli tool get_test_plan_tree --params "{\"includeProperties\":false,\"maxDepth\":2}" --json
```

Options: `<name>` required, `--params <json>` defaults to `{}`.

## Allowed Raw Tools

The IPC server only allows these tools over `jmeter-cli tool`.

### Tree CRUD

- `create_jmeter_element`: `{ "elementType": "...", "elementName": "...", "parentId": 1, "properties": {...} }`
- `update_jmeter_element`: `{ "elementId": 1, "properties": {...} }`
- `batch_update_jmeter_elements`: `{ "elementIds": [1,2], "properties": {...} }`
- `delete_jmeter_element`: `{ "elementId": 1 }`
- `batch_delete_jmeter_elements`: `{ "elementIds": [1,2] }` (descendants of another listed id are skipped)
- `move_jmeter_element`: `{ "elementId": 1, "targetParentId": 2, "position": "last" }`
- `batch_move_jmeter_elements`: `{ "elementIds": [1,2], "targetParentId": 3, "position": "last" }` (all-or-nothing)
- `copy_paste_jmeter_element`: `{ "elementId": 1, "targetParentId": 2, "position": "last" }`
- `toggle_jmeter_element`: `{ "elementId": 1, "action": "enable|disable|toggle" }`
- `batch_toggle_jmeter_elements`: `{ "elementIds": [1,2], "action": "toggle" }`

### Static Validation

- `validate_test_plan`: `{}` (read-only structure/logic lint; direct IPC calls record no goal evidence)

### Inspection

- `get_script_info`: `{}`
- `get_selected_element`: `{ "includeProperties": true, "maxDepth": -1 }`
- `get_test_plan_tree`: `{ "includeProperties": true, "maxDepth": -1 }`
- `find_element`: `{ "searchBy": "name|elementType|path|elementId", "query": "...", "exactMatch": true, "includeProperties": true, "maxDepth": 0, "offset": 0, "limit": 20 }`
- `query_element_properties`: `{ "elementType": "...", "propertyName": "...", "propertyValue": "...", "matchMode": "exact|contains", "includeProperties": true, "maxDepth": 0, "offset": 0, "limit": 20 }`
- `get_log_panel_content`: `{ "startLine": 1, "endLine": 100, "maxLines": 100 }`; omit line bounds for tail mode.

### JMX Files

- `parse_jmx_file`: `{ "filePath": "path.jmx", "includeProperties": true, "maxDepth": -1 }`
- `parse_jmx_file` query mode: `{ "filePath": "path.jmx", "elementType": "httpsampler", "propertyName": "HTTPSampler.domain", "propertyValue": "example.com", "matchMode": "contains", "offset": 0, "limit": 20 }`
- `open_jmx_file`: `{ "filePath": "path.jmx", "merge": false, "includeProperties": false, "maxDepth": -1 }`

### Test Execution

Prefer the dedicated top-level commands — `run`, `stop`, `shutdown`, `status`, `results` — over the raw tools below. The raw tools remain available via `tool` for parity.

- `run_test`: `{ "action": "start|stop|shutdown", "mode": "normal|smoke", "ignore_timers": false }`
- `get_test_status`: `{}`
- `get_test_results`: `{ "format": "summary|samples|both", "limit": 20, "offset": 0, "include_details": false, "status_filter": "all|success|failure" }`

## Common End-to-End Examples

### Create a minimal HTTP test

```bash
jmeter-cli find --searchBy elementType --query testplan --json
jmeter-cli create --elementType threadgroup --elementName TG1 --parentId <testPlanId> --properties "{\"ThreadGroup.num_threads\":5,\"ThreadGroup.ramp_time\":10}" --json
jmeter-cli create --elementType httpsampler --elementName "GET /health" --parentId <threadGroupId> --properties "{\"HTTPSampler.domain\":\"example.com\",\"HTTPSampler.path\":\"/health\",\"HTTPSampler.method\":\"GET\"}" --json
jmeter-cli get --elementId <threadGroupId> --includeProperties true --maxDepth -1 --json
```

### Load and inspect a JMX file

```bash
jmeter-cli tool parse_jmx_file --params "{\"filePath\":\"D:/tests/api.jmx\",\"includeProperties\":false,\"maxDepth\":2}" --json
jmeter-cli open --file "D:/tests/api.jmx" --includeProperties false --maxDepth 2 --json
```

### Run and collect results

```bash
jmeter-cli run --ignoreTimers true --wait --timeout 30000 --json
jmeter-cli status --json
jmeter-cli results --format both --limit 20 --json
```
