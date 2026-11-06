---
name: jmeter-cli
description: Operate a running JMeter GUI through the `jmeter-cli` command — inspect and edit test plans (thread groups, HTTP samplers, assertions, extractors, timers, CSV data), validate, smoke-run, read results and diagnose failures. Use when a task needs JMeter driven from a terminal, a test plan built or repaired by automation, a plan executed, or a failing JMeter test explained.
---

# JMeter CLI

`jmeter-cli` is a thin client: it discovers a running JMeter GUI from
`<JMETER_HOME>/bin/jmeter-agent/ipc/port-<pid>.json`, sends loopback HTTP requests with the IPC
token, and the plugin performs the actual tree operations inside the GUI process. Nothing here
writes a `.jmx` file — you are editing the live plan held in the running GUI.

## Preconditions

- A JMeter **GUI** is running with the jmeter-agent plugin. IPC is on by default; if `list`
  shows nothing, restart JMeter with `-Jjmeter.ai.ipc.enabled=true`.
- Call `jmeter-cli` (on PATH) or `$JMETER_HOME/bin/jmeter-cli.bat` / `jmeter-cli.sh`.
  The launcher infers `JMETER_HOME` from its own location, so setting the env var is optional.
- More than one GUI? Every command then needs `--pid <pid>`.

## What this CLI cannot do

Read this before promising anything. Each row is a hard limit, not a configuration option.

| Need | Reality |
|---|---|
| Save the plan to disk | **No save command.** Edits live only in the running GUI's memory |
| Undo an edit | No undo. Direct CLI edits leave **no snapshot** |
| Run part of the plan | `run` always starts the whole plan; cannot target a sub-tree |
| Create any JMeter element | Only **75 of 166** type keys are creatable (see `element-types.md`) |
| Read an older run | `status`/`results` cover **only the latest run** in this GUI session |
| Inspect a long run | Only the most recent **1000 samples** are retained |
| Deliver a `.jmx` file | You cannot produce one; only the human can save in the GUI |

## Safety rules

- **`run` can rewrite the open `.jmx`.** JMeter defaults to `save_automatically_before_run=true`,
  so `run` (smoke included) saves the live tree to the plan's associated file *before* starting.
  After `open --file plan.jmx`, any run overwrites `plan.jmx` with your edits. Copy the file first
  if it must not change. On a never-saved plan, `run` writes a throwaway file under
  `{workspace}/autosave/` and the plan stays unsaved.
- **No undo, no snapshot for direct commands.** `create`/`update`/`delete`/`move`/`copy`/`batch`
  mutate the live tree irreversibly. Before the first mutating command on an existing plan, run
  `jmeter-cli script-info` and back up the file it reports. Treat `delete`, `batch-delete` and
  `open --merge false` as irreversible. (Only `jmeter-cli agent` turns leave a pre-turn baseline
  in `{workspace}/autosave/`.)
- **`open --merge false` discards unsaved edits without prompting** and replaces the whole plan.
  `--merge true` merges into whatever node the GUI currently has selected.
- **Check for a running test first.** `jmeter-cli status` prints `**State**: Running` while a run
  is live. JMeter runs a clone, so edits mid-run do not corrupt that run — but the results you
  read afterwards describe the plan *as it was at start*. Stop it (`stop`, or `shutdown` for
  graceful) and re-check before structural changes.
- **Always pass `--parentId` to `create`.** Omitting it lands the element on the GUI's current
  selection; the reply echoes the landing path — read that line.
- **Pass absolute paths to `--file`** (`open`, `parse`). Relative paths resolve inside the JMeter
  process (typically `$JMETER_HOME/bin`), not your shell's working directory. Relative paths
  *inside* a plan (CSV files, includes) resolve against the plan's base directory — check with
  `parse` before `open`.
- **Keep updates surgical.** `update` and `batch` touch only the listed properties.
- **Windows:** quote arguments containing cmd metacharacters (`& | < > ^`); the `.bat` launcher
  passes `%*` through cmd. Prefer `jmeter-cli.sh` or a direct `java` call in scripted environments.

## Getting elementId values

