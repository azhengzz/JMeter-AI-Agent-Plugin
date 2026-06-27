# PerforAuto Ultimate Thread Group
> **Source**: Gitee QA extension (third-party plugin `com.gitee.qa.jmeter`, requires the corresponding plugin)

## Description

The PerforAuto Ultimate Thread Group combines the flexible per-row scheduling of the Ultimate Thread Group with performance automation capabilities. It supports:

- **Per-row thread scheduling** — each row independently defines a batch of threads with its own startup, hold, and shutdown timing
- **Scenario tracking** — associates a scenario name with the thread group
- **Record file output** — writes lifecycle data to a CSV file
- **Stop delay** — configurable delay before stopping threads after test completion

This component is designed for automated performance testing pipelines that require complex load patterns with full execution metadata capture.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `ultimatethreadgroupdata` | Yes | — | Array of schedule rows. Each row has 5 fields (see below). | (see examples) |
| `ThreadGroup.on_sample_error` | No | `continue` | Behavior on sampler error. | `continue` |
| `AbstractPerforAutoSimpleThreadGroup.scenario` | No | `单交易基准` | Scenario name for tracking and recording. | `波浪压测` |
| `AbstractPerforAutoSimpleThreadGroup.record_file_path` | No | `""` | File path for test execution records. | `/data/records/ultimate_test.csv` |
| `AbstractPerforAutoSimpleThreadGroup.stop_delay` | No | `0` | Delay in seconds after test completion. | `10` |
| `AbstractPerforAutoSimpleThreadGroup.perfor_auto_args` | No | `""` | Extra arguments for record output. | `env=staging` |
| `ThreadGroup.main_controller` | Yes | — | Loop controller (contains `LoopController.loops`, `LoopController.continue_forever`). | — |
| `LoopController.loops` | Yes | `1` | Number of times to perform the test case. `-1` for infinite. | `-1` |
| `LoopController.continue_forever` | No | `false` | Whether to loop infinitely. | `true` |

### Schedule Row Fields

Each row in `ultimatethreadgroupdata` contains:

| Field | Default | Description |
|-------|---------|-------------|
| Start Threads Count | `100` | Number of threads to start in this batch |
| Initial Delay | `0` | Delay in seconds before this batch starts |
| Startup Time | `30` | Ramp-up time in seconds for this batch |
| Hold Load For | `60` | Hold time in seconds at peak load for this batch |
| Shutdown Time | `10` | Ramp-down time in seconds for this batch |

## Usage Examples

### Example 1: Wave Load with Automation Tracking

```
create_jmeter_element with:
- elementType: "perforautoultimatethreadgroup"
- elementName: "波浪压测-两波50用户"
- properties:
  - ultimatethreadgroupdata:
    - ["50", "0", "10", "60", "10"]
    - ["50", "60", "10", "60", "10"]
  - AbstractPerforAutoSimpleThreadGroup.scenario: "波浪压测-两波50用户"
  - AbstractPerforAutoSimpleThreadGroup.record_file_path: "/data/records/wave_test.csv"
  - AbstractPerforAutoSimpleThreadGroup.stop_delay: "5"
  - ThreadGroup.on_sample_error: "continue"
  - ThreadGroup.main_controller:
    - LoopController.loops: "-1"
    - LoopController.continue_forever: "true"
```

### Example 2: Staircase Load with Extra Arguments

```
create_jmeter_element with:
- elementType: "perforautoultimatethreadgroup"
- elementName: "阶梯加压-4级"
- properties:
  - ultimatethreadgroupdata:
    - ["25", "0", "30", "0", "0"]
    - ["25", "30", "30", "0", "0"]
    - ["25", "60", "30", "0", "0"]
    - ["25", "90", "30", "120", "30"]
  - AbstractPerforAutoSimpleThreadGroup.scenario: "阶梯加压-4级"
  - AbstractPerforAutoSimpleThreadGroup.record_file_path: "/data/records/staircase.csv"
  - AbstractPerforAutoSimpleThreadGroup.stop_delay: "10"
  - AbstractPerforAutoSimpleThreadGroup.perfor_auto_args: "env=staging,version=v2.1"
  - ThreadGroup.on_sample_error: "continue"
  - ThreadGroup.main_controller:
    - LoopController.loops: "-1"
    - LoopController.continue_forever: "true"
```

## Notes

- This is a custom component combining Ultimate Thread Group with PerforAuto automation features
- The `ultimatethreadgroupdata` property uses the same format as Ultimate Thread Group
- NumThreads in the record is set to `NA` since the concurrency varies by schedule row
