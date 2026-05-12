# PerforAuto Stepping Thread Group

## Description

The PerforAuto Stepping Thread Group combines the step-based load generation of the Stepping Thread Group with performance automation capabilities. It supports:

- **Step-based ramp-up/ramp-down** — gradually increase and decrease threads in discrete batches
- **Scenario tracking** — associates a scenario name with the thread group
- **Record file output** — writes lifecycle data and per-step concurrency info to a CSV file
- **Stop delay** — configurable delay before stopping threads after test completion

This component is designed for automated performance testing pipelines that require step-based load profiling with full execution metadata capture.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `ThreadGroup.num_threads` | Yes | `100` | Total number of virtual users (threads). | `100` |
| `Threads initial delay` | No | `0` | Initial delay in seconds before the first batch starts. | `5` |
| `Start users count` | No | `0` | Threads to start per step. `0` = all at once. | `10` |
| `Start users count burst` | No | `0` | Threads in the first (burst) batch. `0` = defaults to Start users count. | `20` |
| `Start users period` | No | `30` | Seconds between each thread-start step. | `30` |
| `rampUp` | No | `0` | Ramp-up time in seconds within each step. | `5` |
| `flighttime` | No | `30` | Hold time in seconds at peak load. | `60` |
| `Stop users period` | No | `0` | Seconds between each thread-stop step. | `10` |
| `Stop users count` | No | `0` | Threads to stop per step. `0` = all at once. | `10` |
| `ThreadGroup.on_sample_error` | No | `continue` | Behavior on sampler error. | `continue` |
| `AbstractPerforAutoSimpleThreadGroup.scenario` | No | — | Scenario name for tracking and recording. | `阶梯加压场景` |
| `AbstractPerforAutoSimpleThreadGroup.record_file_path` | No | — | File path for test execution records. | `/data/records/step_test.csv` |
| `AbstractPerforAutoSimpleThreadGroup.stop_delay` | No | `0` | Delay in seconds after test completion. | `10` |
| `AbstractPerforAutoSimpleThreadGroup.perfor_auto_args` | No | — | Extra arguments for record output. | `env=staging` |
| `ThreadGroup.main_controller` | Yes | — | Loop controller (contains `LoopController.loops`, `LoopController.continue_forever`). | — |

## Usage Examples

### Example 1: Step Load with Automation Tracking

```
create_jmeter_element with:
- elementType: "perforautosteppingthreadgroup"
- elementName: "阶梯加压-100并发"
- properties:
  - ThreadGroup.num_threads: "100"
  - Threads initial delay: "5"
  - Start users count burst: "10"
  - Start users count: "10"
  - Start users period: "30"
  - rampUp: "5"
  - flighttime: "60"
  - Stop users count: "100"
  - Stop users period: "10"
  - AbstractPerforAutoSimpleThreadGroup.scenario: "阶梯加压-100并发"
  - AbstractPerforAutoSimpleThreadGroup.record_file_path: "/data/records/step_100.csv"
  - AbstractPerforAutoSimpleThreadGroup.stop_delay: "5"
  - ThreadGroup.on_sample_error: "continue"
  - ThreadGroup.main_controller:
    - LoopController.loops: "-1"
    - LoopController.continue_forever: "true"
```

## Notes

- This is a custom component combining Stepping Thread Group with PerforAuto automation features
- The record output includes per-step concurrency info (BucketThread field) with timestamps for each step
- NumThreads in the record is set to `NA` since the concurrency varies by step
