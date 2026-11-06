# Task Workflows

## Recipe A — build an API test plan from scratch

```bash
# 1. Root id (once). Depth 0 returns the Test Plan node itself.
jmeter-cli tree --includeProperties false --maxDepth 0
#    -> read the first "elementId" from the output
ROOT=<rootId>

# 2. Thread group
jmeter-cli create --elementType threadgroup --elementName "TG_login_scenario" --parentId $ROOT \
  --properties '{"ThreadGroup.num_threads":5,"ThreadGroup.ramp_time":10,"ThreadGroup.main_controller":{"LoopController.loops":3}}'
#    -> read the "elementId: <n>" line of the reply
TG=<threadGroupId>

# 3. Shared config under the thread group (NOT per sampler)
jmeter-cli create --elementType httpdefaults --elementName "Defaults_api" --parentId $TG \
  --properties '{"HTTPSampler.protocol":"https","HTTPSampler.domain":"api.example.com"}'
jmeter-cli create --elementType headermanager --elementName "Headers_JSON" --parentId $TG \
  --properties '{"HeaderManager.headers":[{"Header.name":"Content-Type","Header.value":"application/json"}]}'

# 4. Sampler
jmeter-cli create --elementType httpsampler --elementName "POST_login" --parentId $TG \
  --properties '{"HTTPSampler.method":"POST","HTTPSampler.path":"/api/login","HTTPSampler.postBodyRaw":true,"HTTPsampler.Arguments":[{"Argument.name":"","Argument.value":"{\"username\":\"admin\",\"password\":\"secret\"}","HTTPArgument.always_encode":false}]}'
#    -> S=<samplerId>

# 5. Assertion and extractor as CHILDREN of that sampler
jmeter-cli create --elementType responseassertion --elementName "Assert_status_200" --parentId $S \
  --properties '{"Assertion.test_field":"Assertion.response_code","Assertion.test_type":8,"Asserion.test_strings":["200"]}'
jmeter-cli create --elementType jsonpostprocessor --elementName "Extract_token" --parentId $S \
  --properties '{"JSONPostProcessor.referenceNames":"token","JSONPostProcessor.jsonPathExprs":"$.data.token"}'
```

Capture each id from the `elementId: <n>` line of the preceding `create`. Then run Recipe B.

## Recipe B — verify and fix

1. `jmeter-cli validate` -> payload opens with `## Test Plan Validation: PASS|FAIL`. Fix every
   item under `1. Structure` and `2. Logic`; re-run until PASS. Exit code is 0 either way.
2. `jmeter-cli run --mode smoke` -> require `Smoke validation completed: PASS (zero errors)`.
   - `FAIL` -> step 3.
   - `FAIL (zero samples executed)` -> nothing ran; check for disabled samplers, false controller
     conditions, an empty thread group.
   - `still running` / `Turn budget exhausted` -> **no verdict**; poll `status` then `results`.
3. `jmeter-cli results --format samples --statusFilter failure --includeDetails true --limit 10`
   -> failed-assertion messages appear only with `--includeDetails true`. Fix the root cause
   (target, correlation, expected value); do not delete the assertion.
4. Repeat. Budget roughly three repair rounds; if it still fails, report the exact failing sampler
   and assertion message rather than shipping it.

## Recipe C — diagnose a failed run

```bash
jmeter-cli status          # **State**, **Started by**, Errors, Error rate
jmeter-cli results --format summary            # per-sampler breakdown localises the failing request
jmeter-cli results --format samples --statusFilter failure --includeDetails true --limit 10
jmeter-cli log --maxLines 100
```

- Results are process-global: if `Started by` is `USER` and you are an automation run, a human
  started a run in between — your numbers were replaced, not lost.
- Only the most recent 1000 samples survive; on a long run, read results promptly.
- A 5xx with a valid assertion means the server failed; a FAIL with code 200 means the assertion
  failed — the difference is only visible with `--includeDetails true`.

## Recipe D — load / performance run

- Sizing: `ThreadGroup.num_threads` + `ThreadGroup.ramp_time` + a loop count
  (`"ThreadGroup.main_controller":{"LoopController.loops":10}`).
- **A scheduler window does not replace the loop count.** `LoopController.loops` defaults to **1**
  and still governs when each thread stops; `ThreadGroup.scheduler`/`duration` only truncates a
  run that would otherwise go longer. Setting `scheduler:true, duration:600` while leaving loops
  alone gives you **one iteration per thread** — a "10-minute load test" that finishes in seconds
  and looks clean in the summary. For a time-boxed run set **both**:
  `"ThreadGroup.scheduler":true,"ThreadGroup.duration":600` **and**
  `"ThreadGroup.main_controller":{"LoopController.loops":-1}` (infinite, truncated by the
  scheduler).
- Pacing: one `uniformrandomtimer` per thread group for think time
  (`{"ConstantTimer.delay":"500","RandomTimer.range":1500.0}` — the range is a **Double**, so
  `1500` is rejected), or `constantthroughputtimer`
  (`{"throughput":600.0,"calcMode":1}` = 600 samples/min across all threads — also a Double).
- Disable graphical listeners you are not reading:
  `batch-toggle --elementIds <ids> --action disable`.
- **Do not** use `run --wait --timeout 20000` for a multi-minute run: on timeout the CLI exits 1
  while the test keeps running and is never cancelled. Start without `--wait`, then poll `status`
  yourself, or set `--timeout` long enough.
- Verify with `--mode smoke` first — 1 thread x 1 iteration across every thread group.

## Recipe E — work on an existing `.jmx`

- `parse --file <abs path>` is **read-only inspection**: the GUI is untouched and the output has
  **no `elementId`**. You cannot update/move/delete what you only parsed.
- To modify: `jmeter-cli open --file <abs path>` (replaces the live plan; unsaved edits are
  discarded without a prompt). Then operate on ids from `tree`.
- Absolute paths only — relative paths resolve inside the JMeter process, not your shell.
- There is **no save command**. When done, ask the human to save in the GUI, or accept that `run`
  will save the live tree over the associated file.
- Prefer `get --elementId <id> --includeProperties true` over re-parsing the file: the live element
  is what will actually run.

## Recipe F — hand a goal to the built-in agent

```bash
jmeter-cli agent "/goal make the login flow pass with assertions on status and token"
```

Use it when the objective needs many iterations or a type that `create` cannot build. It edits,
validates, smoke-runs and repairs across turns, and it leaves a pre-turn baseline under
`{workspace}/autosave/`. **Two kinds of files live there** — pick the right one:

| Name pattern | What it is |
|---|---|
| `{yyyyMMddHHmmssSSS}-{instanceId}-{turnId}-{origin}-{plan}.jmx` | a turn baseline = the plan **before** that agent turn |
| `agent-run-*` / `goal-smoke-*` | autosave written by `run` on a never-saved plan = the plan **at run start** (after your edits) |

Names sort chronologically and the newest is usually an `agent-run-*` file for the smoke run the
turn just did — opening that rolls you to the *post*-edit state, not the pre-turn one. Filter to
the timestamped turn baselines before picking "the newest".

Open one with `open --file <abs snapshot path>`, then have the human Save As — a plain save
overwrites the snapshot itself. It blocks up to `agentTimeoutMs` + 10 s; on timeout the turn is
cancelled server-side and edits may be partial — re-read `tree`. A reply of
`Message injected into current conversation.` means your text was queued, not executed.
