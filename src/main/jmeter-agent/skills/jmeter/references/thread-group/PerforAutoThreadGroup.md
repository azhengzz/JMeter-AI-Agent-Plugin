# PerforAuto Thread Group

## Description

The PerforAuto Thread Group is a custom thread group developed for performance automation testing. It extends the standard Thread Group with additional capabilities:

- **Scenario tracking** — associates a scenario name with the thread group for identification
- **Record file output** — writes thread group lifecycle data (start/end time, duration, thread count, scenario, HTTP request summary) to a CSV file upon test completion
- **Stop delay** — configurable delay before stopping threads after test completion, useful for coordinating with external monitoring systems
- **Extra arguments** — custom arguments that can be passed through to the record output

This thread group is designed for integration with automated performance testing pipelines where test execution metadata needs to be captured and reported.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `ThreadGroup.num_threads` | Yes | `100` | Number of virtual users to simulate. | `100` |
| `PerforAutoThreadGroup.ramp_time` | No | `1` | Ramp-up time in seconds to start all threads. | `10` |
| `PerforAutoThreadGroup.delayedStart` | No | `false` | If `true`, threads are created only when the appropriate proportion of the ramp-up time has elapsed. | `true` |
| `PerforAutoThreadGroup.scheduler` | No | `false` | If `true`, enables scheduler for time-bound testing. | `true` |
| `PerforAutoThreadGroup.duration` | No | `0` | Test duration in seconds. Requires scheduler enabled. | `600` |
| `PerforAutoThreadGroup.delay` | No | `0` | Startup delay in seconds. Requires scheduler enabled. | `5` |
| `ThreadGroup.on_sample_error` | No | `continue` | Behavior on sampler error. | `continue` |
| `PerforAutoThreadGroup.scenario` | No | `单交易基准` | Scenario name for tracking and recording. | `登录接口压测` |
| `PerforAutoThreadGroup.record_file_path` | No | `""` | File path for test execution records. | `/data/records/test.csv` |
| `PerforAutoThreadGroup.stop_delay` | No | `0` | Delay in seconds after test completion before stopping threads. | `10` |
| `PerforAutoThreadGroup.perfor_auto_args` | No | `""` | Extra arguments for performance automation record output. | `env=staging` |
| `ThreadGroup.main_controller` | Yes | — | Loop controller (contains `LoopController.loops`, `LoopController.continue_forever`). | — |
| `ThreadGroup.same_user_on_next_iteration` | No | `true` | Same user on each iteration. | `true` |

### Error Handling Options

| Value | Description |
|-------|-------------|
| `continue` | Ignore the error and continue with the test |
| `startnextloop` | Ignore the error, start next loop and continue with the test |
| `stopthread` | Current thread exits |
| `stoptest` | The entire test is stopped at the end of any current samples |
| `stoptestnow` | The entire test is stopped abruptly. Any current samplers are interrupted if possible |

## Usage Examples

### Example 1: Basic Performance Automation Test

```
create_jmeter_element with:
- elementType: "perforautothreadgroup"
- elementName: "登录接口压测-100并发"
- properties:
  - ThreadGroup.num_threads: "100"
  - PerforAutoThreadGroup.ramp_time: "30"
  - PerforAutoThreadGroup.scheduler: "true"
  - PerforAutoThreadGroup.duration: "300"
  - PerforAutoThreadGroup.scenario: "登录接口压测"
  - PerforAutoThreadGroup.record_file_path: "/data/records/login_test.csv"
  - PerforAutoThreadGroup.stop_delay: "5"
  - ThreadGroup.on_sample_error: "continue"
  - ThreadGroup.main_controller:
    - LoopController.loops: "-1"
    - LoopController.continue_forever: "true"
```

### Example 2: Scenario with Extra Arguments

```
create_jmeter_element with:
- elementType: "perforautothreadgroup"
- elementName: "订单创建场景-50并发"
- properties:
  - ThreadGroup.num_threads: "50"
  - PerforAutoThreadGroup.ramp_time: "10"
  - PerforAutoThreadGroup.scheduler: "true"
  - PerforAutoThreadGroup.duration: "600"
  - PerforAutoThreadGroup.delay: "5"
  - PerforAutoThreadGroup.scenario: "订单创建"
  - PerforAutoThreadGroup.record_file_path: "/data/records/order_test.csv"
  - PerforAutoThreadGroup.stop_delay: "10"
  - PerforAutoThreadGroup.perfor_auto_args: "env=staging,version=v2.1"
  - ThreadGroup.on_sample_error: "continue"
  - ThreadGroup.main_controller:
    - LoopController.loops: "-1"
    - LoopController.continue_forever: "true"
```

### Example 3: Simple Loop-Based Test Without Scheduler

```
create_jmeter_element with:
- elementType: "perforautothreadgroup"
- elementName: "查询接口-10次循环"
- properties:
  - ThreadGroup.num_threads: "10"
  - PerforAutoThreadGroup.ramp_time: "5"
  - PerforAutoThreadGroup.scenario: "查询接口回归"
  - ThreadGroup.on_sample_error: "continue"
  - ThreadGroup.main_controller:
    - LoopController.loops: "10"
```

## Notes

- This is a custom component developed for performance automation, not part of the standard JMeter distribution
- The record file is written after all threads complete, containing: JmxName, ThreadGroupObj, Uuid, ThreadGroupName, Scenario, StartTime, EndTime, Duration, NumThreads, BucketThread, ExtraArgs, HTTPArgs
- `stop_delay` is useful when external monitoring systems need time to collect final metrics before the test process exits
- HTTPArgs in the record output contains the method and path of all enabled HTTP samplers in the thread group
