# Plan Snapshots (Turn Rollback Baselines)

The plugin automatically snapshots the test plan at **turn boundaries**: every agent turn
(panel input, CLI, delegated task, or goal continuation) captures an in-memory baseline of
the tree before any of its tools run; when the turn ends (completed, stopped, aborted, or
reset), the tree is compared against that baseline — **if the plan changed during the turn,
the pre-turn baseline is written to disk**. Use these files to roll the plan back to the
state it had *before a specific turn* (e.g. after a modification went wrong).

## Directory

All snapshots live in the workspace autosave directory:

```
{workspace}/autosave/
```

`{workspace}` resolves in this order:

1. the `agent.workspace.path` JMeter property, if set;
2. `{JMETER_HOME}/bin/jmeter-agent` (default);
3. `{user.home}/.jmeter-ai/agent` (only when JMeter home is not yet initialized).

Two JMeter instances sharing one installation share this directory — file names carry the
instance id, so they never collide.

## File naming

Two kinds of files share the directory:

- **Turn snapshots** (written at turn end when the plan changed during that turn):

  ```
  {yyyyMMddHHmmssSSS}-{instanceId}-{turnId}-{origin}-{planName}.jmx
  ```

  - `yyyyMMddHHmmssSSS` — compact local wall-clock timestamp when the file was written
    (= the moment that turn **ended**, NOT when it started); fixed width, so
    **lexicographic order = chronological order**.
  - `instanceId` — `{pid}-{startedAtMs}` of the JMeter instance that wrote it.
  - `turnId` — the process-unique turn sequence number (plugin-internal bookkeeping;
    not surfaced in the chat transcript, so use ordering, not turn numbers, to pick files).
  - `origin` — one of `local` (panel input), `cli` (jmeter-cli direct), `delegated`
    (peer-instance delegated turn), `republish` (goal continuation or re-published
    leftover-injection turn).
  - `planName` — the plan's associated file name at turn start (base name without
    `.jmx` extension), so snapshots of different scripts are distinguishable at a
    glance; `unsaved` when the plan had never been saved.

  Example: `20261013112304307-33432-1791861424284-2-local-demo.jmx` is the state the
  plan `demo.jmx` had **before** one panel-originated turn (turn 2), written at
  11:23:04.307 (the turn's end moment) by instance `33432-1791861424284`.

- **run_test autosave files** (written by `run_test` when it starts a test on a
  never-saved plan — the state that actually ran):

  ```
  {agent-run|goal-smoke}-{instanceId}-{timestamp}.jmx
  ```

  These serve a different purpose (silent save-before-run so JMeter does not pop a modal
  during agent runs). A turn snapshot captures the state **before** the turn's changes;
  a run_test autosave captures the state **at run time** — both may exist for the same turn.

## Restoring a snapshot

Restoring is manual — open the snapshot file with `open_jmx_file` (open mode replaces the
current plan):

```json
{"filePath": "{workspace}/autosave/20261012-153012-345-12345-678912345-17-local.jmx"}
```

**How to pick the right file.** Each snapshot holds the state the plan had *before* one
modifying turn, and is written at the moment that turn *ended*. Therefore, by write time
(= file name order):

- To undo the **most recent** modifying turn → pick the **newest** file.
- To go back **k** modifying turns → pick the **k-th newest** file.

Do NOT filter by "timestamp precedes the turn you want to undo" — the baseline of turn N
carries turn N's *end* timestamp, so that heuristic skips the file you want and over-rolls
one turn too far.

Typical procedure:

1. `list_dir` the autosave directory to enumerate snapshots (names sort chronologically).
2. Pick per the rule above.
3. `open_jmx_file` it (default `merge=false` replaces the current plan).
4. Verify with `get_test_plan_tree`, then re-apply only the changes you meant to keep.
5. **Save As to a proper location** (do NOT Ctrl+S immediately): after `open_jmx_file`
   the plan's file association points at the snapshot file itself — a plain Ctrl+S would
   silently overwrite that baseline, and the instance's IPC registry would broadcast the
   snapshot path as the "open jmx". Copy the plan to its real path instead.

## Notes

- A turn that left the plan byte-identical (pure chat, or edits fully reverted) writes
  **no** file.
- Snapshots are best-effort: failures are logged and never block the agent run.
- There is no automatic cleanup: the directory accumulates files over time.
- Snapshot files never contain the plugin's internal result collector node.
