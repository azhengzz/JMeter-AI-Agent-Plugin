# Browser Recording Workflow (recording → skeleton → enhancement)

Browser recording turns a human's manual business flow into a JMeter script.
This reference describes the recorded data format and the enhancement playbook
the Agent follows after a session is finalized.

Related tools: `open_recording_browser` / `begin_recording` / `stop_recording` /
`get_recording_status` / `import_recording` (see TOOLS.md — they share the GUI REC
button's single session; open/begin/stop are disabled in goal turns because recording
is human-driven — only the read-only get_recording_status remains available there).
The UI script and control binding live in a CDP isolated world, so the page's own
scripts can neither read nor invoke them.

## Recording data layout

Each session lives in `{recordings-dir}/rec_{yyyyMMdd-HHmmss}-{instanceId}/`
(`recordings-dir` = `jmeter.ai.recorder.output.dir`, default `{workspace}/recordings`):

| File | Content |
|------|---------|
| `events.jsonl` | One JSON event per line, time-ordered. Fields: `seq`, `ts`, `pageId`, `type` + payload. |
| `cookies.json` | Three separated sources: `baseline` (snapshot at begin — carries ARMED-phase login cookies), `refresh` (snapshot at live stop; `null` when the browser window was closed), `setCookies` (raw Set-Cookie headers harvested from `Network.responseReceivedExtraInfo` — Chrome strips Set-Cookie from `responseReceived.headers`; multi-value lines are `
`-joined). When `refresh` is present it is the authoritative final jar (used alone); otherwise baseline ⊕ setCookies applies, honouring `Max-Age=0`/expired deletions. Cookie identity is domain+path+name. |
| `summary.json` | `result`, `startedAt`/`endedAt`, request/response/UI-event counts. |

Event types:

- `request{requestId, method, url, resourceType, headers, postData?, postDataTruncated?}`
- `requestPostData{requestId, postData, postDataTruncated?}` (fetched separately when CDP
  omits an oversized inline body — same pairing key)
- `response{requestId, status, url, headers}` and `responseBody{requestId, body, base64Encoded, bodyTruncated?}`
- `requestfailed{requestId, url, errorText}`
- UI context: `click{url, selector, tag, text}`, `change{url, selector, tag, name, value|redacted}`, `submit{url, selector}`
- `navigate{url}` (document navigations; also subject to include-domain filtering)

Reading guidance: truncate body payloads beyond ~16KB per event when loading
into context (token economy, NOT redaction — credentials are kept in plaintext
by design). Response bodies larger than ~4MB (encoded) are intentionally not
fetched; UI event payloads are server-capped (text 200 / selector 300 / value 200). Multiple `request` events sharing one `requestId` **on the same
page** are redirect hops; only the last is the final request (requestIds are
only unique per page — the pageId field disambiguates).

Include-domain matching (fixed semantics): case-insensitive suffix match —
`example.com` matches `example.com` and any `*.example.com`; trailing-dot FQDNs
(`sub.example.com.`) are normalized as equal; wildcard syntax is NOT supported
literally.

## What the GUI skeleton already produced

The deterministic converter (run by the GUI result dialog) wraps everything in
`录制 {timestamp}` ThreadGroup (1 thread × 1 iteration) + TransactionController,
with CookieManager (merged baseline ⊕ setCookies ⊕ refresh), HeaderManager
(business headers only — browser-fixed headers stripped; `Authorization` stays
sampler-level), optional HTTP Request Defaults for the dominant origin, and one
sampler per captured request (`METHOD /path?query#seq`; urlencoded bodies are
split into Arguments, JSON/raw bodies go to body data with Content-Type).

## Enhancement playbook

1. **Read the session**: `events.jsonl` interleaves UI events with requests —
   use click clusters as business-step boundaries for transaction grouping and
   sampler renaming (e.g. `click #submit-order` → name the following request
   `提交订单`).
2. **Correlation**: extract dynamic ids/tokens from a `responseBody`
   (order id, pay token…) with a JSON/Regex post-processor and reference
   `${var}` in later samplers.
3. **Parameterization**: obvious user inputs (username, search keyword) →
   User Defined Variables or CSV.
4. **Assertions**: add status-code + key-business-field assertions per sampler
   based on observed responses.
5. **Verify**: `validate_test_plan` then `run_test mode:"smoke"`.

To rebuild the skeleton later (after choosing discard, or from a goal turn), call
`import_recording` with the session dir — it writes a standalone .jmx without
touching the current test plan.

## Anti-patterns

- Do NOT re-add login cookies as a HeaderManager entry — the skeleton's
  CookieManager already carries the session (baseline covers ARMED-phase
  login). Duplicating them breaks logout/replay scenarios.
- Do NOT re-add browser-fixed headers (User-Agent, sec-fetch-*, Accept…) — the
  engine manages those.
- Do NOT redact credentials found in recordings when editing scripts —
  plaintext retention is the deliberate design (replayability first). If the
  user asks for redaction, obfuscate only in the delivered script, never in
  the recording files.

## Privacy note

Recording files contain plaintext cookies/tokens for every captured domain.
They stay on the user's machine with no auto-cleanup; sharing or committing
`recordings/` is the user's responsibility — warn them when they ask to copy
scripts elsewhere.
