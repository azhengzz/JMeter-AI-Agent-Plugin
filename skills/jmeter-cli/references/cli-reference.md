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
```

Options: `--searchBy <name|elementType|path|elementId>` required, `--query <q>` required, `--exactMatch <bool>`, `--includeProperties <bool>`, `--maxDepth <n>`, `--offset <n>`, `--limit <n>`.

### `create`

Create a JMeter element.

```bash
jmeter-cli create --elementType threadgroup --elementName TG1 --parentId <testPlanId> --properties "{\"ThreadGroup.num_threads\":5}" --json
```

Options: `--elementType <type>` required, `--elementName <name>` required, `--parentId <id>` strongly recommended, `--properties <json>`.

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

### `move`

Move an element under another parent.

```bash
jmeter-cli move --elementId <id> --targetParentId <parentId> --position last --json
```

Options: `--elementId <id>` required, `--targetParentId <id>` required, `--position first|last|before:<id>|after:<id>`.

### `toggle`

Enable, disable, or toggle an element.

```bash
jmeter-cli toggle --elementId <id> --action disable --json
```

Options: `--elementId <id>` required, `--action enable|disable|toggle`.

### `batch`

Apply the same properties to several elements.

```bash
jmeter-cli batch --elementIds 5,6,7 --properties "{\"HTTPSampler.domain\":\"example.com\"}" --json
```

Options: `--elementIds <csv>` required, `--properties <json>` required.

### `run`

Start the current JMeter test plan (whole plan; cannot target a sub-tree). Returns once the engine confirms start; does not block until completion unless `--wait` is given.

```bash
jmeter-cli run --ignoreTimers true --wait --timeout 30000 --json
```

Options: `--ignoreTimers <bool>` (skip timer delays), `--wait` (poll `status` until the test finishes, bounded by `--timeout`).

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
- `move_jmeter_element`: `{ "elementId": 1, "targetParentId": 2, "position": "last" }`
- `copy_paste_jmeter_element`: `{ "elementId": 1, "targetParentId": 2, "position": "last" }`
- `toggle_jmeter_element`: `{ "elementId": 1, "action": "enable|disable|toggle" }`

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

- `run_test`: `{ "action": "start|stop|shutdown", "ignore_timers": false }`
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
