# setUp Thread Group

## Description

A special type of ThreadGroup that can be utilized to perform Pre-Test Actions. The behavior of these threads is exactly like a normal Thread Group element. The difference is that these type of threads execute before the test proceeds to the executing of regular Thread Groups.

Use setUp Thread Group for initialization tasks such as creating test data, setting up database connections, or preparing environment state before the main test runs.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `ThreadGroup.on_sample_error` | No | `"continue"` | Behavior on sampler error | `"continue"` |
| `ThreadGroup.num_threads` | Yes | `"1"` | Number of virtual users to simulate | `"1"` |
| `ThreadGroup.ramp_time` | Yes | `"1"` | Time (seconds) to start all threads | `"1"` |
| `ThreadGroup.main_controller` | Yes | — | Main controller (LoopController) for this thread group | — |
| `LoopController.loops` | Yes | `"1"` | Number of loops (-1 for infinite) | `"1"` |
| `LoopController.continue_forever` | No | `"false"` | Loop infinitely | `"false"` |
| `ThreadGroup.same_user_on_next_iteration` | Yes | `"true"` | Same user on each iteration | `"true"` |
| `ThreadGroup.scheduler` | No | `"false"` | Enable scheduler for time-bound testing | `"false"` |
| `ThreadGroup.duration` | No | — | Test duration in seconds (requires scheduler) | `"60"` |
| `ThreadGroup.delay` | No | — | Startup delay in seconds (requires scheduler) | `"0"` |

### Error Handling Options

Determines what happens if a sampler error occurs. The possible choices for `ThreadGroup.on_sample_error`:

| Value | Description |
|-------|-------------|
| `continue` | Ignore the error and continue with the test |
| `startnextloop` | Ignore the error, start next loop and continue with the test |
| `stopthread` | Current thread exits |
| `stoptest` | The entire test is stopped at the end of any current samples |
| `stoptestnow` | The entire test is stopped abruptly. Any current samplers are interrupted if possible |

## Usage Examples

### Example 1: Basic Setup - Initialize Test Data

```
create_jmeter_element with:
- elementType: "setupthreadgroup"
- elementName: "初始化测试数据"
- properties:
  - ThreadGroup.num_threads: "1"
  - ThreadGroup.ramp_time: "1"
  - ThreadGroup.on_sample_error: "stopthread"
  - ThreadGroup.main_controller:
    - LoopController.loops: "1"
    - LoopController.continue_forever: "false"
```

### Example 2: Setup with Multiple Threads

```
create_jmeter_element with:
- elementType: "setupthreadgroup"
- elementName: "预创建用户数据"
- properties:
  - ThreadGroup.num_threads: "5"
  - ThreadGroup.ramp_time: "5"
  - ThreadGroup.on_sample_error: "continue"
  - ThreadGroup.main_controller:
    - LoopController.loops: "10"
    - LoopController.continue_forever: "false"
  - ThreadGroup.same_user_on_next_iteration: "true"
```

### Example 3: Setup with Duration

```
create_jmeter_element with:
- elementType: "setupthreadgroup"
- elementName: "环境预热"
- properties:
  - ThreadGroup.num_threads: "1"
  - ThreadGroup.ramp_time: "1"
  - ThreadGroup.scheduler: "true"
  - ThreadGroup.duration: "30"
  - ThreadGroup.main_controller:
    - LoopController.loops: "-1"
    - LoopController.continue_forever: "true"
```

## Best Practices

1. **Use for initialization**: Create test data, warm up caches, set up database state
2. **Keep it simple**: Use 1 thread and 1 loop for most setup tasks
3. **Error handling**: Set `on_sample_error` to `stopthread` or `stoptest` to prevent running main test if setup fails
4. **Sequential execution**: setUp Thread Group completes before any regular Thread Group starts
5. **Avoid long-running tasks**: Keep setup operations fast to avoid delaying the main test

## Notes

- setUp Thread Group executes before all regular Thread Groups
- Behavior is identical to a normal Thread Group except for execution order
- Use with tearDown Thread Group for complete test lifecycle management
- If setUp Thread Group fails, the main test may still run (depends on error handling settings)
- Multiple setUp Thread Groups can be used; they execute in the order they appear in the test plan
