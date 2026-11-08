# Agent Tools

> English translation of [agent-tools.md](./agent-tools.md) (中文版).

This page lists all agent tools built into Gitee Ai - JMeter Agent. For basic usage, see the [README Quick Start](../README_en.md#quick-start).

Except for the subagent tools (off by default), every tool below is registered and enabled under the default configuration; each group can be disabled separately via its config switch (see the [Configuration Reference](configuration_en.md#tool-configuration)).

## JMeter Element Tools

| Tool | Description |
|--------|------|
| `create_jmeter_element` | Create a JMeter element (thread group, sampler, controller, assertion, timer, etc.) |
| `update_jmeter_element` | Update properties of an existing element |
| `batch_update_jmeter_elements` | Batch-update properties of multiple same-type elements (one validation pass, one GUI refresh) |
| `delete_jmeter_element` | Delete a specific element (the TestPlan root cannot be deleted) |
| `batch_delete_jmeter_elements` | Batch-delete multiple elements (validated together before deletion) |
| `move_jmeter_element` | Move an element to a different parent, with precise position control (first / last / before:<id> / after:<id>) |
| `batch_move_jmeter_elements` | Batch-move multiple elements to the same position under the same parent |
| `copy_paste_jmeter_element` | Copy-paste a test plan element |
| `toggle_jmeter_element` | Enable, disable, or toggle an element (disabled elements are skipped during test execution) |
| `batch_toggle_jmeter_elements` | Batch enable, disable, or toggle multiple elements |
| `get_test_plan_tree` | Get the full test plan tree structure (JSON) |
| `get_selected_element` | Get details of the currently selected element |
| `find_element` | Find elements by name, type, or path |
| `query_element_properties` | Query plan elements by property name/value |

## JMX and Script Tools

| Tool | Description |
|--------|------|
| `parse_jmx_file` | Parse an external JMX script file (returns the component tree, or queries elements with filters) |
| `open_jmx_file` | Open an external JMX file in the current GUI (replaces the current plan by default; can merge-load) |
| `get_script_info` | Get current script and runtime environment info (script path, save state, JMeter/JDK versions, JMETER_HOME) |
| `get_log_panel_content` | Read JMeter LoggerPanel log content by line range |

## Test Execution Tools

| Tool | Description |
|--------|------|
| `run_test` | Start, stop, or shut down the current test plan |
| `get_test_status` | Get test execution status (run state, thread progress, sample counts) |
| `get_test_results` | Get test results (response time, throughput, error rate) |

## Cross-Instance Collaboration Tools (registered with IPC, on by default)

| Tool | Description |
|--------|------|
| `list_instances` | List all live JMeter AI instances on this machine (including this one) and their open test plans |
| `delegate_to_instance` | Delegate a task to another JMeter AI instance on this machine and block for its result |

## Filesystem Tools (enabled by default)

| Tool | Description |
|--------|------|
| `read_file` | Read file contents, with pagination |
| `write_file` | Write a file, creating directories as needed |
| `edit_file` | Edit a file (string replacement) |
| `list_dir` | List directory contents, supports recursion |

## Web Tools (enabled by default)

| Tool | Description |
|--------|------|
| `web_search` | Search the internet (Brave, Tavily, Jina, and other search engines) |
| `web_fetch` | Fetch web page content (extracts the body via Jina Reader when possible; direct connections are converted to Markdown/plain text) |

## Command Execution Tool (enabled by default)

| Tool | Description |
|--------|------|
| `exec` | Execute a shell command, with timeout and working-directory configuration |

## Subagent Tools (off by default)

Registered after setting `agent.subagent.enabled=true` (configuration in the [Configuration Reference](configuration_en.md#asynchronous-subagents-subagent)).

| Tool | Description |
|--------|------|
| `spawn` | Delegate a self-contained read-only analysis task to a background subagent |
| `subagent_status` | Query subagent progress and results |

## Browser Recording Tools

Shares the same recording session with the GUI ●REC button (single-session mutual exclusion; the tools can take over a session opened via the button). Not registered when `jmeter.ai.recorder.enabled=false`; open/begin/stop are hard-blocked during goal turns (recording must be human-driven; get_recording_status remains available read-only). Control commands and the UI capture script run in a CDP isolated world (invisible to and uncallable from the page's main world, preventing forgery).

| Tool | Description |
|--------|------|
| `open_recording_browser` | Open the recording browser into the armed state (zero capture; parameters: start URL, domain filter — both optional) |
| `begin_recording` | Explicitly start capturing (the cookie baseline is taken at this moment, so cookies from logging in during the armed phase are preserved) |
| `stop_recording` | Stop and finalize (writes events.jsonl/cookies.json; calling it in the armed state = cancel) |
| `get_recording_status` | Query session state, request/UI event counts, and the data directory |
| `import_recording` | Deterministically convert an existing recording session into a standalone skeleton .jmx (the "import later" entry point; available during goal turns) |

For enhancement patterns (business grouping, `${var}` correlation extraction, parameterization, assertions) see the skill reference `skills/jmeter/references/recording-workflow.md`.