Every mutating command needs an `elementId`. Three rules first:

- **`testplan` is not an elementType.** The Test Plan node is not in the element registry —
  `find --searchBy elementType --query testplan` always fails. Take its id from the tree root.
- **Ids are valid only for the current live tree.** They are object-identity hashes: any `open`
  (merge or not), a GUI-side reload, or a JMeter restart invalidates every id, and an id can be
  reused by a different element. Re-fetch after any load. `parse` never returns ids at all.
- **`tree`/`get`/`parse` report `elementType` as the Java class simple name** (`HTTPSamplerProxy`,
  `ThreadGroup`, `TestAction`), while every `--elementType` argument wants the registry key
  (`httpsampler`, `threadgroup`, `flowcontrolaction`). See `element-types.md`.

**Test Plan (root) id** — the root of a depth-0 tree dump:

```bash
jmeter-cli tree --includeProperties false --maxDepth 0
```

**New element's id** — `create` prints a plain line in its reply:

```bash
id=$(jmeter-cli create --elementType threadgroup --elementName TG1 --parentId <rootId> \
       --properties '{"ThreadGroup.num_threads":5}' | sed -n 's/^elementId: //p')
```

**Finding an existing element** — scope by type and parent, not by name (names are not unique):

```bash
jmeter-cli find --searchBy elementType --query threadgroup --parentId <rootId>
jmeter-cli find --searchBy name --query "POST_login" --exactMatch true
```

`copy` reports `New elementId: <n>`. `move` also reports `New elementId:` — do not chain on it;
re-read `tree` after a move. No `jq` is assumed: to pull the first id out of any payload,

```bash
... | grep -oE '"elementId"[[:space:]]*:[[:space:]]*[0-9]+' | head -1 | grep -oE '[0-9]+'
```

## Operating workflow

1. **Find the instance.**
   ```bash
   jmeter-cli list
   jmeter-cli health --pid <pid>
   ```
   Continue only when `health` succeeds. Note `list` exits **0** when nothing is running.

2. **Read before writing.**
   ```bash
   jmeter-cli script-info
   jmeter-cli tree --includeProperties false --maxDepth 2
   ```
   Capture the root id (previous section). Do not rely on GUI selection unless the user asks for
   selection-relative behavior.

3. **Choose the type key**, then the property JSON. `element-types.md` for the key, placement and
   creatability; `property-recipes.md` for the property names and copy-paste shapes. Do not
   invent either.

4. **Mutate, one meaningful step at a time.**
   ```bash
   jmeter-cli create --elementType threadgroup --elementName "TG_login" --parentId <rootId> \
     --properties '{"ThreadGroup.num_threads":5,"ThreadGroup.ramp_time":10}'
   jmeter-cli update --elementId <id> --properties '{"name":"GET_health"}'
   jmeter-cli get --elementId <id> --includeProperties true --maxDepth 1
   ```
   Read back what you wrote — property names are checked against the component schema, but a
   value you *believe* is set can still be the schema default if you supplied the wrong key.

5. **Verify** — see the next section. Never skip it.

6. **Run and collect** only when asked or when verification requires it.
   ```bash
   jmeter-cli run --mode smoke
   jmeter-cli status
   jmeter-cli results --format both --limit 20
   ```

## Verify before reporting done

After any tree change, verify before you answer — unless the user asked you not to run anything.

```bash
jmeter-cli validate
jmeter-cli run --mode smoke
jmeter-cli results --format samples --statusFilter failure --includeDetails true --limit 10
```

1. `validate` — static structure/logic lint. Its payload opens with
   `## Test Plan Validation: PASS|FAIL`. Fix every listed error and re-run. It does **not** check
   undefined `${var}` references or missing HTTP targets (definition sites and HTTP Request
   Defaults merges are not statically enumerable) — the smoke run is the arbiter for those.
