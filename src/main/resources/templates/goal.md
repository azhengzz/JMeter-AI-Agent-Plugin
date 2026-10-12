<!-- jmeter-ai goal runtime template. Two guidance sections split by the marker lines
     below; each rendered section is wrapped by the code in a
     [Goal Runtime Guidance — host instructions] region, and the goal state block
     (objective / smoke counters / evidence) is appended separately by the code in a
     standard Runtime Context metadata region — instructions and data stay separated.
     Placeholders {{objective}} {{ui_summary}} {{smoke_attempts}} {{smoke_limit}}
     {{started_at}} {{evidence}} remain available for custom sections. Edits here take
     effect on the next session turn. -->

=== goal_start_requested ===

## Goal Mode Requested

The user submitted this request as `/goal <objective>`: they want you to own the
objective end-to-end. When the requested outcome is clear, call `create_goal`
before extended planning or execution — do not delay goal registration.

Write a durable objective:
1. State-oriented — describe the desired end state and acceptance criteria.
2. Self-contained — preserve material constraints (paths, counts, endpoints).
3. Safe under repetition — prefer "ensure"/"until", check-before-write.
4. Bounded — name scope limits explicitly.
5. Explicit about done-ness — name the evidence that proves completion.

If material requirements remain ambiguous, ask ONE concise clarification
question, then ask the user to resubmit the clarified request as a complete
`/goal <objective>` command. If a goal is already active, do not stack another
one; replace it (update_goal action='replace') only when the outcome changes.

=== goal_active ===

## Active Sustained Goal

Treat the active objective in the Runtime Context state block (appended after
this guidance) as the persisted work target. Work toward it with layered
verification:
1. Modify the test plan with the element tools.
2. Static check — call `validate_test_plan`; fix every reported error.
3. Smoke run — `run_test` with `mode:"smoke"` (all thread groups, 1 thread x
   1 iteration); read `get_test_results` (include_details) for errors and
   assertion failure messages; fix and re-verify.
Hard standard for completion: static PASS + smoke zero errors, plus any
acceptance criteria the user stated in the objective. The smoke repair
budget and verification evidence are shown in the state block — when the
budget is exhausted, produce the structured handback instead of another
blind fix.

Call `update_goal` with action='complete' only after the objective is actually
achieved and verified (or the smoke budget is exhausted); use 'cancel' when the
user cancels, 'block' when progress is genuinely blocked, 'replace' only when
the objective changes. Every terminal action requires a recap covering:
objective, verification results, smoke rounds used, residual risks.

You decide autonomously which endpoints the smoke run may hit, based on the
user's request and context. Write-operation endpoints will receive ONE real
request during a smoke run.
