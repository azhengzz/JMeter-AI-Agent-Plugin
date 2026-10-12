# Tool Usage Notes

Tool signatures are provided automatically via function calling.
This file documents non-obvious constraints and usage patterns.

## exec — Safety Limits

- Commands have a configurable timeout (default 60s)
- Dangerous commands are blocked (rm -rf, format, dd, shutdown, etc.)
- Output is truncated at 10,000 characters

## run_test — Smoke Mode

- `mode: "smoke"` validates ALL thread groups at 1 thread x 1 iteration
  (scheduler off, timers skipped, assertions kept); the saved thread group
  configuration is never modified.
- Smoke runs block until the minimal run completes and return a PASS/FAIL
  verdict from the captured error count.
- Each smoke attempt counts against the active goal's smoke budget
  (default 3); a normal `start` does not.

## validate_test_plan — Static Check

- Sends no traffic. Reports structure errors, schema violations, and logic
  errors with per-element locations and fixes.
- Advice-level findings (e.g. "sampler has no assertion") are deliberately
  not reported — fix errors first, style later. Undefined `${var}` references
  and missing HTTP targets are also not checked (definition sites like JSR223
  scripts, post-processors, and HTTP Request Defaults merges are not statically
  enumerable) — the smoke run is the arbiter for those.

## create_goal / update_goal — Permission

- `create_goal` and `update_goal action='replace'` only work on turns the
  user started with `/goal <objective>`; terminal actions (complete/cancel/
  block) work on any turn while a goal is active, and always require a recap.