2. `run --mode smoke` — runs **every** thread group at 1 thread x 1 iteration (scheduler off,
   timers skipped, assertions kept) and blocks up to **180 s** for a verdict. It really sends
   requests: write endpoints are hit **once**, so pick targets deliberately. Verdicts:
   - `Smoke validation completed: PASS (zero errors)` — hard standard met.
   - `Smoke validation completed: FAIL` — go to step 3.
   - `Smoke validation completed: FAIL (zero samples executed ...)` — nothing ran (disabled
     samplers, false controller condition, empty thread group). Counting as a pass is wrong.
   - `Smoke validation still running` / `Turn budget exhausted` — **no verdict**; poll `status`,
     then `results`.
3. On FAIL, the third command carries the **failed assertion messages** — they appear only with
   `--includeDetails true`. Fix the root cause (target, correlation, expected value) — do not
   weaken or delete the assertion to make the smoke pass.
4. Repeat from step 1 until PASS.

**Exit codes do not carry the verdict.** `validate` and `run --mode smoke` exit `0` for both PASS
and FAIL. Gate on the payload text, never on `$?` alone.

## Command choice

- Prefer the top-level verbs for everything they cover: `list`, `health`, `open`, `parse`, `get`,
  `find`, `query`, `tree`, `script-info`, `log`, `create`, `copy`, `update`, `delete`,
  `batch-delete`, `move`, `batch-move`, `toggle`, `batch-toggle`, `batch`, `validate`, `run`,
  `stop`, `shutdown`, `status`, `results`.
- Use `tool <name> --params '<json>'` only for capabilities without a wrapper (e.g.
  `parse_jmx_file` property filters `propertyName`/`propertyValue`/`matchMode`).
- `batch`, `batch-delete`, `batch-move`, `batch-toggle` take **at most 50 ids**, and `batch`
  requires **one elementType** for every id — a mixed list is rejected and nothing is written.
  Group ids by type, one call per type.
- Delegate to the built-in agent only per the next section — not as a substitute for `update`.

## Escalation: hand the objective to the built-in agent

When the task is a *goal* rather than a script of edits — "make the login flow pass", "this plan
should profile checkout with 200 users" — the GUI's own agent is the stronger tool:

```bash
jmeter-cli agent "/goal make the login flow pass with assertions on status and token"
jmeter-cli agent "add an XPath extractor to the third request"      # one-off edit
```

It edits the live plan, validates, smoke-runs, reads failures and iterates across turns, and it
leaves a pre-turn rollback baseline in `{workspace}/autosave/` (direct CLI edits leave none).

Caveats:
- It runs in the **GUI's shared chat session**; the reply is visible in the panel. Pass
  `--session <key>` only to work in an isolated conversation — never hardcode `jmeter-ai-chat`.
- It **blocks** until the turn ends: default wait is the instance's `agentTimeoutMs` + 10 s
  (310 s fallback). On timeout the turn is cancelled **server-side** (`cancelled: true`,
  `cancelReason: timeout`) and edits may be partially applied — re-read `tree` afterwards.
- `"Message injected into current conversation."` means a turn was already running and your text
  was queued, **not** executed.

## Quoting JSON on the command line

`--properties` and `--params` take a JSON **string**. Shells mangle it differently.

**bash / zsh** — single quotes:

```bash
jmeter-cli create --elementType httpsampler --elementName "GET /api" --parentId 5 \
  --properties '{"HTTPSampler.domain":"example.com","HTTPSampler.path":"/api"}'
```

**cmd.exe** — escape the inner quotes:

```bat
jmeter-cli create --elementType httpsampler --elementName "GET /api" --parentId 5 ^
  --properties "{\"HTTPSampler.domain\":\"example.com\"}"
```

**Windows PowerShell 5.1** — single quotes alone are **not** enough: PowerShell strips embedded
double quotes from native-command arguments, so the CLI receives `{HTTPSampler.domain:...}` and
exits 2 with `Invalid JSON for --properties: Unexpected character`. Backslash-escape the inner
quotes:

```powershell
jmeter-cli create --elementType httpsampler --elementName "GET /api" --parentId 5 `
  --properties '{\"HTTPSampler.domain\":\"example.com\",\"HTTPSampler.path\":\"/api\"}'
