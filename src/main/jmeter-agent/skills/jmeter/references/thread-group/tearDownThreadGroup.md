# tearDown Thread Group

## Description

A special type of ThreadGroup that can be utilized to perform Post-Test Actions. The behavior of these threads is exactly like a normal Thread Group element. The difference is that these type of threads execute after the test has finished executing its regular Thread Groups.

Use tearDown Thread Group for cleanup tasks such as deleting test data, resetting database state, or releasing resources after the main test completes.

Note that by default it won't run if Test is gracefully shutdown. If you want to make it run in this case, ensure you check option "Run tearDown Thread Groups after shutdown of main threads" on Test Plan element. If Test Plan is stopped, tearDown will not run even if option is checked.

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

### Example 1: Basic Cleanup - Delete Test Data

```
create_jmeter_element with:
- elementType: "teardownthreadgroup"
- elementName: "清理测试数据"
- properties:
  - ThreadGroup.num_threads: "1"
  - ThreadGroup.ramp_time: "1"
  - ThreadGroup.on_sample_error: "continue"
  - ThreadGroup.main_controller:
    - LoopController.loops: "1"
    - LoopController.continue_forever: "false"
```

### Example 2: Cleanup with Multiple Threads

```
create_jmeter_element with:
- elementType: "teardownthreadgroup"
- elementName: "批量删除测试用户"
- properties:
  - ThreadGroup.num_threads: "5"
  - ThreadGroup.ramp_time: "5"
  - ThreadGroup.on_sample_error: "continue"
  - ThreadGroup.main_controller:
    - LoopController.loops: "10"
    - LoopController.continue_forever: "false"
  - ThreadGroup.same_user_on_next_iteration: "true"
```

### Example 3: Generate Report After Test

```
create_jmeter_element with:
- elementType: "teardownthreadgroup"
- elementName: "生成测试报告"
- properties:
  - ThreadGroup.num_threads: "1"
  - ThreadGroup.ramp_time: "1"
  - ThreadGroup.on_sample_error: "continue"
  - ThreadGroup.main_controller:
    - LoopController.loops: "1"
    - LoopController.continue_forever: "false"
```

## Best Practices

1. **Use for cleanup**: Delete test data, reset database state, release resources
2. **Keep it simple**: Use 1 thread and 1 loop for most cleanup tasks
3. **Tolerate errors**: Set `on_sample_error` to `continue` since cleanup should try all operations even if some fail
4. **Enable run after shutdown**: Check "Run tearDown Thread Groups after shutdown of main threads" on the Test Plan element to ensure cleanup runs even on graceful shutdown
5. **Avoid long-running tasks**: Keep teardown operations fast to avoid delaying test completion

## Notes

- tearDown Thread Group executes after all regular Thread Groups complete
- By default, tearDown will NOT run if the test is gracefully shutdown. Enable "Run tearDown Thread Groups after shutdown of main threads" on the Test Plan element to change this behavior
- If the Test Plan is stopped (not gracefully shutdown), tearDown will not run even if the option is checked
- Behavior is identical to a normal Thread Group except for execution order
- Use with setUp Thread Group for complete test lifecycle management
- Multiple tearDown Thread Groups can be used; they execute in the order they appear in the test plan