```

PowerShell 7.3+ defaults to standard argument passing, where the plain single-quoted form works
and the escaped form may not. Decide empirically, in this order:

1. Try the plain single-quoted form.
2. If it exits 2 with `Unexpected character` naming your first property, switch to the
   backslash-escaped form (5.1) or to the stop-parsing token (both versions):

```powershell
jmeter-cli --% create --properties "{\"HTTPSampler.domain\":\"example.com\"}"
```

`--%` passes everything after it verbatim — no PowerShell variables or expressions may follow it.

Never reformat the JSON to fix this error: the failure is in the shell layer, not the JSON.

## Reading `--json` output

Without `--json`, the tool payload is printed **directly** — for inspection commands that is
already JSON, so prefer the plain form when you only need the data. Add `--json` when you need
transport metadata.

`--json` prints the envelope, not the payload:

```json
{ "success": true, "content": "<payload>", "error": null, "durationMs": 42 }
```

- `content` is **always a string**. For `tree`, `get`, `find`, `query`, `parse`, `script-info`,
  `log`, `open` it is itself pretty-printed JSON — decode twice:
  `jmeter-cli tree --json | jq -r '.content' | jq .`.
- For `create`, `update`, `copy`, `move`, `delete`, `batch*`, `validate`, `status`, `results`,
  `run` it is human-readable text, and verdicts (`PASS`/`FAIL`) live in that text.
- `get --elementId` and `find` return a paginated wrapper `{total, offset, limit, elements:[...]}` —
  read `elements[0]`, not the object root.
- `list --json` is the exception: a bare JSON **array** of instance objects
  (`pid`, `port`, `bind`, `instanceId`, `startedAt`, `jmxPath`, `agentTimeoutMs`, `token`).
  The token is an IPC credential — never echo it into logs or chat.
- On failure `success` is false and `error` carries the reason. For `agent` cancels,
  `cancelled`/`cancelReason`/`partialContent` appear.

## Failure handling

- **Exit codes:** `0` = the command ran (NOT that the plan is valid), `1` = tool/transport
  failure, `2` = bad usage. `validate` and `run --mode smoke` exit `0` on FAIL — always read the
  payload.
- `JMETER_HOME not set` → pass `--jmeter-home <dir>` or set the variable.
- `no running JMeter AI instance` → start the GUI with `-Jjmeter.ai.ipc.enabled=true`; `list`
  prunes stale port files.
- `tool not allowed over IPC` → the allowlist is element CRUD, inspection, validation,
  run/results, jmx load/parse and the log panel. Nothing else is reachable.
- `Could not find element with elementId: N` → the id was invalidated by an `open`/reload, or the
  element was deleted. Re-fetch with `tree`; do not re-create blindly.
- `Unknown elementType: '...'` — `create` prints the same text whether the key is misspelled or
  registered-but-not-creatable (always with `No schema definition found`), so you cannot tell the
  two apart from the message. Check the key against `element-types.md`: if it is listed under
  "Types that CANNOT be created" the type exists but `create` cannot build it; otherwise it is a
  typo. (`find --searchBy elementType` is the one place that distinguishes them: a registered key
  succeeds, an unknown key errors without the schema clause.)
- `Invalid JSON for --properties` → shell quoting (see above), not a JSON authoring error.

## Reference map

| Read this | When |
|---|---|
| [`references/cli-reference.md`](references/cli-reference.md) | You need an exact flag, default, or output shape for one command |
| [`references/element-types.md`](references/element-types.md) | Choosing an `elementType` key, checking whether it is creatable, or deciding where it hangs in the tree |
| [`references/property-recipes.md`](references/property-recipes.md) | Writing `--properties` JSON: HTTP bodies, headers, assertions, extractors, CSV, timers |
| [`references/workflows.md`](references/workflows.md) | Running a whole task: build / verify / diagnose / load-test / edit an existing `.jmx` |

`--elementType` keys and property names come from these files. If the plugin's own `jmeter` skill
is installed alongside this one (`$JMETER_HOME/bin/jmeter-agent/skills/jmeter/`), its
`references/{native,gitee-qa,third-party}/<category>/<Name>.schema.yaml` holds the full property
list for every creatable type — that is the authoritative source when the recipes here fall short.